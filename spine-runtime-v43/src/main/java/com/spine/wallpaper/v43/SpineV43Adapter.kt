package com.spine.wallpaper.v43

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.esotericsoftware.spine.AnimationState
import com.esotericsoftware.spine.AnimationStateData
import com.esotericsoftware.spine.Physics
import com.esotericsoftware.spine.Skeleton
import com.esotericsoftware.spine.SkeletonBinary
import com.esotericsoftware.spine.SkeletonData
import com.esotericsoftware.spine.SkeletonJson
import com.esotericsoftware.spine.SkeletonRenderer
import com.esotericsoftware.spine.Skin
import com.esotericsoftware.spine.attachments.AttachmentLoader
import com.esotericsoftware.spine.attachments.BoundingBoxAttachment
import com.esotericsoftware.spine.attachments.ClippingAttachment
import com.esotericsoftware.spine.attachments.MeshAttachment
import com.esotericsoftware.spine.attachments.PathAttachment
import com.esotericsoftware.spine.attachments.PointAttachment
import com.esotericsoftware.spine.attachments.RegionAttachment
import com.esotericsoftware.spine.attachments.Sequence
import com.spine.wallpaper.bridge.ISpineModelAdapter
import com.spine.wallpaper.bridge.SpineMultiRuntimeManager
import com.spine.wallpaper.bridge.SpineVersion
import java.io.File

/**
 * Isolated Spine 4.3 Runtime Adapter.
 * 使用官方 spine-libgdx 4.3.5 runtime（通过 shadow plugin 重定位到 com.spine.wallpaper.spine43）。
 *
 * 4.3 相对 4.2 的破坏性变化（已用 javap + sources 逐一核对，不能照抄 v42）：
 * 1. `Skeleton.Physics` 提升为顶层 `com.esotericsoftware.spine.Physics`；
 * 2. `Skeleton.setToSetupPose()` → `setupPose()`，`setSlotsToSetupPose()` → `setupPoseSlots()`；
 * 3. `AttachmentLoader` 所有方法签名新增 `placeholder` 参数；
 * 4. 贴图 UV 从 attachment 移入 `Sequence`，`RegionAttachment(String, Sequence)` 的
 *    sequence 不可为 null，`setRegion()` 已不存在。
 */
class SpineV43Adapter(
    private val skelFile: File,
    private val atlas: TextureAtlas,
    private val baseScale: Float = 1.0f,
    private var isPma: Boolean = true
) : ISpineModelAdapter {

    override val runtimeVersion: SpineVersion = SpineVersion.V43

    private var skeletonData: SkeletonData? = null
    private var skeleton: Skeleton? = null
    private var animationState: AnimationState? = null
    private val renderer = SkeletonRenderer()

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
            "json" -> SkeletonJson(atlas).apply { scale = baseScale }.readSkeletonData(handle)
            else -> SkeletonBinary(atlas).apply { scale = baseScale }.readSkeletonData(handle)
        }
        skeletonData = data
        skeleton = Skeleton(data).apply { setupPose() }
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
                skel.setupPose()
            }
        }
        skel.updateWorldTransform(Physics.update)
    }

    override fun render(batch: PolygonSpriteBatch) {
        val skel = skeleton ?: return
        skel.x = offsetX
        skel.y = offsetY
        skel.scaleX = scale
        skel.scaleY = scale
        skel.updateWorldTransform(Physics.update)
        renderer.draw(batch, skel)
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
            skeleton?.setupPoseSlots()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * 多选叠加：保留当前皮肤，把目标皮肤合并进来（同名 slot+attachment 由新皮肤覆盖）。
     *
     * 官方 Spine 的 Skeleton 没有 addSkin，必须借助 Skin 组合实现：
     * 新建一个 Skin，先合并「当前皮肤」再合并「目标皮肤」，最后 setSkin 回 skeleton。
     */
    override fun addSkin(skinName: String) {
        try {
            val skel = skeleton ?: return
            val source = skeletonData?.findSkin(skinName) ?: return
            val current = skel.skin
            if (current == null) {
                skel.setSkin(source)
                skel.setupPoseSlots()
                return
            }
            if (current === source) return
            val combined = Skin(COMBINED_SKIN_NAME)
            combined.addSkin(current)
            combined.addSkin(source)
            skel.setSkin(combined)
            skel.setupPoseSlots()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun setPremultipliedAlpha(pma: Boolean) {
        this.isPma = pma
        renderer.setPremultipliedAlpha(pma)
    }

    override fun setTransform(scale: Float, offsetX: Float, offsetY: Float) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    override fun hitTest(screenX: Float, screenY: Float): String? = "v43_body"

    override fun dispose() {
        skeletonData = null
        skeleton = null
        animationState = null
    }

    companion object {
        fun register() {
            SpineMultiRuntimeManager.registerFactory(object : SpineMultiRuntimeManager.IAdapterFactory {
                override val version: SpineVersion = SpineVersion.V43
                override fun createAdapter(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean): ISpineModelAdapter {
                    return SpineV43Adapter(skelFile, atlas, scale, isPma)
                }
            })
            SpineMultiRuntimeManager.registerPeeker(object : SpineMultiRuntimeManager.ISpinePeeker {
                override val version: SpineVersion = SpineVersion.V43
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

/**
 * 无 GL 环境下的哑附件加载器：仅用于离屏解析动画/皮肤名，不参与渲染。
 *
 * 4.3 的贴图 UV 由 `Sequence` 计算（`RegionAttachment` 不再持有 TextureRegion），
 * 因此这里需要给 sequence 填一个占位的空 TextureRegion —— 官方 `computeUVs(@Null TextureRegion, ...)`
 * 虽允许 null，但填占位更稳妥，避免后续 update 路径上出现空指针。
 */
private class PeekAttachmentLoader : AttachmentLoader {
    override fun newRegionAttachment(
        skin: Skin, placeholder: String, name: String, path: String, sequence: Sequence
    ): RegionAttachment {
        fillDummyRegions(sequence)
        return RegionAttachment(name, sequence)
    }

    override fun newMeshAttachment(
        skin: Skin, placeholder: String, name: String, path: String, sequence: Sequence
    ): MeshAttachment {
        fillDummyRegions(sequence)
        return MeshAttachment(name, sequence)
    }

    override fun newBoundingBoxAttachment(skin: Skin, placeholder: String, name: String): BoundingBoxAttachment =
        BoundingBoxAttachment(name)

    override fun newClippingAttachment(skin: Skin, placeholder: String, name: String): ClippingAttachment =
        ClippingAttachment(name)

    override fun newPathAttachment(skin: Skin, placeholder: String, name: String): PathAttachment =
        PathAttachment(name)

    override fun newPointAttachment(skin: Skin, placeholder: String, name: String): PointAttachment =
        PointAttachment(name)

    private fun fillDummyRegions(sequence: Sequence) {
        val regions = sequence.regions ?: return
        for (i in regions.indices) {
            if (regions[i] == null) regions[i] = TextureRegion()
        }
    }
}

/** 多选叠加时临时构造的复合皮肤名，仅存在于运行时，不是模型自带皮肤。 */
private const val COMBINED_SKIN_NAME = "__combined__"
