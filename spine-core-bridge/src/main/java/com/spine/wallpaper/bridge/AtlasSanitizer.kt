package com.spine.wallpaper.bridge

import com.badlogic.gdx.files.FileHandle
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Atlas file sanitizer.
 * Fixes missing png/jpg extensions, Windows backslashes, and casing discrepancies in .atlas files.
 */
object AtlasSanitizer {

    fun sanitize(atlasFile: File): FileHandle {
        try {
            val lines = atlasFile.readLines(StandardCharsets.UTF_8)
            val parentDir = atlasFile.parentFile ?: return FileHandle(atlasFile)
            val diskFiles = parentDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val diskMap = mutableMapOf<String, String>()
            for (f in diskFiles) {
                diskMap[f.name.lowercase()] = f.name
                diskMap[f.nameWithoutExtension.lowercase()] = f.name
            }

            var modified = false
            val newLines = mutableListOf<String>()

            for (rawLine in lines) {
                var line = rawLine
                // 去掉 UTF-8 BOM（出现在文件首行开头，会使贴图文件名变成 \uFEFFxxx.png 而无法匹配磁盘文件）
                if (line.isNotEmpty() && (line[0] == '\uFEFF' || line[0] == '\uFFFE')) {
                    line = line.substring(1)
                    modified = true
                }
                if (line.contains("\\")) {
                    line = line.replace("\\", "/")
                    modified = true
                }

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
                val sanitizedFile = File(parentDir, "_sanitized_" + atlasFile.name)
                sanitizedFile.writeText(newLines.joinToString(System.lineSeparator()), StandardCharsets.UTF_8)
                return FileHandle(sanitizedFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return FileHandle(atlasFile)
    }
}