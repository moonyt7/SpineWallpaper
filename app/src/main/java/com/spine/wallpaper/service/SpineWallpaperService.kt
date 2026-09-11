package com.spine.wallpaper.service

import android.content.SharedPreferences
import android.service.wallpaper.WallpaperService
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import com.spine.wallpaper.loader.SpineModelLoader
import kotlinx.coroutines.*
import java.io.File

/**
 * 读取槽位已选皮肤集合：
 *   1) 优先 `active_skins_json[_2]` (JSONArray) —— 新格式
 *   2) fallback `fallbackSingle` (String) —— 旧单选字段或预读取的兼容值
 * 返回的集合可能为空，调用方应自行保证至少有一个皮肤。
 */
private fun readSelectedSkins(prefs: SharedPreferences, slot: Int, fallbackSingle: String?): Set<String> {
    val key = if (slot == 1) "active_skins_json_2" else "active_skins_json"
    val result = LinkedHashSet<String>()
    val json = prefs.getString(key, null)
    if (!json.isNullOrEmpty()) {
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "").trim()
                if (name.isNotEmpty()) result.add(name)
            }
        } catch (_: Throwable) {}
    }
    if (result.isEmpty() && !fallbackSingle.isNullOrEmpty()) {
        result.add(fallbackSingle)
    }
    return result
}

/**
 * Android WallpaperService implementation for Spine 2D animations.
 * Uses OpenGL ES via custom SurfaceHolder EGL engine.
 * Pauses rendering on screen off / invisible to maximize battery efficiency.
 *
 * Supports two simultaneous models (Live2DViewerEX style):
 *  - Slot 0 (主模型): prefs active_model_id / model_scale / model_pos_x / model_pos_y
 *  - Slot 1 (副模型): prefs model2_id / model2_scale / model2_pos_x / model2_pos_y
 * Preview gestures (drag / pinch) adjust the slot selected in the app UI (pref "selected_slot").
 */
class SpineWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return SpineWallpaperEngine()
    }

    inner class SpineWallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
        private var isEngineVisible = false
        private var spineRenderer: SpineGlRenderer? = null

        // Touch gesture detectors for scale and drag positioning
        private lateinit var scaleDetector: ScaleGestureDetector
        private lateinit var gestureDetector: GestureDetector

        // Per-slot transform caches
        private val scaleFactor = FloatArray(2) { 1.0f }
        private val offsetX = FloatArray(2) { 0.0f }
        private val offsetY = FloatArray(2) { 0.0f }

        // System settings (kept in fields so the gesture callback sees fresh values)
        @Volatile private var tapAnimEnabled = true
        @Volatile private var targetFps = 60

        private fun prefs(): SharedPreferences =
            applicationContext.getSharedPreferences("spine_wallpaper_prefs", MODE_PRIVATE)

        private fun selectedSlot(): Int =
            if (prefs().getInt("selected_slot", 0) == 1) 1 else 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            SpineModelLoader.ensureNativesLoaded()
            setTouchEventsEnabled(true)
            setupGestureDetectors()

            // Initialize GL Renderer context
            spineRenderer = SpineGlRenderer(surfaceHolder)

            val prefs = prefs()
            prefs.registerOnSharedPreferenceChangeListener(this)

            // Read persisted transforms & PMA
            for (slot in 0 until 2) {
                scaleFactor[slot] = prefs.getFloat(SpineModelLoader.SlotPrefs.scaleKey(slot), 1.0f)
                offsetX[slot] = prefs.getFloat(SpineModelLoader.SlotPrefs.posXKey(slot), 0.0f)
                offsetY[slot] = prefs.getFloat(SpineModelLoader.SlotPrefs.posYKey(slot), 0.0f)
                spineRenderer?.updateTransform(scaleFactor[slot], offsetX[slot], offsetY[slot], slot)
            }
            // Per-slot PMA (independent for primary / secondary models)
            spineRenderer?.setPremultipliedAlpha(prefs.getBoolean("pma_enabled", true), SpineGlRenderer.SLOT_PRIMARY)
            spineRenderer?.setPremultipliedAlpha(prefs.getBoolean("pma2_enabled", true), SpineGlRenderer.SLOT_SECONDARY)

            // System settings: tap-to-switch-animation & frame rate limit
            tapAnimEnabled = prefs.getBoolean("tap_switch_animation", true)
            targetFps = prefs.getInt("target_fps", 60)
            spineRenderer?.setTargetFps(targetFps)

            // Read background color and image
            val bgColorInt = prefs.getInt("bg_color", 0xFF0F172A.toInt())
            val bgImagePath = prefs.getString("bg_image_path", null)
            spineRenderer?.setBackgroundColorInt(bgColorInt)
            spineRenderer?.setBackgroundImagePath(bgImagePath)

            // Asynchronously load the selected Spine model directories
            loadActiveSpineModel()
        }

        private fun setupGestureDetectors() {
            scaleDetector = ScaleGestureDetector(this@SpineWallpaperService,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        // Only allow resizing inside wallpaper preview picker, lock on home desktop screen
                        if (!isPreview) return false
                        val slot = selectedSlot()
                        scaleFactor[slot] = (scaleFactor[slot] * detector.scaleFactor).coerceIn(0.2f, 5.0f)
                        spineRenderer?.updateTransform(scaleFactor[slot], offsetX[slot], offsetY[slot], slot)

                        prefs().edit().putFloat(SpineModelLoader.SlotPrefs.scaleKey(slot), scaleFactor[slot]).apply()
                        return true
                    }
                })

            gestureDetector = GestureDetector(this@SpineWallpaperService,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onScroll(
                        e1: MotionEvent?, e2: MotionEvent,
                        distanceX: Float, distanceY: Float
                    ): Boolean {
                        // Only allow dragging position inside wallpaper preview picker, prevent desktop page swipe from moving character
                        if (!isPreview) return false
                        val slot = selectedSlot()
                        offsetX[slot] -= distanceX * 0.002f
                        offsetY[slot] += distanceY * 0.002f
                        spineRenderer?.updateTransform(scaleFactor[slot], offsetX[slot], offsetY[slot], slot)

                        prefs().edit()
                            .putFloat(SpineModelLoader.SlotPrefs.posXKey(slot), offsetX[slot])
                            .putFloat(SpineModelLoader.SlotPrefs.posYKey(slot), offsetY[slot])
                            .apply()
                        return true
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (tapAnimEnabled) {
                            spineRenderer?.triggerTapAnimation(e.x, e.y)
                        }
                        return true
                    }
                })
        }

        private fun loadActiveSpineModel() {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val prefs = prefs()
                    for (slot in 0 until 2) {
                        scaleFactor[slot] = prefs.getFloat(SpineModelLoader.SlotPrefs.scaleKey(slot), 1.0f)
                        offsetX[slot] = prefs.getFloat(SpineModelLoader.SlotPrefs.posXKey(slot), 0.0f)
                        offsetY[slot] = prefs.getFloat(SpineModelLoader.SlotPrefs.posYKey(slot), 0.0f)
                    }

                    val bgColorInt = prefs.getInt("bg_color", 0xFF0F172A.toInt())
                    val bgImagePath = prefs.getString("bg_image_path", null)

                    spineRenderer?.setPremultipliedAlpha(prefs.getBoolean("pma_enabled", true), SpineGlRenderer.SLOT_PRIMARY)
                    spineRenderer?.setPremultipliedAlpha(prefs.getBoolean("pma2_enabled", true), SpineGlRenderer.SLOT_SECONDARY)
                    for (slot in 0 until 2) {
                        spineRenderer?.updateTransform(scaleFactor[slot], offsetX[slot], offsetY[slot], slot)
                    }
                    spineRenderer?.setBackgroundColorInt(bgColorInt)
                    spineRenderer?.setBackgroundImagePath(bgImagePath)

                    // Slot 0: primary model
                    val modelDir: File? = SpineModelLoader.getActiveModelDir(applicationContext)
                    modelDir?.let { dir ->
                        spineRenderer?.setModelDirectory(dir, SpineGlRenderer.SLOT_PRIMARY)
                    }

                    // Slot 1: secondary model
                    val model2Dir = SpineModelLoader.getModelDirById(
                        applicationContext,
                        SpineModelLoader.getModelId(applicationContext, 1)
                    )
                    spineRenderer?.setModelDirectory(model2Dir, SpineGlRenderer.SLOT_SECONDARY)

                    val activeAnim = prefs.getString("active_animation_name", null)
                    val activeSkin = prefs.getString("active_skin_name", null)
                    val activeAnim2 = prefs.getString("model2_animation", null)
                    val activeSkin2 = prefs.getString("model2_skin", null)

                    if (!activeAnim.isNullOrEmpty()) {
                        spineRenderer?.playAnimation(activeAnim, true, SpineGlRenderer.SLOT_PRIMARY)
                    }
                    val activeSkins = readSelectedSkins(prefs, slot = 0, fallbackSingle = activeSkin)
                    if (activeSkins.isNotEmpty()) {
                        spineRenderer?.setSelectedSkins(activeSkins, SpineGlRenderer.SLOT_PRIMARY)
                    } else if (!activeSkin.isNullOrEmpty()) {
                        spineRenderer?.setSkin(activeSkin, SpineGlRenderer.SLOT_PRIMARY)
                    }
                    if (!activeAnim2.isNullOrEmpty()) {
                        spineRenderer?.playAnimation(activeAnim2, true, SpineGlRenderer.SLOT_SECONDARY)
                    }
                    val activeSkins2 = readSelectedSkins(prefs, slot = 1, fallbackSingle = activeSkin2)
                    if (activeSkins2.isNotEmpty()) {
                        spineRenderer?.setSelectedSkins(activeSkins2, SpineGlRenderer.SLOT_SECONDARY)
                    } else if (!activeSkin2.isNullOrEmpty()) {
                        spineRenderer?.setSkin(activeSkin2, SpineGlRenderer.SLOT_SECONDARY)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isEngineVisible = visible
            if (visible) {
                // Reload active model & transforms when waking up from lock screen or returning to desktop
                loadActiveSpineModel()
                spineRenderer?.onResume()
            } else {
                spineRenderer?.onPause()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loadActiveSpineModel()
            if (isEngineVisible) {
                spineRenderer?.onResume()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (isEngineVisible) {
                spineRenderer?.onResume()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            spineRenderer?.onPause()
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (isPreview) {
                scaleDetector.onTouchEvent(event)
            }
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            when (key) {
                "active_model_id", "active_model_uri", "model2_id" -> {
                    loadActiveSpineModel()
                }
                "bg_color" -> {
                    val bgColorInt = prefs?.getInt("bg_color", 0xFF0F172A.toInt()) ?: 0xFF0F172A.toInt()
                    spineRenderer?.setBackgroundColorInt(bgColorInt)
                }
                "bg_image_path" -> {
                    val bgImagePath = prefs?.getString("bg_image_path", null)
                    spineRenderer?.setBackgroundImagePath(bgImagePath)
                }
                "active_animation_name" -> {
                    val anim = prefs?.getString("active_animation_name", null)
                    if (!anim.isNullOrEmpty()) {
                        spineRenderer?.playAnimation(anim, true, SpineGlRenderer.SLOT_PRIMARY)
                    }
                }
                "active_skin_name", "active_skins_json" -> {
                    val set = if (prefs != null) readSelectedSkins(prefs, slot = 0, fallbackSingle = prefs.getString("active_skin_name", null)) else emptySet()
                    if (set.isNotEmpty()) {
                        spineRenderer?.setSelectedSkins(set, SpineGlRenderer.SLOT_PRIMARY)
                    }
                }
                "model2_animation" -> {
                    val anim = prefs?.getString("model2_animation", null)
                    if (!anim.isNullOrEmpty()) {
                        spineRenderer?.playAnimation(anim, true, SpineGlRenderer.SLOT_SECONDARY)
                    }
                }
                "model2_skin", "active_skins_json_2" -> {
                    val set = if (prefs != null) readSelectedSkins(prefs, slot = 1, fallbackSingle = prefs.getString("model2_skin", null)) else emptySet()
                    if (set.isNotEmpty()) {
                        spineRenderer?.setSelectedSkins(set, SpineGlRenderer.SLOT_SECONDARY)
                    }
                }
                "pma_enabled", "pma2_enabled" -> {
                    val slot = if (key == "pma2_enabled") 1 else 0
                    val isPma = prefs?.getBoolean(key, true) ?: true
                    spineRenderer?.setPremultipliedAlpha(isPma, slot)
                }
                "tap_switch_animation" -> {
                    tapAnimEnabled = prefs?.getBoolean("tap_switch_animation", true) ?: true
                }
                "target_fps" -> {
                    targetFps = prefs?.getInt("target_fps", 60) ?: 60
                    spineRenderer?.setTargetFps(targetFps)
                }
                "model_scale", "model2_scale" -> {
                    val slot = if (key == "model2_scale") 1 else 0
                    scaleFactor[slot] = prefs?.getFloat(key, scaleFactor[slot]) ?: scaleFactor[slot]
                    spineRenderer?.updateTransform(scaleFactor[slot], offsetX[slot], offsetY[slot], slot)
                }
                "model_pos_x", "model_pos_y", "model2_pos_x", "model2_pos_y" -> {
                    val slot = if (key!!.startsWith("model2")) 1 else 0
                    offsetX[slot] = prefs?.getFloat(SpineModelLoader.SlotPrefs.posXKey(slot), offsetX[slot]) ?: offsetX[slot]
                    offsetY[slot] = prefs?.getFloat(SpineModelLoader.SlotPrefs.posYKey(slot), offsetY[slot]) ?: offsetY[slot]
                    spineRenderer?.updateTransform(scaleFactor[slot], offsetX[slot], offsetY[slot], slot)
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            isEngineVisible = false
            val prefs = prefs()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            serviceScope.cancel()
            spineRenderer?.release()
        }
    }
}
