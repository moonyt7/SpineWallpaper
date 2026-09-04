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

        // 同一目录下同 basename 的 .skel/.skel.bytes/.json 视为同一模型，去重后仅导入一次（优先 .skel 二进制）
        val groupedSkel = skelFiles.groupBy { file ->
            val base = file.nameWithoutExtension.removeSuffix(".skel").removeSuffix(".json").lowercase()
            (file.parentFile?.absolutePath ?: "") + "::" + base
        }
        val uniqueSkelFiles = groupedSkel.values.map { group ->
            group.firstOrNull { f -> f.name.lowercase().endsWith(".skel") || f.name.lowercase().endsWith(".skel.bytes") } ?: group.first()
        }

        for (skelFile in uniqueSkelFiles) {
            // 骨架文件所在目录视为本模型的搜索根（可能是子目录，也可能压缩包根）
            val modelRoot = skelFile.parentFile ?: stagingDir

            val modelId = UUID.randomUUID().toString()
            val targetDir = File(modelsRoot, modelId)
            targetDir.mkdirs()

            var rawName = skelFile.nameWithoutExtension.removeSuffix(".skel").removeSuffix(".json")
            if (rawName.equals("skeleton", ignoreCase = true) || rawName.equals("spine", ignoreCase = true) || rawName.equals("data", ignoreCase = true)) {
                if (modelRoot != stagingDir && modelRoot.name.isNotEmpty()) {
                    rawName = modelRoot.name
                }
            }
            val modelName = if (rawName.isEmpty()) "Spine Model" else rawName

            val base = skelFile.nameWithoutExtension.removeSuffix(".skel").removeSuffix(".json")

            // ===== 最小依赖收集：不再整包 copyRecursively，只复制本角色自身用到的文件 =====
            // 1. 骨架文件（.skel/.skel.bytes/.json/.json.txt）
            // 2. 匹配的同名 .atlas（含 .atlas.txt）或该目录下唯一 atlas
            // 3. atlas 文本中所有 page 引用的贴图（.png/.jpg/.webp）
            // 4. 同名 .config.json（可选，提供 scale/动画偏好）
            val dirFiles = modelRoot.listFiles()?.filter { it.isFile } ?: emptyList()

            val matchedAtlas = dirFiles.firstOrNull {
                val n = it.name.lowercase()
                it.nameWithoutExtension.equals(base, ignoreCase = true) && (n.endsWith(".atlas") || n.endsWith(".atlas.txt"))
            } ?: dirFiles.firstOrNull { it.name.lowercase().endsWith(".atlas") || it.name.lowercase().endsWith(".atlas.txt") }

            val skelRel = skelFile.name
            var atlasRel = matchedAtlas?.name

            // 复制骨架文件（平铺到模型目录根）
            skelFile.copyTo(File(targetDir, skelFile.name), overwrite = true)
            val finalSkelFile = File(targetDir, skelFile.name)

            // 收集 atlas 依赖的贴图源文件集合（源文件 -> 复制到目标用相对扁平名）
            val imageSrcTargets = mutableListOf<Pair<File, String>>()
            if (matchedAtlas != null) {
                val atlasTargetName = matchedAtlas.name
                matchedAtlas.copyTo(File(targetDir, atlasTargetName), overwrite = true)

                val usedImages = resolveAtlasImages(matchedAtlas)
                val nameToSrc = dirFiles.associateBy { it.name }
                val lowerToSrc = dirFiles.associateBy { it.name.lowercase() }
                for (img in usedImages) {
                    val src = nameToSrc[img]
                        ?: lowerToSrc[img.lowercase()]
                        ?: lowerToSrc[File(img).name.lowercase()]
                        ?: lowerToSrc[File(img).name.substringAfterLast('/').lowercase()]
                    if (src != null) {
                        imageSrcTargets.add(Pair(src, src.name))
                    }
                }

                // 若无 .atlas 的同名贴图，但确实解析不到任何贴图且目录里只有单个贴图，回退复制目录内该角色前缀贴图
                if (imageSrcTargets.isEmpty()) {
                    dirFiles.filter { it.name.startsWith(base, ignoreCase = true) && isImageFile(it.name) }
                        .forEach { imageSrcTargets.add(Pair(it, it.name)) }
                }
            } else {
                // 无 atlas 时（少见），仅复制同前缀贴图，避免整包
                dirFiles.filter { it.name.startsWith(base, ignoreCase = true) && isImageFile(it.name) }
                    .forEach { imageSrcTargets.add(Pair(it, it.name)) }
            }

            // 复制贴图文件（覆盖式平铺到模型目录）
            for ((src, flatName) in imageSrcTargets) {
                try {
                    src.copyTo(File(targetDir, flatName), overwrite = true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 复制同名 .config.json（若存在）
            dirFiles.firstOrNull {
                it.nameWithoutExtension.equals(base, ignoreCase = true) && it.name.lowercase().endsWith(".config.json")
            }?.let { cfg ->
                try { cfg.copyTo(File(targetDir, cfg.name), overwrite = true) } catch (e: Exception) { e.printStackTrace() }
            }

            // 骨架可能以 .skel 为主但模型需要 .json 做回退；若目录中还存在同前缀的备选骨架(.json/.skel.bytes)，也一并带上（体积小）
            dirFiles.firstOrNull {
                it.nameWithoutExtension.equals(base, ignoreCase = true) &&
                    it.name != skelFile.name &&
                    (it.name.lowercase().endsWith(".skel.bytes") || it.name.lowercase().endsWith(".json"))
            }?.let { alt ->
                try { alt.copyTo(File(targetDir, alt.name), overwrite = true) } catch (e: Exception) { e.printStackTrace() }
            }

            // Detect Spine Version (3.6, 3.7, 3.8, 4.0, 4.1, 4.2)
            val detectedVer = SpineVersionDetector.detectVersionString(finalSkelFile)
            val detectedFmt = SpineVersionDetector.detectFormat(finalSkelFile)

            val (anims, skins) = SpineVersionDetector.peekAnimationsAndSkins(finalSkelFile, File(targetDir, atlasRel ?: ""))

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
                    put("skelFile", skelRel)
                    if (atlasRel != null) put("atlasFile", atlasRel)
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

    /**
     * 一次性回收旧版本导入造成的大量重复占用：
     * 早期实现会把整包 ZIP（含多个角色）原样复制到每个模型目录，导致同一套 77 文件 ≈ 91MB
     * 在每个模型目录里重复出现。这里按 model_meta.json 指向的最小依赖集，把每个模型目录中
     * 用不到的皮肤/贴图/骨架文件清理掉，仅保留：model_meta.json + 骨架 + atlas + atlas 引用的贴图 + 同名 config。
     * 幂等：处理过的目录（已无多余文件）再次执行几乎无操作。由 prefs 标记只跑一次。
     */
    fun cleanupLegacyModelFolders(context: Context) {
        try {
            val prefs = context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("model_storage_cleaned_v2", false)) return

            val models = getSavedModels(context)
            var reclaimedFiles = 0
            for (m in models) {
                try {
                    val dir = File(m.folderPath)
                    if (!dir.isDirectory) continue

                    // 读取该模型的最小依赖名集合
                    val keepNames = mutableSetOf<String>()
                    keepNames.add("model_meta.json")
                    val metaFile = File(dir, "model_meta.json")
                    var skelName: String? = null
                    var atlasName: String? = null
                    if (metaFile.exists()) {
                        try {
                            val meta = JSONObject(metaFile.readText())
                            skelName = meta.optString("skelFile").takeIf { it.isNotEmpty() }
                            atlasName = meta.optString("atlasFile").takeIf { it.isNotEmpty() }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    if (skelName != null) keepNames.add(File(skelName).name)
                    if (atlasName != null) {
                        val atlasFile = File(dir, File(atlasName).name)
                        keepNames.add(File(atlasName).name)
                        if (atlasFile.exists()) {
                            // atlas 引用的所有贴图保留
                            resolveAtlasImages(atlasFile).forEach { keepNames.add(File(it).name) }
                        }
                    }

                    // 兜底：若上面没解析出任何模型文件，跳过该目录（避免误删）
                    if (keepNames.size <= 1) continue

                    val dirFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
                    for (f in dirFiles) {
                        val lower = f.name.lowercase()
                        // 只清理这几类大文件：贴图、atlas、骨架、json。其它文件（如 meta）保留。
                        val isCleanable = isImageFile(lower) ||
                            lower.endsWith(".atlas") || lower.endsWith(".atlas.txt") ||
                            lower.endsWith(".skel") || lower.endsWith(".skel.bytes") ||
                            (lower.endsWith(".json") && !lower.endsWith("model_meta.json"))
                        if (isCleanable && f.name !in keepNames) {
                            if (f.delete()) reclaimedFiles++
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            prefs.edit().putBoolean("model_storage_cleaned_v2", true).apply()
            android.util.Log.i("SpineLoader", "cleanupLegacyModelFolders done, reclaimedFiles=$reclaimedFiles")
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

            // 若被删除的是副模型（槽位1），同步清空槽位1
            if (getModelId(context, 1) == modelId) {
                setModelId(context, 1, null)
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

    // ==================== 双模型槽位 (Live2DViewerEX 风格) ====================
    // Slot 0 复用原有键（完全向后兼容）；Slot 1 使用 model2_* 前缀键。

    object SlotPrefs {
        fun idKey(slot: Int) = if (slot == 0) "active_model_id" else "model2_id"
        fun scaleKey(slot: Int) = if (slot == 0) "model_scale" else "model2_scale"
        fun posXKey(slot: Int) = if (slot == 0) "model_pos_x" else "model2_pos_x"
        fun posYKey(slot: Int) = if (slot == 0) "model_pos_y" else "model2_pos_y"
        fun animKey(slot: Int) = if (slot == 0) "active_animation_name" else "model2_animation"
        fun skinKey(slot: Int) = if (slot == 0) "active_skin_name" else "model2_skin"
    }

    fun getModelId(context: Context, slot: Int): String? {
        val prefs = context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
        return prefs.getString(SlotPrefs.idKey(slot), null)
    }

    fun setModelId(context: Context, slot: Int, modelId: String?) {
        val prefs = context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
        if (slot == 0) {
            prefs.edit().putString("active_model_id", modelId).apply()
        } else {
            prefs.edit().putString("model2_id", modelId).apply()
        }
    }

    fun getModelDirById(context: Context, modelId: String?): File? {
        if (modelId == null) return null
        val item = getSavedModels(context).firstOrNull { it.id == modelId } ?: return null
        val dir = File(item.folderPath)
        return if (dir.exists()) dir else null
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

    private fun isImageFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
    }

    /**
     * 解析 .atlas 文本中所有 page 引用的贴图文件路径。
     * libGDX/Spine atlas 结构：每个 page 以一个贴图文件路径行开头（无缩进、无冒号），
     * 其后紧跟 `size: <w>,<h>`（整张贴图尺寸），随后是若干 region。
     * region 名行也是无冒号行，但其下一属性是 `rotate:` / `xy:` 等而非 `size:`，
     * 因此用「下一非空行以 size: 开头」来区分贴图行与 region 名行。
     */
    private fun resolveAtlasImages(atlasFile: File): Set<String> {
        val result = linkedSetOf<String>()
        try {
            val lines = atlasFile.readLines()
            if (lines.isEmpty()) return result

            // 逐行扫描，记录每个 token(无冒号行)，当下一个 token 之后出现 `size:` 属性行且该属性紧邻 token 之后，
            // 说明该 token 是贴图文件行。
            var i = 0
            while (i < lines.size) {
                var trimmed = lines[i].trim().removePrefix("\uFEFF").removePrefix("\uFFFE")
                if (trimmed.isNotEmpty() && !trimmed.contains(':')) {
                    // candidate token（贴图文件名 或 region 名）
                    var j = i + 1
                    // 跳过可能的空行
                    while (j < lines.size && lines[j].trim().isEmpty()) j++
                    val nextProp = if (j < lines.size) lines[j].trim() else ""
                    if (nextProp.startsWith("size:")) {
                        // 确认为贴图文件行
                        val clean = trimmed.replace("\\", "/")
                        val filePart = clean.substringAfterLast('/')
                        if (filePart.isNotEmpty()) result.add(filePart)
                        i = j
                    }
                }
                i++
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
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