package com.spine.wallpaper.v35

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Array as GdxArray
import com.esotericsoftware.spine.AnimationState
import com.esotericsoftware.spine.AnimationStateData
import com.esotericsoftware.spine.BlendMode
import com.esotericsoftware.spine.Skeleton
import com.esotericsoftware.spine.SkeletonBinary
import com.esotericsoftware.spine.SkeletonData
import com.esotericsoftware.spine.SkeletonJson
import com.esotericsoftware.spine.SkeletonRenderer
import com.esotericsoftware.spine.Skin
import com.esotericsoftware.spine.Slot
import com.esotericsoftware.spine.attachments.AttachmentLoader
import com.esotericsoftware.spine.attachments.BoundingBoxAttachment
import com.esotericsoftware.spine.attachments.MeshAttachment
import com.esotericsoftware.spine.attachments.PathAttachment
import com.esotericsoftware.spine.attachments.RegionAttachment
import com.spine.wallpaper.bridge.ISpineModelAdapter
import com.spine.wallpaper.bridge.SpineMultiRuntimeManager
import com.spine.wallpaper.bridge.SpineVersion
import java.io.File

/**
 * Isolated Spine 3.5 Runtime Adapter.
 * 使用官方 spine-libgdx 3.5.51.1 runtime（通过 shadow plugin 重定位到 com.spine.wallpaper.spine35）。
 *
 * 为什么 3.5 要单独一个模块（不能让 v36 顶替）：
 * - 3.6 runtime 读 **3.5 导出的 .json** 可以（版本兼容分支），但读 **3.5 的 .skel** 会抛
 *   `ArrayIndexOutOfBoundsException`（readVertices / attachment 段布局变了）。
 * - 实测：3.5 的 spineboy.skel / raptor.skel 用 3.6 → 崩；用 3.5 → 8 / 4 个动画全出。
 *
 * 与 3.6 的 API 差异（本文件已按 3.5 处理）：
 * 1. `SkeletonRenderer` 是**泛型** `SkeletonRenderer<T extends Batch>`，需显式给类型参数。
 * 2. `AttachmentLoader` 只有 **4 个**方法（无 ClippingAttachment / PointAttachment —— 3.5 没这两种附件）。
 * 3. `Skin` **没有** `addAttachments(Skin)`（那是 3.6 才加的），只有包私有的 `attachAll`。
 *    ⇒ 多选叠加皮肤改为遍历 slot 逐个 `addAttachment` 手工合并。
 *
 * PMA 处理与 v36 一致：按槽位混合模式拆成两遍（multiply 用 PMA、其余用 non-PMA）。
 */
class SpineV35Adapter(
    private val skelFile: File,
    private val atlas: TextureAtlas,
    private val baseScale: Float = 1.0f,
    private var isPma: Boolean = true
) : ISpineModelAdapter {

    override val runtimeVersion: SpineVersion = SpineVersion.V35

    private var skeletonData: SkeletonData? = null
    private var skeleton: Skeleton? = null
    private var animationState: AnimationState? = null
    // 注意：这里**故意不用** 3.5 自带的 SkeletonRenderer，它的 Batch.draw 调用与
    // 现代 libGDX 的 count 语义不兼容（详见 drawSkeleton 的注释）。

    private var scale = 1.0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var rootSetupScaleX = 1f
    private var rootSetupScaleY = 1f

    private var originalDrawOrder: GdxArray<Slot>? = null
    private val multiplyDrawOrder = GdxArray<Slot>()
    private val normalDrawOrder = GdxArray<Slot>()

    init {
        load()
    }

    private fun load() {
        val handle = FileHandle(skelFile)
        val data: SkeletonData = when (skelFile.extension.lowercase()) {
            "json" -> SkeletonJson(atlas).apply { setScale(baseScale) }.readSkeletonData(handle)
            else -> SkeletonBinary(atlas).apply { setScale(baseScale) }.readSkeletonData(handle)
        }
        skeletonData = data
        skeleton = Skeleton(data).apply { setToSetupPose() }
        skeleton?.rootBone?.let { root ->
            // 3.5 的 Bone.setToSetupPose() 同样只更新 local transform，用 local scale 保留 setup 缩放
            rootSetupScaleX = root.scaleX
            rootSetupScaleY = root.scaleY
        }
        val stateData = AnimationStateData(data)
        stateData.defaultMix = 0.2f
        animationState = AnimationState(stateData)
    }

    override val animationNames: List<String>
        get() = skeletonData?.animations?.map { it.name } ?: emptyList()

    override val skinNames: List<String>
        get() = skeletonData?.skins?.map { it.name } ?: emptyList()

    override val bounds: FloatArray
        get() {
            val d = skeletonData ?: return floatArrayOf(-200f, -200f, 400f, 400f)
            return floatArrayOf(-d.width / 2f, -d.height / 2f, d.width, d.height)
        }

    override fun update(deltaSeconds: Float) {
        val state = animationState
        val skel = skeleton ?: return
        if (state != null) {
            try {
                state.update(deltaSeconds)
                state.apply(skel)
            } catch (e: Throwable) {
                e.printStackTrace()
                animationState = null
                skel.setToSetupPose()
            }
        }
        skel.updateWorldTransform()
    }

    override fun render(batch: PolygonSpriteBatch) {
        val skel = skeleton ?: return
        skel.x = offsetX
        skel.y = offsetY
        val root = skel.rootBone
        if (root != null) {
            root.scaleX = scale * rootSetupScaleX
            root.scaleY = scale * rootSetupScaleY
        }
        skel.updateWorldTransform()

        if (originalDrawOrder == null) {
            originalDrawOrder = GdxArray(skel.drawOrder)
            for (slot in skel.drawOrder) {
                if (slot.data.blendMode == BlendMode.multiply) {
                    multiplyDrawOrder.add(slot)
                } else {
                    normalDrawOrder.add(slot)
                }
            }
        }

        if (multiplyDrawOrder.size > 0) {
            skel.drawOrder = multiplyDrawOrder
            drawSkeleton(batch, skel, true)
        }

        if (normalDrawOrder.size > 0) {
            skel.drawOrder = normalDrawOrder
            drawSkeleton(batch, skel, false)
        }

        skel.drawOrder = originalDrawOrder
    }

    /**
     * 自带的绘制实现（**不能用 3.5 runtime 自带的 SkeletonRenderer**）。
     *
     * 3.5 的 `SkeletonRenderer.draw(Batch, Skeleton)` 内部是：
     * ```java
     * batch.draw(texture, vertices, 0, vertices.length);   // vertices.length = 20
     * ```
     * 但 libGDX 的 `Batch.draw(Texture, float[], int, int)` 里 `count` 语义是**顶点数**，
     * 一个顶点占 5 个 float（x, y, color, u, v）。传 20 会被当成「20 个顶点」→ 多读 5 倍数据、
     * 顶点颜色/UV 全面错位 → 模型肉眼完全不可见（不报错、avail 也正常，极难察觉）。
     *
     * 3.5 时代 gdx 的这个重载语义不同，所以这份 runtime 只有在 gdx 1.9 前后才是对的。
     * 正解是走带 triangles 的 7 参重载，并且按 float 个数传 count。
     */
    private fun drawSkeleton(batch: PolygonSpriteBatch, skel: Skeleton, pma: Boolean) {
        val srcFunc = if (pma) GL20.GL_ONE else GL20.GL_SRC_ALPHA
        batch.setBlendFunction(srcFunc, GL20.GL_ONE_MINUS_SRC_ALPHA)
        var additive = false

        for (slot in skel.drawOrder) {
            val att = slot.attachment ?: continue
            val texture = when (att) {
                is RegionAttachment -> att.region?.texture
                is MeshAttachment -> att.region?.texture
                else -> null
            } ?: continue

            val wantAdditive = slot.data.blendMode == BlendMode.additive
            if (wantAdditive != additive) {
                additive = wantAdditive
                batch.setBlendFunction(srcFunc, if (additive) GL20.GL_ONE else GL20.GL_ONE_MINUS_SRC_ALPHA)
            }

            when (att) {
                is RegionAttachment -> {
                    att.updateWorldVertices(slot, pma)
                    val v = att.worldVertices
                    batch.draw(texture, v, 0, v.size, QUAD_TRIANGLES, 0, QUAD_TRIANGLES.size)
                }
                is MeshAttachment -> {
                    att.updateWorldVertices(slot, pma)
                    val v = att.worldVertices
                    val tri = att.triangles
                    batch.draw(texture, v, 0, v.size, tri, 0, tri.size)
                }
            }
        }
        // 恢复非 additive 混合，避免污染后续绘制
        if (additive) batch.setBlendFunction(srcFunc, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun setAnimation(trackIndex: Int, animationName: String, loop: Boolean) {
        try {
            var state = animationState
            if (state == null) {
                val data = skeletonData ?: return
                val stateData = AnimationStateData(data)
                stateData.defaultMix = 0.2f
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
            skeleton?.setSlotsToSetupPose()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * 多选叠加：保留当前皮肤，把目标皮肤合并进来（同名 slot+attachment 由新皮肤覆盖）。
     *
     * 3.5 的 Skin **没有** addAttachments（3.6 才有），包私有的 attachAll 也不便从外部调用，
     * 所以这里用公开 API 手工合并：对每个 slot 索引取出两张皮肤的附件名，逐个 addAttachment。
     */
    override fun addSkin(skinName: String) {
        try {
            val skel = skeleton ?: return
            val data = skeletonData ?: return
            val source = data.findSkin(skinName) ?: return
            val current = skel.skin
            if (current == null) {
                skel.setSkin(source)
                skel.setSlotsToSetupPose()
                return
            }
            if (current === source) return

            val combined = Skin(COMBINED_SKIN_NAME)
            val slotCount = data.slots.size
            mergeSkin(combined, current, slotCount)
            mergeSkin(combined, source, slotCount)
            skel.setSkin(combined)
            skel.setSlotsToSetupPose()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun mergeSkin(dst: Skin, src: Skin, slotCount: Int) {
        val names = GdxArray<String>()
        for (i in 0 until slotCount) {
            names.clear()
            src.findNamesForSlot(i, names)
            for (n in names) {
                dst.addAttachment(i, n, src.getAttachment(i, n))
            }
        }
    }

    override fun setPremultipliedAlpha(pma: Boolean) {
        // 不同槽位 PMA 模式不同（multiply vs normal），由 render() 内部按 slot 动态决定
        this.isPma = pma
    }

    override fun setTransform(scale: Float, offsetX: Float, offsetY: Float) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    override fun hitTest(screenX: Float, screenY: Float): String? = "v35_body"

    override fun dispose() {
        skeletonData = null
        skeleton = null
        animationState = null
        originalDrawOrder = null
        multiplyDrawOrder.clear()
        normalDrawOrder.clear()
    }

    companion object {
        fun register() {
            SpineMultiRuntimeManager.registerFactory(object : SpineMultiRuntimeManager.IAdapterFactory {
                override val version: SpineVersion = SpineVersion.V35
                override fun createAdapter(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean): ISpineModelAdapter {
                    return SpineV35Adapter(skelFile, atlas, scale, isPma)
                }
            })
            SpineMultiRuntimeManager.registerPeeker(object : SpineMultiRuntimeManager.ISpinePeeker {
                override val version: SpineVersion = SpineVersion.V35
                override fun peek(skelFile: File): Pair<List<String>, List<String>>? = try {
                    val loader = PeekAttachmentLoader()
                    val data: SkeletonData = when (skelFile.extension.lowercase()) {
                        "json" -> SkeletonJson(loader).apply { setScale(1f) }.readSkeletonData(FileHandle(skelFile))
                        else -> SkeletonBinary(loader).apply { setScale(1f) }.readSkeletonData(FileHandle(skelFile))
                    }
                    val anims = data.animations.map { it.name }
                    val skins = data.skins.map { it.name }
                    if (anims.isEmpty() && skins.isEmpty()) null else Pair(anims, skins)
                } catch (e: Throwable) {
                    null
                }
            })
        }
    }
}

/** 无 GL 环境下的哑附件加载器：返回不携带真实纹理的附件，仅用于离屏解析动画/皮肤名 */
private class PeekAttachmentLoader : AttachmentLoader {
    override fun newRegionAttachment(skin: Skin, name: String, path: String): RegionAttachment {
        return RegionAttachment(name).apply { setRegion(TextureRegion()) }
    }
    override fun newMeshAttachment(skin: Skin, name: String, path: String): MeshAttachment {
        return MeshAttachment(name).apply { setRegion(TextureRegion()) }
    }
    override fun newBoundingBoxAttachment(skin: Skin, name: String): BoundingBoxAttachment = BoundingBoxAttachment(name)
    override fun newPathAttachment(skin: Skin, name: String): PathAttachment = PathAttachment(name)
}

/** 多选叠加时临时构造的复合皮肤名，仅存在于运行时，不是模型自带皮肤。 */
private const val COMBINED_SKIN_NAME = "__combined__"

/** region 附件的标准四边形三角索引（两个三角形）。 */
private val QUAD_TRIANGLES = shortArrayOf(0, 1, 2, 2, 3, 0)
