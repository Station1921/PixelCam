package com.station1921.pixelcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.station1921.pixelcam.data.AutoBeauty
import com.station1921.pixelcam.data.AutoReceivePrefs
import com.station1921.pixelcam.data.LocalPhoto
import com.station1921.pixelcam.transfer.TransferStats
import com.station1921.pixelcam.data.PhotoStore
import com.station1921.pixelcam.transfer.MediaKind
import com.station1921.pixelcam.data.TransferActivity
import com.station1921.pixelcam.service.AutoReceiveService
import com.station1921.pixelcam.transfer.WifiConnector
import com.station1921.pixelcam.ui.AppSnackbarHost
import com.station1921.pixelcam.ui.EditorViewModel
import com.station1921.pixelcam.ui.TransferViewModel
import com.station1921.pixelcam.ui.screen.ConnectionScreen
import com.station1921.pixelcam.ui.screen.CaptureScreen
import com.station1921.pixelcam.ui.screen.GalleryScreen
import com.station1921.pixelcam.ui.screen.MonitorScreen
import com.station1921.pixelcam.ui.screen.ViewerScreen
import com.station1921.pixelcam.ui.theme.PixelCamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val ACTION_USB_PERMISSION = "com.station1921.pixelcam.ACTION_USB_PERMISSION"

enum class MainTab { Connect, Monitor, Capture, Gallery }

sealed interface Overlay {
    data class Viewer(val photos: List<LocalPhoto>, val index: Int) : Overlay
}

class MainActivity : ComponentActivity() {

    private var usbCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION != intent?.action) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            usbCallback?.invoke(granted)
            usbCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        ContextCompat.registerReceiver(
            this, usbReceiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            PixelCamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    AppRoot(
                        requestUsbPermission = { device, callback ->
                            usbCallback = callback
                            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                            val pending = android.app.PendingIntent.getBroadcast(
                                this, device.deviceId,
                                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                                android.app.PendingIntent.FLAG_IMMUTABLE or
                                    android.app.PendingIntent.FLAG_ONE_SHOT
                            )
                            manager.requestPermission(device, pending)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(requestUsbPermission: (UsbDevice, (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val transferVm: TransferViewModel = viewModel()
    val editorVm: EditorViewModel = viewModel()
    val wifi = remember { WifiConnector(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(MainTab.Connect) }
    var photos by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }

    // reload 串行化：连续触发时取消上一个再读，避免「先发的旧快照后写回」
    // 把刚入库的照片盖掉/乱序（图库「新图顺序乱、刷新才正常」的根因之一）
    var reloadJob by remember { mutableStateOf<Job?>(null) }
    val reload: () -> Unit = {
        reloadJob?.cancel()
        reloadJob = scope.launch { photos = PhotoStore.listImported(context) }
    }
    LaunchedEffect(Unit) { reload() }

    // USB 设备被拔下：立刻让 ViewModel 清掉来源并刷新状态，
    // 否则来源仍指向失效的 UsbMtpSource，界面/通知会一直显示「已连接」却一张都收不到（修复④）
    DisposableEffect(Unit) {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    transferVm.onUsbDetached()
                }
            }
        }
        ContextCompat.registerReceiver(
            context, rcv, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(rcv) } }
    }

    // 用户开过「后台常驻接收」：进程重启（重新打开 App）后把前台服务拉回来。
    // 服务起来后只是空转等待，连相机仍是用户在「相机连接」页的操作。
    LaunchedEffect(Unit) {
        if (AutoReceivePrefs.load(context).enabled) AutoReceiveService.start(context)
    }

    // 后台常驻接收又存了一张：让「图库」页的列表跟着刷新（含 AI 美化副本）
    val autoRecv by AutoReceiveService.state.collectAsStateWithLifecycle()
    val transferTasks by TransferActivity.tasks.collectAsStateWithLifecycle()
    LaunchedEffect(autoRecv.saved) {
        if (autoRecv.saved > 0) reload()
    }

    // AI 美化队列每完成/失败一张，磁盘上就多了（或标注了）一张「_美化」副本：
    // 之前没有任何 reload 触发，图库里要等手动按刷新才出现——看起来就像「新图顺序乱了」。
    // 这里盯着统计流变化自动补一次刷新（#100）。
    val beautyStats by AutoBeauty.stats.collectAsStateWithLifecycle()
    LaunchedEffect(beautyStats.done, beautyStats.failed) {
        if (beautyStats.done > 0 || beautyStats.failed > 0) reload()
    }

    // 任何一张照片落到手机（拍完 / 后台接收 / 手动导入都走这里）→ 图库立即重扫。
    // 这是全 App 唯一的「入库」权威计数（TransferStats.received），与走哪条收图路径无关；
    // 之前拍完照只靠「切到图库 tab」触发刷新，切换太快要落后于文件落盘，偶发不显示（#129）。
    val transferStats by TransferStats.state.collectAsStateWithLifecycle()
    LaunchedEffect(transferStats.received) {
        if (transferStats.received > 0) reload()
    }

    // 切到「图库」tab 自动重扫磁盘刷新内容：拍完 / 收完照片切过去立即可见，
    // 不必再手动点刷新（#127）。只在真正切进图库时触发，离开不触发。
    LaunchedEffect(tab) {
        if (tab == MainTab.Gallery) reload()
    }

    // 沉浸式：拍照页 / 监视页 是「二级全屏页」（隐藏状态栏+导航栏，像手机相机），离开恢复
    val fullScreenTab = tab == MainTab.Capture || tab == MainTab.Monitor
    LaunchedEffect(fullScreenTab) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullScreenTab) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 系统滑动退出：在拍照/监视全屏页时，边缘返回手势回到「相机连接」主 tab
    BackHandler(enabled = fullScreenTab && overlay == null) {
        tab = MainTab.Connect
    }

    // USB 授权回调
    val pendingUsb by transferVm.permissionRequest.collectAsStateWithLifecycle()
    LaunchedEffect(pendingUsb) {
        val device = pendingUsb ?: return@LaunchedEffect
        requestUsbPermission(device) { granted ->
            if (granted) transferVm.onUsbPermissionGranted(device)
            else transferVm.onUsbPermissionDenied()
        }
    }

    // 系统相册多选导入（图库页 + 号调用）
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var n = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    if (copyToAppStorage(context, uri) != null) n++
                }
            }
            reload()
            snackbar.showSnackbar("已导入 $n 张图片")
        }
    }

    // 全屏覆盖层（大图浏览）覆盖在底部 tab 之上
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                // 拍照页 / 监视页 走全屏沉浸式，不显示底部 tab
                if (tab != MainTab.Capture && tab != MainTab.Monitor) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val items = listOf(
                            MainTab.Connect to Pair("连接", Icons.Filled.Wifi),
                            MainTab.Monitor to Pair("监视", Icons.Filled.Visibility),
                            MainTab.Capture to Pair("拍照", Icons.Filled.CameraAlt),
                            MainTab.Gallery to Pair("图库", Icons.Filled.PhotoLibrary)
                        )
                        items.forEach { (t, label) ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { androidx.compose.material3.Icon(label.second, contentDescription = label.first) },
                                label = { Text(label.first) },
                                alwaysShowLabel = true,
                                // 文字与图标统一用主题色（品牌紫）：选中图标用实色白，
                                // 画在 primaryContainer 色块上，避免品牌紫叠品牌紫显得发灰半透明
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            // 监视页是全屏 tab（隐藏底部 tab 栏），状态栏/导航栏内边距由页面自己管：
            // 这里不再叠加 Scaffold 的 insets 内边距，否则上边距翻倍——页面整体偏低，
            // 且首帧 insets 结算时会「弹」一下；全屏大图预览也因此盖不住状态栏区域。
            Box(
                Modifier.fillMaxSize()
                    .padding(if (tab == MainTab.Monitor) PaddingValues(0.dp) else padding)
            ) {
                when (tab) {
                    MainTab.Connect -> ConnectionScreen(
                        vm = transferVm,
                        wifi = wifi,
                        onGoMonitor = { tab = MainTab.Monitor },
                        onImported = reload
                    )
                    MainTab.Monitor -> MonitorScreen(
                        vm = transferVm,
                        onGoConnect = { tab = MainTab.Connect },
                        onImported = reload,
                        contentPadding = padding
                    )
                    MainTab.Capture -> CaptureScreen(
                        vm = transferVm,
                        onExit = { tab = MainTab.Connect },
                        onGoGallery = { tab = MainTab.Gallery },
                        onImported = reload
                    )
                    MainTab.Gallery -> GalleryScreen(
                        photos = photos,
                        transferTasks = transferTasks,
                        onRefresh = reload,
                        onOpenPhoto = { photo ->
                            if (photo.kind == MediaKind.VIDEO) {
                                playVideo(context, photo.file)
                            } else {
                                val idx = photos.indexOf(photo).coerceAtLeast(0)
                                overlay = Overlay.Viewer(photos, idx)
                            }
                        },
                        onBatchDelete = { list ->
                            scope.launch {
                                list.forEach { PhotoStore.delete(context, it) }
                                reload()
                                snackbar.showSnackbar("已删除 ${list.size} 张")
                            }
                        },
                        onBatchSave = { list ->
                            scope.launch {
                                var ok = 0
                                list.forEach { if (PhotoStore.saveFileToGallery(context, it.file, it.name) != null) ok++ }
                                snackbar.showSnackbar("已保存 $ok/${list.size} 张到相册 Pictures/PixelCam")
                            }
                        }
                    )
                }
            }
        }

        // 大图浏览覆盖层
        overlay?.let { ov ->
            when (ov) {
                is Overlay.Viewer -> {
                    BackHandler { overlay = null; reload() }
                    ViewerScreen(
                        initialPhotos = ov.photos,
                        startIndex = ov.index,
                        editorVm = editorVm,
                        onBack = { overlay = null; reload() },
                        onGalleryChanged = reload
                    )
                }
            }
        }

        AppSnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )
    }
}

private suspend fun copyToAppStorage(context: Context, uri: Uri): File? =
    withContext(Dispatchers.IO) {
        val name = queryDisplayName(context, uri) ?: "IMG_${System.currentTimeMillis()}.jpg"
        val dest = PhotoStore.importTarget(context, name)
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        input.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }
        dest
    }

private fun queryDisplayName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index < 0) return null
        return it.getString(index)
    }
}

/**
 * 相机视频用系统播放器打开：本 App 不内置播放器，直接交给系统（或用户默认）视频应用，
 * 这样所有编码格式都能放、且不用自己处理音轨/字幕。
 * 文件在应用私有目录，需经 FileProvider 暴露 URI 并授予临时读权限。
 */
private fun playVideo(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val mime = when (file.extension.lowercase()) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        else -> "video/*"
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
