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
import com.spine.wallpaper.service.SpineGlRenderer
import java.io.File

@Composable
fun SpineViewCompose(
    modelDir: File?,
    isPlaying: Boolean = true,
    scale: Float = 1.0f,
    pma: Boolean = true,
    activeAnimation: String? = null,
    activeSkin: String? = null,
    bgColor: Color = Color(0xFF0F172A),
    bgImagePath: String? = null,
    onScaleChange: ((Float) -> Unit)? = null,
    onModelLoaded: ((animations: List<String>, skins: List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceView = remember { SurfaceView(context) }
    val renderer = remember { SpineGlRenderer(surfaceView.holder) }

    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Read stored offset & scale
    val prefs = remember { context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE) }
    var currentScale by remember { mutableStateOf(prefs.getFloat("model_scale", scale)) }
    var currentPosX by remember { mutableStateOf(prefs.getFloat("model_pos_x", 0.0f)) }
    var currentPosY by remember { mutableStateOf(prefs.getFloat("model_pos_y", 0.0f)) }

    // Configure touch gesture detectors on preview SurfaceView
    val scaleDetector = remember {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale = (currentScale * detector.scaleFactor).coerceIn(0.2f, 5.0f)
                renderer.updateTransform(currentScale, currentPosX, currentPosY)
                prefs.edit().putFloat("model_scale", currentScale).apply()
                onScaleChange?.invoke(currentScale)
                return true
            }
        })
    }

    val gestureDetector = remember {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                currentPosX -= distanceX * 0.002f
                currentPosY += distanceY * 0.002f
                renderer.updateTransform(currentScale, currentPosX, currentPosY)
                prefs.edit()
                    .putFloat("model_pos_x", currentPosX)
                    .putFloat("model_pos_y", currentPosY)
                    .apply()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                renderer.triggerTapAnimation(e.x, e.y)
                return true
            }
        })
    }

    LaunchedEffect(renderer, onModelLoaded) {
        renderer.onModelLoadedListener = { anims, skins ->
            onModelLoaded?.invoke(anims, skins)
        }
    }

    LaunchedEffect(modelDir) {
        if (modelDir != null && modelDir.exists()) {
            isLoading = true
            errorText = null
            try {
                renderer.setModelDirectory(modelDir)
                renderer.updateTransform(currentScale, currentPosX, currentPosY)
                renderer.setPremultipliedAlpha(pma)
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
        }
    }

    LaunchedEffect(scale) {
        currentScale = scale
        renderer.updateTransform(currentScale, currentPosX, currentPosY)
        prefs.edit().putFloat("model_scale", currentScale).apply()
    }

    LaunchedEffect(pma) {
        renderer.setPremultipliedAlpha(pma)
    }

    LaunchedEffect(activeAnimation) {
        if (!activeAnimation.isNullOrEmpty()) {
            renderer.playAnimation(activeAnimation)
        }
    }

    LaunchedEffect(activeSkin) {
        if (!activeSkin.isNullOrEmpty()) {
            renderer.setSkin(activeSkin)
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