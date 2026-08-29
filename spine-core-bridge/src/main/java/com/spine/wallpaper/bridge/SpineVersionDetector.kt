package com.spine.wallpaper.bridge

import com.badlogic.gdx.files.FileHandle
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * Universal Spine Version Sniffer & Detector.
 * Inspects binary .skel headers and JSON skeleton descriptors to determine the required runtime module.
 *
 * 二进制检测逻辑与官方 spine-libgdx SkeletonBinary 读取方式保持一致（已参照 Spine2 项目验证）：
 * - Spine 3.8 及更早：hash = readString()（字符串）→ version = readString()
 * - Spine 4.0 / 4.1 / 4.2：hash = readLong()（8 字节整数）→ version = readString()
 * readString = varint 长度前缀(7bit 分组, MSB 标记续段) + UTF-8 字节，byteCount==1 表示空串，读取 (byteCount-1) 字节
 */
object SpineVersionDetector {

    /**
     * Detects Spine version from a .skel binary or .json skeleton file.
     */
    fun detectVersion(file: File): SpineVersion {
        if (!file.exists() || file.length() < 8) return SpineVersion.UNKNOWN

        val name = file.name.lowercase()
        return if (name.endsWith(".json") || name.endsWith(".json.txt")) {
            detectFromJson(file)
        } else {
            detectFromBinary(file)
        }
    }

    fun detectVersionString(file: File): String {
        val ver = detectVersion(file)
        return when (ver) {
            SpineVersion.V36 -> "3.6"
            SpineVersion.V37 -> "3.7"
            SpineVersion.V38 -> "3.8"
            SpineVersion.V40 -> "4.0"
            SpineVersion.V41 -> "4.1"
            SpineVersion.V42 -> "4.2"
            SpineVersion.UNKNOWN -> "unknown"
        }
    }

    fun detectFormat(file: File): String {
        val name = file.name.lowercase()
        return if (name.endsWith(".json") || name.endsWith(".json.txt")) "JSON" else "SKEL"
    }

    /**
     * Inspects JSON skeleton "skeleton": { "spine": "x.x.xx" }
     */
    private fun detectFromJson(file: File): SpineVersion {
        return try {
            val text = file.readText(StandardCharsets.UTF_8)
            val json = JSONObject(text)
            val skeletonObj = json.optJSONObject("skeleton")
            val spineVer = skeletonObj?.optString("spine") ?: json.optString("spine", "")
            if (spineVer.isNotEmpty()) {
                SpineVersion.fromString(spineVer)
            } else {
                SpineVersion.UNKNOWN
            }
        } catch (e: Exception) {
            SpineVersion.UNKNOWN
        }
    }

    /**
     * Inspects binary .skel magic byte headers.
     * 方案一：4.0+ 格式 [long hash(8字节)][string version]
     * 方案二：3.8 及更早格式 [string hash][string version]
     * 方案三：头部明文版本号搜索兜底（部分工具打包的文件头为标准布局解析错位）
     */
    private fun detectFromBinary(file: File): SpineVersion {
        // 1. Try Spine 4.x header: 8-byte long hash + varint string
        val v40 = try {
            BufferedInputStream(FileInputStream(file)).use { input ->
                skipFully(input, 8)
                val version = readSkelString(input)
                version?.trim()?.let { SpineVersion.fromString(it) } ?: SpineVersion.UNKNOWN
            }
        } catch (_: Exception) {
            SpineVersion.UNKNOWN
        }
        if (v40 != SpineVersion.UNKNOWN) return v40

        // 2. Try Spine 3.8 header: string hash + string version
        val v38 = try {
            BufferedInputStream(FileInputStream(file)).use { input ->
                readSkelString(input) // hash
                val version = readSkelString(input)
                version?.trim()?.let { SpineVersion.fromString(it) } ?: SpineVersion.UNKNOWN
            }
        } catch (_: Exception) {
            SpineVersion.UNKNOWN
        }
        if (v38 != SpineVersion.UNKNOWN) return v38

        // 3. Fallback: scan raw head bytes for a version pattern like "4.1.20" / "3.8.xx"
        try {
            val header = readFirstBytes(file, 1024)
            val v4 = Regex("""(?<![0-9])4\.([0-9]+)(?:\.[0-9]+)*""").find(header)
            if (v4 != null) {
                val minor = v4.groupValues[1].toIntOrNull()
                if (minor != null && SpineVersion.fromMajorMinor(4, minor) != SpineVersion.UNKNOWN) {
                    return SpineVersion.fromMajorMinor(4, minor)
                }
            }
            val v3 = Regex("""(?<![0-9])3\.([5-8])(?:\.[0-9]+)*""").find(header)
            if (v3 != null) {
                val minor = v3.groupValues[1].toIntOrNull()
                if (minor != null) {
                    return SpineVersion.fromMajorMinor(3, minor)
                }
            }
        } catch (_: Exception) {}

        return SpineVersion.UNKNOWN
    }

    /**
     * Extracts animation and skin names safely across all versions for UI list population.
     *
     * - JSON：直接解析骨架描述
     * - 二进制：委托给已注册的官方 runtime peeker（各 runtime 模块注册），无 GL 环境下解析。
     *   不再使用字节串关键词扫描，避免产生错误的动画名。
     */
    fun peekAnimationsAndSkins(skelFile: File, atlasFile: File? = null): Pair<List<String>, List<String>> {
        val anims = LinkedHashSet<String>()
        val skins = LinkedHashSet<String>()

        val isBinary = detectFormat(skelFile) == "SKEL"
        if (!isBinary) {
            try {
                val jsonStr = skelFile.readText(StandardCharsets.UTF_8)
                val jsonObj = JSONObject(jsonStr)

                val animsObj = jsonObj.optJSONObject("animations")
                if (animsObj != null) {
                    val keys = animsObj.keys()
                    while (keys.hasNext()) {
                        anims.add(keys.next())
                    }
                }

                val skinsObj = jsonObj.optJSONObject("skins")
                if (skinsObj != null) {
                    val keys = skinsObj.keys()
                    while (keys.hasNext()) {
                        skins.add(keys.next())
                    }
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // 二进制：按检测到的版本，用官方 runtime 离屏嗅探
            val detectedVer = detectVersion(skelFile)
            val peeked = SpineMultiRuntimeManager.peek(detectedVer, skelFile)
            if (peeked != null) {
                anims.addAll(peeked.first)
                skins.addAll(peeked.second)
            }
        }

        if (anims.isEmpty()) anims.add("idle")
        if (skins.isEmpty()) skins.add("default")
        return Pair(anims.toList(), skins.toList())
    }

    // ==================== 二进制读取工具（模拟 SkeletonInput） ====================

    private fun readFirstBytes(file: File, maxBytes: Int): String {
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(maxBytes)
                val read = input.read(buffer)
                String(buffer, 0, read.coerceAtLeast(0), StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun skipFully(input: BufferedInputStream, n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped > 0) {
                remaining -= skipped.toInt()
            } else {
                if (input.read() < 0) throw java.io.EOFException("Unexpected end of file")
                remaining--
            }
        }
    }

    /** 模拟 SkeletonInput.readString()：读取 varint 长度前缀 + UTF-8 字节 */
    private fun readSkelString(input: BufferedInputStream): String? {
        val byteCount = readVarint(input)
        return when (byteCount) {
            0 -> null
            1 -> ""
            else -> {
                val bytes = ByteArray(byteCount - 1)
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) throw java.io.EOFException("Unexpected end of file")
                    offset += read
                }
                String(bytes, StandardCharsets.UTF_8)
            }
        }
    }

    /** 模拟 SkeletonInput.readInt(true)：7-bit 分组 varint（无符号，优化为正数） */
    private fun readVarint(input: BufferedInputStream): Int {
        var b = input.read()
        if (b < 0) throw java.io.EOFException("Unexpected end of file")
        var result = b and 0x7f
        if ((b and 0x80) != 0) {
            b = input.read()
            if (b < 0) throw java.io.EOFException("Unexpected end of file")
            result = result or ((b and 0x7f) shl 7)
            if ((b and 0x80) != 0) {
                b = input.read()
                if (b < 0) throw java.io.EOFException("Unexpected end of file")
                result = result or ((b and 0x7f) shl 14)
                if ((b and 0x80) != 0) {
                    b = input.read()
                    if (b < 0) throw java.io.EOFException("Unexpected end of file")
                    result = result or ((b and 0x7f) shl 21)
                    if ((b and 0x80) != 0) {
                        b = input.read()
                        if (b < 0) throw java.io.EOFException("Unexpected end of file")
                        result = result or ((b and 0x7f) shl 28)
                    }
                }
            }
        }
        return result
    }
}
