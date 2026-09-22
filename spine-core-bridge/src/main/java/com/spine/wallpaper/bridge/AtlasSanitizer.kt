package com.spine.wallpaper.bridge

import com.badlogic.gdx.files.FileHandle
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Atlas 文件清理与尺寸校正。
 *
 * 处理 AssetStudio 从 Unity 资源里解包出来的 Spine atlas 的常见毛病：
 *
 * 1) 贴图文件名、反斜杠、UTF-8 BOM 清理（原有逻辑）。
 *
 * 2) atlas 声明的 `size:` 与贴图实际像素尺寸不一致。
 *    Unity 导入纹理时会按 NPOT 规则把整页**非等比拉伸**（例如 1692x2020 -> 2048x2048）。
 *    libGDX 的 TextureAtlas 用「实际贴图尺寸」归一化 UV
 *    （TextureAtlas.load 里 `new AtlasRegion(texture, left, top, w, h)`，
 *     而 TextureRegion.setRegion 再除以 `texture.getWidth()/getHeight()`，
 *     页面头里的 `size:` 根本不参与），
 *    于是同一份 atlas 在 libGDX 下必然错位 —— 这就是「贴图全乱」的根因。
 *
 *    这种拉伸是各向异性的，仅靠缩放 bounds/offsets 无法精确复原：
 *    updateOffset() 里 packedWidth/Height 与 originalWidth/Height 的运算是按
 *    「实际矩形与逻辑区域只差一个纯旋转」设计的，各向异性会破坏这个前提。
 *    所以只能把贴图重采样回声明尺寸；这一步需要图像编解码，由宿主通过 [Resampler] 注入
 *    （app 模块用 Android Bitmap 实现）。不传时跳过尺寸修正。
 *
 *    判定依据（已用矩形足迹自洽性核对过）：把 region 的 packed 矩形按
 *    90/270 交换宽高后，其最大足迹总会严丝合缝地落在 `size:` 声明范围内
 *    （如 a004: 足迹 (1687,2017) vs 声明 (1692,2020)），
 *    说明**声明尺寸才是真实的内容坐标系**，PNG 是被拉伸过的那个。
 *
 * ⚠️ 不要动 `rotate:`。
 *    Spine 4.x 的 `rotate: 90/180/270` 看起来「libGDX 只认 90」，
 *    但实测给 180/270 补旋转补偿会让**原本正常的模型**出现贴图异常
 *    （这些 atlas 的 rotate 字段与实际像素布局对不上，默认行为就已经是正确的那一个）。
 *    唯一需要修的是上面的尺寸不一致。
 */
object AtlasSanitizer {

    /**
     * 中间产物（清理 + 尺寸校正后的 atlas）的文件名前缀。
     *
     * 必须以 `_` 开头 —— 宿主选 atlas 文件时会优先跳过 `_` 开头的名字，
     * 否则目录遍历顺序不保证时可能选中自己的中间产物。
     * 改动生成逻辑时把版本号 +1，于是旧缓存因前缀不匹配而**自然失效**，
     * 不会拿一份由旧逻辑生成的文件去加载。
     */
    private const val CACHE_PREFIX = "_sanitized_v2_"

    /** 贴图尺寸与 atlas 声明不符时，由宿主提供的重采样实现。 */
    fun interface Resampler {
        /**
         * 把 [source] 重采样成 width x height 的新图片。
         * 实现应自带缓存（同一目标尺寸不要每次重算），失败返回 null。
         */
        fun resample(source: File, width: Int, height: Int): File?
    }

    /**
     * @param handle    可直接交给 TextureAtlas 加载的 atlas 文件
     * @param fromCache 这次直接复用了上次的中间产物（没有重新解析、也没有重写盘）。
     *                  交给宿主打日志用 —— 否则「第二次加载一条日志都没有」会被误读成
     *                  修复没生效。（本模块不依赖 Android，所以不在这里打 log。）
     */
    class SanitizeResult(val handle: FileHandle, val fromCache: Boolean = false)

    fun sanitizeEx(atlasFile: File, resampler: Resampler?): SanitizeResult {
        val parentDir = atlasFile.parentFile
            ?: return SanitizeResult(FileHandle(atlasFile))
        try {
            // 上次生成的中间产物若仍然有效就直接复用：否则每次加载模型都要重解析一遍 atlas
            // 并把它重写一次盘（中间文件就落在模型目录里，属于无谓的写放大、也把目录弄脏）。
            val cached = File(parentDir, CACHE_PREFIX + atlasFile.name)
            if (isCacheUsable(cached, atlasFile, parentDir)) {
                return SanitizeResult(FileHandle(cached), fromCache = true)
            }
            val lines = atlasFile.readLines(StandardCharsets.UTF_8).toMutableList()
            val diskFiles = parentDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val diskMap = HashMap<String, String>(diskFiles.size * 2)
            for (f in diskFiles) {
                diskMap[f.name.lowercase()] = f.name
                diskMap[f.nameWithoutExtension.lowercase()] = f.name
            }

            var modified = false

            // ---------- 第 1 步：清理（BOM / 反斜杠 / 贴图文件名大小写） ----------
            for (i in lines.indices) {
                var line = lines[i]
                // 去掉 UTF-8 BOM（出现在文件首行开头，会使贴图文件名变成 \uFEFFxxx.png 而无法匹配磁盘文件）
                if (line.isNotEmpty() && (line[0] == '\uFEFF' || line[0] == '\uFFFE')) {
                    line = line.substring(1)
                    modified = true
                }
                if (line.contains('\\')) {
                    line = line.replace('\\', '/')
                    modified = true
                }

                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.contains(':')) {
                    val cleanBase = trimmed.substringAfterLast('/')
                    val key = cleanBase.lowercase()
                    val matchedActual = diskMap[key]
                        ?: diskMap[key.removeSuffix(".png").removeSuffix(".jpg").removeSuffix(".jpeg")]
                    if (matchedActual != null && matchedActual != trimmed) {
                        line = matchedActual
                        modified = true
                    }
                }
                lines[i] = line
            }

            // ---------- 第 2 步：解析页与区域（只记行号，便于精确改写） ----------
            val pages = ArrayList<PageRef>()
            var page: PageRef? = null
            var inRegion = false
            for (i in lines.indices) {
                val text = lines[i].trim()
                if (text.isEmpty()) continue
                if (!text.contains(':')) {
                    val lower = text.substringAfterLast('/').lowercase()
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                        lower.endsWith(".jpeg") || lower.endsWith(".webp")
                    ) {
                        page = PageRef(i)
                        pages.add(page)
                        inRegion = false
                    } else if (page != null) {
                        inRegion = true
                    }
                    continue
                }
                if (!inRegion) {
                    val colon = text.indexOf(':')
                    val key = text.substring(0, colon).trim().lowercase()
                    if (key == "size") {
                        page?.declared = parseInts(text.substring(colon + 1).trim(), 2)
                    }
                }
            }

            // ---------- 第 3 步：贴图尺寸校正 ----------
            for (p in pages) {
                val imageName = lines[p.nameLine].trim().substringAfterLast('/')
                val declared = p.declared ?: continue
                val imageSize = readImageSize(File(parentDir, imageName)) ?: continue
                if (declared[0] <= 0 || declared[1] <= 0) continue
                if (declared[0] == imageSize[0] && declared[1] == imageSize[1]) continue
                val fixed = resampler?.resample(File(parentDir, imageName), declared[0], declared[1])
                if (fixed != null && fixed.isFile) {
                    lines[p.nameLine] = fixed.name
                    modified = true
                }
            }

            if (modified) {
                val sanitizedFile = File(parentDir, CACHE_PREFIX + atlasFile.name)
                sanitizedFile.writeText(
                    lines.joinToString(System.lineSeparator()),
                    StandardCharsets.UTF_8
                )
                return SanitizeResult(FileHandle(sanitizedFile))
            }
            return SanitizeResult(FileHandle(atlasFile))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return SanitizeResult(FileHandle(atlasFile))
    }

    /**
     * 判断上一次生成的中间产物能否直接复用。
     *
     * 条件：
     * 1. 文件存在，且**不比源 atlas 旧**（源文件被改过就重算）；
     * 2. 它引用的每个贴图文件都还在 —— 例如 `_fixed_*.png` 被清理过，
     *    这时必须重新走一遍流程（[Resampler] 会重新生成它），
     *    不能拿一份指向不存在贴图的 atlas 去加载。
     *
     * 读的是我们自己刚生成的文件（页数很少），开销远小于重新解析 + 重写整份 atlas。
     */
    private fun isCacheUsable(cached: File, source: File, parentDir: File): Boolean {
        if (!cached.isFile) return false
        if (source.isFile && cached.lastModified() < source.lastModified()) return false
        return try {
            var anyPage = false
            val reader = cached.bufferedReader(StandardCharsets.UTF_8)
            reader.use { r ->
                while (true) {
                    val raw = r.readLine() ?: break
                    val text = raw.trim()
                    if (text.isEmpty() || text.contains(':')) continue
                    val lower = text.substringAfterLast('/').lowercase()
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                        lower.endsWith(".jpeg") || lower.endsWith(".webp")
                    ) {
                        anyPage = true
                        if (!File(parentDir, text).isFile) return false
                    }
                }
            }
            anyPage
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 读 PNG / JPEG 头里的宽高，只读文件头部，不解码整张图。
     * 返回 null 表示不是这两种格式（或读取失败），调用方按「无法判断」处理。
     */
    fun readImageSize(file: File): IntArray? {
        if (!file.isFile) return null
        return try {
            file.inputStream().buffered().use { ins ->
                val head = ByteArray(32)
                var read = 0
                while (read < head.size) {
                    val n = ins.read(head, read, head.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read >= 24 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
                    head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
                ) {
                    // PNG: 8 字节签名之后就是 IHDR，宽高在偏移 16 / 20
                    intArrayOf(beInt(head, 16), beInt(head, 20))
                } else {
                    readJpegSize(head, read, ins)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readJpegSize(
        head: ByteArray,
        headLen: Int,
        ins: java.io.InputStream
    ): IntArray? {
        if (headLen < 2 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return null
        // 头部已经吃进 head 缓冲，但缓冲是按需读满的；直接从流继续按段跳过。
        while (true) {
            var b = ins.read()
            if (b < 0) return null
            if (b != 0xFF) continue          // 段之间应以 0xFF 开始，容忍杂字节
            while (b == 0xFF) {              // 跳过填充的 0xFF
                b = ins.read()
                if (b < 0) return null
            }
            val marker = b
            if (marker == 0x01 || marker in 0xD0..0xD8) continue
            val lenHi = ins.read()
            val lenLo = ins.read()
            if (lenHi < 0 || lenLo < 0) return null
            val segLen = ((lenHi and 0xFF) shl 8) or (lenLo and 0xFF)
            if (segLen < 2) return null
            // SOF0..SOF15，排除 DHT(0xC4) / JPG(0xC8) / DAC(0xCC)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val body = ByteArray(5)
                var read = 0
                while (read < body.size) {
                    val n = ins.read(body, read, body.size - read)
                    if (n <= 0) return null
                    read += n
                }
                val h = ((body[1].toInt() and 0xFF) shl 8) or (body[2].toInt() and 0xFF)
                val w = ((body[3].toInt() and 0xFF) shl 8) or (body[4].toInt() and 0xFF)
                return intArrayOf(w, h)
            }
            var remaining = (segLen - 2).toLong()
            while (remaining > 0) {
                val skipped = ins.skip(remaining)
                if (skipped <= 0) return null
                remaining -= skipped
            }
        }
    }

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun parseInts(value: String, expected: Int): IntArray? {
        val parts = value.split(',')
        if (parts.size < expected) return null
        val out = IntArray(parts.size)
        for (i in parts.indices) {
            out[i] = parts[i].trim().toIntOrNull() ?: return null
        }
        return out
    }

    private class PageRef(val nameLine: Int) {
        var declared: IntArray? = null
    }
}
