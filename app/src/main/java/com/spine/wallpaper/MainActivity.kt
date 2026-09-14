package com.spine.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spine.wallpaper.loader.DEFAULT_MODEL_GROUP
import com.spine.wallpaper.loader.SpineModelItem
import com.spine.wallpaper.loader.SpineModelLoader
import com.spine.wallpaper.service.SpineWallpaperService
import com.spine.wallpaper.ui.ColorPickerDialog
import com.spine.wallpaper.ui.SpineViewCompose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Theme-aware UI palette shared by the drawer / bottom bar / dialogs.
 * drawerBg has 60% alpha, bottomBarBg has 30% alpha (see requirements).
 */
private data class UiPalette(
    val drawerBg: Color,      // sidebar container (60% opaque)
    val bottomBarBg: Color,   // bottom control strip (30% opaque)
    val card: Color,
    val cardActive: Color,
    val cardSelected: Color,
    val chip: Color,
    val chipDisabled: Color,
    val divider: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val border: Color,
    val importBtn: Color,
    val wallpaperBtn: Color,
    val dialogBg: Color
)

private val DarkUiPalette = UiPalette(
    drawerBg = Color(0x990F172A),
    bottomBarBg = Color(0x4D1E293B),
    card = Color(0xFF1E293B),
    cardActive = Color(0xFF312E81),
    cardSelected = Color(0xFF4338CA),
    chip = Color(0xB3334155),
    chipDisabled = Color(0x66334155),
    divider = Color(0xFF334155),
    text = Color.White,
    textSecondary = Color(0xFFCBD5E1),
    textMuted = Color.Gray,
    accent = Color(0xFF6366F1),
    accentSoft = Color(0xFF818CF8),
    border = Color(0xFF475569),
    importBtn = Color(0xFF4F46E5),
    wallpaperBtn = Color(0xFF10B981),
    dialogBg = Color(0xFF0F172A)
)

private val LightUiPalette = UiPalette(
    drawerBg = Color(0x99FFFFFF),
    bottomBarBg = Color(0x4DF1F5F9),
    card = Color(0xFFF1F5F9),
    cardActive = Color(0xFFE0E7FF),
    cardSelected = Color(0xFF6366F1),
    chip = Color(0xB3CBD5E1),
    chipDisabled = Color(0x66CBD5E1),
    divider = Color(0xFFCBD5E1),
    text = Color(0xFF1E293B),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    accent = Color(0xFF4F46E5),
    accentSoft = Color(0xFF4338CA),
    border = Color(0xFFCBD5E1),
    importBtn = Color(0xFF4F46E5),
    wallpaperBtn = Color(0xFF10B981),
    dialogBg = Color(0xFFFFFFFF)
)

/**
 * 读取槽位已选皮肤集合：
 *   1) 优先 `active_skins_json` (JSONArray of string) —— 新格式
 *   2) fallback `active_skin_name` (String) —— 旧格式迁移
 *   3) fallback 该模型 skinNames 第一个
 * 过滤掉当前模型已不存在的皮肤名；保证至少有一个皮肤。
 */
private fun loadSelectedSkins(
    prefs: android.content.SharedPreferences,
    slot: Int,
    available: List<String>
): Set<String> {
    val key = if (slot == 1) "active_skins_json_2" else "active_skins_json"
    val result = LinkedHashSet<String>()
    val json = prefs.getString(key, null)
    if (!json.isNullOrEmpty()) {
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "").trim()
                if (name.isNotEmpty() && available.contains(name)) result.add(name)
            }
        } catch (_: Throwable) {}
    }
    if (result.isEmpty()) {
        val legacy = prefs.getString(if (slot == 1) "model2_skin" else "active_skin_name", null)
        if (!legacy.isNullOrEmpty() && available.contains(legacy)) result.add(legacy)
    }
    if (result.isEmpty()) {
        available.firstOrNull()?.let { result.add(it) }
    }
    return result
}

private fun saveSelectedSkins(
    prefs: android.content.SharedPreferences,
    slot: Int,
    set: Set<String>
) {
    val key = if (slot == 1) "active_skins_json_2" else "active_skins_json"
    val arr = org.json.JSONArray()
    for (n in set) arr.put(n)
    prefs.edit()
        .putString(key, arr.toString())
        // 兼容旧字段（写第一个）
        .putString(if (slot == 1) "model2_skin" else "active_skin_name", set.firstOrNull())
        .apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent == null) {
            intent = Intent()
        }
        if (intent.extras == null) {
            intent.putExtras(Bundle())
        }
        super.onCreate(savedInstanceState)

        // 沉浸式全屏
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // 关键：禁用系统栏背景绘制
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        try {
            SpineModelLoader.ensureNativesLoaded()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        try {
            Thread {
                try {
                    SpineModelLoader.cleanupLegacyModelFolders(this)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }.start()
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        setContent {
            val themePrefs = remember { getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE) }
            var isDarkTheme by remember { mutableStateOf(themePrefs.getString("theme_mode", "dark") != "light") }
            MaterialTheme(
                colorScheme = if (isDarkTheme) darkColorScheme(
                    primary = Color(0xFF6366F1),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B)
                ) else lightColorScheme(
                    primary = Color(0xFF4F46E5),
                    background = Color(0xFFF8FAFC),
                    surface = Color(0xFFE2E8F0)
                )
            ) {
                // 核心：包裹一层，告诉compose不要自动应用window insets
                Box(modifier = Modifier.fillMaxSize()) {
                    SpineWallpaperApp(
                        isDarkTheme = isDarkTheme,
                        onThemeChange = { dark -> isDarkTheme = dark }
                    )
                }
            }
        }
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            if (intent.extras == null) {
                intent.putExtras(Bundle())
            }
            setIntent(intent)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 重新隐藏系统栏（部分 ROM/系统对话框/手电筒提醒等会临时显示）
        if (hasFocus) {
            try {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } catch (_: Throwable) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpineWallpaperApp(
    isDarkTheme: Boolean = true,
    onThemeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE) }
    val palette = if (isDarkTheme) DarkUiPalette else LightUiPalette

    var savedModels by remember { mutableStateOf<List<SpineModelItem>>(emptyList()) }
    var activeModelId by remember { mutableStateOf<String?>(null) }
    var activeModelDir by remember { mutableStateOf<File?>(null) }

    // 副模型（槽位1）状态
    var model2Id by remember { mutableStateOf<String?>(null) }
    var model2Dir by remember { mutableStateOf<File?>(null) }
    var animationList2 by remember { mutableStateOf<List<String>>(emptyList()) }
    var skinList2 by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentAnimation2 by remember { mutableStateOf<String?>(null) }
    var currentSkins2 by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 当前正在调整的槽位（0 = 主模型，1 = 副模型）
    var selectedSlot by remember { mutableStateOf(prefs.getInt("selected_slot", 0)) }

    var animationList by remember { mutableStateOf<List<String>>(emptyList()) }
    var skinList by remember { mutableStateOf<List<String>>(emptyList()) }

    var currentAnimation by remember { mutableStateOf<String?>(null) }
    var currentSkins by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isPlaying by remember { mutableStateOf(true) }
    var scaleValue by remember { mutableStateOf(prefs.getFloat("model_scale", 1.0f)) }
    var scale2Value by remember { mutableStateOf(prefs.getFloat("model2_scale", 1.0f)) }
    var resetTick by remember { mutableStateOf(0) }
    var isPma by remember { mutableStateOf(prefs.getBoolean("pma_enabled", true)) }
    var isPma2 by remember { mutableStateOf(prefs.getBoolean("pma2_enabled", true)) }

    // 系统设置
    var tapAnimEnabled by remember { mutableStateOf(prefs.getBoolean("tap_switch_animation", true)) }
    var targetFps by remember { mutableStateOf(prefs.getInt("target_fps", 60)) }

    val savedColorInt = remember { prefs.getInt("bg_color", 0xFF0F172A.toInt()) }
    var selectedBgColor by remember { mutableStateOf(Color(savedColorInt)) }
    var customBgPath by remember { mutableStateOf(prefs.getString("bg_image_path", null)) }
    var showColorPicker by remember { mutableStateOf(false) }

    var isImporting by remember { mutableStateOf(false) }
    var showVersionInfoDialog by remember { mutableStateOf(false) }

    // ===== 模型库分组 =====
    /** 当前所有分组（由模型推导，随模型增删自动变化） */
    var libraryGroups by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 折叠的分组名集合 */
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
    /** 分组选择弹窗模式：null=关闭，"import"=导入选组，"move"=移动已有模型 */
    var groupDialogMode by remember { mutableStateOf<String?>(null) }
    var groupDialogSelection by remember { mutableStateOf(DEFAULT_MODEL_GROUP) }
    var groupDialogCreating by remember { mutableStateOf(false) }
    var groupDialogNewName by remember { mutableStateOf("") }
    /** 待导入的 ZIP（选组确认后才真正解析导入） */
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    /** 待移动分组的模型 id */
    var pendingMoveModelId by remember { mutableStateOf<String?>(null) }

    var isModelsExpanded by remember { mutableStateOf(true) }
    var isSettingsExpanded by remember { mutableStateOf(true) }

    // 底栏内嵌面板：null=收起，"anim"=动作列表，"skin"=部件列表
    var bottomPanel by remember { mutableStateOf<String?>(null) }
    /** 部件面板的「多选 / 单选」开关 */
    var skinMultiSelect by remember { mutableStateOf(prefs.getBoolean("skin_multi_select", false)) }

    fun refreshModelsList() {
        val models = SpineModelLoader.getSavedModels(context)
        savedModels = models
        libraryGroups = SpineModelLoader.getGroups(context)
        val activeId = SpineModelLoader.getActiveModelId(context) ?: models.firstOrNull()?.id
        activeModelId = activeId

        if (activeId != null) {
            val activeItem = models.firstOrNull { it.id == activeId }
            if (activeItem != null) {
                activeModelDir = File(activeItem.folderPath)
                animationList = activeItem.animations
                skinList = activeItem.skins
                // 恢复上次选中的动画，避免启动后总是回到第一个
                val savedAnim = prefs.getString("active_animation_name", null)
                currentAnimation = if (savedAnim != null && activeItem.animations.contains(savedAnim)) savedAnim else activeItem.animations.firstOrNull()
                currentSkins = loadSelectedSkins(prefs, 0, activeItem.skins)
            } else {
                activeModelDir = SpineModelLoader.getActiveModelDir(context)
            }
        } else {
            activeModelDir = null
        }

        // 副模型（槽位1）
        model2Id = SpineModelLoader.getModelId(context, 1)
        val item2 = model2Id?.let { id -> models.firstOrNull { it.id == id } }
        if (item2 != null) {
            model2Dir = File(item2.folderPath)
            animationList2 = item2.animations
            skinList2 = item2.skins
            val savedAnim2 = prefs.getString("model2_animation", null)
            currentAnimation2 = if (savedAnim2 != null && item2.animations.contains(savedAnim2)) savedAnim2 else item2.animations.firstOrNull()
            currentSkins2 = loadSelectedSkins(prefs, 1, item2.skins)
        } else {
            model2Dir = null
            animationList2 = emptyList()
            skinList2 = emptyList()
            currentAnimation2 = null
            currentSkins2 = emptySet()
        }

        // 选中槽位失效时回退到主模型
        if (selectedSlot == 1 && model2Id == null) {
            selectedSlot = 0
            prefs.edit().putInt("selected_slot", 0).apply()
        }
    }

    LaunchedEffect(Unit) {
        refreshModelsList()
    }

    /** 解析 ZIP 并导入到指定分组（分组名不存在即新建）。 */
    fun doImport(uri: Uri, group: String) {
        val target = group.trim().ifBlank { DEFAULT_MODEL_GROUP }
        isImporting = true
        scope.launch(Dispatchers.IO) {
            try {
                val items = SpineModelLoader.importZipToLibrary(context, uri, target)
                withContext(Dispatchers.Main) {
                    isImporting = false
                    prefs.edit().putString("last_import_group", target).apply()
                    refreshModelsList()
                    Toast.makeText(context, "🎉 已导入 ${items.size} 个模型到「$target」", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isImporting = false
                    Toast.makeText(context, "❌ 导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun selectModel(modelId: String) {
        SpineModelLoader.setActiveModelId(context, modelId)
        refreshModelsList()
        scope.launch { drawerState.close() }
        Toast.makeText(context, "已切换模型", Toast.LENGTH_SHORT).show()
    }

    /** 添加/移除副模型（槽位1） */
    fun toggleSecondModel(modelId: String) {
        if (model2Id == modelId) {
            SpineModelLoader.setModelId(context, 1, null)
            if (selectedSlot == 1) {
                selectedSlot = 0
                prefs.edit().putInt("selected_slot", 0).apply()
            }
            Toast.makeText(context, "已移除副模型", Toast.LENGTH_SHORT).show()
        } else {
            SpineModelLoader.setModelId(context, 1, modelId)
            selectedSlot = 1
            prefs.edit().putInt("selected_slot", 1).apply()
            Toast.makeText(context, "已设为副模型，可单独调整其位置与大小", Toast.LENGTH_SHORT).show()
        }
        refreshModelsList()
    }

    fun selectSlot(slot: Int) {
        if (slot == 1 && model2Id == null) {
            Toast.makeText(context, "请先在模型库中将某个模型「设为副模型」", Toast.LENGTH_LONG).show()
            return
        }
        selectedSlot = slot
        prefs.edit().putInt("selected_slot", slot).apply()
    }

    fun deleteModel(modelId: String) {
        SpineModelLoader.deleteModel(context, modelId)
        refreshModelsList()
        Toast.makeText(context, "已删除该模型", Toast.LENGTH_SHORT).show()
    }

    fun setAsWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, SpineWallpaperService::class.java)
            )
        }
        context.startActivity(intent)
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 先让用户选择「导入到哪个分组 / 新建分组」，确认后再解析导入
            groupDialogSelection = prefs.getString("last_import_group", DEFAULT_MODEL_GROUP)
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_GROUP
            groupDialogCreating = false
            groupDialogNewName = ""
            pendingImportUri = uri
            groupDialogMode = "import"
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bgFile = File(context.filesDir, "custom_bg_" + System.currentTimeMillis() + ".png")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        bgFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        customBgPath = bgFile.absolutePath
                        prefs.edit().putString("bg_image_path", bgFile.absolutePath).apply()
                        Toast.makeText(context, "已选择自定义背景图片", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "图片加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = palette.drawerBg,
                modifier = Modifier.width(320.dp),
                windowInsets = WindowInsets(0,0,0,0)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // ========== 这里面你的所有原有drawer内容完全不动 ==========

                    // Header Area
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(palette.accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Animation,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Spine 2D 工坊",
                                color = palette.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "全版本多模型支持引擎 (3.6~4.2)",
                                color = palette.accentSoft,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = { showVersionInfoDialog = true }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "版本兼容说明",
                                tint = palette.accentSoft,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Divider(color = palette.divider, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Import Button
                    Button(
                        onClick = { zipPicker.launch(arrayOf("application/zip", "*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.importBtn),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在智能解析多版本模型...")
                        } else {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导入新 Spine 模型 ZIP", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ===== 固定的模型库标题栏：不随下方列表滚动，始终可见 =====
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isModelsExpanded = !isModelsExpanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📁 我的模型库 (${savedModels.size})",
                            color = palette.accentSoft,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            if (isModelsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "折叠/展开",
                            tint = palette.accentSoft,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Section 1: Saved Model List（按分组展示）
                        item {
                            if (isModelsExpanded) {
                                if (savedModels.isEmpty()) {
                                    Surface(
                                        color = palette.card,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "暂未保存任何 Spine 模型。\n支持导入 Spine 3.6 / 3.7 / 3.8 / 4.0 / 4.1 / 4.2 的 .zip 压缩包！",
                                            color = palette.textMuted,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(14.dp)
                                        )
                                    }
                                } else {
                                    // 按 group 字段分组；顺序优先跟随 libraryGroups（默认组在最前），
                                    // 出现库里还没有的新分组时自动追加到末尾。
                                    val groupedModels = savedModels.groupBy { it.group.ifBlank { DEFAULT_MODEL_GROUP } }
                                    val orderedGroups = (libraryGroups + groupedModels.keys)
                                        .distinct()
                                        .filter { groupedModels.containsKey(it) }

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        orderedGroups.forEach { g ->
                                            val groupItems = groupedModels[g].orEmpty()
                                            val collapsed = collapsedGroups[g] == true

                                            GroupHeaderRow(
                                                name = g,
                                                count = groupItems.size,
                                                collapsed = collapsed,
                                                palette = palette,
                                                onToggle = { collapsedGroups[g] = !collapsed }
                                            )
                                            if (collapsed) return@forEach

                                            groupItems.forEach { item ->
                                                ModelCardRow(
                                                    item = item,
                                                    isActive = item.id == activeModelId,
                                                    isSecond = item.id == model2Id,
                                                    palette = palette,
                                                    onSelect = { selectModel(item.id) },
                                                    onToggleSecond = { toggleSecondModel(item.id) },
                                                    onDelete = { deleteModel(item.id) },
                                                    onMoveGroup = {
                                                        groupDialogSelection = item.group.ifBlank { DEFAULT_MODEL_GROUP }
                                                        groupDialogCreating = false
                                                        groupDialogNewName = ""
                                                        pendingMoveModelId = item.id
                                                        groupDialogMode = "move"
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section 4: PMA Switch & Wallpaper Options
                        item {
                            Text(
                                "⚙️ 渲染与壁纸配置",
                                color = palette.accentSoft,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // PMA Switch Row (per-slot, independent)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.card)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("PMA 预乘 Alpha · 模型1", color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("开启可消除纹理半透明边缘白边/黑边", color = palette.textMuted, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = isPma,
                                    onCheckedChange = { checked ->
                                        isPma = checked
                                        prefs.edit().putBoolean("pma_enabled", checked).apply()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = palette.accent
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // PMA Switch Row for secondary model
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.card)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("PMA 预乘 Alpha · 模型2", color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("副模型独立开关，与模型1互不影响", color = palette.textMuted, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = isPma2,
                                    onCheckedChange = { checked ->
                                        isPma2 = checked
                                        prefs.edit().putBoolean("pma2_enabled", checked).apply()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFFF59E0B)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Background Color & Image Selector + Set-as-Wallpaper
                            Text(
                                "🎨 背景颜色与壁纸图片",
                                color = palette.accentSoft,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 单个彩色按钮：显示当前背景色，点击弹出调色盘
                                val colorModeActive = customBgPath == null
                                val paletteIconTint = if (selectedBgColor.luminance() > 0.5f)
                                    Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.85f)
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.sweepGradient(
                                                listOf(
                                                    Color(0xFFFF5252), Color(0xFFFFD740),
                                                    Color(0xFF69F0AE), Color(0xFF40C4FF),
                                                    Color(0xFF7C4DFF), Color(0xFFFF5252)
                                                )
                                            )
                                        )
                                        .padding(3.dp)
                                        .clip(CircleShape)
                                        .background(selectedBgColor)
                                        .border(
                                            width = if (colorModeActive) 2.dp else 1.dp,
                                            color = if (colorModeActive) palette.accent else palette.border,
                                            shape = CircleShape
                                        )
                                        .clickable { showColorPicker = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Palette,
                                        contentDescription = "选择颜色",
                                        tint = paletteIconTint,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // 背景图片按钮：与颜色按钮同尺寸
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (customBgPath != null) palette.accent else palette.chip)
                                        .border(
                                            width = if (customBgPath != null) 2.dp else 1.dp,
                                            color = if (customBgPath != null) palette.accentSoft else palette.border,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            imagePicker.launch("image/*")
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (customBgPath != null) Icons.Default.Image else Icons.Default.Add,
                                        contentDescription = "添加背景图片",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                // 设为壁纸按钮（从原顶部栏迁移至此，紧邻背景图片按钮）
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(palette.wallpaperBtn)
                                        .clickable { setAsWallpaper() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Wallpaper,
                                        contentDescription = "设为壁纸",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            // Show custom background info if chosen
                            if (customBgPath != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.card)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.PhotoLibrary,
                                            contentDescription = null,
                                            tint = palette.accentSoft,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "已应用自定义背景图",
                                            color = palette.text,
                                            fontSize = 12.sp
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            customBgPath = null
                                            prefs.edit().remove("bg_image_path").apply()
                                            Toast.makeText(context, "已恢复纯色背景", Toast.LENGTH_SHORT).show()
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("移除", color = Color(0xFFEF4444), fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // Section 5: System Settings (点击切换动画 / 帧率限制 / 主题)
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isSettingsExpanded = !isSettingsExpanded }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "🛠️ 系统设置",
                                    color = palette.accentSoft,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    if (isSettingsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "折叠/展开",
                                    tint = palette.accentSoft,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            if (isSettingsExpanded) {
                                // 1) 点击切换动画（开关）
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(palette.card)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("点击切换动画", color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text("开启后：点击模型切换到下一个动作", color = palette.textMuted, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = tapAnimEnabled,
                                        onCheckedChange = { checked ->
                                            tapAnimEnabled = checked
                                            prefs.edit().putBoolean("tap_switch_animation", checked).apply()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = palette.accent
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // 2) 帧率限制
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(palette.card)
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text("帧率限制", color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("降低帧率可减少耗电与发热", color = palette.textMuted, fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(30, 45, 60).forEach { fps ->
                                            FilterChip(
                                                selected = targetFps == fps,
                                                onClick = {
                                                    targetFps = fps
                                                    prefs.edit().putInt("target_fps", fps).apply()
                                                },
                                                label = { Text("${fps} FPS", fontSize = 11.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    containerColor = palette.chip,
                                                    selectedContainerColor = palette.accent,
                                                    labelColor = palette.text,
                                                    selectedLabelColor = Color.White
                                                )
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // 3) 主题（亮 / 暗）
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(palette.card)
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text("主题", color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("切换应用界面亮色 / 暗色风格", color = palette.textMuted, fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = !isDarkTheme,
                                            onClick = {
                                                prefs.edit().putString("theme_mode", "light").apply()
                                                onThemeChange(false)
                                            },
                                            label = { Text("☀️ 亮色", fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                containerColor = palette.chip,
                                                selectedContainerColor = palette.accent,
                                                labelColor = palette.text,
                                                selectedLabelColor = Color.White
                                            )
                                        )
                                        FilterChip(
                                            selected = isDarkTheme,
                                            onClick = {
                                                prefs.edit().putString("theme_mode", "dark").apply()
                                                onThemeChange(true)
                                            },
                                            label = { Text("🌙 暗色", fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                containerColor = palette.chip,
                                                selectedContainerColor = palette.accent,
                                                labelColor = palette.text,
                                                selectedLabelColor = Color.White
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0,0,0,0)
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(selectedBgColor)
            ) {

            if (activeModelDir != null) {
                    SpineViewCompose(
                        modelDir = activeModelDir,
                        model2Dir = model2Dir,
                        selectedSlot = selectedSlot,
                        isPlaying = isPlaying,
                        scale = scaleValue,
                        scale2 = scale2Value,
                        pma = isPma,
                        pma2 = isPma2,
                        activeAnimation = currentAnimation,
                        activeAnimation2 = currentAnimation2,
                        activeSkins = currentSkins,
                        activeSkins2 = currentSkins2,
                        bgColor = selectedBgColor,
                        bgImagePath = customBgPath,
                        resetTick = resetTick,
                        tapAnimEnabled = tapAnimEnabled,
                        targetFps = targetFps,
                        // 底栏面板展开时让 SurfaceView 放行触摸，
                        // 否则它的 OnTouchListener 会吞掉事件、遮罩收不到点击
                        touchEnabled = bottomPanel == null,
                        onScaleChange = { slot, newScale ->
                            if (slot == 1) scale2Value = newScale else scaleValue = newScale
                        },
                        onModelLoaded = { slot, anims, skins ->
                            if (slot == 1) {
                                animationList2 = anims
                                skinList2 = skins
                            } else {
                                animationList = anims
                                skinList = skins
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = palette.accentSoft
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "尚未选择 Spine 2D 模型",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.text
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "点击底部【☰】菜单展开侧边栏，导入并管理模型与动作",
                            fontSize = 13.sp,
                            color = palette.textMuted
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { zipPicker.launch(arrayOf("application/zip", "*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.importBtn)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导入首个 Spine 模型 ZIP")
                        }
                    }
                }

                // 底栏列表展开时的「点击别处收起」遮罩。
                // 必须放在模型视图**之后**（Compose 的 Box 后写者在上层），
                // 且放在底栏**之前**，这样它盖住模型区、又不挡底栏的交互。
                if (bottomPanel != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { bottomPanel = null }
                    )
                }

                // ==================== 底栏（照抄 Spine2 实现）====================
                Surface(
                    color = palette.bottomBarBg,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(0.92f)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            // 遮罩已经铺满全屏，这里是它「挖」出来的底栏区域。
                            // 面板展开时，点底栏的空白处（非按钮）也顺手收起，
                            // 未展开时不吃点击，保持原行为。
                            .then(
                                if (bottomPanel != null) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { bottomPanel = null }
                                } else Modifier
                            )
                    ) {
                        val bottomAnims = if (selectedSlot == 1) animationList2 else animationList
                        val bottomCurAnim = if (selectedSlot == 1) currentAnimation2 else currentAnimation
                        val bottomSkins = if (selectedSlot == 1) skinList2 else skinList
                        val bottomSelectedSkins = if (selectedSlot == 1) currentSkins2 else currentSkins
                        val skinSummary = when {
                            bottomSelectedSkins.isEmpty() -> "未选"
                            bottomSelectedSkins.size == 1 -> bottomSelectedSkins.first()
                            else -> "${bottomSelectedSkins.first()} +${bottomSelectedSkins.size - 1}"
                        }

                        fun applyAnimation(anim: String) {
                            if (selectedSlot == 1) {
                                currentAnimation2 = anim
                            } else {
                                currentAnimation = anim
                            }
                            prefs.edit().putString(SpineModelLoader.SlotPrefs.animKey(selectedSlot), anim).apply()
                        }

                        fun applySkins(next: List<String>) {
                            val asSet: Set<String> = LinkedHashSet(next)
                            if (selectedSlot == 1) {
                                currentSkins2 = asSet
                            } else {
                                currentSkins = asSet
                            }
                            saveSelectedSkins(prefs, selectedSlot, asSet)
                        }

                        val popupAnim = tween<IntSize>(durationMillis = 150)
                        AnimatedVisibility(
                            visible = bottomPanel == "anim",
                            enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = popupAnim),
                            exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = popupAnim)
                        ) {
                            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                if (bottomAnims.isEmpty()) {
                                    Text("暂无动作", color = palette.textMuted, fontSize = 12.sp)
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 180.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        bottomAnims.forEach { anim ->
                                            val isSelected = anim == bottomCurAnim
                                            Surface(
                                                color = if (isSelected) palette.cardSelected else palette.card,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(32.dp)
                                                    .clickable {
                                                        applyAnimation(anim)
                                                        bottomPanel = null
                                                    }
                                            ) {
                                                Box(contentAlignment = Alignment.CenterStart) {
                                                    Text(
                                                        anim,
                                                        color = if (isSelected) Color.White else palette.textSecondary,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(horizontal = 10.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = bottomPanel == "skin",
                            enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = popupAnim),
                            exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = popupAnim)
                        ) {
                            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                ) {
                                    val skinModeColor = if (isDarkTheme) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                                    Checkbox(
                                        checked = skinMultiSelect,
                                        onCheckedChange = {
                                            skinMultiSelect = it
                                            prefs.edit().putBoolean("skin_multi_select", it).apply()
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = skinModeColor,
                                            uncheckedColor = skinModeColor,
                                            checkmarkColor = if (isDarkTheme) Color(0xFF0F172A) else Color.White
                                        ),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        if (skinMultiSelect) "多选" else "单选",
                                        color = skinModeColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (bottomSkins.isEmpty()) {
                                    Text("暂无部件", color = palette.textMuted, fontSize = 12.sp)
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 180.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        bottomSkins.forEach { skin ->
                                            val isSelected = bottomSelectedSkins.contains(skin)
                                            Surface(
                                                color = if (isSelected) palette.cardSelected else palette.card,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(32.dp)
                                                    .clickable {
                                                        if (skinMultiSelect) {
                                                            applySkins(
                                                                if (isSelected) {
                                                                    bottomSelectedSkins.filter { it != skin }
                                                                } else {
                                                                    (bottomSelectedSkins + skin).toList()
                                                                }
                                                            )
                                                        } else {
                                                            applySkins(listOf(skin))
                                                            bottomPanel = null
                                                        }
                                                    }
                                            ) {
                                                Box(contentAlignment = Alignment.CenterStart) {
                                                    Text(
                                                        skin,
                                                        color = if (isSelected) Color.White else palette.textSecondary,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(horizontal = 10.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                color = if (bottomPanel == "anim") palette.cardSelected else palette.chip,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clickable {
                                        bottomPanel = if (bottomPanel == "anim") null else "anim"
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "动作",
                                        color = if (bottomPanel == "anim") Color.White else palette.accentSoft,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        bottomCurAnim ?: "无",
                                        color = if (bottomPanel == "anim") Color.White else palette.text,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (bottomPanel == "anim") Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "展开动作列表",
                                        tint = if (bottomPanel == "anim") Color.White else palette.textMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = if (bottomPanel == "skin") palette.cardSelected else palette.chip,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clickable {
                                        bottomPanel = if (bottomPanel == "skin") null else "skin"
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "部件",
                                        color = if (bottomPanel == "skin") Color.White else palette.accentSoft,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        skinSummary,
                                        color = if (bottomPanel == "skin") Color.White else palette.text,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (bottomPanel == "skin") Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "展开部件列表",
                                        tint = if (bottomPanel == "skin") Color.White else palette.textMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "菜单", tint = palette.text)
                                }
                                IconButton(onClick = { isPlaying = !isPlaying }) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "播放/暂停",
                                        tint = palette.text
                                    )
                                }
                                FilterChip(
                                    selected = selectedSlot == 0,
                                    onClick = { selectSlot(0) },
                                    label = { Text("模型1", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = palette.chip,
                                        selectedContainerColor = palette.accent,
                                        labelColor = palette.text,
                                        selectedLabelColor = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                FilterChip(
                                    selected = selectedSlot == 1,
                                    onClick = { selectSlot(1) },
                                    label = { Text("模型2", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = if (model2Id == null) palette.chipDisabled else palette.chip,
                                        selectedContainerColor = Color(0xFFF59E0B),
                                        labelColor = palette.text,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (selectedSlot == 1) scale2Value = 1.0f else scaleValue = 1.0f
                                    resetTick++
                                    Toast.makeText(
                                        context,
                                        if (selectedSlot == 1) "已重置模型2的大小和位置" else "已重置模型1的大小和位置",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = palette.text),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "重置",
                                    tint = palette.text,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("重置", color = palette.text, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        if (showColorPicker) {
            ColorPickerDialog(
                initialColor = selectedBgColor,
                onDismiss = { showColorPicker = false },
                onConfirm = { color ->
                    showColorPicker = false
                    selectedBgColor = color
                    customBgPath = null
                    prefs.edit()
                        .putInt("bg_color", color.toArgb())
                        .remove("bg_image_path")
                        .apply()
                    Toast.makeText(context, "已应用自定义颜色", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showVersionInfoDialog) {
            AlertDialog(
                onDismissRequest = { showVersionInfoDialog = false },
                confirmButton = {
                    TextButton(onClick = { showVersionInfoDialog = false }) {
                        Text("我知道了", color = palette.accentSoft, fontWeight = FontWeight.Bold)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = null,
                            tint = palette.accentSoft,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Spine 多版本兼容引擎", fontWeight = FontWeight.Bold, color = palette.text, fontSize = 16.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "本应用内置了多版本运行时隔离引擎，支持自适应检测并加载主流 Spine 模型：",
                            color = palette.textSecondary,
                            fontSize = 13.sp
                        )

                        val versionDetails = listOf(
                            Triple("v3.8 (主流游戏)", "独立模块 :spine-runtime-v38 隔离运行，支持明日方舟、蔚蓝档案等", Color(0xFF059669)),
                            Triple("v4.0 (官方隔离)", "独立模块 :spine-runtime-v40 隔离运行", Color(0xFF0891B2)),
                            Triple("v4.1 (官方隔离)", "独立模块 :spine-runtime-v41 隔离运行", Color(0xFF4F46E5)),
                            Triple("v4.2 (官方隔离)", "独立模块 :spine-runtime-v42 隔离运行", Color(0xFF9333EA))
                        )

                        versionDetails.forEach { (ver, desc, color) ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.card)
                                    .padding(8.dp)
                            ) {
                                Surface(
                                    color = color,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        ver,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    desc,
                                    color = palette.text,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text(
                            "💡 导入提示：直接将包含 .skel/.json、.atlas 和 .png 的 .zip 文件导入即可，系统会自动检测版本并调用对应隔离运行时模块。",
                            color = palette.textMuted,
                            fontSize = 11.sp
                        )
                    }
                },
                containerColor = palette.dialogBg
            )
        }

        // ===== 分组选择弹窗：导入选组 / 移动已有模型 =====
        val dialogMode = groupDialogMode
        if (dialogMode != null) {
            GroupPickerDialog(
                palette = palette,
                title = if (dialogMode == "import") "导入到分组" else "移动到分组",
                subtitle = if (dialogMode == "import") "ZIP 中的模型将归入选中的分组" else "选择该模型要移入的分组",
                groups = libraryGroups,
                groupCounts = savedModels.groupingBy { it.group.ifBlank { DEFAULT_MODEL_GROUP } }.eachCount(),
                selectedGroup = groupDialogSelection,
                creating = groupDialogCreating,
                newGroupName = groupDialogNewName,
                confirmLabel = if (dialogMode == "import") "开始导入" else "移入",
                confirmEnabled = !groupDialogCreating || groupDialogNewName.isNotBlank(),
                onSelectGroup = {
                    groupDialogSelection = it
                    groupDialogCreating = false
                },
                onSelectCreate = { groupDialogCreating = true },
                onNewGroupNameChange = { groupDialogNewName = it },
                onConfirm = {
                    val target = if (groupDialogCreating) groupDialogNewName.trim() else groupDialogSelection
                    if (target.isNotEmpty()) {
                        if (dialogMode == "import") {
                            val uri = pendingImportUri
                            groupDialogMode = null
                            pendingImportUri = null
                            if (uri != null) doImport(uri, target)
                        } else {
                            val id = pendingMoveModelId
                            groupDialogMode = null
                            pendingMoveModelId = null
                            if (id != null) {
                                SpineModelLoader.setModelGroup(context, id, target)
                                refreshModelsList()
                                Toast.makeText(context, "已移动到「$target」", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onDismiss = {
                    groupDialogMode = null
                    pendingImportUri = null
                    pendingMoveModelId = null
                }
            )
        }
    }
}

// ==================== 模型库分组 UI 组件 ====================

/** 分组标题行（点击折叠/展开该分组）。 */
@Composable
private fun GroupHeaderRow(
    name: String,
    count: Int,
    collapsed: Boolean,
    palette: UiPalette,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.card)
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
            contentDescription = if (collapsed) "展开分组" else "折叠分组",
            tint = palette.accentSoft,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Icon(
            Icons.Default.FolderSpecial,
            contentDescription = null,
            tint = palette.accentSoft,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            name,
            color = palette.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("$count", color = palette.textMuted, fontSize = 11.sp)
    }
}

/** 模型库中的单个模型卡片。 */
@Composable
private fun ModelCardRow(
    item: SpineModelItem,
    isActive: Boolean,
    isSecond: Boolean,
    palette: UiPalette,
    onSelect: () -> Unit,
    onToggleSecond: () -> Unit,
    onDelete: () -> Unit,
    onMoveGroup: () -> Unit
) {
    val verBadgeColor = when (item.version) {
        "3.8" -> Color(0xFF059669)
        "4.1" -> Color(0xFF4F46E5)
        "3.7" -> Color(0xFFD97706)
        "3.6" -> Color(0xFFEA580C)
        "4.0" -> Color(0xFF0891B2)
        "4.2" -> Color(0xFF9333EA)
        else -> Color(0xFF64748B)
    }

    Surface(
        color = if (isActive) palette.cardActive else palette.card,
        shape = RoundedCornerShape(12.dp),
        border = if (isActive) androidx.compose.foundation.BorderStroke(1.dp, palette.accent) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        color = palette.text,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(color = verBadgeColor, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            "v${item.version}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(color = palette.divider, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            item.format,
                            color = palette.textSecondary,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                    if (isActive) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(color = Color(0xFF10B981), shape = RoundedCornerShape(4.dp)) {
                            Text(
                                "使用中",
                                color = Color.White,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (isSecond) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(color = Color(0xFFF59E0B), shape = RoundedCornerShape(4.dp)) {
                            Text(
                                "副模型",
                                color = Color.White,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "动作: ${item.animations.size} 个 | 皮肤: ${item.skins.size} 个",
                    color = palette.textMuted,
                    fontSize = 11.sp
                )
            }

            IconButton(onClick = onMoveGroup, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.DriveFileMove,
                    contentDescription = "移动到分组",
                    tint = palette.accentSoft,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(onClick = onToggleSecond, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (isSecond) Icons.Default.Cancel else Icons.Default.GroupAdd,
                    contentDescription = if (isSecond) "移除副模型" else "设为副模型",
                    tint = if (isSecond) Color(0xFFF59E0B) else palette.accentSoft,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 分组选择弹窗：选择已有分组，或切到「新建分组」输入新名字。 */
@Composable
private fun GroupPickerDialog(
    palette: UiPalette,
    title: String,
    subtitle: String,
    groups: List<String>,
    groupCounts: Map<String, Int>,
    selectedGroup: String,
    creating: Boolean,
    newGroupName: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onSelectGroup: (String) -> Unit,
    onSelectCreate: () -> Unit,
    onNewGroupNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val existingGroups = if (groups.isEmpty()) listOf(DEFAULT_MODEL_GROUP) else groups

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    confirmLabel,
                    color = if (confirmEnabled) palette.accentSoft else palette.textMuted,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = palette.textMuted)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FolderSpecial,
                    contentDescription = null,
                    tint = palette.accentSoft,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, color = palette.text, fontSize = 16.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subtitle, color = palette.textSecondary, fontSize = 12.sp)

                LazyColumn(
                    modifier = Modifier.heightIn(max = 190.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(existingGroups) { g ->
                        val selected = !creating && g == selectedGroup
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) palette.cardActive else palette.card)
                                .clickable { onSelectGroup(g) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) palette.accent else palette.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                g,
                                color = palette.text,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${groupCounts[g] ?: 0} 个",
                                color = palette.textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (creating) palette.cardActive else palette.card)
                        .clickable { onSelectCreate() }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (creating) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (creating) palette.accent else palette.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = null,
                        tint = palette.accentSoft,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("新建分组", color = palette.text, fontSize = 13.sp)
                }

                if (creating) {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = onNewGroupNameChange,
                        singleLine = true,
                        placeholder = { Text("输入新分组名称", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        containerColor = palette.dialogBg
    )
}
