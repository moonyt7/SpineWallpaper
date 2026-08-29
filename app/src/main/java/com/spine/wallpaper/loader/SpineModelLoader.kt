package com.spine.wallpaper.loader

import android.content.Context
import android.net.Uri
import com.spine.wallpaper.bridge.SpineVersionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class SpineModelItem(
    val id: String,
    val name: String,
    val folderPath: String,
    val animations: List<String>,
    val skins: List<String>,
    val version: String = "4.1",
    val format: String = "SKEL",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Asynchronous Spine Model Loader with persistent local library storage.
 * Extracts ZIP model files (.skel, .atlas, .png, .config.json) to internal app storage
 * and detects Spine versions (3.6, 3.7, 3.8, 4.0, 4.1, 4.2).
 */
object SpineModelLoader {

    init {
        ensureNativesLoaded()
    }

    fun ensureNativesLoaded() {
        try {
            com.badlogic.gdx.utils.GdxNativesLoader.load()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        try {
            System.loadLibrary("gdx")
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        if (com.badlogic.gdx.Gdx.app == null) {
            try {
                val dummyApp = java.lang.reflect.Proxy.newProxyInstance(
                    com.badlogic.gdx.Application::class.java.classLoader,
                    arrayOf(com.badlogic.gdx.Application::class.java)
                ) { _, method, args ->
                    when (method.name) {
                        "getType" -> com.badlogic.gdx.Application.ApplicationType.Android
                        "getLogLevel" -> 0
                        else -> {
                            val returnType = method.returnType
                            when {
                                returnType == Boolean::class.javaPrimitiveType -> false
                                returnType == Int::class.javaPrimitiveType -> 0
                                returnType == Float::class.javaPrimitiveType -> 0.0f
                                returnType == String::class.java -> ""
                                else -> null
                            }
                        }
                    }
                } as com.badlogic.gdx.Application
                com.badlogic.gdx.Gdx.app = dummyApp
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        if (com.badlogic.gdx.Gdx.graphics == null) {
            try {
                val dummyGraphics = java.lang.reflect.Proxy.newProxyInstance(
                    com.badlogic.gdx.Graphics::class.java.classLoader,
                    arrayOf(com.badlogic.gdx.Graphics::class.java)
                ) { _, method, args ->
                    when (method.name) {
                        "supportsExtension" -> false
                        "isGL30Available" -> false
                        "getWidth" -> 1080
                        "getHeight" -> 1920
                        "getBackBufferWidth" -> 1080
                        "getBackBufferHeight" -> 1920
                        "getDeltaTime" -> 0.016f
                        "getGLVersion" -> com.badlogic.gdx.graphics.glutils.GLVersion(
                            com.badlogic.gdx.Application.ApplicationType.Android,
                            "2.0",
                            "Generic",
                            "Generic"
                        )
                        else -> {
                            val returnType = method.returnType
                            when {
                                returnType == Boolean::class.javaPrimitiveType -> false
                                returnType == Int::class.javaPrimitiveType -> 0
                                returnType == Float::class.javaPrimitiveType -> 0.0f
                                returnType == Long::class.javaPrimitiveType -> 0L
                                returnType == String::class.java -> ""
                                else -> null
                            }
                        }
                    }
                } as com.badlogic.gdx.Graphics
                com.badlogic.gdx.Gdx.graphics = dummyGraphics
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        if (com.badlogic.gdx.Gdx.files == null) {
            try {
                val dummyFiles = java.lang.reflect.Proxy.newProxyInstance(
                    com.badlogic.gdx.Files::class.java.classLoader,
                    arrayOf(com.badlogic.gdx.Files::class.java)
                ) { _, method, args ->
                    when (method.name) {
                        "getFileHandle", "absolute", "external", "internal", "local" -> {
                            if (args != null && args.isNotEmpty() && args[0] is String) {
                                com.badlogic.gdx.files.FileHandle(args[0] as String)
                            } else null
                        }
                        "isExternalStorageAvailable", "isLocalStorageAvailable" -> true
                        "getExternalStoragePath", "getLocalStoragePath" -> ""
                        else -> {
                            val returnType = method.returnType
                            when {
                                returnType == Boolean::class.javaPrimitiveType -> false
                                returnType == Int::class.javaPrimitiveType -> 0
                                returnType == String::class.java -> ""
                                else -> null
                            }
                        }
                    }
                } as com.badlogic.gdx.Files
                com.badlogic.gdx.Gdx.files = dummyFiles
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    suspend fun importZipToLibrary(context: Context, uri: Uri): List<SpineModelItem> = withContext(Dispatchers.IO) {
        ensureNativesLoaded()

        val stagingDir = File(context.cacheDir, "zip_staging_" + UUID.randomUUID().toString())
        stagingDir.mkdirs()

        var extractedCount = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                extractedCount = extractZipToDir(stream, stagingDir)
            }
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw e
        }

        if (extractedCount == 0) {
            stagingDir.deleteRecursively()
            throw IllegalArgumentException("无法从所选 ZIP 文件解压内容，请确认选择的是有效的 Spine 2D ZIP 压缩包")
        }

        val allFiles = stagingDir.walkTopDown().filter { it.isFile }.toList()
        val skelFiles = allFiles.filter { file ->
            val name = file.name.lowercase()
            if (name.endsWith(".skel") || name.endsWith(".skel.bytes")) {
                true
            } else if (name.endsWith(".json") && !name.endsWith(".config.json") && !name.endsWith("build.json") && !name.endsWith("model_meta.json")) {
                try {
                    val jsonText = file.readText()
                    jsonText.contains("bones") || jsonText.contains("skeleton") || jsonText.contains("animations")
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        }

        if (skelFiles.isEmpty()) {
            stagingDir.deleteRecursively()
            throw IllegalArgumentException("压缩包内未找到 Spine .skel / .json 骨骼数据文件")
        }

        val modelsRoot = File(context.filesDir, "spine_models")
        modelsRoot.mkdirs()

        val importedItems = mutableListOf<SpineModelItem>()

        for (skelFile in skelFiles) {
            val modelId = UUID.randomUUID().toString()
            val targetDir = File(modelsRoot, modelId)
            targetDir.mkdirs()

            val parentDir = skelFile.parentFile ?: stagingDir
            var rawName = skelFile.nameWithoutExtension.removeSuffix(".skel").removeSuffix(".json")
            if (rawName.equals("skeleton", ignoreCase = true) || rawName.equals("spine", ignoreCase = true) || rawName.equals("data", ignoreCase = true)) {
                if (parentDir != stagingDir && parentDir.name.isNotEmpty()) {
                    rawName = parentDir.name
                }
            }
            val modelName = if (rawName.isEmpty()) "Spine Model" else rawName

            if (parentDir != stagingDir) {
                parentDir.copyRecursively(targetDir, overwrite = true)
            } else {
                stagingDir.copyRecursively(targetDir, overwrite = true)
            }

            // Also mirror any nested image/texture files from stagingDir to targetDir to prevent subfolder path resolution errors
            stagingDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val flatTarget = File(targetDir, f.name)
                if (!flatTarget.exists()) {
                    try { f.copyTo(flatTarget, overwrite = true) } catch (_: Exception) {}
                }
            }

            val relativePath = skelFile.relativeTo(if (parentDir != stagingDir) parentDir else stagingDir).path
            val targetSkelFile = File(targetDir, relativePath)
            val finalSkelFile = if (targetSkelFile.exists()) targetSkelFile else skelFile

            // Detect Spine Version (3.6, 3.7, 3.8, 4.0, 4.1, 4.2)
            val detectedVer = SpineVersionDetector.detectVersionString(finalSkelFile)
            val detectedFmt = SpineVersionDetector.detectFormat(finalSkelFile)

            val base = skelFile.nameWithoutExtension.removeSuffix(".skel").removeSuffix(".json")
            val matchedAtlas = allFiles.firstOrNull { 
                it.name.startsWith(base, ignoreCase = true) && (it.name.endsWith(".atlas") || it.name.endsWith(".atlas.txt")) 
            } ?: allFiles.firstOrNull { it.name.endsWith(".atlas") || it.name.endsWith(".atlas.txt") }

            val (anims, skins) = SpineVersionDetector.peekAnimationsAndSkins(finalSkelFile, matchedAtlas)

            val item = SpineModelItem(
                id = modelId,
                name = modelName,
                folderPath = targetDir.absolutePath,
                animations = anims,
                skins = skins,
                version = detectedVer,
                format = detectedFmt
            )

            try {
                val metaObj = JSONObject().apply {
                    put("id", modelId)
                    put("name", modelName)
                    put("version", detectedVer)
                    put("format", detectedFmt)
                    put("skelFile", relativePath)
                    if (matchedAtlas != null) {
                        val atlasRel = matchedAtlas.relativeTo(if (parentDir != stagingDir) parentDir else stagingDir).path
                        put("atlasFile", atlasRel)
                    }
                }
                File(targetDir, "model_meta.json").writeText(metaObj.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }

            saveModelMetadata(context, item)
            importedItems.add(item)
        }

        stagingDir.deleteRecursively()

        if (importedItems.isNotEmpty()) {
            setActiveModelId(context, importedItems[0].id)
        }

        return@withContext importedItems
    }

    fun getSavedModels(context: Context): List<SpineModelItem> {
        val prefs = context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_models_list_json", "[]") ?: "[]"
        val result = mutableListOf<SpineModelItem>()

        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val animArray = obj.optJSONArray("animations") ?: JSONArray()
                val skinArray = obj.optJSONArray("skins") ?: JSONArray()

                val anims = mutableListOf<String>()
                for (a in 0 until animArray.length()) anims.add(animArray.getString(a))

                val skins = mutableListOf<String>()
                for (s in 0 until skinArray.length()) skins.add(skinArray.getString(s))

                result.add(
                    SpineModelItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        folderPath = obj.getString("folderPath"),
                        animations = anims,
                        skins = skins,
                        version = obj.optString("version", "4.1"),
                        format = obj.optString("format", "SKEL"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    private fun saveModelMetadata(context: Context, item: SpineModelItem) {
        val list = getSavedModels(context).toMutableList()
        list.removeAll { it.id == item.id }
        list.add(0, item)

        val array = JSONArray()
        list.forEach { m ->
            val obj = JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("folderPath", m.folderPath)
                put("animations", JSONArray(m.animations))
                put("skins", JSONArray(m.skins))
                put("version", m.version)
                put("format", m.format)
                put("createdAt", m.createdAt)
            }
            array.put(obj)
        }

        context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_models_list_json", array.toString())
            .apply()
    }

    fun deleteModel(context: Context, modelId: String) {
        val list = getSavedModels(context).toMutableList()
        val item = list.firstOrNull { it.id == modelId }
        if (item != null) {
            File(item.folderPath).deleteRecursively()
            list.removeAll { it.id == modelId }

            val array = JSONArray()
            list.forEach { m ->
                val obj = JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                    put("folderPath", m.folderPath)
                    put("animations", JSONArray(m.animations))
                    put("skins", JSONArray(m.skins))
                    put("version", m.version)
                    put("format", m.format)
                    put("createdAt", m.createdAt)
                }
                array.put(obj)
            }

            val prefs = context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("saved_models_list_json", array.toString()).apply()

            if (getActiveModelId(context) == modelId) {
                val nextId = list.firstOrNull()?.id
                setActiveModelId(context, nextId)
            }
        }
    }

    fun getActiveModelId(context: Context): String? {
        val prefs = context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
        return prefs.getString("active_model_id", null)
    }

    fun setActiveModelId(context: Context, modelId: String?) {
        val prefs = context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("active_model_id", modelId).apply()
    }

    fun getActiveModelDir(context: Context): File? {
        val activeId = getActiveModelId(context)
        if (activeId != null) {
            val item = getSavedModels(context).firstOrNull { it.id == activeId }
            if (item != null) {
                val dir = File(item.folderPath)
                if (dir.exists()) return dir
            }
        }
        val fallbackModels = getSavedModels(context)
        if (fallbackModels.isNotEmpty()) {
            return File(fallbackModels[0].folderPath)
        }
        val tempDir = File(context.cacheDir, "spine_model_temp")
        if (tempDir.exists() && tempDir.listFiles()?.isNotEmpty() == true) {
            return tempDir
        }
        return null
    }

    private fun extractZipToDir(inputStream: InputStream, targetDir: File): Int {
        var count = 0
        try {
            val zip = ZipInputStream(inputStream)
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryPath = entry.name
                    val fileName = File(entryPath).name
                    if (fileName.isNotEmpty() && !fileName.startsWith(".") && !entryPath.contains("__MACOSX")) {
                        val relativeFile = File(targetDir, entryPath)
                        relativeFile.parentFile?.mkdirs()
                        FileOutputStream(relativeFile).use { out ->
                            zip.copyTo(out)
                        }

                        val flatFile = File(targetDir, fileName)
                        if (!flatFile.exists()) {
                            relativeFile.copyTo(flatFile, overwrite = true)
                        }
                        count++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return count
    }
}