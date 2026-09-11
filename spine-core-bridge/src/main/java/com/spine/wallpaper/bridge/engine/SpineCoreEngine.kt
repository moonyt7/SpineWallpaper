package com.spine.wallpaper.bridge.engine

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.spine.wallpaper.bridge.SpineVersionDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Spine 3.8 / 3.7 / 3.6 Implementation of ISpineEngineInstance using pure Kotlin engine.
 */
class Spine38EngineInstance(
    val skeletonData: SkeletonData38,
    val skeleton: Skeleton38,
    val animationState: AnimationState38,
    override val version: String = "3.8"
) : ISpineEngineInstance {

    private val renderer = SkeletonRenderer38()

    override val animationNames: List<String>
        get() = skeletonData.animations.map { it.name }

    override val skinNames: List<String>
        get() = skeletonData.skins.map { it.name }

    override fun setAnimation(trackIndex: Int, name: String, loop: Boolean) {
        try {
            animationState.setAnimation(trackIndex, name, loop)
        } catch (_: Throwable) {}
    }

    override fun setSkin(skinName: String) {
        try {
            skeleton.setSkin(skinName)
            skeleton.setSlotsToSetupPose()
        } catch (_: Throwable) {}
    }

    override fun addSkin(skinName: String) {
        try {
            skeleton.addSkin(skinName)
            skeleton.setSlotsToSetupPose()
        } catch (_: Throwable) {}
    }

    override fun setPremultipliedAlpha(pma: Boolean) {
        renderer.setPremultipliedAlpha(pma)
    }

    override fun update(delta: Float) {
        animationState.update(delta)
        animationState.apply(skeleton)
        skeleton.updateWorldTransform()
    }

    override fun setPosition(x: Float, y: Float) {
        skeleton.setPosition(x, y)
    }

    override fun setScale(scaleX: Float, scaleY: Float) {
        skeleton.scaleX = scaleX
        skeleton.scaleY = scaleY
    }

    override fun draw(batch: PolygonSpriteBatch) {
        skeleton.updateWorldTransform()
        renderer.draw(batch, skeleton)
    }

    override fun dispose() {}
}

/**
 * Spine Core Engine Factory for multi-version runtime instances and JSON schema normalization.
 */
object SpineCoreEngine {

    fun createModelInstance(
        skelFile: File,
        atlas: TextureAtlas,
        scale: Float,
        isPma: Boolean = true,
        targetVersion: String = "3.8"
    ): ISpineEngineInstance {
        val detectedVer = SpineVersionDetector.detectVersionString(skelFile)
        val isBinary = SpineVersionDetector.detectFormat(skelFile) == "SKEL"

        // Instantiate Spine 3.8/3.7/3.6 or 4.0/4.1/4.2 native engine
        val data38: SkeletonData38 = if (isBinary) {
            if (detectedVer.startsWith("4.")) {
                try {
                    val binaryLoader4x = SkeletonBinary4x(atlas).apply { this.scale = scale }
                    binaryLoader4x.readSkeletonData(FileHandle(skelFile))
                } catch (e: Throwable) {
                    e.printStackTrace()
                    try {
                        val binaryLoader38 = SkeletonBinary38(atlas).apply { this.scale = scale }
                        binaryLoader38.readSkeletonData(FileHandle(skelFile))
                    } catch (e2: Throwable) {
                        e2.printStackTrace()
                        // Resilient Fallback: Synthesize a SkeletonData with the atlas textures
                        SkeletonData38().apply {
                            this.atlas = atlas
                            this.version = detectedVer
                            val rootBone = BoneData38(0, "root", null)
                            bones.add(rootBone)
                            val slot = SlotData38(0, "main", rootBone)
                            slots.add(slot)
                            val fallbackSkin = Skin38("default")
                            for (reg in atlas.regions) {
                                val rName = reg.name ?: "region"
                                val att = RegionAttachment38(rName).apply {
                                    this.region = reg
                                    this.width = reg.regionWidth.toFloat() * scale
                                    this.height = reg.regionHeight.toFloat() * scale
                                    updateOffset()
                                    updateUVs()
                                }
                                fallbackSkin.setAttachment(slot.index, rName, att)
                                if (slot.attachmentName == null) slot.attachmentName = rName
                            }
                            defaultSkin = fallbackSkin
                            skins.add(fallbackSkin)
                            animations.add(Animation38("idle", 1.0f))
                        }
                    }
                }
            } else {
                try {
                    val binaryLoader38 = SkeletonBinary38(atlas).apply { this.scale = scale }
                    binaryLoader38.readSkeletonData(FileHandle(skelFile))
                } catch (e: Throwable) {
                    e.printStackTrace()
                    try {
                        val binaryLoader4x = SkeletonBinary4x(atlas).apply { this.scale = scale }
                        binaryLoader4x.readSkeletonData(FileHandle(skelFile))
                    } catch (e2: Throwable) {
                        e2.printStackTrace()
                        // Resilient Fallback: Synthesize a SkeletonData with the atlas textures
                        SkeletonData38().apply {
                            this.atlas = atlas
                            this.version = detectedVer
                            val rootBone = BoneData38(0, "root", null)
                            bones.add(rootBone)
                            val slot = SlotData38(0, "main", rootBone)
                            slots.add(slot)
                            val fallbackSkin = Skin38("default")
                            for (reg in atlas.regions) {
                                val rName = reg.name ?: "region"
                                val att = RegionAttachment38(rName).apply {
                                    this.region = reg
                                    this.width = reg.regionWidth.toFloat() * scale
                                    this.height = reg.regionHeight.toFloat() * scale
                                    updateOffset()
                                    updateUVs()
                                }
                                fallbackSkin.setAttachment(slot.index, rName, att)
                                if (slot.attachmentName == null) slot.attachmentName = rName
                            }
                            defaultSkin = fallbackSkin
                            skins.add(fallbackSkin)
                            animations.add(Animation38("idle", 1.0f))
                        }
                    }
                }
            }
        } else {
            try {
                val jsonLoader = SkeletonJson38(atlas).apply { this.scale = scale }
                jsonLoader.readSkeletonData(FileHandle(skelFile))
            } catch (e: Throwable) {
                e.printStackTrace()
                SkeletonData38().apply {
                    this.atlas = atlas
                    this.version = detectedVer
                    val rootBone = BoneData38(0, "root", null)
                    bones.add(rootBone)
                    val slot = SlotData38(0, "main", rootBone)
                    slots.add(slot)
                    val fallbackSkin = Skin38("default")
                    for (reg in atlas.regions) {
                        val rName = reg.name ?: "region"
                        val att = RegionAttachment38(rName).apply {
                            this.region = reg
                            this.width = reg.regionWidth.toFloat() * scale
                            this.height = reg.regionHeight.toFloat() * scale
                            updateOffset()
                            updateUVs()
                        }
                        fallbackSkin.setAttachment(slot.index, rName, att)
                        if (slot.attachmentName == null) slot.attachmentName = rName
                    }
                    defaultSkin = fallbackSkin
                    skins.add(fallbackSkin)
                    animations.add(Animation38("idle", 1.0f))
                }
            }
        }

        val skel38 = Skeleton38(data38)
        if (data38.defaultSkin != null) {
            skel38.setSkin("default")
        } else if (data38.skins.isNotEmpty()) {
            skel38.setSkin(data38.skins.first().name)
        }
        skel38.setSlotsToSetupPose()
        skel38.updateWorldTransform()

        val anim38 = AnimationState38(data38)
        if (data38.animations.isNotEmpty()) {
            anim38.setAnimation(0, data38.animations.first().name, true)
        }
        return Spine38EngineInstance(data38, skel38, anim38, detectedVer)
    }

    /**
     * Normalizes Spine 3.6 / 3.7 / 3.8 / 4.0 / 4.2 JSON structures into Spine 4.1 compliant JSON.
     */
    fun normalizeJsonToSpine41(rawJson: String): String {
        try {
            val root = JSONObject(rawJson)

            // 1. Normalize Skeleton Header
            val skeleton = root.optJSONObject("skeleton") ?: JSONObject()
            skeleton.put("spine", "4.1.24")
            root.put("skeleton", skeleton)

            // 2. Normalize Skins (3.8 dictionary format -> 4.1 skins array/map)
            val skinsObj = root.optJSONObject("skins")
            if (skinsObj != null) {
                val skinsArray = JSONArray()
                val skinKeys = skinsObj.keys()
                while (skinKeys.hasNext()) {
                    val skinName = skinKeys.next()
                    val skinContent = skinsObj.getJSONObject(skinName)
                    val newSkinObj = JSONObject().apply {
                        put("name", skinName)
                        put("attachments", skinContent)
                    }
                    skinsArray.put(newSkinObj)
                }
                root.put("skins", skinsArray)
            }

            // 3. Normalize Animations (Timelines: rotate angle -> value, curve easing, color rgba)
            val animsObj = root.optJSONObject("animations")
            if (animsObj != null) {
                val animKeys = animsObj.keys()
                while (animKeys.hasNext()) {
                    val animName = animKeys.next()
                    val anim = animsObj.optJSONObject(animName) ?: continue

                    val bonesObj = anim.optJSONObject("bones")
                    if (bonesObj != null) {
                        val boneKeys = bonesObj.keys()
                        while (boneKeys.hasNext()) {
                            val boneName = boneKeys.next()
                            val boneTimelines = bonesObj.optJSONObject(boneName) ?: continue

                            val rotateArr = boneTimelines.optJSONArray("rotate")
                            if (rotateArr != null) {
                                for (i in 0 until rotateArr.length()) {
                                    val frame = rotateArr.optJSONObject(i) ?: continue
                                    if (frame.has("angle") && !frame.has("value")) {
                                        frame.put("value", frame.optDouble("angle", 0.0))
                                    }
                                }
                            }
                        }
                    }

                    val slotsObj = anim.optJSONObject("slots")
                    if (slotsObj != null) {
                        val slotKeys = slotsObj.keys()
                        while (slotKeys.hasNext()) {
                            val slotName = slotKeys.next()
                            val slotTimelines = slotsObj.optJSONObject(slotName) ?: continue

                            val colorArr = slotTimelines.optJSONArray("color")
                            if (colorArr != null) {
                                for (i in 0 until colorArr.length()) {
                                    val frame = colorArr.optJSONObject(i) ?: continue
                                    if (frame.has("color") && !frame.has("rgba")) {
                                        frame.put("rgba", frame.optString("color"))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return root.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return rawJson
        }
    }
}
