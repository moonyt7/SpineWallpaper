package com.spine.wallpaper.v36

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Array
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
import com.esotericsoftware.spine.attachments.ClippingAttachment
import com.esotericsoftware.spine.attachments.MeshAttachment
import com.esotericsoftware.spine.attachments.PathAttachment
import com.esotericsoftware.spine.attachments.PointAttachment
import com.esotericsoftware.spine.attachments.RegionAttachment
import com.spine.wallpaper.bridge.ISpineModelAdapter
import com.spine.wallpaper.bridge.SpineMultiRuntimeManager
import com.spine.wallpaper.bridge.SpineVersion
import java.io.File

/**
 * Isolated Spine 3.6 Runtime Adapter.
 * 使用官方 spine-libgdx 3.6.53.1 runtime（通过 shadow plugin 重定位到 com.spine.wallpaper.spine36）。
 * 3.6 runtime 官方支持读取 3.5 导出的数据，因此负责 Spine 3.5 / 3.6 两个版本的模型。
 *
 * 关于 PMA 的处理：
 * 3.5/3.6 导出的 PNG 通常是非 PMA 的。但不同槽位对 PMA 的需求不同：
 * - multiply 槽位（如 103230 的 sai 腮红）需要 PMA 渲染，否则 alpha 时间线淡出时
 *   会看到 sai 颜色 × 肤色的「乘色」（用户感知为「白底」）。
 * - normal 槽位的「白色高光/泪痕」类贴图（rgb 接近 255，alpha 较低）如果按 PMA 渲染，
 *   会因为 src + dst*(1-srcA) 公式导致 rgb 溢出 clamp 到 1.0，显示为纯白；
 *   按 non-PMA 渲染则显示为淡肤色叠加（自然）。
 * 因此本适配器在渲染时按槽位混合模式拆成两遍：
 *   ① multiply 槽位用 PMA 渲染（让 alpha 0 完全透明）
 *   ② 其他槽位用 non-PMA 渲染（避免高光类贴图溢出成白）
 * 贴图本身不做任何修改，保持 export 时的非 PMA 状态。
 */
class SpineV36Adapter(
    private val skelFile: File,
    private val atlas: TextureAtlas,
    private val baseScale: Float = 1.0f,
    private var isPma: Boolean = true
) : ISpineModelAdapter {

    override val runtimeVersion: SpineVersion = SpineVersion.V36

    private var skeletonData: SkeletonData? = null
    private var skeleton: Skeleton? = null
    private var animationState: AnimationState? = null
    private val renderer = SkeletonRenderer()

    private var scale = 1.0f
    private var offsetX = 0f
    private var offsetY = 0f
    // 根骨骼的 setup 缩放（模型自带），渲染时在此基础上叠加外部缩放
    private var rootSetupScaleX = 1f
    private var rootSetupScaleY = 1f

    // 缓存的 drawOrder，避免每次 render 都重新分配
    private var originalDrawOrder: Array<Slot>? = null
    private val multiplyDrawOrder = Array<Slot>()
    private val normalDrawOrder = Array<Slot>()

    init {
        load()
    }

    private fun load() {
        val handle = FileHandle(skelFile)
        val data: SkeletonData = when (skelFile.extension.lowercase()) {
            "json" -> SkeletonJson(atlas).apply { scale = baseScale }.readSkeletonData(handle)
            else -> SkeletonBinary(atlas).apply { scale = baseScale }.readSkeletonData(handle)
        }
        skeletonData = data
        skeleton = Skeleton(data).apply { setToSetupPose() }
        skeleton?.rootBone?.let { root ->
            // 3.6 Bone.setToSetupPose() 只更新 local transform，不更新 applied transform。
            // 因此 ascaleX/ascaleY 初始为 0，必须使用 local scale（getScaleX/Y）来保留 setup 缩放。
            rootSetupScaleX = root.getScaleX()
            rootSetupScaleY = root.getScaleY()
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
                // 该动画数据不完整（文件被重打包/版本错位），停用动画显示静态模型，避免每帧崩溃
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
        // 3.6 Skeleton 没有整体缩放 API，通过根骨骼缩放实现（保留模型 setup 缩放，每帧渲染前设置）
        val root = skel.rootBone
        root.setScaleX(scale * rootSetupScaleX)
        root.setScaleY(scale * rootSetupScaleY)
        skel.updateWorldTransform()

        // 按槽位混合模式拆成两组：multiply 用 PMA 渲染，其他用 non-PMA 渲染。
        // 首次渲染时按 skel.drawOrder 顺序缓存，之后每帧只更新 attachment 引用。
        if (originalDrawOrder == null) {
            originalDrawOrder = Array(skel.drawOrder)
            for (slot in skel.drawOrder) {
                if (slot.data.blendMode == BlendMode.multiply) {
                    multiplyDrawOrder.add(slot)
                } else {
                    normalDrawOrder.add(slot)
                }
            }
        }

        // 第一次：multiply 槽位，PMA 渲染（让 alpha 0 完全透明）
        if (multiplyDrawOrder.size > 0) {
            skel.drawOrder = multiplyDrawOrder
            renderer.setPremultipliedAlpha(true)
            renderer.draw(batch, skel)
        }

        // 第二次：其他槽位，non-PMA 渲染（避免高光/泪痕类贴图溢出成白）
        if (normalDrawOrder.size > 0) {
            skel.drawOrder = normalDrawOrder
            renderer.setPremultipliedAlpha(false)
            renderer.draw(batch, skel)
        }

        // 恢复 drawOrder，避免破坏外部对 skeleton 的预期
        skel.drawOrder = originalDrawOrder
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

    override fun setPremultipliedAlpha(pma: Boolean) {
        // 不同槽位 PMA 模式不同（multiply vs normal），由 render() 内部按 slot 动态决定。
        // 这里只更新字段标记，不影响实际渲染。
        this.isPma = pma
    }

    override fun setTransform(scale: Float, offsetX: Float, offsetY: Float) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    override fun hitTest(screenX: Float, screenY: Float): String? = "v36_body"

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
                override val version: SpineVersion = SpineVersion.V36
                override fun createAdapter(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean): ISpineModelAdapter {
                    return SpineV36Adapter(skelFile, atlas, scale, isPma)
                }
            })
            SpineMultiRuntimeManager.registerPeeker(object : SpineMultiRuntimeManager.ISpinePeeker {
                override val version: SpineVersion = SpineVersion.V36
                override fun peek(skelFile: File): Pair<List<String>, List<String>>? = try {
                    val loader = PeekAttachmentLoader()
                    val data: SkeletonData = when (skelFile.extension.lowercase()) {
                        "json" -> SkeletonJson(loader).apply { scale = 1f }.readSkeletonData(FileHandle(skelFile))
                        else -> SkeletonBinary(loader).apply { scale = 1f }.readSkeletonData(FileHandle(skelFile))
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
    override fun newClippingAttachment(skin: Skin, name: String): ClippingAttachment = ClippingAttachment(name)
    override fun newPathAttachment(skin: Skin, name: String): PathAttachment = PathAttachment(name)
    override fun newPointAttachment(skin: Skin, name: String): PointAttachment = PointAttachment(name)
}
