package com.spine.wallpaper.service

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import android.view.SurfaceHolder
import com.badlogic.gdx.backends.android.AndroidGL20
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.esotericsoftware.spine.*
import com.spine.wallpaper.loader.SpineModelLoader
import com.spine.wallpaper.model.Live2DConfig
import org.json.JSONObject
import java.io.File

/**
 * Robust OpenGL ES Renderer managing Spine Skeleton & AnimationState loops on SurfaceHolder with EGL14 context.
 * Implements SurfaceHolder.Callback to reliably recover OpenGL surface after returning from wallpaper settings or lock screen.
 */
class SpineGlRenderer(private val surfaceHolder: SurfaceHolder) : SurfaceHolder.Callback {

    private var isRunning = false
    private var renderThread: Thread? = null
    private var hasValidSurface = false

    private var modelDir: File? = null
    private var isModelPending = false

    private var modelInstance: com.spine.wallpaper.adapter.ISpineModelInstance? = null
    private var atlas: TextureAtlas? = null
    private var config: Live2DConfig? = null

    private var batch: PolygonSpriteBatch? = null
    private var camera: OrthographicCamera? = null

    private var scale = 1.0f
    private var posX = 0.0f
    private var posY = 0.0f
    private var isPma = true
    private var animationIndex = 0

    private var pendingAnimationName: String? = null
    private var pendingSkinName: String? = null
    private var bgRed = 0.08f
    private var bgGreen = 0.09f
    private var bgBlue = 0.12f
    private var bgAlpha = 1.0f

    private var bgImagePath: String? = null
    private var bgTexture: Texture? = null
    private var isBgTexturePending = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    var onModelLoadedListener: ((animations: List<String>, skins: List<String>) -> Unit)? = null

    private var viewportWidth = 1080
    private var viewportHeight = 1920

    init {
        surfaceHolder.addCallback(this)
        hasValidSurface = surfaceHolder.surface.isValid
        val frame = surfaceHolder.surfaceFrame
        if (frame != null && frame.width() > 0 && frame.height() > 0) {
            viewportWidth = frame.width()
            viewportHeight = frame.height()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(this) {
            hasValidSurface = true
            isModelPending = true
            val frame = holder.surfaceFrame
            if (frame != null && frame.width() > 0 && frame.height() > 0) {
                viewportWidth = frame.width()
                viewportHeight = frame.height()
            }
        }
        onResume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(this) {
            hasValidSurface = true
            isModelPending = true
            if (width > 0 && height > 0) {
                viewportWidth = width
                viewportHeight = height
            }
        }
        if (!isRunning) {
            onResume()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(this) {
            hasValidSurface = false
            isModelPending = true
            destroyEglSurface()
        }
    }

    fun setModelDirectory(dir: File) {
        synchronized(this) {
            this.modelDir = dir
            this.isModelPending = true
        }
    }

    fun setPremultipliedAlpha(pma: Boolean) {
        synchronized(this) {
            this.isPma = pma
            this.modelInstance?.setPremultipliedAlpha(pma)
        }
    }

    fun updateTransform(scale: Float, x: Float, y: Float) {
        synchronized(this) {
            this.scale = scale
            this.posX = x
            this.posY = y
        }
    }

    fun playAnimation(animName: String, loop: Boolean = true) {
        synchronized(this) {
            val model = modelInstance ?: run {
                pendingAnimationName = animName
                return
            }
            try {
                model.setAnimation(0, animName, loop)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setSkin(skinName: String) {
        synchronized(this) {
            val model = modelInstance ?: run {
                pendingSkinName = skinName
                return
            }
            try {
                model.setSkin(skinName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setBackgroundColor(r: Float, g: Float, b: Float, a: Float = 1.0f) {
        synchronized(this) {
            this.bgRed = r
            this.bgGreen = g
            this.bgBlue = b
            this.bgAlpha = a
        }
    }

    fun setBackgroundColorInt(colorInt: Int) {
        val a = android.graphics.Color.alpha(colorInt) / 255f
        val r = android.graphics.Color.red(colorInt) / 255f
        val g = android.graphics.Color.green(colorInt) / 255f
        val b = android.graphics.Color.blue(colorInt) / 255f
        setBackgroundColor(r, g, b, a)
    }

    fun setBackgroundImagePath(path: String?) {
        synchronized(this) {
            this.bgImagePath = path
            this.isBgTexturePending = true
        }
    }

    fun triggerTapAnimation(screenX: Float, screenY: Float) {
        synchronized(this) {
            val model = modelInstance ?: return
            val anims = model.animationNames
            if (anims.isEmpty()) return

            animationIndex = (animationIndex + 1) % anims.size
            val nextAnim = anims[animationIndex]
            model.setAnimation(0, nextAnim, true)
        }
    }

    fun onResume() {
        synchronized(this) {
            isModelPending = true
            isBgTexturePending = true
            if (isRunning && renderThread?.isAlive == true) {
                return
            }
            isRunning = true
        }

        renderThread = Thread {
            SpineModelLoader.ensureNativesLoaded()
            if (!initEGLBase()) {
                synchronized(this) {
                    isRunning = false
                }
                return@Thread
            }

            var lastTime = SystemClock.uptimeMillis()

            while (isRunning) {
                if (!hasValidSurface || !surfaceHolder.surface.isValid) {
                    destroyEglSurface()
                    try {
                        Thread.sleep(30)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    if (!createEglSurface()) {
                        try {
                            Thread.sleep(30)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                }

                val now = SystemClock.uptimeMillis()
                val deltaSeconds = ((now - lastTime) / 1000.0f).coerceIn(0.001f, 0.1f)
                lastTime = now

                synchronized(this) {
                    if (isModelPending && modelDir != null) {
                        loadModelOnGlThread(modelDir!!)
                        isModelPending = false
                    }

                    renderFrame(deltaSeconds)
                }

                if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                    try {
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        destroyEglSurface()
                    }
                }

                try {
                    Thread.sleep(16) // ~60 FPS
                } catch (e: InterruptedException) {
                    break
                }
            }

            releaseGL()
            releaseEGL()
        }.apply {
            name = "SpineGLRenderThread"
            start()
        }
    }

    fun onPause() {
        synchronized(this) {
            isRunning = false
            isModelPending = true
        }
        try {
            renderThread?.join(500)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        renderThread = null
    }

    private fun loadModelOnGlThread(dir: File) {
        try {
            releaseGL()

            var skelFile: File? = null
            var atlasFile: File? = null

            val metaFile = File(dir, "model_meta.json")
            if (metaFile.exists()) {
                try {
                    val metaObj = JSONObject(metaFile.readText())
                    val skelRel = metaObj.optString("skelFile")
                    val atlasRel = metaObj.optString("atlasFile")
                    if (skelRel.isNotEmpty()) skelFile = File(dir, skelRel)
                    if (atlasRel.isNotEmpty()) atlasFile = File(dir, atlasRel)
                } catch (_: Exception) {}
            }

            val allFiles = dir.walkTopDown().filter { it.isFile }.toList()

            if (skelFile == null || !skelFile.exists()) {
                skelFile = allFiles.firstOrNull {
                    val name = it.name.lowercase()
                    name.endsWith(".skel") || name.endsWith(".skel.bytes")
                } ?: allFiles.firstOrNull {
                    val name = it.name.lowercase()
                    (name.endsWith(".json") || name.endsWith(".json.txt")) && !name.endsWith(".config.json") && !name.endsWith("model_meta.json")
                }
            }

            if (atlasFile == null || !atlasFile.exists()) {
                atlasFile = allFiles.firstOrNull {
                    val name = it.name.lowercase()
                    name.endsWith(".atlas") || name.endsWith(".atlas.txt")
                }
            }

            val configFile = allFiles.firstOrNull { it.name.lowercase().endsWith(".config.json") }

            if (skelFile == null || atlasFile == null) return

            config = configFile?.let { Live2DConfig.parse(it.readText()) }

            val atlasHandle = com.spine.wallpaper.adapter.SpineVersionAdapter.sanitizeAtlasFile(atlasFile)
            val loadedAtlas = TextureAtlas(atlasHandle)
            this.atlas = loadedAtlas

            // Create Universal Multi-Version Model Instance (3.6, 3.7, 3.8, 4.0, 4.1, 4.2)
            val instance = com.spine.wallpaper.adapter.SpineVersionAdapter.createModelInstance(
                skelFile = skelFile,
                atlas = loadedAtlas,
                scale = config?.scale ?: 1.0f,
                isPma = isPma
            )
            this.modelInstance = instance

            val animNames = instance.animationNames
            val skinNames = instance.skinNames

            val idleAnim = config?.idle_motion
            var animationSet = false
            if (!idleAnim.isNullOrEmpty() && animNames.contains(idleAnim)) {
                try {
                    instance.setAnimation(0, idleAnim, true)
                    animationSet = true
                } catch (_: Exception) {}
            }
            if (!animationSet && animNames.isNotEmpty()) {
                try {
                    instance.setAnimation(0, animNames[0], true)
                } catch (_: Exception) {}
            }

            val targetSkin = pendingSkinName?.takeIf { skinNames.contains(it) }
                ?: skinNames.firstOrNull { it.equals("default", ignoreCase = true) }
                ?: skinNames.firstOrNull()
            if (targetSkin != null) {
                try {
                    instance.setSkin(targetSkin)
                } catch (_: Exception) {}
            }
            pendingSkinName = null

            if (!pendingAnimationName.isNullOrEmpty() && animNames.contains(pendingAnimationName)) {
                try {
                    instance.setAnimation(0, pendingAnimationName!!, true)
                } catch (_: Exception) {}
                pendingAnimationName = null
            }

            batch = PolygonSpriteBatch()
            camera = OrthographicCamera()

            onModelLoadedListener?.invoke(animNames, skinNames)

        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun renderFrame(delta: Float) {
        val frame = surfaceHolder.surfaceFrame
        val width = if (frame != null && frame.width() > 0) frame.width() else viewportWidth
        val height = if (frame != null && frame.height() > 0) frame.height() else viewportHeight
        if (width <= 0 || height <= 0) return

        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(bgRed, bgGreen, bgBlue, bgAlpha)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Handle background texture creation/destruction on GL thread
        if (isBgTexturePending) {
            try {
                bgTexture?.dispose()
                bgTexture = null
                val path = bgImagePath
                if (!path.isNullOrEmpty()) {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        bgTexture = Texture(FileHandle(file))
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            isBgTexturePending = false
        }

        if (batch == null) {
            batch = PolygonSpriteBatch()
        }
        if (camera == null) {
            camera = OrthographicCamera()
        }

        val pBatch = batch ?: return
        val cam = camera ?: return

        cam.setToOrtho(false, width.toFloat(), height.toFloat())
        cam.update()

        pBatch.projectionMatrix = cam.combined
        pBatch.begin()

        // 1. Draw custom background texture covering the entire viewport if loaded
        val bg = bgTexture
        if (bg != null) {
            pBatch.draw(bg, 0f, 0f, width.toFloat(), height.toFloat())
        }

        // 2. Draw Spine model (Universal multi-version instance)
        val model = modelInstance
        if (model != null) {
            model.setPremultipliedAlpha(isPma)
            model.update(delta)

            val safeScale = if (scale > 0.001f) scale else 1.0f
            val centerX = width / 2.0f + posX * width
            val centerY = height / 2.0f + posY * height
            model.setPosition(centerX, centerY)
            model.setScale(safeScale, safeScale)

            model.draw(pBatch)
        }

        pBatch.end()
    }

    private fun initEGLBase(): Boolean {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            return true
        }
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            return false
        }
        val selectedConfig = configs[0] ?: return false
        this.eglConfig = selectedConfig

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(eglDisplay, selectedConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        return true
    }

    private fun createEglSurface(): Boolean {
        val config = eglConfig ?: return false
        if (!surfaceHolder.surface.isValid) return false

        try {
            destroyEglSurface()

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surfaceHolder.surface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            val madeCurrent = EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            if (!madeCurrent) return false

            try {
                val gl20 = AndroidGL20()
                com.badlogic.gdx.Gdx.gl = gl20
                com.badlogic.gdx.Gdx.gl20 = gl20
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun destroyEglSurface() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            try {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun releaseGL() {
        try {
            bgTexture?.dispose()
            batch?.dispose()
            atlas?.dispose()
            modelInstance?.dispose()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        bgTexture = null
        batch = null
        atlas = null
        modelInstance = null
        camera = null
    }

    private fun releaseEGL() {
        destroyEglSurface()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    fun release() {
        surfaceHolder.removeCallback(this)
        onPause()
    }
}