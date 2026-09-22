package com.station1921.pixelcam.ui.screen

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.station1921.pixelcam.beauty.BeautyParams
import com.station1921.pixelcam.beauty.BeautyPreset
import com.station1921.pixelcam.beauty.DEFAULT_PRESETS
import com.station1921.pixelcam.beauty.FilterDef
import com.station1921.pixelcam.beauty.Filters
import com.station1921.pixelcam.beauty.OpenCv
import com.station1921.pixelcam.beauty.SuperRes
import com.station1921.pixelcam.beauty.toBgr
import com.station1921.pixelcam.beauty.toBitmap
import com.station1921.pixelcam.ui.AppSnackbarHost
import com.station1921.pixelcam.ui.EditorViewModel
import com.station1921.pixelcam.data.AutoBeautyPrefs
import com.station1921.pixelcam.ui.ZoomableImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** 底部工具（下标即顺序）：预设 / 美颜 / 调色 / 滤镜 / 风光 / 构图 */
private data class ToolSpec(val label: String, val icon: ImageVector)

private val TOOLS = listOf(
    ToolSpec("预设", Icons.Filled.AutoAwesome),
    ToolSpec("美颜", Icons.Filled.Face),
    ToolSpec("调色", Icons.Filled.Tune),
    ToolSpec("滤镜", Icons.Filled.PhotoFilter),
    ToolSpec("风光", Icons.Filled.Landscape),
    ToolSpec("构图", Icons.Filled.Crop),
    ToolSpec("超分", Icons.Filled.ZoomIn)
)

/** 各工具面板高度（dp）：抽屉化后 美颜/调色/风光 只有 chip 行 + 单滑块，很矮 */
private val PANEL_H = intArrayOf(84, 104, 104, 112, 104, 76, 96)

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val params by vm.params.collectAsStateWithLifecycle()
    val rendered by vm.rendered.collectAsStateWithLifecycle()
    val original by vm.original.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val faceCount by vm.faceCount.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val aiBusy by vm.aiBusy.collectAsStateWithLifecycle()
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val aiParams by vm.aiParams.collectAsStateWithLifecycle()
    val customPresets by vm.customPresets.collectAsStateWithLifecycle()
    val superResAvailable = remember { SuperRes.available() }

    // 输出质量：与「AI 自动美化」共用同一份偏好（AutoBeautyPrefs.quality），全 app 单一质量设置
    val context = LocalContext.current
    var quality by remember { mutableStateOf(AutoBeautyPrefs.load(context).quality) }
    fun setQuality(q: Int) {
        quality = q
        AutoBeautyPrefs.save(context, AutoBeautyPrefs.load(context).copy(quality = q))
    }

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 长按看原图（构图后、美颜前）
    var showOriginal by remember { mutableStateOf(false) }

    // 当前选中的底部工具
    var tool by remember { mutableStateOf(1) } // 默认进「美颜」

    // —— 裁剪模式 ——
    var cropping by remember { mutableStateOf(false) }
    var cropInit by remember { mutableStateOf<RectF>(RectF(0f, 0f, 1f, 1f)) }

    fun beginCrop() {
        cropInit = params.geo.crop ?: RectF(0f, 0f, 1f, 1f)
        vm.beginCropSession()
        cropping = true
    }

    fun cancelCrop() {
        vm.cancelCropSession()
        cropping = false
    }

    fun applyCrop(r: RectF) {
        vm.confirmCrop(r)
        cropping = false
    }

    // 相对本次打开起点是否有未保存调整
    val dirty = baseline != null && params != baseline
    // AI 优化开关态：当前参数 = 上一次 AI 优化结果 时视为"已开启"
    val aiActive = aiParams != null && params == aiParams
    var confirmExit by remember { mutableStateOf(false) }

    // 输出质量浮层（细滑块）开关
    var showQualitySheet by remember { mutableStateOf(false) }

    // 保存预设命名弹窗开关
    var showSavePreset by remember { mutableStateOf(false) }

    fun requestExit() {
        if (cropping) {
            // 裁剪中按返回 = 取消裁剪，先回编辑态
            cancelCrop()
            return
        }
        if (dirty) confirmExit = true else onBack()
    }

    BackHandler { requestExit() }

    val renderedAspect: Float =
        if (rendered != null && rendered!!.height > 0) rendered!!.width.toFloat() / rendered!!.height else 1f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
        EditorTopBar(
            name = name,
            faceCount = faceCount,
            exporting = exporting,
            canUndo = canUndo,
            canRedo = canRedo,
            onBack = ::requestExit,
            onUndo = { vm.undo() },
            onRedo = { vm.redo() },
            onReset = {
                vm.resetBeauty()
                scope.launch { snackbar.showSnackbar("已重置全部美化与风光增强，构图保留") }
            },
            onSaveAdjust = {
                scope.launch {
                    val ok = vm.saveAdjust(quality)
                    snackbar.showSnackbar(
                        if (ok) "已保存调整，首页缩略图已同步" else "保存调整失败"
                    )
                }
            },
            onExport = {
                scope.launch {
                    val uri = vm.export(quality)
                    snackbar.showSnackbar(
                        if (uri != null) "已保存到相册 Pictures/PixelCam" else "保存失败"
                    )
                }
            },
            onSavePreset = { showSavePreset = true }
        )

        if (cropping) {
            // 裁剪模式：预览 + 裁剪框占用主区域
            CropEditor(
                image = rendered,
                aspect = renderedAspect,
                initialCrop = cropInit,
                onCancel = ::cancelCrop,
                onApply = ::applyCrop,
                modifier = Modifier.weight(1f)
            )
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                // 主图支持双指捏合 / 双击放大 / 单指拖动；长按看原图（松手还原）
                ZoomableImage(
                    bitmap = if (showOriginal) original else rendered,
                    modifier = Modifier.fillMaxSize(),
                    onLongPress = { showOriginal = true },
                    onLongPressEnd = { showOriginal = false }
                )
                if (showOriginal) {
                    Text(
                        "原图",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(
                                MaterialTheme.colorScheme.scrim,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                // 输出质量浮标：右上角、半透明、置于图片顶层；点击弹出细滑块
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    val qText = if (quality >= 100) "无损" else "质量 $quality"
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                            .clickable { showQualitySheet = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            qText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            // ——— 工具面板区（按选中工具切换，高度自适应） ———
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PANEL_H[tool].dp)
            ) {
                when (tool) {
                    0 -> PresetPanel(
                        current = params,
                        aiBusy = aiBusy,
                        aiActive = aiActive,
                        customPresets = customPresets,
                        onAi = { vm.aiEnhance() },
                        onPick = { vm.applyPreset(it) },
                        onDeletePreset = { vm.deletePreset(context, it) }
                    )
                    1 -> DrawerPanel(
                        specs = BEAUTY_SPECS,
                        current = params,
                        enabled = { i -> i < SKIN_PARAMS.size || faceCount > 0 },
                        onChange = { vm.updateParams(it) },
                        onGestureStart = { vm.beginEdit() },
                        onGestureEnd = { vm.endEdit() },
                        onResetCurrent = { vm.pushUndoSnapshot() }
                    )
                    2 -> DrawerPanel(
                        specs = COLOR_PARAMS,
                        current = params,
                        onChange = { vm.updateParams(it) },
                        onGestureStart = { vm.beginEdit() },
                        onGestureEnd = { vm.endEdit() },
                        onResetCurrent = { vm.pushUndoSnapshot() }
                    )
                    3 -> FilterPanel(
                        current = params,
                        base = original ?: rendered,
                        onPick = { vm.applyFilter(it) }
                    )
                    4 -> DrawerPanel(
                        specs = SCENE_PARAMS,
                        current = params,
                        onChange = { vm.updateParams(it) },
                        onGestureStart = { vm.beginEdit() },
                        onGestureEnd = { vm.endEdit() },
                        onResetCurrent = { vm.pushUndoSnapshot() }
                    )
                    5 -> GeometryPanel(
                        hasTransform = !params.geo.isIdentity,
                        onRotate = { vm.rotateCW() },
                        onMirror = { vm.mirrorH() },
                        onCrop = ::beginCrop,
                        onReset = { vm.resetGeometry() }
                    )
                    6 -> SuperResPanel(
                        available = superResAvailable,
                        busy = busy,
                        onApply = { vm.applySuperRes() }
                    )
                }
            }

            // ——— 底部工具条 ———
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TOOLS.forEachIndexed { index, spec ->
                    val selected = tool == index
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { tool = index }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            spec.icon,
                            contentDescription = spec.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            spec.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        }

    // 输出质量浮层：半透明遮罩 + 底部细滑块卡片，点击遮罩或「完成」关闭
    if (showQualitySheet) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                .clickable { showQualitySheet = false }
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { }
                    .padding(20.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("输出质量", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (quality >= 100) "无损 $quality" else "质量 $quality",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    ParamSlider(
                        value = quality.toFloat(),
                        range = 70f..100f,
                        onValueChange = { setQuality((it + 0.5f).toInt()) }
                    )
                    Text(
                        "70 省空间 ←→ 100 无损",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showQualitySheet = false }) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    }

    // 悬浮提示浮层：叠加在最上层、不参与 Column 布局，
    // 弹出时不会把底部面板/工具条顶起来造成页面抖动
    AppSnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (cropping) 12.dp else (58 + PANEL_H[tool] + 12).dp)
        )
    }

    // 保存预设命名弹窗
    if (showSavePreset) {
        var presetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSavePreset = false },
            title = { Text("保存为预设") },
            text = {
                Column {
                    Text(
                        "把当前所有调整（磨皮 / 美型 / 调色 / 滤镜 / 风光）保存成一个预设模板，可在修图页「预设」里随时套用，也会出现在首页自动美化的自定义方案里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("预设名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSavePreset = false
                    val ok = vm.savePreset(context, presetName)
                    scope.launch {
                        snackbar.showSnackbar(
                            if (ok) "已保存预设「$presetName」" else "当前没有调整，无法保存"
                        )
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSavePreset = false }) { Text("取消") }
            }
        )
    }

    // 返回确认：有未保存调整时给出三个去向
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("尚未保存本次调整") },
            text = { Text("退出后，本次磨皮 / 调色 / 风光 / 构图调整将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    scope.launch {
                        if (vm.saveAdjust(quality)) onBack()
                        else snackbar.showSnackbar("保存失败，请重试或放弃退出")
                    }
                }) { Text("保存并退出") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmExit = false
                        onBack()
                    }) {
                        Text("放弃并退出", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { confirmExit = false }) { Text("继续编辑") }
                }
            }
        )
    }
}

@Composable
private fun EditorTopBar(
    name: String,
    faceCount: Int,
    exporting: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onSaveAdjust: () -> Unit,
    onExport: () -> Unit,
    onSavePreset: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                if (faceCount > 0) "检测到 $faceCount 张人脸" else "未检测到人脸",
                style = MaterialTheme.typography.labelSmall,
                color = if (faceCount > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "重做")
        }
        IconButton(onClick = onReset) {
            Icon(Icons.Filled.RestartAlt, contentDescription = "重置全部美化（保留构图）")
        }
        // 三点菜单：保存调整（回写应用内图片）/ 保存到手机（导出相册）
        Box {
            IconButton(onClick = { menu = true }, enabled = !exporting) {
                if (exporting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "保存选项",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("保存调整") },
                    leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        menu = false
                        onSaveAdjust()
                    }
                )
                DropdownMenuItem(
                    text = { Text("保存到手机") },
                    leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
                    onClick = {
                        menu = false
                        onExport()
                    }
                )
                DropdownMenuItem(
                    text = { Text("保存为预设") },
                    leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                    onClick = {
                        menu = false
                        onSavePreset()
                    }
                )
            }
        }
    }
}

// ——————————————————— 面板 ———————————

@Composable
private fun PresetPanel(
    current: BeautyParams,
    aiBusy: Boolean,
    aiActive: Boolean,
    customPresets: List<BeautyPreset>,
    onAi: () -> Unit,
    onPick: (BeautyPreset) -> Unit,
    onDeletePreset: (String) -> Unit
) {
    // 是否展开「自定义」分组：展开后默认预设让位给已保存的自定义预设
    var customExpanded by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxSize()
    ) {
        // AI 优化：一颗"动作胶囊"，不是常亮预设。点一次应用，chip 呈已优化态；
        // 再点一次 = 取消（回到优化前）；调节过任何滑块后自动回到待应用态。
        item(key = "ai") {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (aiActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        RoundedCornerShape(50)
                    )
                    .clickable(enabled = !aiBusy, onClick = onAi)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (aiBusy) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        color = if (aiActive) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        if (aiActive) Icons.Filled.Check else Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = if (aiActive) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (aiBusy) "AI 优化中…" else "AI 优化",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (aiActive) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }

        if (customExpanded) {
            // —— 自定义分组：返回键 + 已保存的预设 ——
            item(key = "custom_back") {
                PresetChip("默认", false, onClick = { customExpanded = false })
            }
            if (customPresets.isEmpty()) {
                item(key = "custom_empty") {
                    Text(
                        "暂无保存的预设 · 点右上角 ⋮ → 保存为预设",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            } else {
                items(customPresets, key = { "c_${it.name}" }) { preset ->
                    CustomPresetChip(
                        preset = preset,
                        active = current.matchesPreset(preset),
                        onClick = { onPick(preset) },
                        onDelete = { onDeletePreset(preset.name) }
                    )
                }
            }
        } else {
            items(DEFAULT_PRESETS, key = { it.name }) { preset ->
                val active = current.matchesPreset(preset)
                Box(
                    modifier = Modifier
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50)
                        )
                        .clickable { onPick(preset) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        preset.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            // 有自定义预设时才显示「自定义」入口，点开切换为自定义分组
            if (customPresets.isNotEmpty()) {
                item(key = "custom") {
                    PresetChip("自定义", false, onClick = { customExpanded = true })
                }
            }
        }
    }
}

/** 预设条上的普通胶囊（名称按钮） */
@Composable
private fun PresetChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 自定义预设胶囊：名称可点套用，右侧小「×」删除 */
@Composable
private fun CustomPresetChip(
    preset: BeautyPreset,
    active: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                preset.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "删除预设",
                    tint = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ————— 抽屉式滑条：参数规格 —————

private data class ParamSpec(
    val label: String,
    val range: ClosedFloatingPointRange<Float> = 0f..100f,
    val get: (BeautyParams) -> Float,
    val set: (BeautyParams, Float) -> BeautyParams
)

private val SKIN_PARAMS = listOf(
    ParamSpec("磨皮", get = { it.smooth }, set = { p, v -> p.copy(smooth = v) }),
    ParamSpec("美白", get = { it.whiten }, set = { p, v -> p.copy(whiten = v) }),
    ParamSpec("祛痘", get = { it.blemish }, set = { p, v -> p.copy(blemish = v) }),
    ParamSpec("红润", get = { it.ruddy }, set = { p, v -> p.copy(ruddy = v) })
)

private val SHAPE_PARAMS = listOf(
    ParamSpec("瘦脸", get = { it.faceSlim }, set = { p, v -> p.copy(faceSlim = v) }),
    ParamSpec("V 脸", get = { it.vFace }, set = { p, v -> p.copy(vFace = v) }),
    ParamSpec("大眼", get = { it.bigEye }, set = { p, v -> p.copy(bigEye = v) }),
    ParamSpec("瘦鼻", get = { it.noseSlim }, set = { p, v -> p.copy(noseSlim = v) })
)

private val BEAUTY_SPECS: List<ParamSpec> = SKIN_PARAMS + SHAPE_PARAMS

private val COLOR_PARAMS = listOf(
    ParamSpec("亮度", -100f..100f, get = { it.brightness }, set = { p, v -> p.copy(brightness = v) }),
    ParamSpec("对比度", -100f..100f, get = { it.contrast }, set = { p, v -> p.copy(contrast = v) }),
    ParamSpec("饱和度", -100f..100f, get = { it.saturation }, set = { p, v -> p.copy(saturation = v) }),
    ParamSpec("色温", -100f..100f, get = { it.temperature }, set = { p, v -> p.copy(temperature = v) }),
    ParamSpec("高光", -100f..100f, get = { it.highlights }, set = { p, v -> p.copy(highlights = v) }),
    ParamSpec("阴影", -100f..100f, get = { it.shadows }, set = { p, v -> p.copy(shadows = v) }),
    ParamSpec("锐化", get = { it.sharpen }, set = { p, v -> p.copy(sharpen = v) }),
    ParamSpec("暗角", get = { it.vignette }, set = { p, v -> p.copy(vignette = v) })
)

private val SCENE_PARAMS = listOf(
    ParamSpec("夜景提亮", get = { it.lowLight }, set = { p, v -> p.copy(lowLight = v) }),
    ParamSpec("去雾", get = { it.dehaze }, set = { p, v -> p.copy(dehaze = v) }),
    ParamSpec("清晰增强", get = { it.clarity }, set = { p, v -> p.copy(clarity = v) })
)

/**
 * 抽屉式参数面板：顶部一行可横滑的参数 chip，选中哪个下面只显示它的一根滑块。
 * 面板矮 → 预览区最大化；chip 上小圆点表示该项已被调过（非 0）。
 */
@Composable
private fun DrawerPanel(
    specs: List<ParamSpec>,
    current: BeautyParams,
    enabled: (Int) -> Boolean = { true },
    onChange: (BeautyParams) -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onResetCurrent: () -> Unit
) {
    // 进来时自动定位到第一个调过的参数（默认自然观感=磨皮）
    var sel by remember(specs) {
        val firstModified = specs.indices.firstOrNull { enabled(it) && specs[it].get(current) != 0f }
        mutableStateOf(firstModified ?: 0)
    }
    val index = if (sel in specs.indices && enabled(sel)) sel
    else specs.indices.firstOrNull { enabled(it) } ?: 0
    val spec = specs[index]
    val value = spec.get(current)
    val modified = value != 0f

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(specs, key = { _, s -> s.label }) { i, s ->
                    val on = i == index
                    val used = s.get(current) != 0f
                    Row(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable(enabled = enabled(i)) { sel = i }
                            .padding(
                                start = 13.dp, top = 6.dp, bottom = 6.dp,
                                end = if (used) 9.dp else 13.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            s.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                on -> MaterialTheme.colorScheme.onPrimary
                                enabled(i) -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            }
                        )
                        if (used) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .background(
                                        if (on) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    )
                            )
                        }
                    }
                }
            }
            // 行尾「归零」只重置当前选中的这一个参数（顶栏 ↺ 才重置全部美化）
            if (modified) {
                TextButton(
                    onClick = {
                        onResetCurrent()
                        onChange(spec.set(current, 0f))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        Icons.Filled.RestartAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "归零",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.width(58.dp))
            }
        }
        // 单滑块行（当前选中参数）
        ParamSlider(
            value = value,
            range = spec.range,
            onValueChange = { onChange(spec.set(current, it)) },
            onGestureStart = onGestureStart,
            onGestureEnd = onGestureEnd
        )
    }
}

/** 滤镜页：对当前构图后原图逐滤镜生成实时小样 */
@Composable
private fun FilterPanel(current: BeautyParams, base: Bitmap?, onPick: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(Filters.all, key = { it.id }) { def ->
            FilterCell(
                src = base,
                def = def,
                active = def.id == current.filterId,
                onClick = { onPick(def.id) }
            )
        }
    }
}

@Composable
private fun FilterCell(
    src: Bitmap?,
    def: FilterDef,
    active: Boolean,
    onClick: () -> Unit
) {
    var thumb by remember(src, def.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(src, def.id) {
        thumb = if (src != null) filterThumb(src, def.id) else null
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (active) Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(10.dp)
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            thumb?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (active) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            def.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 用 72px 小图跑一遍 LUT，生成滤镜缩略小样 */
private suspend fun filterThumb(src: Bitmap, id: Int): Bitmap? = withContext(Dispatchers.IO) {
    if (!OpenCv.ensureLoaded()) return@withContext null
    val scale = min(1f, 72f / max(src.width, src.height))
    val w = max(1, (src.width * scale).toInt())
    val h = max(1, (src.height * scale).toInt())
    val small = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
    val bgr = small.toBgr()
    try {
        val out = Filters.apply(bgr, id)
        val res = out.toBitmap()
        if (out !== bgr) out.release()
        res
    } finally {
        bgr.release()
        if (small !== src) small.recycle()
    }
}

@Composable
private fun GeometryPanel(
    hasTransform: Boolean,
    onRotate: () -> Unit,
    onMirror: () -> Unit,
    onCrop: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GeometryButton(Icons.AutoMirrored.Filled.RotateRight, "旋转", onRotate)
        GeometryButton(Icons.Filled.Flip, "镜像", onMirror)
        GeometryButton(Icons.Filled.Crop, "裁剪", onCrop)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onReset, enabled = hasTransform) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("还原")
        }
    }
}

/** 神经超分面板：单按钮触发 Real-ESRGAN ×4 放大增强 */
@Composable
private fun SuperResPanel(
    available: Boolean,
    busy: Boolean,
    onApply: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (available) "Real-ESRGAN 神经超分：把当前画面放大 4 倍并增强细节"
            else "超分模型未内置，无法使用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onApply,
            enabled = available && !busy
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.ZoomIn, contentDescription = null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(if (busy) "超分中…" else "高清超分 ×4")
        }
    }
}

@Composable
private fun GeometryButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ——————————————————— 滑条 ———————————

/**
 * 自绘细轨滑条：2dp 细轨、活动/静止轨道无缝衔接；10dp 圆点拖动放大；点哪跳哪。
 * 参数名由上层抽屉 chip 承担，这里不再重复标签；一次完整手势记一步撤销。
 */
@Composable
internal fun ParamSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    onValueChange: (Float) -> Unit,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {}
) {
    var dragging by remember { mutableStateOf(false) }
    val thumbR by animateFloatAsState(
        targetValue = if (dragging) 7.dp.value else 5.dp.value,
        label = "thumbR"
    )

    // 手势块用 pointerInput(Unit) 长驻，不随重组重建；必须经 UpdatedState 取"最新"的回调
    // 与量程。否则切到同量程的另一参数后拖动仍命中上一参数闭包（画面在变、滑块/数值不动）。
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestStart by rememberUpdatedState(onGestureStart)
    val latestEnd by rememberUpdatedState(onGestureEnd)
    val latestRange by rememberUpdatedState(range)

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val thumbColor = MaterialTheme.colorScheme.primary
    val thumbBorder = MaterialTheme.colorScheme.surface
    val zeroMarkColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(Unit) {
                    fun setFromX(x: Float) {
                        val r = latestRange
                        val fraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        latestOnChange(r.start + fraction * (r.endInclusive - r.start))
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        latestStart()
                        setFromX(down.position.x)
                        drag(down.id) { change ->
                            setFromX(change.position.x)
                            change.consume()
                        }
                        dragging = false
                        latestEnd()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val trackH = 2.dp.toPx()
                val r = thumbR * density
                val edge = r + 1.dp.toPx()
                val minC = edge
                val maxC = size.width - edge
                val span = (range.endInclusive - range.start).coerceAtLeast(0.001f)
                val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
                val cx = minC + fraction * (maxC - minC)

                // 双向（含 0）量程：以「0 点」为锚，向左右各自填充，中间为零时两侧都是空的。
                // 单向（0..100）量程：锚点就是左端，行为与原来一致。
                val bipolar = range.start < 0f && range.endInclusive > 0f
                val anchorC = if (bipolar) {
                    minC + ((0f - range.start) / span).coerceIn(0f, 1f) * (maxC - minC)
                } else {
                    minC
                }
                val left = minOf(anchorC, cx)
                val right = maxOf(anchorC, cx)

                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(size.width, trackH),
                    cornerRadius = CornerRadius(trackH / 2f)
                )
                // 0 点刻度：双向滑条才画，方便看出「没调整」的位置
                if (bipolar) {
                    val markW = 1.5.dp.toPx()
                    val markH = 9.dp.toPx()
                    drawRoundRect(
                        color = zeroMarkColor,
                        topLeft = Offset(anchorC - markW / 2f, cy - markH / 2f),
                        size = Size(markW, markH),
                        cornerRadius = CornerRadius(markW / 2f)
                    )
                }
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(left, cy - trackH / 2f),
                    size = Size((right - left).coerceAtLeast(0f), trackH),
                    cornerRadius = CornerRadius(trackH / 2f)
                )
                drawCircle(color = thumbBorder, radius = r + 1.5.dp.toPx(), center = Offset(cx, cy))
                drawCircle(color = thumbColor, radius = r, center = Offset(cx, cy))
            }
        }
        Text(
            value.toInt().toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
    }
}
