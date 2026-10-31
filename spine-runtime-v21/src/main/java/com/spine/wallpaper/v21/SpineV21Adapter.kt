package com.spine.wallpaper.v21

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Array as GdxArray
import com.esotericsoftware.spine.AnimationState
import com.esotericsoftware.spine.AnimationStateData
import com.esotericsoftware.spine.Skeleton
import com.esotericsoftware.spine.SkeletonBinary
import com.esotericsoftware.spine.SkeletonData
import com.esotericsoftware.spine.SkeletonJson
import com.esotericsoftware.spine.SkeletonRenderer
import com.esotericsoftware.spine.Skin
import com.esotericsoftware.spine.V21Support
import com.esotericsoftware.spine.attachments.AttachmentLoader
import com.esotericsoftware.spine.attachments.BoundingBoxAttachment
import com.esotericsoftware.spine.attachments.MeshAttachment
import com.esotericsoftware.spine.attachments.RegionAttachment
import com.esotericsoftware.spine.attachments.SkinnedMeshAttachment
import com.spine.wallpaper.bridge.ISpineModelAdapter
import com.spine.wallpaper.bridge.SpineMultiRuntimeManager
import com.spine.wallpaper.bridge.SpineVersion
import java.io.File

/**
 * Isolated Spine 2.1 Runtime Adapter.
 *
 * 2.1 没有 maven 产物，runtime 源码直接内嵌在 `src/main/java/com/esotericsoftware/spine`
 * （取自 spine-runtimes tag 2.1.25），随 shadow 一起重定位到 `com.spine.wallpaper.spine21`。
 *
 * 与 3.x 的 API 差异（本文件已按 2.1 处理）：
 * 1. `SkeletonRenderer` **不是泛型**，`draw(PolygonSpriteBatch, Skeleton)` 直接可用。
 * 2. `AttachmentLoader` 有 **4 个**方法，其中 `newSkinnedMeshAttachment` 是 2.1 独有
 *    （3.x 的 MeshAttachment 在这里被拆成 MeshAttachment + SkinnedMeshAttachment）。
 * 3. **`Bone.scaleX/scaleY` 是包私有字段**（无 getter/setter），2.1 的 Skeleton 又没有整体缩放
 *    ⇒ 只能靠同包类 [V21Support] 读写根骨骼缩放。这是本模块必须内嵌源码的**根本原因**：
 *    网上找不到 2.1 的 jar，而任何「外部改根骨骼缩放」的写法在 2.1 上都编译不过。
 * 4. `SlotData` 只有 `additiveBlending` 布尔值，**没有 BlendMode 枚举**（3.6 才有 multiply/screen）
 *    ⇒ 不做 v36 那种「按混合模式拆两遍渲染」，混合由 runtime 内部按槽位处理。
 * 5. `Skin` 没有 `addAttachments`（3.6 才有），多选皮肤用 slot 遍历手工合并。
 * 6. 2.1 的 `.skel` / `.json` **头部没有可靠版本串**（老导出器连 skeleton 节点都没有，或写 "Dev"），
 *    所以版本识别依赖上层 [com.spine.wallpaper.bridge.SpineVersionDetector] 的结构启发式 + 降级链。
 */
class SpineV21Adapter(
    private val skelFile: File,
    private val atlas: TextureAtlas,
    private val baseScale: Float = 1.0f,
    private var isPma: Boolean = true
) : ISpineModelAdapter {

    override val runtimeVersion: SpineVersion = SpineVersion.V21

    private var skeletonData: SkeletonData? = null
    private var skeleton: Skeleton? = null
    private var animationState: AnimationState? = null
    private val renderer = SkeletonRenderer()

    private var scale = 1.0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var rootSetupScaleX = 1f
    private var rootSetupScaleY = 1f

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
        // 2.1 的 setup 缩放同样只能从根骨骼的 local scale 取（ascale 初始为 0）
        rootSetupScaleX = V21Support.getRootScaleX(skeleton)
        rootSetupScaleY = V21Support.getRootScaleY(skeleton)
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
                // 动画数据不完整时停用动画（保留静态模型），避免每帧崩溃
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
        renderer.setPremultipliedAlpha(isPma)
        // 2.1 无整体缩放 API，只能改根骨骼（包私有字段 → 同包 helper）
        V21Support.setRootScale(skel, scale * rootSetupScaleX, scale * rootSetupScaleY)
        skel.updateWorldTransform()

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
            skeleton?.setSlotsToSetupPose()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * 多选叠加：把目标皮肤合并进当前皮肤（同名 slot+attachment 由新皮肤覆盖）。
     * 2.1 的 Skin 只有 addAttachment(int, String, Attachment)，没有 3.6 的 addAttachments(Skin)，
     * 所以按 slot 索引逐个搬运。
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
        this.isPma = pma
    }

    override fun setTransform(scale: Float, offsetX: Float, offsetY: Float) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    override fun hitTest(screenX: Float, screenY: Float): String? = "v21_body"

    override fun dispose() {
        skeletonData = null
        skeleton = null
        animationState = null
    }

    companion object {
        fun register() {
            SpineMultiRuntimeManager.registerFactory(object : SpineMultiRuntimeManager.IAdapterFactory {
                override val version: SpineVersion = SpineVersion.V21
                override fun createAdapter(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean): ISpineModelAdapter {
                    return SpineV21Adapter(skelFile, atlas, scale, isPma)
                }
            })
            SpineMultiRuntimeManager.registerPeeker(object : SpineMultiRuntimeManager.ISpinePeeker {
                override val version: SpineVersion = SpineVersion.V21
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

/** 无 GL 环境下的哑附件加载器：返回不携带真实纹理的附件，仅用于离屏解析动画/皮肤名。 */
private class PeekAttachmentLoader : AttachmentLoader {
    override fun newRegionAttachment(skin: Skin, name: String, path: String): RegionAttachment {
        return RegionAttachment(name).apply { setRegion(TextureRegion()) }
    }
    override fun newMeshAttachment(skin: Skin, name: String, path: String): MeshAttachment {
        return MeshAttachment(name).apply { setRegion(TextureRegion()) }
    }
    // 2.1 独有：带蒙皮的网格是独立类型
    override fun newSkinnedMeshAttachment(skin: Skin, name: String, path: String): SkinnedMeshAttachment {
        return SkinnedMeshAttachment(name).apply { setRegion(TextureRegion()) }
    }
    override fun newBoundingBoxAttachment(skin: Skin, name: String): BoundingBoxAttachment = BoundingBoxAttachment(name)
}

/** 多选叠加时临时构造的复合皮肤名，仅存在于运行时，不是模型自带皮肤。 */
private const val COMBINED_SKIN_NAME = "__combined__"
