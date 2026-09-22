package com.spine.wallpaper.service

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import com.badlogic.gdx.backends.android.AndroidGL20
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.spine.wallpaper.SPINE_DBG_SHARED
import com.spine.wallpaper.SPINE_DBG_TAG_SHARED
import com.spine.wallpaper.loader.AtlasImageResampler
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
        /**
         * 「期望播放的动作」。**不是一次性 pending** —— 模型切换后重新加载时，
         * 只要新模型含同名动作就继续播它。
         *
         * 旧字段名 pendingAnimationName，加载完即置 null。于是「两个模型动作重名」时：
         * 上层 Compose 的 LaunchedEffect(activeAnimation) 以**动作名**为 key，名字没变就不重跑
         * → 不会重发 playAnimation → 新模型既没有 pending 指令，就掉回 config.idle_motion
         * 或 animNames[0]，出现「界面显示的动作 ≠ 实际播放的动作」。
         */
        var desiredAnimationName: String? = null
        var pendingSkinName: String? = null
        /**
         * 已选皮肤（含基底），用于多选叠加。
         * 它同时就是「期望皮肤」，跨模型切换保留；加载时与新模型皮肤列表做交集后重建，
         * 避免重名皮肤场景下新模型掉回 default。
         */
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
            // 注意：**不**清空 selectedSkins / desiredAnimationName / pendingAddSkins ——
            // 它们是「上层期望状态」而不是槽位资源。加载函数会在加载完成后按新模型
            // 重建 selectedSkins；这里清掉的话，模型被移除再加回来时选择就丢了，
            // 上层又因为值没变化不会重发，于是显示与实际播放再次不一致。
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

    /**
     * slot index -> (dirPath, animations, skins)
     *
     * 注意：这个回调是在 **GL 渲染线程**上、且**持有本对象锁**时发出的。
     * 监听方必须立刻把数据转投到主线程再改 Compose 状态，
     * 否则状态写入会丢失（界面表现为「切换模型后列表/当前值不刷新」）。
     *
     * `dirPath` 是这次报告对应的模型目录绝对路径 —— 加载期间用户若又切了模型，
     * 上层可以据此丢弃过期回调，避免用旧模型的列表覆盖新模型的状态。
     */
    var onModelLoadedListener:
        ((slot: Int, dirPath: String, animations: List<String>, skins: List<String>) -> Unit)? = null

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
            // 先无条件记下「期望动作」（跨模型切换保留）：
            // 模型已就绪就立刻应用；还没就绪则由加载流程在加载完成后补上。
            // 不能只在 instance == null 时记 —— 切换模型瞬间 instance 仍是旧模型的，
            // 那条指令会随旧模型一起被释放掉，新模型就只剩默认动作了。
            s.desiredAnimationName = animName
            val model = s.instance ?: return
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
                            // 先清标志再加载：加载期间若又来了新的 setModelDirectory，
                            // 它能重新置位，不会被这一帧的收尾无条件抹掉。
                            s.isPending = false
                            loadModelOnGlThread(s, i)
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
        // 记下本次加载对应的目录。加载耗时可能几百毫秒，期间用户可能又切了模型，
        // 那样本次加载结果就作废了，既不该上报、也不该清掉后来者的 pending 标志。
        val dirAtStart = s.dir?.absolutePath
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
                // 只认原始 atlas：`_` 开头的是我们在旁边生成的中间产物
                // （`_sanitized_v2_*.atlas` = 清理/尺寸校正后的 atlas，`_fixed_*.png` = 重采样贴图），
                // 目录遍历顺序不保证，绝不能让它被选中（它的内容随时可能被下次清理覆盖）。
                atlasFile = allFiles.firstOrNull {
                    val name = it.name.lowercase()
                    !it.name.startsWith("_") && (name.endsWith(".atlas") || name.endsWith(".atlas.txt"))
                } ?: allFiles.firstOrNull {
                    val name = it.name.lowercase()
                    name.endsWith(".atlas") || name.endsWith(".atlas.txt")
                }
            }

            val configFile = allFiles.firstOrNull { it.name.lowercase().endsWith(".config.json") }

            if (skelFile == null || atlasFile == null) return

            val config = configFile?.let { Live2DConfig.parse(it.readText()) }

            val sanitized = com.spine.wallpaper.bridge.AtlasSanitizer.sanitizeEx(
                atlasFile, AtlasImageResampler
            )
            val atlasHandle = sanitized.handle
            // 让「修复链路走到哪一步」在日志里可见：cache-hit = 直接复用了上次的中间产物
            // （没重解析、没重写盘）；resolved = 这次重新解析了源 atlas，
            // 此时 file 以 `_` 开头就说明刚生成了中间产物。
            if (SPINE_DBG_SHARED) {
                Log.d(
                    SPINE_DBG_TAG_SHARED,
                    "GL atlas slot=$slotIndex " +
                        (if (sanitized.fromCache) "cache-hit" else "resolved") +
                        " file=${atlasHandle.name()}"
                )
            }
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

            // ===== 动作：上层指定的（= 界面显示的那个）> config.idle_motion > 第一个 =====
            // 上层优先是关键。两个模型动作重名时，上层不会重发 playAnimation
            // （Compose 的 LaunchedEffect 以动作名为 key，名字没变就不重跑），
            // 只能靠这里兜住，否则新模型会掉回第一个动作，与界面显示不符。
            // desiredAnimationName 用后**不清空** —— 它就是期望状态，跨模型切换保留。
            val desiredAnim = s.desiredAnimationName
            var appliedAnim: String? = null
            if (!desiredAnim.isNullOrEmpty() && animNames.contains(desiredAnim)) {
                try {
                    instance.setAnimation(0, desiredAnim, true)
                    appliedAnim = desiredAnim
                } catch (_: Exception) {}
            }
            if (appliedAnim == null) {
                val idleAnim = config?.idle_motion
                if (!idleAnim.isNullOrEmpty() && animNames.contains(idleAnim)) {
                    try {
                        instance.setAnimation(0, idleAnim, true)
                        appliedAnim = idleAnim
                    } catch (_: Exception) {}
                }
            }
            if (appliedAnim == null && animNames.isNotEmpty()) {
                try {
                    instance.setAnimation(0, animNames[0], true)
                } catch (_: Exception) {}
            }

            // ===== 部件：期望集合（上层当前显示的）∩ 新模型可用皮肤 =====
            // selectedSkins 就是上层的期望值，releaseSlot() 刻意没有清它。
            val wantedSkins = s.selectedSkins.filter { skinNames.contains(it) }
            val targetSkin = wantedSkins.firstOrNull()
                ?: s.pendingSkinName?.takeIf { skinNames.contains(it) }
                ?: skinNames.firstOrNull { it.equals("default", ignoreCase = true) }
                ?: skinNames.firstOrNull()

            val appliedSkins = LinkedHashSet<String>()
            if (targetSkin != null) {
                try {
                    instance.setSkin(targetSkin)
                    appliedSkins.add(targetSkin)
                } catch (_: Exception) {}
            }
            // 期望集合里除基底外的都要叠加；再加上模型未就绪期间排队的 addSkin 请求
            val overlays = wantedSkins.filter { it != targetSkin } +
                s.pendingAddSkins.filter { skinNames.contains(it) }
            for (name in overlays) {
                if (!appliedSkins.add(name)) continue
                try {
                    instance.addSkin(name)
                } catch (_: Exception) {
                    appliedSkins.remove(name)
                }
            }
            s.pendingAddSkins.clear()
            s.pendingSkinName = null
            // 与模型实际状态对齐，并作为下一次模型切换的「期望值」继续传递
            s.selectedSkins.clear()
            s.selectedSkins.addAll(appliedSkins)

            // 一行看清「实际播了什么 / 期望播什么」。切换模型后若 anim 不等于 want，
            // 说明期望值不在新模型的列表里，属正常回退；若界面显示与 anim 不符才是 bug。
            if (SPINE_DBG_SHARED) {
                Log.d(
                    SPINE_DBG_TAG_SHARED,
                    "GL load slot=$slotIndex dir=${dirAtStart?.let { File(it).name } ?: "-"} " +
                        "anim=$appliedAnim (want=${desiredAnim ?: "-"}, avail=${animNames.size}) " +
                        "skins=$appliedSkins (avail=${skinNames.size})"
                )
            }

            if (batch == null) {
                batch = PolygonSpriteBatch()
            }
            if (camera == null) {
                camera = OrthographicCamera()
            }

            // 只有「本次加载对应的目录仍是当前目录」才上报。加载期间目录被换掉时，
            // 上报的会是旧模型的动作/部件列表，上层据此校正只会把新模型的状态改错。
            if (s.dir?.absolutePath == dirAtStart) {
                onModelLoadedListener?.invoke(
                    slotIndex, dirAtStart ?: "", animNames, skinNames
                )
            }

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
