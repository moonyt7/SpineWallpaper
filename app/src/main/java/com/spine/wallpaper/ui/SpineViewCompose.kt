package com.spine.wallpaper.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.spine.wallpaper.loader.SpineModelLoader
import com.spine.wallpaper.service.SpineGlRenderer
import java.io.File

/**
 * In-app dual-model preview (Live2DViewerEX style).
 * Slot 0 = primary (behind), Slot 1 = secondary (front).
 * Drag / pinch gestures adjust the currently selected slot's transform.
 */
@Composable
fun SpineViewCompose(
    modelDir: File?,
    model2Dir: File? = null,
    selectedSlot: Int = 0,
    isPlaying: Boolean = true,
    scale: Float = 1.0f,
    scale2: Float = 1.0f,
    pma: Boolean = true,
    pma2: Boolean = true,
    activeAnimation: String? = null,
    activeAnimation2: String? = null,
    activeSkin: String? = null,
    activeSkin2: String? = null,
    bgColor: Color = Color(0xFF0F172A),
    bgImagePath: String? = null,
    resetTick: Int = 0,
    tapAnimEnabled: Boolean = true,
    targetFps: Int = 60,
    onScaleChange: ((slot: Int, scale: Float) -> Unit)? = null,
    onModelLoaded: ((slot: Int, animations: List<String>, skins: List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceView = remember { SurfaceView(context) }
    val renderer = remember { SpineGlRenderer(surfaceView.holder) }

    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Read stored offset & scale for both slots
    val prefs = remember { context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE) }
    val currentScale = remember {
        floatArrayOf(
            prefs.getFloat(SpineModelLoader.SlotPrefs.scaleKey(0), scale),
            prefs.getFloat(SpineModelLoader.SlotPrefs.scaleKey(1), scale2)
        )
    }
    val currentPosX = remember {
        floatArrayOf(
            prefs.getFloat(SpineModelLoader.SlotPrefs.posXKey(0), 0.0f),
            prefs.getFloat(SpineModelLoader.SlotPrefs.posXKey(1), 0.0f)
        )
    }
    val currentPosY = remember {
        floatArrayOf(
            prefs.getFloat(SpineModelLoader.SlotPrefs.posYKey(0), 0.0f),
            prefs.getFloat(SpineModelLoader.SlotPrefs.posYKey(1), 0.0f)
        )
    }

    // Gesture target slot (kept in a state so detector closures see fresh value)
    val slotRef = remember { mutableIntStateOf(selectedSlot) }

    // Tap-to-switch-animation toggle (kept in a state so the gesture closure sees fresh value)
    val tapEnabledRef = remember { mutableStateOf(tapAnimEnabled) }

    fun applyTransform(slot: Int) {
        renderer.updateTransform(currentScale[slot], currentPosX[slot], currentPosY[slot], slot)
    }

    // Configure touch gesture detectors on preview SurfaceView
    val scaleDetector = remember {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val slot = slotRef.intValue
                currentScale[slot] = (currentScale[slot] * detector.scaleFactor).coerceIn(0.2f, 5.0f)
                applyTransform(slot)
                prefs.edit().putFloat(SpineModelLoader.SlotPrefs.scaleKey(slot), currentScale[slot]).apply()
                onScaleChange?.invoke(slot, currentScale[slot])
                return true
            }
        })
    }

    val gestureDetector = remember {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val slot = slotRef.intValue
                currentPosX[slot] -= distanceX * 0.002f
                currentPosY[slot] += distanceY * 0.002f
                applyTransform(slot)
                prefs.edit()
                    .putFloat(SpineModelLoader.SlotPrefs.posXKey(slot), currentPosX[slot])
                    .putFloat(SpineModelLoader.SlotPrefs.posYKey(slot), currentPosY[slot])
                    .apply()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (tapEnabledRef.value) {
                    renderer.triggerTapAnimation(e.x, e.y)
                }
                return true
            }
        })
    }

    LaunchedEffect(renderer, onModelLoaded) {
        renderer.onModelLoadedListener = { slot, anims, skins ->
            onModelLoaded?.invoke(slot, anims, skins)
        }
    }

    LaunchedEffect(selectedSlot) {
        slotRef.intValue = selectedSlot
    }

    LaunchedEffect(tapAnimEnabled) {
        tapEnabledRef.value = tapAnimEnabled
    }

    LaunchedEffect(targetFps) {
        renderer.setTargetFps(targetFps)
    }

    LaunchedEffect(modelDir) {
        if (modelDir != null && modelDir.exists()) {
            isLoading = true
            errorText = null
            try {
                renderer.setModelDirectory(modelDir, SpineGlRenderer.SLOT_PRIMARY)
                applyTransform(0)
                renderer.setPremultipliedAlpha(pma, SpineGlRenderer.SLOT_PRIMARY)
                renderer.setBackgroundColor(
                    bgColor.red,
                    bgColor.green,
                    bgColor.blue,
                    bgColor.alpha
                )
                renderer.setBackgroundImagePath(bgImagePath)
                renderer.onResume()
                isLoading = false
            } catch (e: Exception) {
                e.printStackTrace()
                isLoading = false
                errorText = e.message ?: "模型解压与加载失败"
            }
        } else {
            renderer.setModelDirectory(null, SpineGlRenderer.SLOT_PRIMARY)
        }
    }

    LaunchedEffect(model2Dir) {
        if (model2Dir != null && model2Dir.exists()) {
            try {
                renderer.setModelDirectory(model2Dir, SpineGlRenderer.SLOT_SECONDARY)
                applyTransform(1)
                renderer.setPremultipliedAlpha(pma2, SpineGlRenderer.SLOT_SECONDARY)
                renderer.onResume()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            renderer.setModelDirectory(null, SpineGlRenderer.SLOT_SECONDARY)
        }
    }

    LaunchedEffect(scale, scale2) {
        currentScale[0] = scale
        currentScale[1] = scale2
        applyTransform(0)
        applyTransform(1)
    }

    // Reset size & position of the selected slot when resetTick changes (>0)
    LaunchedEffect(resetTick) {
        if (resetTick > 0) {
            val slot = slotRef.intValue
            currentScale[slot] = 1.0f
            currentPosX[slot] = 0.0f
            currentPosY[slot] = 0.0f
            applyTransform(slot)
            prefs.edit()
                .putFloat(SpineModelLoader.SlotPrefs.scaleKey(slot), 1.0f)
                .putFloat(SpineModelLoader.SlotPrefs.posXKey(slot), 0.0f)
                .putFloat(SpineModelLoader.SlotPrefs.posYKey(slot), 0.0f)
                .apply()
            onScaleChange?.invoke(slot, 1.0f)
        }
    }

    // PMA is independent per slot (primary / secondary models may need different modes)
    LaunchedEffect(pma) {
        renderer.setPremultipliedAlpha(pma, SpineGlRenderer.SLOT_PRIMARY)
    }

    LaunchedEffect(pma2) {
        renderer.setPremultipliedAlpha(pma2, SpineGlRenderer.SLOT_SECONDARY)
    }

    LaunchedEffect(activeAnimation) {
        if (!activeAnimation.isNullOrEmpty()) {
            renderer.playAnimation(activeAnimation, true, SpineGlRenderer.SLOT_PRIMARY)
        }
    }

    LaunchedEffect(activeAnimation2) {
        if (!activeAnimation2.isNullOrEmpty()) {
            renderer.playAnimation(activeAnimation2, true, SpineGlRenderer.SLOT_SECONDARY)
        }
    }

    LaunchedEffect(activeSkin) {
        if (!activeSkin.isNullOrEmpty()) {
            renderer.setSkin(activeSkin, SpineGlRenderer.SLOT_PRIMARY)
        }
    }

    LaunchedEffect(activeSkin2) {
        if (!activeSkin2.isNullOrEmpty()) {
            renderer.setSkin(activeSkin2, SpineGlRenderer.SLOT_SECONDARY)
        }
    }

    LaunchedEffect(bgColor) {
        renderer.setBackgroundColor(
            bgColor.red,
            bgColor.green,
            bgColor.blue,
            bgColor.alpha
        )
    }

    LaunchedEffect(bgImagePath) {
        renderer.setBackgroundImagePath(bgImagePath)
    }

    // Lifecycle observer to guarantee renderer resumes when returning to MainActivity
    DisposableEffect(lifecycleOwner, isPlaying) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (isPlaying) {
                        renderer.onResume()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    renderer.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (isPlaying) {
            renderer.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            renderer.onPause()
            renderer.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = {
                surfaceView.apply {
                    setOnTouchListener { _, event ->
                        scaleDetector.onTouchEvent(event)
                        gestureDetector.onTouchEvent(event)
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC0F172A)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF6366F1))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "正在装载 Spine 2D 骨骼模型...",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }

        if (errorText != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xEE0F172A))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "❌ Spine 模型加载失败",
                            color = Color(0xFFEF4444),
                            fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            errorText!!,
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
