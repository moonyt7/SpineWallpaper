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
 * Android WallpaperService implementation for Spine 2D animations.
 * Uses OpenGL ES via custom SurfaceHolder EGL engine.
 * Pauses rendering on screen off / invisible to maximize battery efficiency.
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

        private var scaleFactor = 1.0f
        private var offsetX = 0.0f
        private var offsetY = 0.0f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            SpineModelLoader.ensureNativesLoaded()
            setTouchEventsEnabled(true)
            setupGestureDetectors()

            // Initialize GL Renderer context
            spineRenderer = SpineGlRenderer(surfaceHolder)

            val prefs = applicationContext.getSharedPreferences("spine_wallpaper_prefs", MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)

            // Read persisted transform & PMA
            scaleFactor = prefs.getFloat("model_scale", 1.0f)
            offsetX = prefs.getFloat("model_pos_x", 0.0f)
            offsetY = prefs.getFloat("model_pos_y", 0.0f)
            val isPma = prefs.getBoolean("pma_enabled", true)
            spineRenderer?.setPremultipliedAlpha(isPma)
            spineRenderer?.updateTransform(scaleFactor, offsetX, offsetY)

            // Read background color and image
            val bgColorInt = prefs.getInt("bg_color", 0xFF0F172A.toInt())
            val bgImagePath = prefs.getString("bg_image_path", null)
            spineRenderer?.setBackgroundColorInt(bgColorInt)
            spineRenderer?.setBackgroundImagePath(bgImagePath)

            // Asynchronously load the selected Spine model directory
            loadActiveSpineModel()
        }

        private fun setupGestureDetectors() {
            scaleDetector = ScaleGestureDetector(this@SpineWallpaperService, 
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        // Only allow resizing inside wallpaper preview picker, lock on home desktop screen
                        if (!isPreview) return false
                        scaleFactor *= detector.scaleFactor
                        scaleFactor = scaleFactor.coerceIn(0.2f, 5.0f)
                        spineRenderer?.updateTransform(scaleFactor, offsetX, offsetY)

                        val prefs = applicationContext.getSharedPreferences("spine_wallpaper_prefs", MODE_PRIVATE)
                        prefs.edit().putFloat("model_scale", scaleFactor).apply()
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
                        offsetX -= distanceX * 0.002f
                        offsetY += distanceY * 0.002f
                        spineRenderer?.updateTransform(scaleFactor, offsetX, offsetY)

                        val prefs = applicationContext.getSharedPreferences("spine_wallpaper_prefs", MODE_PRIVATE)
                        prefs.edit().putFloat("model_pos_x", offsetX).putFloat("model_pos_y", offsetY).apply()
                        return true
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        spineRenderer?.triggerTapAnimation(e.x, e.y)
                        return true
                    }
                })
        }

        private fun loadActiveSpineModel() {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val prefs = applicationContext.getSharedPreferences("spine_wallpaper_prefs", MODE_PRIVATE)
                    val isPma = prefs.getBoolean("pma_enabled", true)
                    scaleFactor = prefs.getFloat("model_scale", 1.0f)
                    offsetX = prefs.getFloat("model_pos_x", 0.0f)
                    offsetY = prefs.getFloat("model_pos_y", 0.0f)

                    val bgColorInt = prefs.getInt("bg_color", 0xFF0F172A.toInt())
                    val bgImagePath = prefs.getString("bg_image_path", null)

                    spineRenderer?.setPremultipliedAlpha(isPma)
                    spineRenderer?.updateTransform(scaleFactor, offsetX, offsetY)
                    spineRenderer?.setBackgroundColorInt(bgColorInt)
                    spineRenderer?.setBackgroundImagePath(bgImagePath)

                    val modelDir = SpineModelLoader.getActiveModelDir(applicationContext)
                    modelDir?.let { dir ->
                        spineRenderer?.setModelDirectory(dir)
                    }

                    val activeAnim = prefs.getString("active_animation_name", null)
                    val activeSkin = prefs.getString("active_skin_name", null)
                    
                    if (!activeAnim.isNullOrEmpty()) {
                        spineRenderer?.playAnimation(activeAnim)
                    }
                    if (!activeSkin.isNullOrEmpty()) {
                        spineRenderer?.setSkin(activeSkin)
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
            if (key == "active_model_id" || key == "active_model_uri") {
                loadActiveSpineModel()
            } else if (key == "bg_color") {
                val bgColorInt = prefs?.getInt("bg_color", 0xFF0F172A.toInt()) ?: 0xFF0F172A.toInt()
                spineRenderer?.setBackgroundColorInt(bgColorInt)
            } else if (key == "bg_image_path") {
                val bgImagePath = prefs?.getString("bg_image_path", null)
                spineRenderer?.setBackgroundImagePath(bgImagePath)
            } else if (key == "active_animation_name") {
                val anim = prefs?.getString("active_animation_name", null)
                if (!anim.isNullOrEmpty()) {
                    spineRenderer?.playAnimation(anim)
                }
            } else if (key == "active_skin_name") {
                val skin = prefs?.getString("active_skin_name", null)
                if (!skin.isNullOrEmpty()) {
                    spineRenderer?.setSkin(skin)
                }
            } else if (key == "pma_enabled") {
                val isPma = prefs?.getBoolean("pma_enabled", true) ?: true
                spineRenderer?.setPremultipliedAlpha(isPma)
            } else if (key == "model_scale" || key == "model_pos_x" || key == "model_pos_y") {
                scaleFactor = prefs?.getFloat("model_scale", scaleFactor) ?: scaleFactor
                offsetX = prefs?.getFloat("model_pos_x", offsetX) ?: offsetX
                offsetY = prefs?.getFloat("model_pos_y", offsetY) ?: offsetY
                spineRenderer?.updateTransform(scaleFactor, offsetX, offsetY)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            isEngineVisible = false
            val prefs = applicationContext.getSharedPreferences("spine_wallpaper_prefs", MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            serviceScope.cancel()
            spineRenderer?.release()
        }
    }
}