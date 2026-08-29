package com.spine.wallpaper.v37

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.esotericsoftware.spine.AnimationState
import com.esotericsoftware.spine.AnimationStateData
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
import com.spine.wallpaper.bridge.ISpineModelAdapter
import com.spine.wallpaper.bridge.SpineMultiRuntimeManager
import com.spine.wallpaper.bridge.SpineVersion
import java.io.File

/**
 * Isolated Spine 3.7 Runtime Adapter.
 * 使用官方 spine-libgdx 3.7.83.1 runtime（通过 shadow plugin 重定位到 com.spine.wallpaper.spine37）。
 * 3.7 导出的 JSON 仍是老 skins 字典格式，3.8+ runtime 无法解析，必须由本模块处理。
 */
class SpineV37Adapter(
    private val skelFile: File,
    private val atlas: TextureAtlas,
    private val baseScale: Float = 1.0f,
    private var isPma: Boolean = true
) : ISpineModelAdapter {

    override val runtimeVersion: SpineVersion = SpineVersion.V37

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
        skeleton = Skeleton(data).apply { setToSetupPose() }
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
        skel.scaleX = scale
        skel.scaleY = scale
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

    override fun setPremultipliedAlpha(pma: Boolean) {
        this.isPma = pma
        renderer.setPremultipliedAlpha(pma)
    }

    override fun setTransform(scale: Float, offsetX: Float, offsetY: Float) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    override fun hitTest(screenX: Float, screenY: Float): String? = "v37_body"

    override fun dispose() {
        skeletonData = null
        skeleton = null
        animationState = null
    }

    companion object {
        fun register() {
            SpineMultiRuntimeManager.registerFactory(object : SpineMultiRuntimeManager.IAdapterFactory {
                override val version: SpineVersion = SpineVersion.V37
                override fun createAdapter(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean): ISpineModelAdapter {
                    return SpineV37Adapter(skelFile, atlas, scale, isPma)
                }
            })
            SpineMultiRuntimeManager.registerPeeker(object : SpineMultiRuntimeManager.ISpinePeeker {
                override val version: SpineVersion = SpineVersion.V37
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
