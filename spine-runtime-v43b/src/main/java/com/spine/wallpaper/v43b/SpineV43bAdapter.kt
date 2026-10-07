package com.spine.wallpaper.v43b

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.esotericsoftware.spine.AnimationState
import com.esotericsoftware.spine.AnimationStateData
import com.esotericsoftware.spine.Physics
import com.esotericsoftware.spine.Skeleton
import com.esotericsoftware.spine.SkeletonBinary
import com.esotericsoftware.spine.SkeletonData
import com.esotericsoftware.spine.SkeletonJson
import com.esotericsoftware.spine.SkeletonRenderer
import com.esotericsoftware.spine.Skin
import com.esotericsoftware.spine.V43bSupport
import com.esotericsoftware.spine.utils.TwoColorPolygonBatch
import com.spine.wallpaper.bridge.ISpineModelAdapter
import com.spine.wallpaper.bridge.SpineMultiRuntimeManager
import com.spine.wallpaper.bridge.SpineVersion
import java.io.File

/**
 * Isolated **Spine 4.3.39-beta（dev 窗口）** Runtime Adapter。
 *
 * ## 为什么单独做一版
 * `hero_11000501` / `heroCG_11000501` 这批模型是版本串 `4.3.39-beta` 的**中间格式**：
 * 骨骼核心字段顺序是 4.3 的（`inherit` 在 `length` 前），但尾部没有 `iconSize/iconRotation`，
 * 且 `readVertices` 还是**逐顶点读 boneCount** 的旧版。已发布的 4.2.12 / 4.3.0~4.3.5 **全部读不了**
 * （4.3.5 会抛 `NegativeArraySizeException` 之类的错），于是被调度器降级到内置兜底引擎，
 * 表现为「动作只剩 1 个（idle）」。
 *
 * 实测：`4.3.39-beta` 的 libgdx runtime 能把这批文件**完整读出来**（两个 hero：10 / 13 个动画
 * 与皮肤名全对）。本模块就是把那份 runtime 隔离进来。
 *
 * ## 与 4.3.5 的 API 差异（逐条 javap 核对，不能照抄 SpineV43Adapter）
 * 1. `SkeletonBinary / SkeletonJson` 的构造器**只收 `TextureAtlas`**（不是 `AttachmentLoader`）；
 * 2. `AttachmentLoader` 是 **4 参**（没有 `placeholder`），`RegionAttachment(String)` + `setSequence(Seq)`；
 * 3. `SkeletonData.width/height/version`、`Skeleton.x/y/skin/scaleX`、`AnimationStateData.defaultMix`
 *    都是包私有且无 getter → 统一走同包助手 [V43bSupport]；
 * 4. `Skin.addSkin` 被撤了 → [V43bSupport.combineSkins] 自己合；
 * 5. ⚠️ **`SkeletonRenderer.draw` 只收 `TwoColorPolygonBatch`**，而它是 `implements PolygonBatch`
 *    的独立实现（不是 `PolygonSpriteBatch` 的子类），宿主的 `PolygonSpriteBatch` 传不进去；
 *    而且它用 **6 float/顶点**（x, y, color, u, v, light, dark 里的 light/dark 双色）的布局，
 *    与 gdx `PolygonSpriteBatch` 的 5 float 布局**不兼容**，所以不能简单转调。
 *    → 本 adapter **自带一个 `TwoColorPolygonBatch`**，在宿主批次 begin/end 之间「插空」渲染。
 */
class SpineV43bAdapter(
    private val skelFile: File,
    private val atlas: TextureAtlas,
    private val baseScale: Float = 1.0f,
    private var isPma: Boolean = true
) : ISpineModelAdapter {

    override val runtimeVersion: SpineVersion = SpineVersion.V43B

    private var skeletonData: SkeletonData? = null
    private var skeleton: Skeleton? = null
    private var animationState: AnimationState? = null
    private val renderer = SkeletonRenderer()

    /**
     * dev 版渲染器只吃 `TwoColorPolygonBatch`。惰性创建（构造会建 Mesh + 编译着色器，需要 GL 上下文，
     * 而 adapter 可能在非 GL 线程被构造）。
     */
    private var twoColorBatch: TwoColorPolygonBatch? = null

    private var scale = 1.0f
    private var offsetX = 0f
    private var offsetY = 0f

    init {
        renderer.setPremultipliedAlpha(isPma)
        load()
    }

    private fun load() {
        val handle = FileHandle(skelFile)
        val data: SkeletonData = when (skelFile.extension.lowercase()) {
            "json" -> SkeletonJson(atlas).apply { setScale(baseScale) }.readSkeletonData(handle)
            else -> SkeletonBinary(atlas).apply { setScale(baseScale) }.readSkeletonData(handle)
        }
        skeletonData = data
        skeleton = Skeleton(data).apply { setupPose() }
        val stateData = AnimationStateData(data)
        stateData.setDefaultMix(0.2f)
        animationState = AnimationState(stateData)
    }

    override val animationNames: List<String>
        get() = skeletonData?.animations?.map { it.name } ?: emptyList()

    override val skinNames: List<String>
        get() = skeletonData?.skins?.map { it.name } ?: emptyList()

    override val bounds: FloatArray
        get() {
            val d = skeletonData ?: return FALLBACK_BOUNDS
            val w = V43bSupport.getWidth(d)
            val h = V43bSupport.getHeight(d)
            if (w <= 0f || h <= 0f) return FALLBACK_BOUNDS
            return floatArrayOf(-w / 2f, -h / 2f, w, h)
        }

    override fun update(deltaSeconds: Float) {
        val state = animationState
        val skel = skeleton ?: return
        if (state != null) {
            try {
                state.update(deltaSeconds)
                state.apply(skel)
            } catch (e: Throwable) {
                // 该动画数据不完整（文件被重打包/版本错位），停用动画显示静态模型，避免每帧崩溃
                e.printStackTrace()
                animationState = null
                skel.setupPose()
            }
        }
        skel.updateWorldTransform(Physics.update)
    }

    override fun render(batch: PolygonSpriteBatch) {
        val skel = skeleton ?: return
        V43bSupport.setPosition(skel, offsetX, offsetY)
        skel.setScale(scale, scale)
        skel.updateWorldTransform(Physics.update)

        val tc = twoColorBatch ?: TwoColorPolygonBatch().also {
            it.setPremultipliedAlpha(isPma)
            twoColorBatch = it
        }
        tc.setPremultipliedAlpha(isPma)
        tc.setProjectionMatrix(batch.projectionMatrix)
        tc.setTransformMatrix(batch.transformMatrix)

        // 宿主批次此时正处于 begin 状态（背景已画完）：先 flush 收尾，插空画完自己的再交还，
        // 以保证图层前后顺序与其他 runtime 一致。
        val wasDrawing = batch.isDrawing
        if (wasDrawing) batch.end()
        try {
            tc.begin()
            renderer.draw(tc, skel)
            tc.end()
        } catch (t: Throwable) {
            t.printStackTrace()
            try {
                if (tc.isDrawing) tc.end()
            } catch (_: Throwable) {
            }
        } finally {
            if (wasDrawing && !batch.isDrawing) batch.begin()
        }
    }

    override fun setAnimation(trackIndex: Int, animationName: String, loop: Boolean) {
        try {
            var state = animationState
            if (state == null) {
                val data = skeletonData ?: return
                val stateData = AnimationStateData(data)
                stateData.setDefaultMix(0.2f)
                state = AnimationState(stateData)
                animationState = state
            }
            state.setAnimation(trackIndex, animationName, loop)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun setSkin(skinName: String) {
        try {
            skeleton?.setSkin(skinName)
            skeleton?.setupPoseSlots()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * 多选叠加：保留当前皮肤，把目标皮肤合并进来（同名 slot+attachment 由新皮肤覆盖）。
     * 这一版 runtime 没有 `Skin.addSkin`，用 [V43bSupport.combineSkins] 组合。
     */
    override fun addSkin(skinName: String) {
        try {
            val skel = skeleton ?: return
            val source = skeletonData?.findSkin(skinName) ?: return
            val current = V43bSupport.getSkin(skel)
            if (current == null) {
                skel.setSkin(source)
                skel.setupPoseSlots()
                return
            }
            if (current === source) return
            skel.setSkin(V43bSupport.combineSkins(current, source))
            skel.setupPoseSlots()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun setPremultipliedAlpha(pma: Boolean) {
        this.isPma = pma
        renderer.setPremultipliedAlpha(pma)
        twoColorBatch?.setPremultipliedAlpha(pma)
    }

    override fun setTransform(scale: Float, offsetX: Float, offsetY: Float) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    override fun hitTest(screenX: Float, screenY: Float): String? = "v43b_body"

    override fun dispose() {
        skeletonData = null
        skeleton = null
        animationState = null
        try {
            twoColorBatch?.dispose()
        } catch (_: Throwable) {
        }
        twoColorBatch = null
    }

    companion object {
        private val FALLBACK_BOUNDS = floatArrayOf(-200f, -200f, 400f, 400f)

        fun register() {
            SpineMultiRuntimeManager.registerFactory(object : SpineMultiRuntimeManager.IAdapterFactory {
                override val version: SpineVersion = SpineVersion.V43B
                override fun createAdapter(
                    skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean
                ): ISpineModelAdapter = SpineV43bAdapter(skelFile, atlas, scale, isPma)
            })
            SpineMultiRuntimeManager.registerPeeker(object : SpineMultiRuntimeManager.ISpinePeeker {
                override val version: SpineVersion = SpineVersion.V43B

                /**
                 * 这一版**无法离屏嗅探**，固定返回 `null` —— 注册它只为让调度器立刻放弃，
                 * 不再白跑 V42…V36（那些注定也读不了）。
                 *
                 * 下面三条都实测过，**别再试了**：
                 * 1. 该版 `SkeletonBinary / SkeletonJson` 只保留 `(TextureAtlas)` 构造器，
                 *    源码里那个 `(AttachmentLoader)` 构造器已被删除，外面无法传 loader；
                 * 2. 离屏没有 GL ⇒ 构造不出真 `TextureAtlas`；而「空纹理占位 `AtlasRegion`」
                 *    **物理上造不出来** —— `AtlasRegion` 的每个构造器都要算 `1f/texture.getWidth()`，
                 *    传 null texture 必 NPE（`allowMissingRegions=true` 也不行：
                 *    它把 null 交给 `MeshAttachment.setRegion`，那里直接抛 `region cannot be null`）；
                 * 3. 剩下的唯一路子是反射改写 `SkeletonLoader.attachmentLoader`
                 *    （package-private final）—— 桌面 JVM 上有效，**但 ART 上不生效**
                 *    （final 字段被常量折叠），内部仍用空 atlas，最终抛
                 *    `Region not found in atlas: xxx`，被这里吞成 null。
                 *
                 * ⇒ 动作/部件名的可靠来源是**渲染器真正加载之后**（那时才有真 atlas + GL），
                 * 由 `SpineViewCompose` 的 onModelLoaded 回调经
                 * `SpineModelLoader.updateModelAnimSkinsByDir` 回写 prefs。
                 */
                override fun peek(skelFile: File): Pair<List<String>, List<String>>? = null
            })
        }
    }
}
