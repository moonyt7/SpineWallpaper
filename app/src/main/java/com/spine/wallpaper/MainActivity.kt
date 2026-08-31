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
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Safeguard intent and bundle extras to prevent MIUI SuggestManager / ActivityThread deliverResultsIfNeeded NPE
        if (intent == null) {
            intent = Intent()
        }
        if (intent.extras == null) {
            intent.putExtras(Bundle())
        }
        super.onCreate(savedInstanceState)
        try {
            SpineModelLoader.ensureNativesLoaded()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6366F1),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B)
                )
            ) {
                SpineWallpaperApp()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpineWallpaperApp() {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE) }

    var savedModels by remember { mutableStateOf<List<SpineModelItem>>(emptyList()) }
    var activeModelId by remember { mutableStateOf<String?>(null) }
    var activeModelDir by remember { mutableStateOf<File?>(null) }

    // 副模型（槽位1）状态
    var model2Id by remember { mutableStateOf<String?>(null) }
    var model2Dir by remember { mutableStateOf<File?>(null) }
    var animationList2 by remember { mutableStateOf<List<String>>(emptyList()) }
    var skinList2 by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentAnimation2 by remember { mutableStateOf<String?>(null) }
    var currentSkin2 by remember { mutableStateOf<String?>(null) }

    // 当前正在调整的槽位（0 = 主模型，1 = 副模型）
    var selectedSlot by remember { mutableStateOf(prefs.getInt("selected_slot", 0)) }

    var animationList by remember { mutableStateOf<List<String>>(emptyList()) }
    var skinList by remember { mutableStateOf<List<String>>(emptyList()) }

    var currentAnimation by remember { mutableStateOf<String?>(null) }
    var currentSkin by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var scaleValue by remember { mutableStateOf(prefs.getFloat("model_scale", 1.0f)) }
    var scale2Value by remember { mutableStateOf(prefs.getFloat("model2_scale", 1.0f)) }
    var resetTick by remember { mutableStateOf(0) }
    var isPma by remember { mutableStateOf(prefs.getBoolean("pma_enabled", true)) }
    var isPma2 by remember { mutableStateOf(prefs.getBoolean("pma2_enabled", true)) }

    val savedColorInt = remember { prefs.getInt("bg_color", 0xFF0F172A.toInt()) }
    var selectedBgColor by remember { mutableStateOf(Color(savedColorInt)) }
    var customBgPath by remember { mutableStateOf(prefs.getString("bg_image_path", null)) }
    var showColorPicker by remember { mutableStateOf(false) }

    var isImporting by remember { mutableStateOf(false) }
    var showVersionInfoDialog by remember { mutableStateOf(false) }

    var isModelsExpanded by remember { mutableStateOf(true) }
    var isAnimationsExpanded by remember { mutableStateOf(true) }
    var isSkinsExpanded by remember { mutableStateOf(true) }

    fun refreshModelsList() {
        val models = SpineModelLoader.getSavedModels(context)
        savedModels = models
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
                currentSkin = activeItem.skins.firstOrNull()
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
            currentSkin2 = item2.skins.firstOrNull()
        } else {
            model2Dir = null
            animationList2 = emptyList()
            skinList2 = emptyList()
            currentAnimation2 = null
            currentSkin2 = null
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
            Toast.makeText(context, "请先在模型库中添加一个副模型", Toast.LENGTH_SHORT).show()
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

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImporting = true
            scope.launch(Dispatchers.IO) {
                try {
                    val items = SpineModelLoader.importZipToLibrary(context, uri)
                    withContext(Dispatchers.Main) {
                        isImporting = false
                        refreshModelsList()
                        Toast.makeText(context, "🎉 成功导入 ${items.size} 个 Spine 模型！", Toast.LENGTH_SHORT).show()
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
                drawerContainerColor = Color(0xFF0F172A),
                modifier = Modifier.width(320.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
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
                                .background(Color(0xFF6366F1)),
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
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "全版本多模型支持引擎 (3.6~4.2)",
                                color = Color(0xFFA5B4FC),
                                fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = { showVersionInfoDialog = true }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "版本兼容说明",
                                tint = Color(0xFF818CF8),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Divider(color = Color(0xFF334155), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Import Button
                    Button(
                        onClick = { zipPicker.launch(arrayOf("application/zip", "*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
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

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Section 1: Saved Model List
                        item {
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
                                    color = Color(0xFF818CF8),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    if (isModelsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "折叠/展开",
                                    tint = Color(0xFF818CF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            if (isModelsExpanded) {
                                if (savedModels.isEmpty()) {
                                    Surface(
                                        color = Color(0xFF1E293B),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "暂未保存任何 Spine 模型。\n支持导入 Spine 3.6 / 3.7 / 3.8 / 4.0 / 4.1 / 4.2 的 .zip 压缩包！",
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(14.dp)
                                        )
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        savedModels.forEach { item ->
                                            val isActive = item.id == activeModelId
                                            val isSecond = item.id == model2Id
                                            val verBadgeColor = when (item.version) {
                                                "3.8" -> Color(0xFF059669) // Emerald
                                                "4.1" -> Color(0xFF4F46E5) // Indigo
                                                "3.7" -> Color(0xFFD97706) // Amber
                                                "3.6" -> Color(0xFFEA580C) // Orange
                                                "4.0" -> Color(0xFF0891B2) // Cyan
                                                "4.2" -> Color(0xFF9333EA) // Purple
                                                else -> Color(0xFF64748B)
                                            }

                                            Surface(
                                                color = if (isActive) Color(0xFF312E81) else Color(0xFF1E293B),
                                                shape = RoundedCornerShape(12.dp),
                                                border = if (isActive) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1)) else null,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { selectModel(item.id) }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(
                                                                item.name,
                                                                color = Color.White,
                                                                fontWeight = FontWeight.Medium,
                                                                fontSize = 14.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.weight(1f, fill = false)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            // Spine Version Tag
                                                            Surface(
                                                                color = verBadgeColor,
                                                                shape = RoundedCornerShape(4.dp)
                                                            ) {
                                                                Text(
                                                                    "v${item.version}",
                                                                    color = Color.White,
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            // Format Tag (.skel / JSON)
                                                            Surface(
                                                                color = Color(0xFF334155),
                                                                shape = RoundedCornerShape(4.dp)
                                                            ) {
                                                                Text(
                                                                    item.format,
                                                                    color = Color(0xFF94A3B8),
                                                                    fontSize = 9.sp,
                                                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                            if (isActive) {
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Surface(
                                                                    color = Color(0xFF10B981),
                                                                    shape = RoundedCornerShape(4.dp)
                                                                ) {
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
                                                                Surface(
                                                                    color = Color(0xFFF59E0B),
                                                                    shape = RoundedCornerShape(4.dp)
                                                                ) {
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
                                                            color = Color.Gray,
                                                            fontSize = 11.sp
                                                        )
                                                    }

                                                    // 添加/移除为副模型
                                                    IconButton(
                                                        onClick = { toggleSecondModel(item.id) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            if (isSecond) Icons.Default.Cancel else Icons.Default.GroupAdd,
                                                            contentDescription = if (isSecond) "移除副模型" else "设为副模型",
                                                            tint = if (isSecond) Color(0xFFF59E0B) else Color(0xFF818CF8),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }

                                                    IconButton(
                                                        onClick = { deleteModel(item.id) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
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
                                    }
                                }
                            }
                        }

                        // Section 2: Animations List (作用于当前选中槽位)
                        item {
                            val slotAnims = if (selectedSlot == 1) animationList2 else animationList
                            val slotCurAnim = if (selectedSlot == 1) currentAnimation2 else currentAnimation
                            val slotLabel = if (selectedSlot == 1) "副模型" else "主模型"
                            val targetModelName = if (selectedSlot == 1)
                                savedModels.firstOrNull { it.id == model2Id }?.name ?: "未设置"
                            else
                                savedModels.firstOrNull { it.id == activeModelId }?.name ?: "未设置"

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isAnimationsExpanded = !isAnimationsExpanded }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "🎬 动作列表 (${slotAnims.size}) · $slotLabel",
                                        color = Color(0xFF818CF8),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        targetModelName,
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    if (isAnimationsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "折叠/展开",
                                    tint = Color(0xFF818CF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            if (isAnimationsExpanded) {
                                if (slotAnims.isEmpty()) {
                                    Text("模型中未检测到动作列表", color = Color.Gray, fontSize = 12.sp)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        slotAnims.forEach { anim ->
                                            val isSelected = anim == slotCurAnim
                                            Surface(
                                                color = if (isSelected) Color(0xFF4338CA) else Color(0xFF1E293B),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (selectedSlot == 1) {
                                                            currentAnimation2 = anim
                                                        } else {
                                                            currentAnimation = anim
                                                        }
                                                        context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
                                                            .edit()
                                                            .putString(SpineModelLoader.SlotPrefs.animKey(selectedSlot), anim)
                                                            .apply()
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        if (isSelected) Icons.Default.PlayArrow else Icons.Default.Movie,
                                                        contentDescription = null,
                                                        tint = if (isSelected) Color.White else Color.Gray,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        anim,
                                                        color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section 3: Skins List (作用于当前选中槽位)
                        if (if (selectedSlot == 1) skinList2.isNotEmpty() else skinList.isNotEmpty()) {
                            item {
                                val slotSkins = if (selectedSlot == 1) skinList2 else skinList
                                val slotCurSkin = if (selectedSlot == 1) currentSkin2 else currentSkin
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isSkinsExpanded = !isSkinsExpanded }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "👗 皮肤部件 (${slotSkins.size})" + if (selectedSlot == 1) " · 副模型" else "",
                                        color = Color(0xFF818CF8),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Icon(
                                        if (isSkinsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "折叠/展开",
                                        tint = Color(0xFF818CF8),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))

                                if (isSkinsExpanded) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(slotSkins) { skin ->
                                            val isSelected = skin == slotCurSkin
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    if (selectedSlot == 1) {
                                                        currentSkin2 = skin
                                                    } else {
                                                        currentSkin = skin
                                                    }
                                                    context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
                                                        .edit()
                                                        .putString(SpineModelLoader.SlotPrefs.skinKey(selectedSlot), skin)
                                                        .apply()
                                                },
                                                label = { Text(skin) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Section 4: PMA Switch & Wallpaper Options
                        item {
                            Text(
                                "⚙️ 渲染与壁纸配置",
                                color = Color(0xFF818CF8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // PMA Switch Row (per-slot, independent)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E293B))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("PMA 预乘 Alpha · 模型1", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("开启可消除纹理半透明边缘白边/黑边", color = Color.Gray, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = isPma,
                                    onCheckedChange = { checked ->
                                        isPma = checked
                                        prefs.edit().putBoolean("pma_enabled", checked).apply()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF6366F1)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // PMA Switch Row for secondary model
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E293B))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("PMA 预乘 Alpha · 模型2", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("副模型独立开关，与模型1互不影响", color = Color.Gray, fontSize = 11.sp)
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

                            // Background Color & Image Selector
                            Text(
                                "🎨 背景颜色与壁纸图片",
                                color = Color(0xFF818CF8),
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
                                            color = if (colorModeActive) Color(0xFF6366F1) else Color(0xFF475569),
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
                                        .background(if (customBgPath != null) Color(0xFF4338CA) else Color(0xFF334155))
                                        .border(
                                            width = if (customBgPath != null) 2.dp else 1.dp,
                                            color = if (customBgPath != null) Color(0xFF818CF8) else Color(0xFF64748B),
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
                            }

                            // Show custom background info if chosen
                            if (customBgPath != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1E293B))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.PhotoLibrary,
                                            contentDescription = null,
                                            tint = Color(0xFF818CF8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "已应用自定义背景图",
                                            color = Color.White,
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
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "打开侧边栏", tint = Color.White)
                        }
                    },
                    title = {
                        Column {
                            Text(
                                "Spine 动态壁纸",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            val activeItem = savedModels.firstOrNull { it.id == activeModelId }
                            val secondItem = savedModels.firstOrNull { it.id == model2Id }
                            if (activeItem != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "主: ${activeItem.name}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF818CF8)
                                    )
                                    if (secondItem != null) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "副: ${secondItem.name}",
                                            fontSize = 11.sp,
                                            color = Color(0xFFF59E0B)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    val topBadgeColor = when (activeItem.version) {
                                        "3.8" -> Color(0xFF059669)
                                        "4.1" -> Color(0xFF4F46E5)
                                        "3.7" -> Color(0xFFD97706)
                                        "3.6" -> Color(0xFFEA580C)
                                        "4.0" -> Color(0xFF0891B2)
                                        "4.2" -> Color(0xFF9333EA)
                                        else -> Color(0xFF64748B)
                                    }
                                    Surface(
                                        color = topBadgeColor,
                                        shape = RoundedCornerShape(3.dp)
                                    ) {
                                        Text(
                                            "v${activeItem.version}",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                    putExtra(
                                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                        ComponentName(context, SpineWallpaperService::class.java)
                                    )
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Wallpaper, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("设为壁纸", fontSize = 13.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E293B))
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
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
                        activeSkin = currentSkin,
                        activeSkin2 = currentSkin2,
                        bgColor = selectedBgColor,
                        bgImagePath = customBgPath,
                        resetTick = resetTick,
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
                            tint = Color(0xFF818CF8)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "尚未选择 Spine 2D 模型",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "点击左上角【☰】菜单展开侧边栏，导入并管理模型与动作",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { zipPicker.launch(arrayOf("application/zip", "*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导入首个 Spine 模型 ZIP")
                        }
                    }
                }

                // Bottom Floating Control Overlay Strip
                Surface(
                    color = Color(0xDD1E293B),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(0.92f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "菜单", tint = Color.White)
                                }
                                IconButton(onClick = { isPlaying = !isPlaying }) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "播放/暂停",
                                        tint = Color.White
                                    )
                                }
                                // 槽位切换：模型1（主）/ 模型2（副）
                                FilterChip(
                                    selected = selectedSlot == 0,
                                    onClick = { selectSlot(0) },
                                    label = { Text("模型1", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = Color(0xFF334155),
                                        selectedContainerColor = Color(0xFF6366F1),
                                        labelColor = Color.White,
                                        selectedLabelColor = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                FilterChip(
                                    selected = selectedSlot == 1,
                                    onClick = { selectSlot(1) },
                                    label = { Text("模型2", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = if (model2Id == null) Color(0xFF1E293B) else Color(0xFF334155),
                                        selectedContainerColor = Color(0xFFF59E0B),
                                        labelColor = Color.White,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }

                            // 重置按钮：重置当前选中模型的大小和位置
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
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "重置",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("重置", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        val bottomAnims = if (selectedSlot == 1) animationList2 else animationList
                        val bottomCurAnim = if (selectedSlot == 1) currentAnimation2 else currentAnimation
                        if (bottomAnims.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(bottomAnims) { anim ->
                                    val isSelected = anim == bottomCurAnim
                                    SuggestionChip(
                                        onClick = {
                                            if (selectedSlot == 1) {
                                                currentAnimation2 = anim
                                            } else {
                                                currentAnimation = anim
                                            }
                                            context.getSharedPreferences("spine_wallpaper_prefs", Context.MODE_PRIVATE)
                                                .edit()
                                                .putString(SpineModelLoader.SlotPrefs.animKey(selectedSlot), anim)
                                                .apply()
                                        },
                                        label = { Text(anim, fontSize = 12.sp) },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = if (isSelected) Color(0xFF6366F1) else Color(0xFF334155),
                                            labelColor = Color.White
                                        )
                                    )
                                }
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
                        Text("我知道了", color = Color(0xFF818CF8), fontWeight = FontWeight.Bold)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = null,
                            tint = Color(0xFF818CF8),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Spine 多版本兼容引擎", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "本应用内置了多版本运行时隔离引擎，支持自适应检测并加载主流 Spine 模型：",
                            color = Color(0xFFCBD5E1),
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
                                    .background(Color(0xFF1E293B))
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
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text(
                            "💡 导入提示：直接将包含 .skel/.json、.atlas 和 .png 的 .zip 文件导入即可，系统会自动检测版本并调用对应隔离运行时模块。",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                },
                containerColor = Color(0xFF0F172A)
            )
        }
    }
}
