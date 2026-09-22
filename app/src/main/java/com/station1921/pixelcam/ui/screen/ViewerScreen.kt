package com.station1921.pixelcam.ui.screen

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import com.station1921.pixelcam.data.LocalPhoto
import com.station1921.pixelcam.data.PhotoStore
import com.station1921.pixelcam.ui.AppSnackbarHost
import com.station1921.pixelcam.ui.EditorViewModel
import com.station1921.pixelcam.util.BitmapIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import java.io.File

/**
 * 大图浏览：左右滑翻页 + 单张编辑/保存/删除 + 选择态批量操作。
 *
 * 设计取舍：
 * - 翻页用官方 [HorizontalPager]（foundation 自带，无需加库），预加载相邻页防白屏。
 * - 「编辑」把 [EditorScreen] 作为覆盖层嵌进来，编辑完回到同一张、翻页位置不丢。
 * - 删除/保存后就地刷新列表（[items] 是状态），空了自动回首页。
 */
@Composable
fun ViewerScreen(
    initialPhotos: List<LocalPhoto>,
    startIndex: Int,
    editorVm: EditorViewModel,
    onBack: () -> Unit,
    onGalleryChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 本页是黑底：状态栏图标改浅色，避免"黑背景黑字"看不见；离开页面时还原
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val originLight = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (originLight != null) controller?.isAppearanceLightStatusBars = originLight
            // 离开大图页：确保系统状态栏（电池/时间/信号）恢复显示
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT < 30) {
                @Suppress("DEPRECATION")
                window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
    }

    // 沉浸模式：单击图片进入/退出，隐藏顶/底栏（含系统状态栏）
    var immersive by remember { mutableStateOf(false) }

    // 沉浸模式：单击图片进入/退出，除了隐藏 App 顶/底栏，还要把系统状态栏（含电池电量条）
    // 一并隐藏，做到纯图沉浸；退出或离开页面由 onDispose 恢复。
    // 说明：
    //  - 用 insets controller 隐藏「整条 systemBars」（状态栏+导航栏），只隐藏 statusBars 时
    //    部分机型会残留一条黑条；
    //  - 用 DisposableEffect（同步、提交即生效）而不是 LaunchedEffect（协程调度慢一拍），
    //    单击后立刻隐藏，感知上不拖沓；
    //  - BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE：边缘滑出时系统栏以半透明浮层短暂出现，
    //    不再把内容挤下去；
    //  - API < 30 再补一发 FLAG_FULLSCREEN 旧标志，老系统上 insets 隐藏不彻底时兜底。
    DisposableEffect(immersive) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (immersive) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT < 30) {
                @Suppress("DEPRECATION")
                window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT < 30) {
                @Suppress("DEPRECATION")
                window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT < 30) {
                @Suppress("DEPRECATION")
                window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
    }

    var items by remember(initialPhotos) { mutableStateOf(initialPhotos) }
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (initialPhotos.size - 1).coerceAtLeast(0)),
        pageCount = { items.size }
    )

    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    var editing by remember { mutableStateOf(false) }
    var editFile by remember { mutableStateOf<File?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val current = items.getOrNull(pagerState.currentPage)

    // 大图缩放/平移状态（提升到本层：翻页时复位、并控制 Pager 放大时禁用横向滑动）
    val scaleState = remember { mutableStateOf(1f) }
    val offsetState = remember { mutableStateOf(Offset.Zero) }
    // 双指捏合旋转（绕中心）：提升到本层，翻页时复位
    val rotationState = remember { mutableStateOf(0f) }
    // 只在「是否放大」跨阈值时才通知上层，避免拖动/捏合时每帧重组整棵 Pager 造成卡顿抖动
    val zoomed by remember { derivedStateOf { scaleState.value > 1.001f } }

    // 放大态下拖到图片边缘后继续拖 → 翻到上一张/下一张（delta: -1 上一张，+1 下一张）。
    // 翻页前先复位缩放，否则会带着放大状态滑走；页容量从 pagerState 读（实时，不用捕获 items）。
    val goPage: (Int) -> Unit = { delta ->
        val last = (pagerState.pageCount - 1).coerceAtLeast(0)
        val target = (pagerState.currentPage + delta).coerceIn(0, last)
        if (target != pagerState.currentPage) {
            scaleState.value = 1f
            offsetState.value = Offset.Zero
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }

    // 翻到另一张时复位缩放，避免缩放态带入相邻页
    LaunchedEffect(pagerState.currentPage) {
        scaleState.value = 1f
        offsetState.value = Offset.Zero
        rotationState.value = 0f
    }

    // 返回键：编辑态交给 EditorScreen 自己处理；选择态先退出选择；否则回首页
    BackHandler(enabled = !editing) {
        when {
            selectMode -> {
                selectMode = false
                selected = emptySet()
            }
            else -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (items.isEmpty()) {
            // 理论上不会长时间出现（空了就回首页），保险占位
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                // 放大后禁用 Pager 横向滑动，改由手势平移浏览
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val photo = items[page]
                val active = page == pagerState.currentPage
                Box(Modifier.fillMaxSize()) {
                    ViewerPage(
                        photo = photo,
                        scale = scaleState,
                        offset = offsetState,
                        rotation = rotationState,
                        active = active,
                        onEdgeSwipe = goPage,
                        onSingleTap = { immersive = !immersive }
                    )
                }
            }
        }

        // —————————— 顶部栏（编辑态 / 沉浸态隐藏，避免与编辑页标题栏重叠出现"上一页标题"） ——————————
        if (!editing && !immersive) {
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.32f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (selectMode) {
                    selectMode = false
                    selected = emptySet()
                } else {
                    onBack()
                }
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }

            if (selectMode) {
                Text(
                    "已选 ${selected.size} 张",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            } else {
                Spacer(Modifier.weight(1f))
                Text(
                    "${pagerState.currentPage + 1} / ${items.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
            }

            // 选择态右侧：当前这张的选择复选框（固定在顶栏，不随 Pager 跑、必可点）
            if (selectMode) {
                Spacer(Modifier.weight(1f))
                val picked = current?.file?.absolutePath in selected
                IconButton(onClick = {
                    val p = current?.file?.absolutePath ?: return@IconButton
                    selected = if (p in selected) selected - p else selected + p
                }) {
                    Icon(
                        if (picked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = "选择当前",
                        tint = if (picked) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
            } else if (current != null) {
                // 普通态右侧：选择（图片旋转用双指扭转手势，不提供整屏旋转按钮）
                IconButton(onClick = {
                    selectMode = true
                    selected = emptySet()
                }) {
                    Icon(
                        Icons.Filled.SelectAll,
                        contentDescription = "选择",
                        tint = Color.White
                    )
                }
            }
        }
        }

        // —————————— 底部工具条 ——————————
        if (!immersive && selectMode) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                ViewerSelectBar(
                    items = items,
                    selected = selected,
                    onToggle = { p ->
                        selected = if (p in selected) selected - p else selected + p
                    },
                    onSelectAll = {
                        selected = if (selected.size == items.size) emptySet()
                        else items.map { it.file.absolutePath }.toSet()
                    },
                    onBatchSave = {
                        scope.launch {
                            var ok = 0
                            items.filter { it.file.absolutePath in selected }.forEach { photo ->
                                if (PhotoStore.saveFileToGallery(context, photo.file, photo.name) != null) ok++
                            }
                            selected = emptySet()
                            selectMode = false
                            onGalleryChanged()
                            snackbar.showSnackbar("已保存 $ok 张到相册 Pictures/PixelCam")
                        }
                    },
                    onBatchDelete = {
                        scope.launch {
                            val toDel = items.filter { it.file.absolutePath in selected }
                            var ok = 0
                            toDel.forEach { if (PhotoStore.delete(context, it)) ok++ }
                            val gone = toDel.map { it.file.absolutePath }.toSet()
                            items = items.filter { it.file.absolutePath !in gone }
                            selected = emptySet()
                            selectMode = false
                            onGalleryChanged()
                            snackbar.showSnackbar("已删除 $ok 张")
                            if (items.isEmpty()) onBack()
                        }
                    }
                )
            }
        } else if (current != null && !editing && !immersive) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                ViewerToolBar(
                    onEdit = {
                        // 同步清空编辑器旧图，避免进入编辑先闪出上一张、再跳到本张
                        editorVm.load(current.file)
                        editing = true
                        editFile = current.file
                    },
                    onSave = {
                        scope.launch {
                            val ok = PhotoStore.saveFileToGallery(context, current.file, current.name)
                            snackbar.showSnackbar(
                                if (ok != null) "已保存到相册 Pictures/PixelCam" else "保存失败"
                            )
                        }
                    },
                    onDelete = {
                        scope.launch {
                            val ok = PhotoStore.delete(context, current)
                            items = items.filter { it.file != current.file }
                            onGalleryChanged()
                            snackbar.showSnackbar(if (ok) "已删除" else "删除失败")
                            if (items.isEmpty()) onBack()
                        }
                    }
                )
            }
        }

        // 编辑覆盖层（保留翻页位置，返回只关掉它）
        if (editing && editFile != null) {
            Box(Modifier.fillMaxSize()) {
                EditorScreen(
                    vm = editorVm,
                    onBack = {
                        editing = false
                        // 编辑可能改动文件或打标记，就地刷新当前这张的角标
                        val f = editFile
                        if (f != null && f.exists()) {
                            items = items.map { old ->
                                if (old.file.absolutePath == f.absolutePath)
                                    PhotoStore.localPhotoOf(context, f) else old
                            }
                        }
                        editFile = null
                        onGalleryChanged()
                    }
                )
            }
        }

        AppSnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 76.dp)
        )
    }
}

/**
 * 大图单页：按长边 2000 解码。手势完全自管，避免官方 transformable 的阻尼/默认中心缩放问题：
 * - 双指捏合：以两指中心为锚点缩放（不再从布局中心放大、不会带着下一张一起放）。
 * - 单指：scale≈1 不消费事件 → 交给 Pager 横向翻页；scale>1 时 1:1 拖动浏览、无阻尼。
 * - 双击：以手指点击处为锚点放大到 2.5×；再双击同一处复位。
 */
@Composable
private fun ViewerPage(
    photo: LocalPhoto,
    scale: MutableState<Float>,
    offset: MutableState<Offset>,
    rotation: MutableState<Float>,
    active: Boolean,
    onEdgeSwipe: (Int) -> Unit,
    onSingleTap: () -> Unit
) {
    var bitmap by remember(photo.file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    val sizeState = remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(photo.file.absolutePath) {
        withContext(Dispatchers.IO) {
            bitmap = BitmapIo.decode(photo.file, 2000)
        }
    }

    val bmp = bitmap

    // 把平移量钳制在图片边界内，防止滑出黑边露出背景。
    // 坐标系：视口坐标（screen = local*scale + offset）。旋转 90°/270° 时，屏幕可见区域
    // 映射回未旋转坐标系是宽高互换、缩小 k 倍的居中窗口，钳制在窗口内做，
    // 旋转后超出屏幕的内容才拖得出来。
    val clampOffset: (Float, Offset) -> Offset = { s, o ->
        if (bmp == null || s <= 1.001f || sizeState.value.width == 0) {
            Offset.Zero
        } else {
            val vw0 = sizeState.value.width.toFloat()
            val vh0 = sizeState.value.height.toFloat()
            val fit0 = minOf(vw0 / bmp.width.toFloat(), vh0 / bmp.height.toFloat())
            val dw = bmp.width.toFloat() * fit0   // Fit 后图片实际显示尺寸
            val dh = bmp.height.toFloat() * fit0
            val r = ((rotation.value % 360f) + 360f) % 360f
            val q90 = r > 45f && r < 135f
            val q270 = r > 225f && r < 315f
            val k = if (q90 || q270) minOf(vw0 / dh, vh0 / dw) else 1f
            val vw = if (q90 || q270) vh0 / k else vw0
            val vh = if (q90 || q270) vw0 / k else vh0
            val wx0 = (vw0 - vw) / 2f
            val wy0 = (vh0 - vh) / 2f
            val cx = (vw0 - dw) / 2f              // 图片在视口内的居中留白
            val cy = (vh0 - dh) / 2f
            val sw = dw * s                       // 缩放后图片屏幕尺寸
            val sh = dh * s
            // 图片小于窗口 → 强制居中；大于窗口 → 不许越过边界（不露黑边）
            val minX = if (sw <= vw) (vw - sw) / 2f - cx * s else vw - (cx + dw) * s
            val maxX = if (sw <= vw) (vw - sw) / 2f - cx * s else -cx * s
            val minY = if (sh <= vh) (vh - sh) / 2f - cy * s else vh - (cy + dh) * s
            val maxY = if (sh <= vh) (vh - sh) / 2f - cy * s else -cy * s
            Offset(
                (o.x - wx0).coerceIn(minX, maxX) + wx0,
                (o.y - wy0).coerceIn(minY, maxY) + wy0
            )
        }
    }

    // 屏幕坐标 → 未旋转坐标系：旋转后内层位移会被外层旋转「带着转」，
    // 手势输入必须先映射回未旋转视口，拖动方向才与手指一致。
    fun toUnrot(pt: Offset): Offset {
        val vw0 = sizeState.value.width.toFloat()
        val vh0 = sizeState.value.height.toFloat()
        val b = bmp
        if (vw0 <= 0f || vh0 <= 0f || b == null) return pt
        val r = ((rotation.value % 360f) + 360f) % 360f
        val q90 = r > 45f && r < 135f
        val q180 = r > 135f && r < 225f
        val q270 = r > 225f && r < 315f
        if (!q90 && !q180 && !q270) return pt
        val fit0 = minOf(vw0 / b.width, vh0 / b.height)
        val dw = b.width * fit0
        val dh = b.height * fit0
        val k = if (q90 || q270) minOf(vw0 / dh, vh0 / dw) else 1f
        val dx = pt.x - vw0 / 2f
        val dy = pt.y - vh0 / 2f
        return when {
            q90 -> Offset(vw0 / 2f + dy / k, vh0 / 2f - dx / k)
            q180 -> Offset(vw0 / 2f - dx, vh0 / 2f - dy)
            else -> Offset(vw0 / 2f - dy / k, vh0 / 2f + dx / k)
        }
    }

    // 未旋转坐标系里的「位移向量」→ 屏幕位移向量（toUnrot 的逆变换，只用于向量不需平移）。
    // 翻页判定必须取换算后的屏幕横向分量：旋转 90°/270° 后图片坐标系与屏幕横纵互换，
    // 直接用图片坐标系的 x 判边缘，会把「上下拖」误判成翻页（用户反馈）。
    fun toScreenDelta(d: Offset): Offset {
        val vw0 = sizeState.value.width.toFloat()
        val vh0 = sizeState.value.height.toFloat()
        val b = bmp
        if (vw0 <= 0f || vh0 <= 0f || b == null) return d
        val r = ((rotation.value % 360f) + 360f) % 360f
        val q90 = r > 45f && r < 135f
        val q180 = r > 135f && r < 225f
        val q270 = r > 225f && r < 315f
        if (!q90 && !q180 && !q270) return d
        val fit0 = minOf(vw0 / b.width, vh0 / b.height)
        val dw = b.width * fit0
        val dh = b.height * fit0
        val k = if (q90 || q270) minOf(vw0 / dh, vh0 / dw) else 1f
        return when {
            q90 -> Offset(-k * d.y, k * d.x)
            q180 -> Offset(-d.x, -d.y)
            else -> Offset(k * d.y, -k * d.x)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 关键：手势挂在外层「未变换」的 Box 上。
            // 旧版挂在被 graphicsLayer 缩放的 Image 上，position 会被引擎逆变换，
            // 而 offset 每帧都在变 → 逆变换基准漂移，差分 delta 里混进上一帧位移，
            // 导致拖动被抵消（强阻尼）且来回抖。这里 position 就是视口坐标，直接 1:1。
            .pointerInput(active) {
                coroutineScope {
                    awaitPointerEventScope {
                        var wasDown = false
                        var downPos = Offset.Zero
                        var moved = false
                        var prevCentroid: Offset? = null
                        var prevDist = 0f
                        var prevCount = 0
                        var prevAngle = 0f
                        // 双指扭转：累计整段手势的扭转角，到阈值触发一次 90° 旋转后锁住本次手势
                        var twistConsumed = false
                        var twistAccum = 0f
                        var lastTapTime = 0L
                        var lastTapPos = Offset.Zero
                        // 拖到图片边缘后的「溢出量」：到边后仍继续拖就翻页
                        var overScroll = 0f
                        var edgeFired = false
                        var singleJob: Job? = null

                        // 双击：以点击处为锚点放大/复位（视口坐标：offset = pos*(1-scale)）；复位同时清旋转
                        fun toggleZoomAt(pos: Offset) {
                            if (scale.value > 1.001f) {
                                scale.value = 1f
                                offset.value = Offset.Zero
                                rotation.value = 0f
                            } else {
                                val s = 2.5f
                                scale.value = s
                                offset.value = clampOffset(s, pos * (1f - s))
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            // 非当前页：不消费、不改共享状态 → 交给 Pager 翻页
                            if (!active) continue
                            val pressed = event.changes.filter { it.pressed }

                            if (pressed.isEmpty()) {
                                if (wasDown) {
                                    wasDown = false
                                    prevCentroid = null
                                    prevDist = 0f
                                    prevCount = 0
                                    prevAngle = 0f
                                    twistConsumed = false
                                    twistAccum = 0f
                                    overScroll = 0f
                                    edgeFired = false
                                    if (!moved) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapTime < 300 &&
                                            (downPos - lastTapPos).getDistance() < 60f
                                        ) {
                                            // 双击 → 缩放（并复位旋转），取消待触发单击
                                            singleJob?.cancel()
                                            singleJob = null
                                            toggleZoomAt(downPos)
                                            lastTapTime = 0
                                        } else {
                                            lastTapTime = now
                                            lastTapPos = downPos
                                            // 延迟 300ms 判定为单击：进入/退出沉浸模式
                                            singleJob?.cancel()
                                            singleJob = launch {
                                                delay(300)
                                                onSingleTap()
                                            }
                                        }
                                    }
                                }
                                continue
                            }

                            if (!wasDown) {
                                wasDown = true
                                downPos = toUnrot(pressed[0].position)
                                moved = false
                                prevCentroid = null
                                prevDist = 0f
                                prevCount = pressed.size
                                prevAngle = 0f
                                twistConsumed = false
                                twistAccum = 0f
                                overScroll = 0f
                                edgeFired = false
                            }

                            when {
                                pressed.size >= 2 -> {
                                    moved = true
                                    // 扭转角用屏幕坐标（物理扭转角）；缩放/平移用未旋转坐标
                                    val ptsScreen = pressed.map { it.position }
                                    val pts = pressed.map { toUnrot(it.position) }
                                    val centroid =
                                        pts.fold(Offset.Zero) { a, b -> a + b } / pts.size.toFloat()
                                    val dist = (pts[0] - pts[1]).getDistance()
                                    val oldScale = scale.value
                                    if (prevDist > 0f) {
                                        val newScale =
                                            (oldScale * (dist / prevDist)).coerceIn(1f, 5f)
                                        if (newScale != oldScale) {
                                            // 视口坐标下的焦点公式：offset = centroid*(1-r) + offset*r
                                            val r = newScale / oldScale
                                            scale.value = newScale
                                            offset.value = clampOffset(
                                                newScale,
                                                centroid * (1f - r) + offset.value * r
                                            )
                                        }
                                        // 两指整体平移（缩放同时也能拖动）
                                        val pan = centroid - (prevCentroid ?: centroid)
                                        if (pan != Offset.Zero) {
                                            offset.value = clampOffset(
                                                scale.value,
                                                offset.value + pan
                                            )
                                        }
                                    }
                                    // 双指扭转旋转：限制为 90° 步进。阈值看「整段手势的累计扭转角」，
                                    // 累计扭过 30° 才触发（阈值过低的体验是「太灵敏」），按方向
                                    // 转 90° 并锁住本次手势（抬手解锁）。
                                    val angle = atan2(
                                        (ptsScreen[1].y - ptsScreen[0].y).toDouble(),
                                        (ptsScreen[1].x - ptsScreen[0].x).toDouble()
                                    )
                                    if (prevAngle != 0f && !twistConsumed) {
                                        var d = angle - prevAngle
                                        while (d > Math.PI) d -= 2 * Math.PI
                                        while (d < -Math.PI) d += 2 * Math.PI
                                        twistAccum += Math.toDegrees(d).toFloat()
                                        if (Math.abs(twistAccum) > 30f) {
                                            rotation.value += if (twistAccum > 0) 90f else -90f
                                            twistConsumed = true
                                        }
                                    }
                                    prevAngle = angle.toFloat()
                                    prevCentroid = centroid
                                    prevDist = dist
                                    prevCount = pressed.size
                                    event.changes.forEach { it.consume() }
                                }

                                pressed.size == 1 -> {
                                    val p = toUnrot(pressed[0].position)
                                    if ((p - downPos).getDistance() > 10f) moved = true
                                    if (edgeFired) {
                                        // 已触发翻页：剩余滑动继续消费，避免同一手势被 Pager 再翻一张
                                        prevCentroid = p
                                        prevCount = 1
                                        pressed[0].consume()
                                    } else if (scale.value > 1.001f) {
                                        // 2指→1指松手当帧重置参考点；其余帧 1:1 跟手（视口坐标，无需乘 scale）
                                        if (prevCount < 2 && prevCentroid != null) {
                                            val target = offset.value + (p - prevCentroid!!)
                                            val clamped = clampOffset(scale.value, target)
                                            // 已经拖到边缘：把「还想继续拖」的量累积起来，超过阈值就翻页。
                                            // 关键：over-drag 是图片坐标系向量，要先转回屏幕位移、只取
                                            // 横向分量——旋转 90° 后两套坐标横纵互换，直接用 x 会把
                                            // 「上下拖」误判成翻页（用户反馈）。阈值屏宽 30%（≥140px）。
                                            if (!edgeFired) {
                                                val overX = toScreenDelta(target - clamped).x
                                                if (overX != 0f) {
                                                    overScroll += overX
                                                    val threshold =
                                                        (sizeState.value.width * 0.30f)
                                                            .coerceAtLeast(140f)
                                                    if (overScroll > threshold) {
                                                        // 手指继续往右拖 → 上一张
                                                        onEdgeSwipe(-1)
                                                        edgeFired = true
                                                    } else if (overScroll < -threshold) {
                                                        // 手指继续往左拖 → 下一张
                                                        onEdgeSwipe(1)
                                                        edgeFired = true
                                                    }
                                                } else {
                                                    overScroll = 0f
                                                }
                                            }
                                            offset.value = clamped
                                        }
                                        prevCentroid = p
                                        prevCount = 1
                                        pressed[0].consume()
                                    } else {
                                        // 原比例：不消费，交给 Pager 横向翻页
                                        prevCentroid = null
                                        prevCount = 1
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        if (bmp != null) {
            // 外层 Box 承载「双指捏合旋转」：绕视口中心旋转，不影响内部捏合缩放/平移坐标系。
            // 旋转到 90°/270° 时补一个适配缩放：把旋转后的包围盒重新撑到视口能容纳的最大尺寸，
            // 避免横图竖转后被限制在原宽度里显得很小、四周大片黑边。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = if (active) rotation.value else 0f
                        val r = (((if (active) rotation.value else 0f) % 360f) + 360f) % 360f
                        val rotated = (r > 45f && r < 135f) || (r > 225f && r < 315f)
                        val vw = sizeState.value.width.toFloat()
                        val vh = sizeState.value.height.toFloat()
                        if (rotated && vw > 0f && vh > 0f) {
                            val fit0 = minOf(vw / bmp.width, vh / bmp.height)
                            val dw = bmp.width * fit0
                            val dh = bmp.height * fit0
                            if (dw > 0f && dh > 0f) {
                                val k = minOf(vw / dh, vh / dw)
                                scaleX = k
                                scaleY = k
                            }
                        }
                    }
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = photo.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { sizeState.value = it }
                        .graphicsLayer {
                            // 左上原点：screen(local) = local*scale + offset，锚点公式才成立
                            transformOrigin = TransformOrigin(0f, 0f)
                            // 只有当前页应用缩放/平移；相邻页保持原比例 → 不会被一起放大叠加
                            val s = if (active) scale.value else 1f
                            val o = if (active) offset.value else Offset.Zero
                            scaleX = s
                            scaleY = s
                            translationX = o.x
                            translationY = o.y
                        }
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

/** 普通态底部工具条 */
@Composable
private fun ViewerToolBar(
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ViewerAction(Icons.Filled.Edit, "编辑", onEdit)
        ViewerAction(Icons.Filled.Download, "保存", onSave)
        ViewerAction(Icons.Filled.Delete, "删除", onDelete, tint = Color(0xFFFF6B6B))
    }
}

/** 选择态底部：胶片条 + 批量条 */
@Composable
private fun ViewerSelectBar(
    items: List<LocalPhoto>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onBatchSave: () -> Unit,
    onBatchDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.file.absolutePath }) { photo ->
                val path = photo.file.absolutePath
                val picked = path in selected
                var thumb by remember(photo.file.absolutePath) { mutableStateOf<Bitmap?>(null) }
                val ctx = LocalContext.current
                LaunchedEffect(photo.file.absolutePath) {
                    withContext(Dispatchers.IO) { thumb = BitmapIo.decode(photo.file, 160) }
                }
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onToggle(path) }
                ) {
                    thumb?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = photo.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (picked) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f))
                        )
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(18.dp)
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onSelectAll) {
                Text(
                    if (selected.size == items.size) "取消全选" else "全选",
                    color = Color.White
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onBatchDelete,
                enabled = selected.isNotEmpty()
            ) {
                Text("批量删除", color = if (selected.isNotEmpty()) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.4f))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onBatchSave,
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("批量保存")
            }
        }
    }
}

@Composable
private fun ViewerAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}
