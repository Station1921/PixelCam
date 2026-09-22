package com.station1921.pixelcam.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.station1921.pixelcam.data.LocalPhoto
import com.station1921.pixelcam.data.TransferActivity
import com.station1921.pixelcam.transfer.MediaKind
import com.station1921.pixelcam.util.BitmapIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 底部 4 个主 tab 之一：图库（按日期分组 + 美化标签筛选） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    photos: List<LocalPhoto>,
    onOpenPhoto: (LocalPhoto) -> Unit,
    onBatchDelete: (List<LocalPhoto>) -> Unit,
    onBatchSave: (List<LocalPhoto>) -> Unit,
    transferTasks: List<TransferActivity.Task> = emptyList(),
    onRefresh: () -> Unit = {}
) {
    var filter by remember { mutableStateOf(0) } // 0=全部 1=AI美化 2=已美化
    var fmtFilter by remember { mutableStateOf("all") } // all/image/raw/video
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val shown = photos.filter { p ->
        val okFmt = when (fmtFilter) {
            "image" -> p.kind == MediaKind.JPEG || p.kind == MediaKind.IMAGE_OTHER
            "raw" -> p.kind == MediaKind.RAW
            "video" -> p.kind == MediaKind.VIDEO
            else -> true
        }
        val okTag = when (filter) {
            1 -> p.ai
            2 -> p.edited
            else -> true
        }
        okFmt && okTag
    }
    val selectedPhotos = shown.filter { it.file.absolutePath in selected }

    // 按天分组（降序）
    val groups = remember(shown) {
        shown.groupBy { dayKey(it.modified) }
            .toSortedMap(compareByDescending { it })
            .mapValues { it.value.sortedByDescending { p -> p.modified } }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(22.dp))
        // 第一排：格式筛选（主筛选，勾选态 FilterChip）；数量 + 刷新挤在同一排右端，
        // 「图库」大标题按需求去掉，筛选标签顶到页首
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = fmtFilter == "all", onClick = { fmtFilter = "all" }, label = { Text("全部") }, shape = RoundedCornerShape(10.dp))
            FilterChip(selected = fmtFilter == "image", onClick = { fmtFilter = "image" }, label = { Text("图片") }, shape = RoundedCornerShape(10.dp))
            FilterChip(selected = fmtFilter == "raw", onClick = { fmtFilter = "raw" }, label = { Text("RAW") }, shape = RoundedCornerShape(10.dp))
            FilterChip(selected = fmtFilter == "video", onClick = { fmtFilter = "video" }, label = { Text("视频") }, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.weight(1f))
            Text("${shown.size} 张", color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Refresh, contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 第二排：标签筛选 —— 描边小胶囊样式，与上排的实心 FilterChip 明显区分开
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagPill("AI美化", filter == 1) { filter = if (filter == 1) 0 else 1 }
            TagPill("已美化", filter == 2) { filter = if (filter == 2) 0 else 2 }
        }
        Spacer(Modifier.height(10.dp))

        // —— 进行中：正在传输 / 正在美化的占位（一拍完就显示，不用等照片落盘）——
        // 队列一长会把整屏占满：默认折叠只展示前 3 个，可展开看全部（#102）
        if (transferTasks.isNotEmpty()) {
            val transferring = transferTasks.filter { it.phase == TransferActivity.Phase.TRANSFER }
            val beautifying = transferTasks.filter { it.phase == TransferActivity.Phase.BEAUTY }
            var expanded by remember { mutableStateOf(false) }
            val shownTransfer = if (expanded) transferring else transferring.take(3)
            val shownBeauty = if (expanded) beautifying else beautifying.take((3 - shownTransfer.size).coerceAtLeast(0))
            val hiddenCount = transferTasks.size - shownTransfer.size - shownBeauty.size
            shownTransfer.forEach { TransferCard(it) }
            if (beautifying.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                BeautyQueueCard(tasks = shownBeauty, pending = beautifying.size)
            }
            if (hiddenCount > 0 || expanded) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起队列" else "还有 $hiddenCount 个任务，展开队列")
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.PhotoLibrary, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("还没有照片，去「相机连接」收图吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier.weight(1f)
            ) {
                groups.forEach { (day, list) ->
                    // 日期分类单占整行（全宽），下面再排当天缩略图
                    items(1, span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                dayLabel(day),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(list, key = { it.file.absolutePath }) { photo ->
                        PhotoCell(
                            photo = photo,
                            selected = photo.file.absolutePath in selected,
                            onClick = {
                                if (selecting) {
                                    val p = photo.file.absolutePath
                                    selected = if (p in selected) selected - p else selected + p
                                } else onOpenPhoto(photo)
                            },
                            onLongClick = {
                                selecting = true
                                val p = photo.file.absolutePath
                                selected = if (p in selected) selected - p else selected + p
                            }
                        )
                    }
                }
            }

            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("取消") }
                    TextButton(onClick = {
                        selected = if (selected.size == shown.size) emptySet()
                        else shown.map { it.file.absolutePath }.toSet()
                    }) { Text(if (selected.size == shown.size) "全不选" else "全选") }
                    Spacer(Modifier.weight(1f))
                    Text("已选 ${selected.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = {
                            onBatchDelete(selectedPhotos)
                            selecting = false
                            selected = emptySet()
                        },
                        enabled = selectedPhotos.isNotEmpty()
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.size(4.dp))
                    Button(
                        onClick = {
                            onBatchSave(selectedPhotos)
                            selecting = false
                            selected = emptySet()
                        },
                        enabled = selectedPhotos.isNotEmpty()
                    ) { Text("保存") }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun dayKey(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

private fun dayLabel(day: String): String {
    val today = dayKey(System.currentTimeMillis())
    val yest = dayKey(System.currentTimeMillis() - 86400000L)
    return when (day) {
        today -> "今天"
        yest -> "昨天"
        else -> day
    }
}

/**
 * 第二排标签筛选的描边小胶囊：与第一排实心 FilterChip 做出样式区分（#102）。
 * 选中 = 主色描边 + 主色文字；未选中 = 弱化描边 + 次级文字。
 */
@Composable
private fun TagPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val stroke = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, stroke, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
private fun PhotoCell(
    photo: LocalPhoto,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isVideo = photo.kind == MediaKind.VIDEO
    var bitmap by remember(photo.file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photo.file.absolutePath) {
        withContext(Dispatchers.IO) {
            bitmap = if (isVideo) BitmapIo.videoFrame(photo.file, 320)
                     else BitmapIo.decode(photo.file, 320)
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) Modifier.border(
                    2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = photo.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // 视频：居中播放图标
        if (isVideo) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f)))
            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp))
        }
        // 格式角标（右上角）：JPG / RAW / MP4 …
        FormatPill(photo.formatLabel, Modifier.align(Alignment.TopEnd).padding(6.dp))
        // 美化角标（左上角）
        when {
            photo.ai -> BadgePill("AI美化", ai = true, Modifier.align(Alignment.TopStart).padding(6.dp))
            photo.edited -> BadgePill("美化", ai = false, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
    }
}

@Composable
private fun BadgePill(text: String, ai: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (ai) MaterialTheme.colorScheme.primary else Color(0xB3000000))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (ai) {
            Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
            Spacer(Modifier.size(3.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = if (ai) MaterialTheme.colorScheme.onPrimary else Color.White)
    }
}

@Composable
private fun FormatPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xB3000000))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = Color.White)
    }
}

/** 图库顶部「进行中」的传输占位卡：拍完照立刻出现，下载完自动消失 */
@Composable
private fun TransferCard(task: TransferActivity.Task) {
    val known = task.progress >= 0
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (known) {
                CircularProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.name, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (known) "正在传输到图库… ${task.progress}%" else "正在传输到图库…",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 自动美化队列卡：美化是整张渲染、没有单张内的细粒度进度，整体百分比算不准
 * （串行收图时批次边界模糊，曾试过两版都显示假 0%，用户反馈后去掉）。
 * 现在只展示可靠信息：头部「AI 自动美化中…（待处理 n 张）」+ 在处理/排队的单张文件名。
 */
@Composable
private fun BeautyQueueCard(tasks: List<TransferActivity.Task>, pending: Int) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (pending > 1) "AI 自动美化中…（待处理 $pending 张）" else "AI 自动美化中…",
                    fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1
                )
            }
            tasks.forEach { t ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 行前只留缩进与头部文字对齐：转圈整卡只保留头部一个，不重复显示
                    Spacer(Modifier.width(28.dp))
                    Text(
                        t.name, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
