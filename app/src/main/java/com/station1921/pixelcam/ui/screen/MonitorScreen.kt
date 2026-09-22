package com.station1921.pixelcam.ui.screen

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.station1921.pixelcam.service.AutoReceiveService
import com.station1921.pixelcam.ui.TransferViewModel
import com.station1921.pixelcam.ui.ZoomableImage

/** 相机相册在监视页里最多铺多少张缩略图（够看、又不至于把相机的缩略图接口刷爆） */
private const val ALBUM_MAX = 60

/**
 * 底部 4 个主 tab 之一：监视（即拍即看）。
 *
 * 这个页面**不是取景器**，也不显示相机的实时画面——实时取景在「拍照」页。
 * 它做的事是：连上相机后持续差分轮询，相机每拍一张，约 3 秒内把这张照片
 * 出现在这里，点开就能存进手机图库。
 */
@Composable
fun MonitorScreen(
    vm: TransferViewModel,
    onGoConnect: () -> Unit,
    onImported: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 进入监视页自动开始轮询（已连接时）——监视是常开的，不需要手动开关（#113）。
    // 「后台常驻接收」开着时这里照样轮询：同一条连接上谁先认领新照片谁下载（共用已见集合，
    // 不会重复下载），另一方自动跳过——监视页从此不会再因为服务接管而看不到新图（#112）。
    // 注意：离开本页**不**停止轮询——连上相机后就该一直收，切走页面也继续。
    val recv by AutoReceiveService.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.connected) {
        if (ui.connected) vm.startWatch() else vm.stopWatch()
    }

    // 大图预览状态
    var previewId by remember { mutableStateOf<String?>(null) }
    var landscape by remember { mutableStateOf(false) }
    // 大图放大态拖到边缘翻页用（ZoomableImage 的手势被自己消费，Pager 收不到）
    val scope = rememberCoroutineScope()
    // 大图沉浸模式：单击图片进入/退出，隐藏顶栏与底部保存栏
    var immersive by remember { mutableStateOf(false) }

    // 系统返回手势/返回键：大图预览打开时先关掉预览回到监视列表，
    // 不能让系统直接退回上一层（用户反馈「滑动返回直接回首页」）。
    BackHandler(enabled = previewId != null) { previewId = null }

    // 沉浸模式还要隐藏系统栏（状态栏的电池/时间/信号 + 导航栏）——之前只藏了 App 自己的
    // 顶/底栏，系统栏残留一条黑条。用 DisposableEffect 同步生效；关闭预览 / 离开页面恢复。
    val view = LocalView.current
    DisposableEffect(immersive) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (immersive) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // 关闭大图预览（返回键/保存后自动关）时退出沉浸态，避免下次进来还是隐藏状态
    LaunchedEffect(previewId) {
        if (previewId == null) immersive = false
    }

    val activity = context.findActivity()
    LaunchedEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!ui.connected) {
            Column(
                Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.PhotoLibrary, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("尚未连接相机", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "这里显示相机新拍的照片（即拍即看）。先在「相机连接」里连上相机，再回到这里。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onGoConnect) { Text("去连接相机") }
            }
        } else {
            Column(
                Modifier.fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(14.dp))
                // 顶部：退出按钮常驻（系统滑动也可退出）；标题与监视开关仅竖屏显示
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.4f))
                            .clickable { onGoConnect() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, null,
                            tint = Color.White, modifier = Modifier.size(20.dp)
                        )
                    }
                    if (!landscape) {
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "监视",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                when {
                                    recv.running && recv.saved > 0 ->
                                        "后台常驻接收中 · 已自动保存 ${recv.saved} 张"
                                    recv.running -> "后台常驻接收中（${recv.status}）"
                                    ui.scanning -> "实时接收中 · 扫描新照片…"
                                    else -> "实时接收中"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 刷新按钮（#113）：重扫相机相册，清掉相机里已删掉的旧图
                        IconButton(onClick = { vm.refreshMonitor() }) {
                            Icon(
                                Icons.Filled.Refresh, contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (!landscape) {
                    Text(
                        "相机每拍一张，3 秒内自动出现在这里；点开可存进手机图库。" +
                            "这里不是取景画面——要看实时取景请去「拍照」页。",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // 相机相册最近若干张：没有新照片时它就是页面内容，
                // 免得刚进来只看到一个转圈、不知道该页是干嘛的
                val album = ui.photos.take(ALBUM_MAX)

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (ui.live.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionLabel("新照片 · ${ui.live.size} 张")
                        }
                        items(ui.live, key = { "live_${it.id}" }) { photo ->
                            ThumbCell(
                                bitmap = ui.liveThumbs[photo.id],
                                name = photo.name,
                                isNew = true,
                                onClick = {
                                    previewId = photo.id
                                    vm.openPreview(photo.id)
                                }
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionLabel("相机相册 · 最近 ${album.size} 张")
                        }
                    } else {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            WaitingNotice(
                                watching = ui.watching,
                                background = recv.running,
                                albumCount = album.size
                            )
                        }
                    }
                    items(album, key = { "alb_${it.id}" }) { photo ->
                        ThumbCell(
                            bitmap = ui.thumbs[photo.id],
                            name = photo.name,
                            isNew = false,
                            onClick = {
                                previewId = photo.id
                                vm.openPreview(photo.id)
                            }
                        )
                    }
                }
            }
        }

        // —————————— 大图预览覆盖层（支持左右滑动翻页） ——————————
        if (previewId != null) {
            val pid = previewId
            // 翻页序列：新照片在前、相册在后，与网格的排列顺序一致；按 id 去重
            val previewList = remember(ui.live, ui.photos) {
                (ui.live + ui.photos).distinctBy { it.id }
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                key(previewList.size) {
                    val pagerState = rememberPagerState(
                        initialPage = previewList.indexOfFirst { it.id == pid }.coerceAtLeast(0)
                    ) { previewList.size }

                    // 翻页 → 更新选中 id，并按需加载那一张的中尺寸预览
                    LaunchedEffect(previewList) {
                        snapshotFlow { pagerState.currentPage }.collect { page ->
                            val p = previewList.getOrNull(page) ?: return@collect
                            if (p.id != previewId) {
                                previewId = p.id
                                vm.openPreview(p.id)
                            }
                        }
                    }
                    // 网格点进来时把 pager 对齐到选中那张（外部 previewId 变化时同样跟齐）
                    LaunchedEffect(previewId) {
                        val idx = previewList.indexOfFirst { it.id == previewId }
                        if (idx >= 0 && pagerState.currentPage != idx) pagerState.scrollToPage(idx)
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val p = previewList.getOrNull(page)
                        // 只有当前页对应的预览图到位了才显示，否则显示加载提示（避免纯黑卡片）
                        val pageBmp = if (p != null && p.id == ui.livePreviewId) ui.livePreview else null
                        if (pageBmp != null) {
                            ZoomableImage(
                                bitmap = pageBmp,
                                onTap = { immersive = !immersive },
                                // 放大态拖到图片边缘继续拖 → 翻一张（方向与图库查看器一致）
                                onEdgeSwipe = { dir ->
                                    scope.launch {
                                        val next = (pagerState.currentPage + dir)
                                            .coerceIn(0, previewList.size - 1)
                                        if (next != pagerState.currentPage) {
                                            pagerState.animateScrollToPage(next)
                                        }
                                    }
                                }
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = Color.White, modifier = Modifier.size(36.dp), strokeWidth = 3.dp
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text("正在加载预览…", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // 顶栏（沉浸态隐藏）。样式与图库查看器完全一致：半透明黑 + 状态栏内边距，
                // 叠在照片上是统一的暗色；叠在照片留白（纯黑）上则与留白融为一体。
                if (!immersive) Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .padding(contentPadding)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { previewId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(previewList.indexOfFirst { it.id == previewId } + 1).coerceAtLeast(1)} / ${previewList.size}",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { landscape = !landscape }) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, null, tint = Color.White)
                    }
                }

                // 底部保存：保存状态按「当前这张图」独立判断（importingIds），
                // 保存 A 时仍可立刻翻到 B 再点保存，互不占用（#101）。沉浸态隐藏。
                // 不加背景色（用户反馈）：按钮直接浮在图上，仅保留导航栏内边距防遮挡。
                if (!immersive) Box(
                    Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(contentPadding)
                    .padding(12.dp)
                ) {
                    val currentId = previewId
                    val currentSaving = currentId != null && currentId in ui.importingIds
                    Button(
                        onClick = {
                            val id = previewId ?: return@Button
                            if (id in ui.importingIds) return@Button
                            vm.importOne(id) { n ->
                                onImported()
                                if (n > 0 && previewList.size <= 1) previewId = null
                            }
                        },
                        enabled = currentId != null && !currentSaving,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (currentSaving) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在保存…")
                        } else {
                            Icon(Icons.Filled.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text("保存到图库")
                        }
                    }
                }
            }
        }
    }
}

/** 分组小标题（跨整行） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/**
 * 没有新照片时的说明块：把「这个页面在等什么」讲清楚，
 * 并说明下面列出的其实是相机相册里的存量照片。
 */
@Composable
private fun WaitingNotice(watching: Boolean, background: Boolean, albumCount: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (watching) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                when {
                    background -> "后台常驻接收中，新照片会直接进图库"
                    watching -> "正在等相机拍下一张…"
                    else -> "接收已停止"
                },
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (albumCount > 0)
                "下面列出的是相机相册里最近的 $albumCount 张（连接时就有的存量）。" +
                    "相机新拍的照片会自动出现在列表最上面，并打上「新」标记。"
            else
                "没读到相机相册。请到「相机连接」页确认相机已连上并切到回放模式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 一张缩略图格子；[isNew] 为真时打「新」标记 */
@Composable
private fun ThumbCell(
    bitmap: Bitmap?,
    name: String,
    isNew: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        if (isNew) {
            Text(
                "新",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun Context.findActivity(): ComponentActivity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
