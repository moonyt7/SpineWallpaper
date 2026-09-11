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
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.spine.wallpaper.loader.SpineModelLoader
import com.spine.wallpaper.model.Live2DConfig
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashSet

/**
 * Robust OpenGL ES Renderer managing Spine Skeleton & AnimationState loops on SurfaceHolder with EGL14 context.
 * Implements SurfaceHolder.Callback to reliably recover OpenGL surface after returning from wallpaper settings or lock screen.
 *
 * Supports up to TWO simultaneous models (Live2DViewerEX style):
 *  - Slot 0: primary model (drawn LAST, in FRONT / on top)
 *  - Slot 1: secondary model (drawn first, behind)
 * Each slot keeps its own adapter instance, atlas, transform (scale / offsetX / offsetY),
 * PMA flag, animation and skin state.
 */
class SpineGlRenderer(private val surfaceHolder: SurfaceHolder) : SurfaceHolder.Callback {

    companion object {
        const val SLOT_PRIMARY = 0
        const val SLOT_SECONDARY = 1
        const val SLOT_COUNT = 2
    }

    /** Per-slot model state. Accessed only inside synchronized(this) blocks / render thread. */
    private class SlotState {
        var dir: File? = null
        var isPending = false
        var instance: com.spine.wallpaper.bridge.ISpineModelAdapter? = null
        var atlas: TextureAtlas? = null
        var scale = 1.0f
        var posX = 0.0f
        var posY = 0.0f
        var isPma = true
        var pendingAnimationName: String? = null
        var pendingSkinName: String? = null
        /** 已选皮肤（含基底），用于多选叠加 */
        val selectedSkins: LinkedHashSet<String> = LinkedHashSet()
        /** 模型未就绪时排队等待执行的 addSkin 列表（已 selectedSkins 之外的增量） */
        val pendingAddSkins: MutableList<String> = mutableListOf()

        fun releaseSlot() {
            try {
                instance?.dispose()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            try {
                atlas?.dispose()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            instance = null
            atlas = null
            selectedSkins.clear()
            pendingAddSkins.clear()
        }
    }

    private var isRunning = false
    private var renderThread: Thread? = null
    private var hasValidSurface = false

    /** Target frame rate limit (FPS). 15..120, default 60. */
    @Volatile private var targetFps = 60

    private val slots = Array(SLOT_COUNT) { SlotState() }

    private var batch: PolygonSpriteBatch? = null
    private var camera: OrthographicCamera? = null

    private var animationIndex = 0

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

    /** slot index -> (animations, skins) */
    var onModelLoadedListener: ((slot: Int, animations: List<String>, skins: List<String>) -> Unit)? = null

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
            slots.forEach { it.isPending = true }
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
            slots.forEach { it.isPending = true }
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
            slots.forEach { it.isPending = true }
            destroyEglSurface()
        }
    }

    /**
     * Sets the model directory for a slot. Pass null to clear (remove) the slot's model.
     */
    fun setModelDirectory(dir: File?, slot: Int = SLOT_PRIMARY) {
        val s = slotAt(slot) ?: return
        synchronized(this) {
            val changed = s.dir?.absolutePath != dir?.absolutePath
            s.dir = dir
            if (dir == null) {
                // Explicit removal: drop instance immediately on GL thread next frame
                s.isPending = true
            } else if (changed || s.instance == null) {
                s.isPending = true
            }
        }
    }

    /**
     * Sets the premultiplied-alpha rendering flag for one slot.
     * Each slot's PMA mode is independent (model textures differ between slots).
     */
    fun setPremultipliedAlpha(pma: Boolean, slot: Int = SLOT_PRIMARY) {
        val s = slotAt(slot) ?: return
        synchronized(this) {
            s.isPma = pma
            s.instance?.setPremultipliedAlpha(pma)
        }
    }

    fun updateTransform(scale: Float, x: Float, y: Float, slot: Int = SLOT_PRIMARY) {
        val s = slotAt(slot) ?: return
        synchronized(this) {
            s.scale = scale
            s.posX = x
            s.posY = y
        }
    }

    fun playAnimation(animName: String, loop: Boolean = true, slot: Int = SLOT_PRIMARY) {
        val s = slotAt(slot) ?: return
        synchronized(this) {
            val model = s.instance ?: run {
                s.pendingAnimationName = animName
                return
            }
            try {
                model.setAnimation(0, animName, loop)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setSkin(skinName: String, slot: Int = SLOT_PRIMARY) {
        val s = slotAt(slot) ?: return
        synchronized(this) {
            // 单选：清空多选状态后只保留当前一个基底皮肤
            s.selectedSkins.clear()
            s.pendingAddSkins.clear()
            s.selectedSkins.add(skinName)
            val model = s.instance ?: run {
                s.pendingSkinName = skinName
                return
            }
            try {
                model.setSkin(skinName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 多选：叠加一个皮肤，不影响之前已选的皮肤。
     * 已包含同名皮肤时为 no-op。
     */
    fun addSkin(skinName: String, slot: Int = SLOT_PRIMARY) {
        val s = slotAt(slot) ?: return
        synchronized(this) {
            if (!s.selectedSkins.add(skinName)) return  // 已选过
            val model = s.instance ?: run {
                s.pendingAddSkins.add(skinName)
                return
            }
            try {
                model.addSkin(skinName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 取消选中：从已选集合中移除一个皮肤。若移除后集合为空或缺少底层基底，
     * 则回退到首个 skin 名为基底（保证模型至少有皮肤可显示）。
     */
    fun removeSkin(skinName: String, slot: Int = SLOT_PRIMARY) {
        val s = slotAt(slot) ?: return
        synchronized(this) {
            if (!s.selectedSkins.remove(skinName)) return
            val model = s.instance
            if (s.selectedSkins.isEmpty()) {
                // 至少保留一个（data.defaultSkin 或第一个可用皮肤）
                val fallback = model?.skinNames?.firstOrNull { it.equals("default", ignoreCase = true) }
                    ?: model?.skinNames?.firstOrNull()
                if (fallback != null) {
                    s.selectedSkins.add(fallback)
                    try { model?.setSkin(fallback) } catch (e: Exception) { e.printStackTrace() }
                }
            } else {
                // 已选集合非空：当前 spine runtime 缺乏「删除单个 attachment」语义，
                // 最稳妥的方式是重放：先 setSkin 到第一个 selected，再 addSkin 其余
                try {
                    val first = s.selectedSkins.first()
                    model?.setSkin(first)
                    for (other in s.selectedSkins.drop(1)) {
                        model?.addSkin(other)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    /**
     * 整体替换已选皮肤集合（保留顺序，按集合中第一个为基底，其余为叠加）。
     * 模型未就绪时全部进入 pending 队列。
     */
    fun setSelectedSkins(skinNames: Set<String>, slot: Int = SLOT_PRIMARY) {
        val s = slotAt(slot) ?: return
        synchronized(this) {
            s.selectedSkins.clear()
            s.pendingAddSkins.clear()
            if (skinNames.isEmpty()) return
            s.selectedSkins.addAll(skinNames)
            val model = s.instance
            if (model == null) {
                s.pendingSkinName = s.selectedSkins.first()
                s.pendingAddSkins.addAll(s.selectedSkins.drop(1))
                return
            }
            try {
                val first = s.selectedSkins.first()
                model.setSkin(first)
                for (other in s.selectedSkins.drop(1)) {
                    model.addSkin(other)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun getSelectedSkins(slot: Int = SLOT_PRIMARY): List<String> {
        val s = slotAt(slot) ?: return emptyList()
        return synchronized(this) { s.selectedSkins.toList() }
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

    /**
     * Sets the frame rate limit for the render loop (frames per second).
     * The loop sleeps for the remainder of each frame budget after rendering.
     */
    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(15, 120)
    }

    /**
     * Tap: cycles the animation of the model under the finger.
     * Checks the front slot (primary, 0) first, then secondary.
     */
    fun triggerTapAnimation(screenX: Float, screenY: Float) {
        synchronized(this) {
            val target = hitTestSlot(screenX, screenY)
            val model = if (target >= 0) slots[target].instance else slots[SLOT_PRIMARY].instance ?: slots[SLOT_SECONDARY].instance
            model ?: return
            val anims = model.animationNames
            if (anims.isEmpty()) return

            animationIndex = (animationIndex + 1) % anims.size
            val nextAnim = anims[animationIndex]
            model.setAnimation(0, nextAnim, true)
        }
    }

    /**
     * Returns the slot index whose rendered bounds contain the screen point, or -1 if none.
     * Front slot (primary, 0) is checked first.
     */
    fun hitTestSlot(screenX: Float, screenY: Float): Int {
        val frame = surfaceHolder.surfaceFrame
        val width = if (frame != null && frame.width() > 0) frame.width().toFloat() else viewportWidth.toFloat()
        val height = if (frame != null && frame.height() > 0) frame.height().toFloat() else viewportHeight.toFloat()
        for (i in 0 until SLOT_COUNT) {
            val s = slots[i]
            val instance = s.instance ?: continue
            val bounds = instance.bounds
            if (bounds.size < 4) continue
            val safeScale = if (s.scale > 0.001f) s.scale else 1.0f
            // Model is centered at (width/2 + posX*width, height/2 + posY*height)
            val centerX = width / 2.0f + s.posX * width
            val centerY = height / 2.0f + s.posY * height
            val halfW = bounds[2] * safeScale / 2.0f
            val halfH = bounds[3] * safeScale / 2.0f
            if (halfW <= 0f || halfH <= 0f) continue
            if (screenX >= centerX - halfW && screenX <= centerX + halfW &&
                screenY >= centerY - halfH && screenY <= centerY + halfH
            ) {
                return i
            }
        }
        return -1
    }

    fun onResume() {
        synchronized(this) {
            slots.forEach { it.isPending = true }
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

                val frameStartMs = SystemClock.uptimeMillis()
                val now = frameStartMs
                val deltaSeconds = ((now - lastTime) / 1000.0f).coerceIn(0.001f, 0.1f)
                lastTime = now

                synchronized(this) {
                    for (i in 0 until SLOT_COUNT) {
                        val s = slots[i]
                        if (s.isPending) {
                            loadModelOnGlThread(s, i)
                            s.isPending = false
                        }
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

                // Frame pacing: sleep for the remainder of the frame budget (targetFps)
                try {
                    val frameBudgetMs = 1000.0 / targetFps
                    val elapsedMs = (SystemClock.uptimeMillis() - frameStartMs).toDouble()
                    val sleepMs = (frameBudgetMs - elapsedMs).toLong().coerceAtLeast(1L)
                    Thread.sleep(sleepMs)
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
            slots.forEach { it.isPending = true }
        }
        try {
            renderThread?.join(500)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        renderThread = null
    }

    private fun slotAt(slot: Int): SlotState? {
        return if (slot in 0 until SLOT_COUNT) slots[slot] else null
    }

    private fun loadModelOnGlThread(s: SlotState, slotIndex: Int) {
        try {
            s.releaseSlot()

            val dir = s.dir ?: return

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

            val config = configFile?.let { Live2DConfig.parse(it.readText()) }

            val atlasHandle = com.spine.wallpaper.bridge.AtlasSanitizer.sanitize(atlasFile)
            val loadedAtlas: TextureAtlas = try {
                TextureAtlas(atlasHandle)
            } catch (e: Throwable) {
                e.printStackTrace()
                try {
                    TextureAtlas(FileHandle(atlasFile))
                } catch (e2: Throwable) {
                    e2.printStackTrace()
                    TextureAtlas()
                }
            }
            s.atlas = loadedAtlas

            // Create Multi-Version Model Adapter via Multi-Runtime Dispatcher
            val instance = com.spine.wallpaper.bridge.SpineMultiRuntimeManager.createModelAdapter(
                skelFile = skelFile,
                atlas = loadedAtlas,
                scale = config?.scale ?: 1.0f,
                isPma = s.isPma
            )
            s.instance = instance

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

            val targetSkin = s.pendingSkinName?.takeIf { skinNames.contains(it) }
                ?: skinNames.firstOrNull { it.equals("default", ignoreCase = true) }
                ?: skinNames.firstOrNull()
            if (targetSkin != null) {
                try {
                    instance.setSkin(targetSkin)
                } catch (_: Exception) {}
            }
            // 若 setSkin 由上层（setSelectedSkins）预先设了基底，且 selectedSkins 已有内容，
            // 需保证 selectedSkins 与 model 实际状态一致。
            s.selectedSkins.clear()
            s.selectedSkins.add(targetSkin ?: "")
            // 应用排队中的叠加皮肤（来自 addSkin 在模型未就绪时累积的请求）
            if (s.pendingAddSkins.isNotEmpty()) {
                val pending = s.pendingAddSkins.filter { skinNames.contains(it) }
                s.pendingAddSkins.clear()
                for (name in pending) {
                    try {
                        instance.addSkin(name)
                        s.selectedSkins.add(name)
                    } catch (_: Exception) {}
                }
            }
            s.pendingSkinName = null

            if (!s.pendingAnimationName.isNullOrEmpty() && animNames.contains(s.pendingAnimationName)) {
                try {
                    instance.setAnimation(0, s.pendingAnimationName!!, true)
                } catch (_: Exception) {}
                s.pendingAnimationName = null
            }

            if (batch == null) {
                batch = PolygonSpriteBatch()
            }
            if (camera == null) {
                camera = OrthographicCamera()
            }

            onModelLoadedListener?.invoke(slotIndex, animNames, skinNames)

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

        // 1. Draw custom background texture: keep original aspect ratio
        //    (scale to cover the viewport, centered; no stretching)
        val bg = bgTexture
        if (bg != null) {
            val imgW = bg.width.toFloat()
            val imgH = bg.height.toFloat()
            if (imgW > 0f && imgH > 0f) {
                val coverScale = maxOf(width / imgW, height / imgH)
                val drawW = imgW * coverScale
                val drawH = imgH * coverScale
                val drawX = (width - drawW) / 2f
                val drawY = (height - drawH) / 2f
                pBatch.draw(bg, drawX, drawY, drawW, drawH)
            } else {
                pBatch.draw(bg, 0f, 0f, width.toFloat(), height.toFloat())
            }
        }

        // 2. Draw Spine models: slot 1 (secondary) first = behind, slot 0 (primary) LAST = on top
        for (i in SLOT_COUNT - 1 downTo 0) {
            val s = slots[i]
            val model = s.instance ?: continue
            model.setPremultipliedAlpha(s.isPma)
            model.update(delta)

            val safeScale = if (s.scale > 0.001f) s.scale else 1.0f
            val centerX = width / 2.0f + s.posX * width
            val centerY = height / 2.0f + s.posY * height
            model.setTransform(safeScale, centerX, centerY)

            model.render(pBatch)
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
            slots.forEach { it.releaseSlot() }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        bgTexture = null
        batch = null
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
