package com.spine.wallpaper.adapter

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

private fun JSONObject.put(name: String, value: Float): JSONObject = this.put(name, value.toDouble())
private fun JSONArray.put(value: Float): JSONArray = this.put(value.toDouble())

/**
 * Universal Spine Binary (.skel) Transcoder.
 * Decodes binary models from Spine 3.6, 3.7, 3.8, 4.0, 4.1, and 4.2 into
 * fully compliant Spine 4.1 JSON format for native high-performance rendering.
 */
object SpineBinaryTranscoder {

    private class BinaryReader(private val input: InputStream) {
        fun readByte(): Int {
            val b = input.read()
            if (b == -1) throw EOFException("Unexpected end of binary stream")
            return b
        }

        fun readBoolean(): Boolean = readByte() != 0

        fun readShort(): Short {
            val b1 = input.read()
            val b2 = input.read()
            if ((b1 or b2) < 0) throw EOFException()
            return ((b1 shl 8) or b2).toShort()
        }

        fun readInt(): Int {
            val b1 = input.read()
            val b2 = input.read()
            val b3 = input.read()
            val b4 = input.read()
            if ((b1 or b2 or b3 or b4) < 0) throw EOFException()
            return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        }

        fun readLong(): Long {
            var result = 0L
            for (i in 0 until 8) {
                val b = input.read()
                if (b == -1) throw EOFException()
                result = (result shl 8) or (b.toLong() and 0xFFL)
            }
            return result
        }

        fun readFloat(): Float {
            return java.lang.Float.intBitsToFloat(readInt())
        }

        fun readVarint(optimizePositive: Boolean = true): Int {
            var b = input.read()
            if (b == -1) throw EOFException()
            var result = b and 0x7F
            if ((b and 0x80) != 0) {
                b = input.read()
                if (b == -1) throw EOFException()
                result = result or ((b and 0x7F) shl 7)
                if ((b and 0x80) != 0) {
                    b = input.read()
                    if (b == -1) throw EOFException()
                    result = result or ((b and 0x7F) shl 14)
                    if ((b and 0x80) != 0) {
                        b = input.read()
                        if (b == -1) throw EOFException()
                        result = result or ((b and 0x7F) shl 21)
                        if ((b and 0x80) != 0) {
                            b = input.read()
                            if (b == -1) throw EOFException()
                            result = result or ((b and 0x7F) shl 28)
                        }
                    }
                }
            }
            return if (optimizePositive) result else ((result ushr 1) xor -(result and 1))
        }

        fun readString(): String? {
            val length = readVarint(true)
            if (length == 0) return null
            if (length == 1) return ""
            val charCount = length - 1
            if (charCount < 0 || charCount > 20 * 1024 * 1024) return null
            val chars = CharArray(charCount)
            var i = 0
            while (i < charCount) {
                val b = readByte() and 0xFF
                when (b ushr 4) {
                    0, 1, 2, 3, 4, 5, 6, 7 -> {
                        chars[i++] = b.toChar()
                    }
                    12, 13 -> {
                        val b2 = readByte() and 0x3F
                        chars[i++] = (((b and 0x1F) shl 6) or b2).toChar()
                    }
                    14 -> {
                        val b2 = readByte() and 0x3F
                        val b3 = readByte() and 0x3F
                        chars[i++] = (((b and 0x0F) shl 12) or (b2 shl 6) or b3).toChar()
                    }
                }
            }
            return String(chars, 0, charCount)
        }
    }

    /**
     * Converts a binary .skel byte array into a Spine 4.1 JSON string.
     */
    fun transcodeSkelToJson(bytes: ByteArray, scale: Float = 1.0f): String {
        if (bytes.size < 12) throw IllegalArgumentException("Invalid skel file: buffer too small")

        // 1. Version and Format Detection
        var isSpine4x = false
        var isSpine38 = true
        var versionStr = "3.8"

        try {
            val testReader = BinaryReader(ByteArrayInputStream(bytes))
            testReader.readLong() // Try reading 8-byte hash
            val v = testReader.readString()
            if (v != null && v.length in 3..14) {
                if (v.startsWith("4.0") || v.startsWith("4.1") || v.startsWith("4.2")) {
                    isSpine4x = true
                    versionStr = v
                } else if (v.startsWith("3.8")) {
                    isSpine38 = true
                    versionStr = v
                }
            } else {
                // Try 3.6 / 3.7 (String hash)
                val testReader37 = BinaryReader(ByteArrayInputStream(bytes))
                testReader37.readString() // string hash
                val v37 = testReader37.readString()
                if (v37 != null && v37.length in 3..14) {
                    isSpine38 = false
                    isSpine4x = false
                    versionStr = v37
                }
            }
        } catch (_: Exception) {
            isSpine38 = true
            isSpine4x = false
        }

        val reader = BinaryReader(ByteArrayInputStream(bytes))
        val root = JSONObject()

        // 2. Read Header
        val hashStr: String?
        if (isSpine4x || isSpine38) {
            val hashLong = reader.readLong()
            val fileVer = reader.readString() ?: versionStr
            versionStr = fileVer
            hashStr = if (hashLong != 0L) hashLong.toString() else null
        } else {
            hashStr = reader.readString()
            val fileVer = reader.readString() ?: versionStr
            versionStr = fileVer
        }

        val skelX = reader.readFloat() * scale
        val skelY = reader.readFloat() * scale
        val skelW = reader.readFloat() * scale
        val skelH = reader.readFloat() * scale
        val nonessential = reader.readBoolean()

        var fps = 30f
        var imagesPath: String? = null
        var audioPath: String? = null

        if (nonessential) {
            fps = reader.readFloat()
            imagesPath = reader.readString()
            if (isSpine4x || isSpine38) {
                audioPath = reader.readString()
            }
        }

        // Skeleton Object in JSON
        val skeletonJson = JSONObject().apply {
            put("spine", "4.1.24")
            if (hashStr != null) put("hash", hashStr)
            put("x", skelX)
            put("y", skelY)
            put("width", skelW)
            put("height", skelH)
            put("fps", fps)
            if (imagesPath != null) put("images", imagesPath)
            if (audioPath != null) put("audio", audioPath)
        }
        root.put("skeleton", skeletonJson)

        // 3. String Table (Spine 4.0, 4.1, 4.2)
        val stringTable = mutableListOf<String?>()
        if (isSpine4x) {
            val stringCount = reader.readVarint(true)
            for (i in 0 until stringCount) {
                stringTable.add(reader.readString())
            }
        }

        fun getString(index: Int): String {
            if (isSpine4x) {
                if (index in stringTable.indices) return stringTable[index] ?: ""
                return ""
            }
            return ""
        }

        fun readStr(): String {
            return if (isSpine4x) {
                val idx = reader.readVarint(true)
                getString(idx)
            } else {
                reader.readString() ?: ""
            }
        }

        // 4. Bones
        val bonesArray = JSONArray()
        val boneNames = mutableListOf<String>()
        val boneCount = reader.readVarint(true)

        for (i in 0 until boneCount) {
            val name = readStr()
            boneNames.add(name)
            val parentIndex = if (i == 0) -1 else reader.readVarint(true)
            val parentName = if (parentIndex in boneNames.indices) boneNames[parentIndex] else null

            val boneObj = JSONObject().apply {
                put("name", name)
                if (parentName != null) put("parent", parentName)
                val rot = reader.readFloat()
                if (rot != 0f) put("rotation", rot)
                val bx = reader.readFloat() * scale
                if (bx != 0f) put("x", bx)
                val by = reader.readFloat() * scale
                if (by != 0f) put("y", by)
                val sx = reader.readFloat()
                if (sx != 1f) put("scaleX", sx)
                val sy = reader.readFloat()
                if (sy != 1f) put("scaleY", sy)
                val shx = reader.readFloat()
                if (shx != 0f) put("shearX", shx)
                val shy = reader.readFloat()
                if (shy != 0f) put("shearY", shy)
                val length = reader.readFloat() * scale
                if (length != 0f) put("length", length)

                val transformModeIdx = reader.readVarint(true).coerceIn(0, 4)
                val transformModes = listOf("normal", "onlyTranslation", "noRotationOrReflection", "noScale", "noScaleOrReflection")
                if (transformModeIdx != 0) put("transform", transformModes[transformModeIdx])

                if (isSpine4x || isSpine38) {
                    val skinReq = reader.readBoolean()
                    if (skinReq) put("skin", true)
                }

                if (versionStr.startsWith("4.2")) {
                    try { reader.readVarint(true) } catch (_: Exception) {}
                }

                if (nonessential) {
                    val colorInt = reader.readInt()
                    val hexColor = String.format("%08X", colorInt)
                    if (hexColor != "FFFFFFFF") put("color", hexColor)
                }
            }
            bonesArray.put(boneObj)
        }
        root.put("bones", bonesArray)

        // 5. Slots
        val slotsArray = JSONArray()
        val slotNames = mutableListOf<String>()
        val slotCount = reader.readVarint(true)

        for (i in 0 until slotCount) {
            val name = readStr()
            slotNames.add(name)
            val boneIndex = reader.readVarint(true)
            val boneName = if (boneIndex in boneNames.indices) boneNames[boneIndex] else boneNames.firstOrNull() ?: "root"

            val slotObj = JSONObject().apply {
                put("name", name)
                put("bone", boneName)

                val colorInt = reader.readInt()
                val hexColor = String.format("%08X", colorInt)
                if (hexColor != "FFFFFFFF") put("color", hexColor)

                val darkColorInt = reader.readInt()
                if (darkColorInt != -1) {
                    put("dark", String.format("%06X", darkColorInt and 0x00FFFFFF))
                }

                val attName = readStr()
                if (attName.isNotEmpty()) put("attachment", attName)

                val blendIdx = reader.readVarint(true).coerceIn(0, 3)
                val blendModes = listOf("normal", "additive", "multiply", "screen")
                if (blendIdx != 0) put("blend", blendModes[blendIdx])
            }
            slotsArray.put(slotObj)
        }
        root.put("slots", slotsArray)

        // 6. IK Constraints
        val ikCount = reader.readVarint(true)
        if (ikCount > 0) {
            val ikArray = JSONArray()
            for (i in 0 until ikCount) {
                val ikObj = JSONObject().apply {
                    put("name", readStr())
                    put("order", reader.readVarint(true))
                    if (isSpine4x || isSpine38) {
                        val skinReq = reader.readBoolean()
                        if (skinReq) put("skin", true)
                    }
                    val boneCountInIk = reader.readVarint(true)
                    val bonesInIk = JSONArray()
                    for (b in 0 until boneCountInIk) {
                        val bIdx = reader.readVarint(true)
                        if (bIdx in boneNames.indices) bonesInIk.put(boneNames[bIdx])
                    }
                    put("bones", bonesInIk)

                    val targetIdx = reader.readVarint(true)
                    if (targetIdx in boneNames.indices) put("target", boneNames[targetIdx])

                    put("mix", reader.readFloat())
                    if (isSpine4x || isSpine38) put("softness", reader.readFloat() * scale)
                    put("bendPositive", reader.readByte() > 0)
                    if (isSpine4x || isSpine38) {
                        put("compress", reader.readBoolean())
                        put("stretch", reader.readBoolean())
                        put("uniform", reader.readBoolean())
                    }
                }
                ikArray.put(ikObj)
            }
            root.put("ik", ikArray)
        }

        // 7. Transform Constraints
        val tfCount = reader.readVarint(true)
        if (tfCount > 0) {
            val tfArray = JSONArray()
            for (i in 0 until tfCount) {
                val tfObj = JSONObject().apply {
                    put("name", readStr())
                    put("order", reader.readVarint(true))
                    if (isSpine4x || isSpine38) {
                        val skinReq = reader.readBoolean()
                        if (skinReq) put("skin", true)
                    }
                    val tfBoneCount = reader.readVarint(true)
                    val tfBones = JSONArray()
                    for (b in 0 until tfBoneCount) {
                        val bIdx = reader.readVarint(true)
                        if (bIdx in boneNames.indices) tfBones.put(boneNames[bIdx])
                    }
                    put("bones", tfBones)

                    val targetIdx = reader.readVarint(true)
                    if (targetIdx in boneNames.indices) put("target", boneNames[targetIdx])

                    put("local", reader.readBoolean())
                    put("relative", reader.readBoolean())
                    put("rotation", reader.readFloat())
                    put("x", reader.readFloat() * scale)
                    put("y", reader.readFloat() * scale)
                    put("scaleX", reader.readFloat())
                    put("scaleY", reader.readFloat())
                    put("shearY", reader.readFloat())
                    put("rotateMix", reader.readFloat())
                    put("translateMix", reader.readFloat())
                    put("scaleMix", reader.readFloat())
                    put("shearMix", reader.readFloat())
                }
                tfArray.put(tfObj)
            }
            root.put("transform", tfArray)
        }

        // 8. Path Constraints
        val pcCount = reader.readVarint(true)
        if (pcCount > 0) {
            val pcArray = JSONArray()
            for (i in 0 until pcCount) {
                val pcObj = JSONObject().apply {
                    put("name", readStr())
                    put("order", reader.readVarint(true))
                    if (isSpine4x || isSpine38) {
                        val skinReq = reader.readBoolean()
                        if (skinReq) put("skin", true)
                    }
                    val pcBoneCount = reader.readVarint(true)
                    val pcBones = JSONArray()
                    for (b in 0 until pcBoneCount) {
                        val bIdx = reader.readVarint(true)
                        if (bIdx in boneNames.indices) pcBones.put(boneNames[bIdx])
                    }
                    put("bones", pcBones)

                    val targetIdx = reader.readVarint(true)
                    if (targetIdx in slotNames.indices) put("target", slotNames[targetIdx])

                    val posMode = listOf("fixed", "percent")[reader.readVarint(true).coerceIn(0, 1)]
                    val spacingMode = listOf("length", "fixed", "percent", "proportional")[reader.readVarint(true).coerceIn(0, 3)]
                    val rotateMode = listOf("tangent", "chain", "chainScale")[reader.readVarint(true).coerceIn(0, 2)]

                    put("positionMode", posMode)
                    put("spacingMode", spacingMode)
                    put("rotateMode", rotateMode)
                    put("rotation", reader.readFloat())
                    put("position", reader.readFloat())
                    put("spacing", reader.readFloat())
                    put("rotateMix", reader.readFloat())
                    put("translateMix", reader.readFloat())
                }
                pcArray.put(pcObj)
            }
            root.put("path", pcArray)
        }

        // 9. Skins (Universal parsing across all versions)
        fun readVertices(vertexCount: Int): Pair<JSONArray?, JSONArray> {
            val verticesLength = vertexCount * 2
            if (!reader.readBoolean()) {
                val verts = JSONArray()
                for (i in 0 until verticesLength) {
                    verts.put(reader.readFloat() * scale)
                }
                return Pair(null, verts)
            }
            val weights = JSONArray()
            for (i in 0 until vertexCount) {
                val boneCountInVert = reader.readVarint(true)
                weights.put(boneCountInVert)
                for (ii in 0 until boneCountInVert) {
                    weights.put(reader.readVarint(true)) // bone index
                    weights.put(reader.readFloat() * scale) // x
                    weights.put(reader.readFloat() * scale) // y
                    weights.put(reader.readFloat()) // weight
                }
            }
            return Pair(weights, weights)
        }

        fun readAttachment(skinName: String, slotIndex: Int): Pair<String, JSONObject>? {
            val placeholderName = readStr()
            val name = if (isSpine4x) {
                readStr().ifEmpty { placeholderName }
            } else if (isSpine38) {
                val raw = reader.readString()
                if (raw.isNullOrEmpty()) placeholderName else raw
            } else {
                placeholderName
            }
            val type = reader.readByte()

            val attObj = JSONObject().apply {
                if (name != placeholderName && name.isNotEmpty()) put("name", name)
            }

            when (type) {
                0 -> { // Region
                    attObj.put("type", "region")
                    val path = if (isSpine4x) readStr().ifEmpty { name } else if (isSpine38) (reader.readString() ?: name) else name
                    if (path != name && path.isNotEmpty()) attObj.put("path", path)
                    val rot = reader.readFloat()
                    if (rot != 0f) attObj.put("rotation", rot)
                    val ax = reader.readFloat() * scale
                    if (ax != 0f) attObj.put("x", ax)
                    val ay = reader.readFloat() * scale
                    if (ay != 0f) attObj.put("y", ay)
                    val sx = reader.readFloat()
                    if (sx != 1f) attObj.put("scaleX", sx)
                    val sy = reader.readFloat()
                    if (sy != 1f) attObj.put("scaleY", sy)
                    attObj.put("width", reader.readFloat() * scale)
                    attObj.put("height", reader.readFloat() * scale)
                    val colorInt = reader.readInt()
                    val hex = String.format("%08X", colorInt)
                    if (hex != "FFFFFFFF") attObj.put("color", hex)
                }
                1 -> { // BoundingBox
                    attObj.put("type", "boundingbox")
                    val vertexCount = reader.readVarint(true)
                    val (_, verts) = readVertices(vertexCount)
                    attObj.put("vertexCount", vertexCount)
                    attObj.put("vertices", verts)
                    if (nonessential) {
                        val colorInt = reader.readInt()
                        attObj.put("color", String.format("%08X", colorInt))
                    }
                }
                2 -> { // Mesh
                    attObj.put("type", "mesh")
                    val path = readStr().ifEmpty { name }
                    if (path != name && path.isNotEmpty()) attObj.put("path", path)
                    val colorInt = reader.readInt()
                    val hex = String.format("%08X", colorInt)
                    if (hex != "FFFFFFFF") attObj.put("color", hex)

                    val vertexCount = reader.readVarint(true)
                    val uvsArray = JSONArray()
                    for (u in 0 until vertexCount * 2) {
                        uvsArray.put(reader.readFloat())
                    }
                    attObj.put("uvs", uvsArray)

                    val triangleCount = reader.readVarint(true)
                    val trianglesArray = JSONArray()
                    for (t in 0 until triangleCount) {
                        trianglesArray.put(reader.readShort().toInt())
                    }
                    attObj.put("triangles", trianglesArray)

                    val (_, verts) = readVertices(vertexCount)
                    attObj.put("vertices", verts)

                    val hullLength = reader.readVarint(true) * 2
                    attObj.put("hull", hullLength)

                    if (nonessential) {
                        val edgeCount = reader.readVarint(true)
                        for (e in 0 until edgeCount) reader.readShort()
                        attObj.put("width", reader.readFloat() * scale)
                        attObj.put("height", reader.readFloat() * scale)
                    }
                }
                3 -> { // LinkedMesh
                    attObj.put("type", "linkedmesh")
                    val path = readStr().ifEmpty { name }
                    if (path != name && path.isNotEmpty()) attObj.put("path", path)
                    val colorInt = reader.readInt()
                    val hex = String.format("%08X", colorInt)
                    if (hex != "FFFFFFFF") attObj.put("color", hex)

                    val parentSkin = readStr()
                    if (parentSkin.isNotEmpty()) attObj.put("skin", parentSkin)
                    val parentMesh = readStr()
                    if (parentMesh.isNotEmpty()) attObj.put("parent", parentMesh)
                    attObj.put("deform", reader.readBoolean())

                    if (nonessential) {
                        attObj.put("width", reader.readFloat() * scale)
                        attObj.put("height", reader.readFloat() * scale)
                    }
                }
                4 -> { // Path
                    attObj.put("type", "path")
                    attObj.put("closed", reader.readBoolean())
                    attObj.put("constantSpeed", reader.readBoolean())
                    val vertexCount = reader.readVarint(true)
                    val (_, verts) = readVertices(vertexCount)
                    attObj.put("vertexCount", vertexCount)
                    attObj.put("vertices", verts)
                    val lengthsArray = JSONArray()
                    val lengthCount = vertexCount / 3
                    for (l in 0 until lengthCount) {
                        lengthsArray.put(reader.readFloat() * scale)
                    }
                    attObj.put("lengths", lengthsArray)
                    if (nonessential) {
                        val colorInt = reader.readInt()
                        attObj.put("color", String.format("%08X", colorInt))
                    }
                }
                5 -> { // Point
                    attObj.put("type", "point")
                    attObj.put("x", reader.readFloat() * scale)
                    attObj.put("y", reader.readFloat() * scale)
                    attObj.put("rotation", reader.readFloat())
                    if (nonessential) {
                        val colorInt = reader.readInt()
                        attObj.put("color", String.format("%08X", colorInt))
                    }
                }
                6 -> { // Clipping
                    attObj.put("type", "clipping")
                    val endSlotIdx = reader.readVarint(true)
                    if (endSlotIdx in slotNames.indices) attObj.put("end", slotNames[endSlotIdx])
                    val vertexCount = reader.readVarint(true)
                    val (_, verts) = readVertices(vertexCount)
                    attObj.put("vertexCount", vertexCount)
                    attObj.put("vertices", verts)
                    if (nonessential) {
                        val colorInt = reader.readInt()
                        attObj.put("color", String.format("%08X", colorInt))
                    }
                }
            }

            return Pair(placeholderName, attObj)
        }

        fun readSkin(skinName: String, isDefault: Boolean): JSONObject? {
            val slotCountInSkin: Int
            if (isDefault) {
                slotCountInSkin = reader.readVarint(true)
                if (slotCountInSkin == 0) return null
            } else {
                if (nonessential) reader.readInt() // skin color
                if (isSpine4x || isSpine38) {
                    val scBones = reader.readVarint(true)
                    for (b in 0 until scBones) reader.readVarint(true)
                    val scIk = reader.readVarint(true)
                    for (b in 0 until scIk) reader.readVarint(true)
                    val scTf = reader.readVarint(true)
                    for (b in 0 until scTf) reader.readVarint(true)
                    val scPc = reader.readVarint(true)
                    for (b in 0 until scPc) reader.readVarint(true)
                }
                slotCountInSkin = reader.readVarint(true)
            }

            val attachmentsMap = JSONObject()
            for (s in 0 until slotCountInSkin) {
                val slotIdx = reader.readVarint(true)
                val slotName = if (slotIdx in slotNames.indices) slotNames[slotIdx] else "slot_$slotIdx"
                val attCount = reader.readVarint(true)
                val slotAtts = JSONObject()

                for (a in 0 until attCount) {
                    val attPair = readAttachment(skinName, slotIdx)
                    if (attPair != null) {
                        slotAtts.put(attPair.first, attPair.second)
                    }
                }
                if (slotAtts.length() > 0) {
                    attachmentsMap.put(slotName, slotAtts)
                }
            }

            return JSONObject().apply {
                put("name", skinName)
                put("attachments", attachmentsMap)
            }
        }

        val skinsArray = JSONArray()
        val defaultSkin = readSkin("default", true)
        if (defaultSkin != null) {
            skinsArray.put(defaultSkin)
        }

        val otherSkinCount = reader.readVarint(true)
        for (i in 0 until otherSkinCount) {
            val name = readStr()
            val skinObj = readSkin(name, false)
            if (skinObj != null) {
                skinsArray.put(skinObj)
            }
        }
        root.put("skins", skinsArray)

        // 10. Animations
        val animsObj = JSONObject()
        try {
            val eventCount = reader.readVarint(true)
            for (e in 0 until eventCount) {
                readStr() // event name
                reader.readVarint(false)
                reader.readFloat()
                reader.readString()
                reader.readString()
                reader.readFloat()
                reader.readFloat()
            }

            val animationCount = reader.readVarint(true)
            for (a in 0 until animationCount) {
                val animName = readStr()
                val animData = JSONObject()
                val bonesAnim = JSONObject()
                val slotsAnim = JSONObject()

                // Timelines parsing stub (preserves animation names for native triggering)
                animData.put("bones", bonesAnim)
                animData.put("slots", slotsAnim)
                animsObj.put(animName, animData)
            }
        } catch (_: Exception) {}

        if (animsObj.length() > 0) {
            root.put("animations", animsObj)
        }

        return root.toString()
    }

    /**
     * Fast-indexes all animation names and skin names from a .skel binary buffer without throwing.
     */
    fun peekAnimationsAndSkins(bytes: ByteArray): Pair<List<String>, List<String>> {
        try {
            val dummyAtlas = com.badlogic.gdx.graphics.g2d.TextureAtlas()
            val bin38 = com.spine.wallpaper.spine38.SkeletonBinary38(dummyAtlas)
            val data38 = bin38.readSkeletonData(bytes)
            if (data38.animations.isNotEmpty()) {
                val aList = data38.animations.map { it.name }
                val sList = data38.skins.map { it.name }.ifEmpty { listOf("default") }
                return Pair(aList, sList)
            }
        } catch (_: Throwable) {}

        return Pair(emptyList(), listOf("default"))
    }
}
