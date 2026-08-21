package com.spine.wallpaper.adapter

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.esotericsoftware.spine.AnimationState
import com.esotericsoftware.spine.AnimationStateData
import com.esotericsoftware.spine.Skeleton
import com.esotericsoftware.spine.SkeletonBinary
import com.esotericsoftware.spine.SkeletonData
import com.esotericsoftware.spine.SkeletonJson
import com.esotericsoftware.spine.SkeletonRenderer
import com.spine.wallpaper.spine38.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.charset.StandardCharsets

/**
 * Spine 4.1 Implementation of ISpineModelInstance.
 */
class Spine41ModelInstance(
    val skeletonData: SkeletonData,
    val skeleton: Skeleton,
    val animationState: AnimationState,
    override val version: String = "4.1"
) : ISpineModelInstance {

    private val renderer = SkeletonRenderer()

    override val animationNames: List<String>
        get() {
            val list = mutableListOf<String>()
            for (i in 0 until skeletonData.animations.size) {
                list.add(skeletonData.animations.get(i).name)
            }
            return list
        }

    override val skinNames: List<String>
        get() {
            val list = mutableListOf<String>()
            for (i in 0 until skeletonData.skins.size) {
                list.add(skeletonData.skins.get(i).name)
            }
            return list
        }

    override fun setAnimation(trackIndex: Int, name: String, loop: Boolean) {
        animationState.setAnimation(trackIndex, name, loop)
    }

    override fun setSkin(skinName: String) {
        skeleton.setSkin(skinName)
        skeleton.setSlotsToSetupPose()
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
        skeleton.setScaleX(scaleX)
        skeleton.setScaleY(scaleY)
    }

    override fun draw(batch: PolygonSpriteBatch) {
        skeleton.updateWorldTransform()
        renderer.draw(batch, skeleton)
    }

    override fun dispose() {}
}

/**
 * Spine 3.8 / 3.7 / 3.6 Implementation of ISpineModelInstance.
 */
class Spine38ModelInstance(
    val skeletonData: SkeletonData38,
    val skeleton: Skeleton38,
    val animationState: AnimationState38,
    override val version: String = "3.8"
) : ISpineModelInstance {

    private val renderer = SkeletonRenderer38()

    override val animationNames: List<String>
        get() = skeletonData.animations.map { it.name }

    override val skinNames: List<String>
        get() = skeletonData.skins.map { it.name }

    override fun setAnimation(trackIndex: Int, name: String, loop: Boolean) {
        animationState.setAnimation(trackIndex, name, loop)
    }

    override fun setSkin(skinName: String) {
        skeleton.setSkin(skinName)
        skeleton.setSlotsToSetupPose()
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
 * Universal Spine Multi-Version Engine Adapter.
 * Seamlessly loads models across Spine 3.6, 3.7, 3.8, 4.0, 4.1, and 4.2.
 */
object SpineVersionAdapter {

    /**
     * Detects Spine version from JSON header or binary .skel stream.
     */
    fun detectVersion(file: File): String {
        if (!file.exists() || !file.canRead()) return "4.1"
        val name = file.name.lowercase()
        val isBinary = name.endsWith(".skel") || name.endsWith(".skel.bytes")

        if (isBinary) {
            try {
                val bytes = file.readBytes()
                if (bytes.size > 12) {
                    // 1. Try Spine 3.8+ (8 bytes hash + version string)
                    try {
                        val r38 = BinaryStreamReader38(ByteArrayInputStream(bytes))
                        r38.readLong()
                        val v38 = r38.readString()
                        if (v38 != null && v38.length in 3..14) {
                            for (ver in listOf("4.2", "4.1", "4.0", "3.8")) {
                                if (v38.startsWith(ver)) return ver
                            }
                        }
                    } catch (_: Exception) {}

                    // 2. Try Spine 3.6/3.7 (string hash + version string)
                    try {
                        val r37 = BinaryStreamReader38(ByteArrayInputStream(bytes))
                        r37.readString() // string hash
                        val v37 = r37.readString()
                        if (v37 != null && v37.length in 3..14) {
                            for (ver in listOf("3.7", "3.6", "3.5", "3.4")) {
                                if (v37.startsWith(ver)) return ver
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 3. Fallback scan in first 512 bytes
                val head = String(bytes, 0, minOf(bytes.size, 512), StandardCharsets.ISO_8859_1)
                for (ver in listOf("4.2", "4.1", "4.0", "3.8", "3.7", "3.6", "3.5")) {
                    if (head.contains(ver)) return ver
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return "3.8"
        } else {
            try {
                val headerSample = file.bufferedReader().use { r ->
                    val chars = CharArray(1024)
                    val len = r.read(chars)
                    if (len > 0) String(chars, 0, len) else ""
                }
                for (ver in listOf("3.6", "3.7", "3.8", "4.0", "4.1", "4.2")) {
                    if (headerSample.contains(ver)) {
                        return ver
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return "4.1"
        }
    }

    fun detectFormat(file: File): String {
        val name = file.name.lowercase()
        return if (name.endsWith(".skel") || name.endsWith(".skel.bytes")) "SKEL" else "JSON"
    }

    /**
     * Sanitizes Atlas files to repair Windows path separators, match disk PNG names (case-insensitively), and fix format anomalies.
     */
    fun sanitizeAtlasFile(atlasFile: File): FileHandle {
        try {
            val parentDir = atlasFile.parentFile ?: File(".")
            val diskFiles = parentDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val diskMap = mutableMapOf<String, String>() // normalized name -> actual file name
            for (f in diskFiles) {
                diskMap[f.name.lowercase()] = f.name
                diskMap[f.nameWithoutExtension.lowercase()] = f.name
            }

            val lines = atlasFile.readLines(StandardCharsets.UTF_8)
            var modified = false
            val newLines = mutableListOf<String>()

            for (i in lines.indices) {
                var line = lines[i]
                if (line.contains("\\")) {
                    line = line.replace("\\", "/")
                    modified = true
                }

                // If line looks like a texture page header (e.g., ends in .png/.jpg or single word without colon and previous line is blank/header)
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.contains(':')) {
                    val cleanBase = trimmed.replace("\\", "/").substringAfterLast('/')
                    val matchedActual = diskMap[cleanBase.lowercase()] ?: diskMap[cleanBase.removeSuffix(".png").removeSuffix(".jpg").lowercase()]
                    if (matchedActual != null && matchedActual != trimmed) {
                        line = matchedActual
                        modified = true
                    }
                }
                newLines.add(line)
            }

            if (modified) {
                val sanitizedFile = File(atlasFile.parentFile, "_sanitized_" + atlasFile.name)
                sanitizedFile.writeText(newLines.joinToString(System.lineSeparator()), StandardCharsets.UTF_8)
                return FileHandle(sanitizedFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return FileHandle(atlasFile)
    }

    /**
     * Creates a Universal Model Instance supporting Spine 3.6, 3.7, 3.8, 4.0, 4.1, 4.2.
     */
    fun createModelInstance(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean = true): ISpineModelInstance {
        val version = detectVersion(skelFile)
        val isBinary = detectFormat(skelFile) == "SKEL"
        android.util.Log.i("SpineWallpaper", "createModelInstance: file=${skelFile.name}, format=${if (isBinary) "SKEL" else "JSON"}, detectedVersion=$version, scale=$scale")

        val is3x = version.startsWith("3.") || version <= "3.8"
        if (is3x) {
            try {
                return createSpine38Instance(skelFile, atlas, scale, isPma, version)
            } catch (e38: Throwable) {
                android.util.Log.e("SpineWallpaper", "Spine 3.8 engine failed for ${skelFile.name}, attempting Spine 4.1 fallback: ${e38.message}", e38)
                try {
                    return createSpine41Instance(skelFile, atlas, scale, isPma, version)
                } catch (e41: Throwable) {
                    android.util.Log.e("SpineWallpaper", "Spine 4.1 fallback also failed: ${e41.message}", e41)
                    throw e38
                }
            }
        } else {
            try {
                return createSpine41Instance(skelFile, atlas, scale, isPma, version)
            } catch (e41: Throwable) {
                android.util.Log.e("SpineWallpaper", "Spine 4.1 engine failed for ${skelFile.name}, attempting Spine 3.8 fallback: ${e41.message}", e41)
                try {
                    return createSpine38Instance(skelFile, atlas, scale, isPma, version)
                } catch (e38: Throwable) {
                    android.util.Log.e("SpineWallpaper", "Spine 3.8 fallback also failed: ${e38.message}", e38)
                    throw e41
                }
            }
        }
    }

    private fun createSpine38Instance(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean, version: String): ISpineModelInstance {
        val isBinary = detectFormat(skelFile) == "SKEL"
        val data38: SkeletonData38 = if (isBinary) {
            val binaryLoader = SkeletonBinary38(atlas).apply { this.scale = scale }
            binaryLoader.readSkeletonData(FileHandle(skelFile))
        } else {
            val jsonLoader = SkeletonJson38(atlas).apply { this.scale = scale }
            jsonLoader.readSkeletonData(FileHandle(skelFile))
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
        return Spine38ModelInstance(data38, skel38, anim38, version)
    }

    private fun createSpine41Instance(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean, version: String): ISpineModelInstance {
        val isBinary = detectFormat(skelFile) == "SKEL"
        val data: SkeletonData = if (isBinary) {
            val binary = SkeletonBinary(atlas).apply { this.scale = scale }
            binary.readSkeletonData(FileHandle(skelFile))
        } else {
            val rawJson = skelFile.readText(StandardCharsets.UTF_8)
            val normalizedJson = normalizeJsonToSpine41(rawJson)
            val jsonLoader = SkeletonJson(atlas).apply { this.scale = scale }
            val tempJsonFile = File(skelFile.parentFile, "_norm_" + skelFile.name)
            tempJsonFile.writeText(normalizedJson, StandardCharsets.UTF_8)
            try {
                jsonLoader.readSkeletonData(FileHandle(tempJsonFile))
            } catch (_: Exception) {
                jsonLoader.readSkeletonData(FileHandle(skelFile))
            }
        }

        val skel = Skeleton(data)
        if (data.defaultSkin != null) {
            skel.setSkin(data.defaultSkin)
        } else if (data.skins.size > 0) {
            skel.setSkin(data.skins.first())
        }
        skel.setSlotsToSetupPose()
        skel.updateWorldTransform()

        val animState = AnimationState(AnimationStateData(data))
        if (data.animations.size > 0) {
            animState.setAnimation(0, data.animations.first().name, true)
        }
        return Spine41ModelInstance(data, skel, animState, version)
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

    /**
     * Extracts animation and skin names safely across all versions for UI list population.
     * High-speed parsing with zero active GL requirement.
     */
    fun peekAnimationsAndSkins(skelFile: File, atlasFile: File?): Pair<List<String>, List<String>> {
        val anims = mutableListOf<String>()
        val skins = mutableListOf<String>()

        val isBinary = detectFormat(skelFile) == "SKEL"
        if (!isBinary) {
            try {
                val jsonStr = skelFile.readText(StandardCharsets.UTF_8)
                val jsonObj = JSONObject(jsonStr)

                jsonObj.optJSONObject("animations")?.keys()?.forEach { anims.add(it) }

                val skinsObj = jsonObj.optJSONObject("skins")
                if (skinsObj != null) {
                    skinsObj.keys().forEach { skins.add(it) }
                } else {
                    val skinsArr = jsonObj.optJSONArray("skins")
                    if (skinsArr != null) {
                        for (i in 0 until skinsArr.length()) {
                            val sObj = skinsArr.optJSONObject(i)
                            val sName = sObj?.optString("name")
                            if (!sName.isNullOrEmpty()) skins.add(sName)
                        }
                    }
                }
                if (anims.isNotEmpty() || skins.isNotEmpty()) {
                    if (skins.isEmpty()) skins.add("default")
                    return Pair(anims, skins)
                }
            } catch (_: Exception) {}
        } else {
            // 1. Try native Spine 3.8 / 3.7 / 3.6 binary parse
            try {
                val dummyAtlas = TextureAtlas()
                val bin38 = SkeletonBinary38(dummyAtlas)
                val data38 = bin38.readSkeletonData(FileHandle(skelFile))
                if (data38.animations.isNotEmpty()) {
                    return Pair(data38.animations.map { it.name }, data38.skins.map { it.name }.ifEmpty { listOf("default") })
                }
            } catch (_: Throwable) {}

            // 2. Try official Spine 4.1 / 4.0 / 4.2 binary parse
            try {
                val dummyAtlas = TextureAtlas()
                val bin41 = com.esotericsoftware.spine.SkeletonBinary(dummyAtlas)
                val data41 = bin41.readSkeletonData(FileHandle(skelFile))
                if (data41.animations.size > 0) {
                    val aList = mutableListOf<String>()
                    for (i in 0 until data41.animations.size) aList.add(data41.animations.get(i).name)
                    val sList = mutableListOf<String>()
                    for (i in 0 until data41.skins.size) sList.add(data41.skins.get(i).name)
                    return Pair(aList, sList.ifEmpty { listOf("default") })
                }
            } catch (_: Throwable) {}

            // 3. Scan strings from binary buffer using length-prefixed string reader + string table
            try {
                val bytes = skelFile.readBytes()
                var offset = 0
                val allStrings = mutableListOf<String>()
                while (offset < bytes.size - 2) {
                    var b = bytes[offset++].toInt()
                    var result = b and 0x7F
                    if ((b and 0x80) != 0) {
                        b = bytes[offset++].toInt()
                        result = result or ((b and 0x7F) shl 7)
                    }
                    val strLen = result - 1
                    if (strLen in 2..64 && offset + strLen <= bytes.size) {
                        val slice = bytes.copyOfRange(offset, offset + strLen)
                        val isAscii = slice.all { it in 32..126 }
                        if (isAscii) {
                            val str = String(slice, StandardCharsets.UTF_8)
                            if (!str.endsWith(".png") && !str.endsWith(".atlas") && !str.endsWith(".skel") && !str.contains("/")) {
                                allStrings.add(str)
                            }
                        }
                    }
                }

                val animKeywords = listOf("idle", "walk", "run", "attack", "atk", "jump", "hit", "die", "dead", "stand", "action", "touch", "tap", "motion", "pose", "win", "lose", "wait", "talk", "move", "skill", "start", "loop", "end", "anim", "act", "play", "show", "hide", "open", "close", "normal", "special", "fall", "hurt", "cheer", "dance", "blink", "shake", "f0", "f1", "f2", "f3", "f4", "f5", "m_", "a_", "act_", "anim_", "motion_", "state_")
                for (w in allStrings) {
                    val lower = w.lowercase()
                    if (w.endsWith(".png") || w.endsWith(".jpg") || w.endsWith(".atlas") || w.endsWith(".skel") || w.startsWith("Spine") || w.length < 2) continue
                    if (lower == "default" || lower.startsWith("skin") || lower.contains("costume") || lower.contains("outfit")) {
                        if (!skins.contains(w)) skins.add(w)
                    } else if (animKeywords.any { lower.contains(it) } && !anims.contains(w) && !w.all { it.isDigit() }) {
                        if (anims.size < 100) anims.add(w)
                    }
                }
                if (anims.isNotEmpty()) {
                    if (skins.isEmpty()) skins.add("default")
                    return Pair(anims, skins)
                }
            } catch (_: Exception) {}
        }

        // Return defaults if parsing failed during zip indexing
        if (anims.isEmpty()) anims.add("idle")
        if (skins.isEmpty()) skins.add("default")
        return Pair(anims, skins)
    }
}
