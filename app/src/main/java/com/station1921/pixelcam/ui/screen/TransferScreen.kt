package com.station1921.pixelcam.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.station1921.pixelcam.transfer.RemotePhoto
import com.station1921.pixelcam.ui.TransferMode
import com.station1921.pixelcam.ui.TransferUi
import com.station1921.pixelcam.ui.TransferViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    vm: TransferViewModel,
    onBack: () -> Unit,
    onImported: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val devices by vm.usbDevices.collectAsStateWithLifecycle()

    /** 0 = 照片（批量导入），1 = 监视（即拍即看） */
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "相机图传",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 来源切换
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TransferMode.entries.forEach { mode ->
                FilterChip(
                    selected = ui.mode == mode,
                    onClick = { vm.setMode(mode) },
                    label = {
                        Text(
                            when (mode) {
                                TransferMode.USB -> "USB 直连"
                                TransferMode.FTP -> "相机 WiFi"
                                TransferMode.WIFI_SD -> "无线 SD 卡"
                            }
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 连接面板
        when (ui.mode) {
            TransferMode.USB -> UsbPanel(vm, devices)
            TransferMode.FTP -> FtpPanel(vm)
            TransferMode.WIFI_SD -> WifiSdPanel(vm)
        }

        if (ui.busy) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(ui.status, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (ui.status.isNotBlank()) {
            Text(
                ui.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        ui.error?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        // 连上之后才有得选：照片（勾选批量导入）/ 监视（即拍即看）
        if (ui.connected) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransferTab(text = "照片", selected = tab == 0) { tab = 0 }
                TransferTab(text = "监视", selected = tab == 1) { tab = 1 }
            }
        }

        // 断开连接后回到「照片」页，避免停在无源的监视页
        LaunchedEffect(ui.connected) { if (!ui.connected) tab = 0 }

        // 切到监视页才开始轮询，切走立刻停，不在后台空转耗电
        LaunchedEffect(tab, ui.connected) {
            if (tab == 1 && ui.connected) vm.startWatch() else vm.stopWatch()
        }

        when {
            !ui.connected -> Box(Modifier.weight(1f))

            tab == 1 -> WatchPanel(
                vm = vm,
                ui = ui,
                onImported = onImported,
                modifier = Modifier.weight(1f)
            )

            ui.photos.isNotEmpty() -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { vm.toggleAll() }) {
                        Text(if (ui.selected.size == ui.photos.size) "取消全选" else "全选")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "已选 ${ui.selected.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { vm.importSelected { onImported() } },
                        enabled = ui.selected.isNotEmpty() && !ui.importing
                    ) {
                        Text("导入")
                    }
                }

                if (ui.importing) {
                    LinearProgressIndicator(
                        progress = { ui.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }

                PhotoGrid(
                    photos = ui.photos,
                    selected = ui.selected,
                    thumbs = ui.thumbs,
                    onToggle = vm::toggle,
                    modifier = Modifier.weight(1f)
                )
            }

            else -> Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TransferTab(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

/**
 * 监视页：相机每拍一张，约 3 秒后自动出现在这里。
 * 只预览、不自动落盘——勾掉不想要的再导入，流量和存储都可控。
 */
@Composable
private fun WatchPanel(
    vm: TransferViewModel,
    ui: TransferUi,
    onImported: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (ui.watching) Color(0xFF4CAF50)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        CircleShape
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (ui.watching) "正在监视 · 每 3 秒检查一次" else "监视已停止",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            if (ui.live.isNotEmpty()) {
                Text(
                    "新增 ${ui.live.size} 张",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val bmp = ui.livePreview
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (ui.live.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("等待相机拍摄新照片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "拍一张，约 3 秒后自动出现在这里",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        val current = ui.live.firstOrNull { it.id == ui.livePreviewId }
        if (current != null) {
            Text(
                current.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            if (ui.livePreview == null) {
                Text(
                    "这个文件无法预览（多为 RAW），可直接导入后查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        if (ui.live.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
            ) {
                lazyItems(ui.live, key = { it.id }) { photo ->
                    LiveThumb(
                        thumb = ui.liveThumbs[photo.id],
                        active = photo.id == ui.livePreviewId,
                        picked = photo.id in ui.liveSelected,
                        onClick = { vm.openPreview(photo.id) },
                        onToggle = { vm.toggleLive(photo.id) }
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = vm::toggleAllLive, enabled = ui.live.isNotEmpty()) {
                Text(
                    if (ui.live.isNotEmpty() && ui.liveSelected.size == ui.live.size) "取消全选"
                    else "全选"
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "已选 ${ui.liveSelected.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { vm.importLiveSelected { onImported() } },
                enabled = ui.liveSelected.isNotEmpty() && !ui.importing
            ) { Text("导入") }
        }

        if (ui.importing) {
            LinearProgressIndicator(
                progress = { ui.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }
    }
}

/** 监视页底部时间线里的一格：点图切换大图，点右上角圆圈勾选 */
@Composable
private fun LiveThumb(
    thumb: Bitmap?,
    active: Boolean,
    picked: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (active) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(10.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
            }
        }
        Icon(
            if (picked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = "选择",
            tint = if (picked) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(18.dp)
        )
    }
}

@Composable
private fun UsbPanel(vm: TransferViewModel, devices: List<android.hardware.usb.UsbDevice>) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        // 与相机 WiFi 页的「连接」按钮同级：全宽、居中、主色，一眼可见
        Button(
            onClick = vm::refreshUsbDevices,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("扫描 USB 设备")
        }

        Spacer(Modifier.height(8.dp))

        if (devices.isEmpty()) {
            Text(
                "没有检测到设备。请用 OTG 转接线把相机连到手机，并确认相机已开机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            devices.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.connectUsb(device) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.productName ?: "未知设备",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            listOfNotNull(
                                device.manufacturerName,
                                "ID ${device.vendorId}:${device.productId}"
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("连接", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun FtpPanel(vm: TransferViewModel) {
    var host by remember { mutableStateOf("192.168.1.1") }
    var port by remember { mutableStateOf("21") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }

    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "连上相机自带的热点后，用 FTP 拉取照片。索尼、佳能、尼康的多数机型都提供这一能力。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("相机 IP") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("端口") }, singleLine = true,
                modifier = Modifier.width(110.dp)
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = path, onValueChange = { path = it },
                label = { Text("根目录") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("用户名（可留空）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("密码（可留空）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                vm.connectFtp(host, port.toIntOrNull() ?: 21, user, pass, path)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("连接") }
    }
}

@Composable
private fun WifiSdPanel(vm: TransferViewModel) {
    var host by remember { mutableStateOf("192.168.4.1") }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            "支持 FlashAir（走 command.cgi）与 ez Share 等以普通 HTTP 目录列表暴露文件的无线 SD 卡。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("卡片地址") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("192.168.4.1", "192.168.0.1", "flashair").forEach { preset ->
                TextButton(onClick = { host = preset }) { Text(preset) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.probeWifiSd(host) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("探测并连接") }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<RemotePhoto>,
    selected: Set<String>,
    thumbs: Map<String, Bitmap>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        modifier = modifier
    ) {
        items(photos, key = { it.id }) { photo ->
            val picked = photo.id in selected
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (picked) Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(12.dp)
                        ) else Modifier
                    )
                    .clickable { onToggle(photo.id) }
            ) {
                val bmp = thumbs[photo.id]
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = photo.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                    }
                }

                if (picked) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                    )
                }

                Text(
                    photo.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                )
            }
        }
    }
}
