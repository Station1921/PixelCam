package com.station1921.pixelcam.ui.screen

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.station1921.pixelcam.data.PhotoStore
import com.station1921.pixelcam.transfer.CameraProp
import com.station1921.pixelcam.transfer.CameraProps
import com.station1921.pixelcam.transfer.propLabel
import com.station1921.pixelcam.transfer.toRows
import com.station1921.pixelcam.ui.AppSnackbarHost
import com.station1921.pixelcam.ui.LiveViewStatus
import com.station1921.pixelcam.ui.LiveViewUi
import com.station1921.pixelcam.ui.TransferViewModel
import com.station1921.pixelcam.util.CameraSettings
import com.station1921.pixelcam.util.CameraSettingsHolder
import com.station1921.pixelcam.util.readExif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** 底部 4 个主 tab 之一：拍照（全屏取景 + 屏幕辅助线 + 参数 + 快门 + 水平仪） */
@Composable
fun CaptureScreen(
    vm: TransferViewModel,
    onExit: () -> Unit,
    onGoGallery: () -> Unit,
    onImported: () -> Unit
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.cameraSettings.collectAsStateWithLifecycle()
    val liveView by vm.liveView.collectAsStateWithLifecycle()
    val lastFrame by vm.lastFrame.collectAsStateWithLifecycle()
    val shotPreview by vm.shotPreview.collectAsStateWithLifecycle()
    val shotSaving by vm.shotSaving.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 调相机参数（白平衡 / ISO / 快门等写相机）做轻量防抖：拖动刻度时每个档位都会触发一次
    // WiFi 往返 + 回读全部属性，连续触发会卡顿、高亮还跟不上手指。这里取消上一次、120ms 后才真正下发，
    // 拖动过程只发「最终档位」，配合 CameraStyleSlider 的本地乐观高亮，滑动即跟手（#130）。
    var propWriteJob by remember { mutableStateOf<Job?>(null) }
    val onPropChangeDebounced: (String, String, String) -> Unit = { name, raw, label ->
        propWriteJob?.cancel()
        propWriteJob = scope.launch {
            delay(120)
            vm.setProp(name, raw, label) { msg ->
                if (msg != null) scope.launch { snackbar.showSnackbar(msg) }
            }
        }
    }

    // —— 实时取景 ——
    // 进入本页就开始向相机要取景画面（目前只有奥林巴斯/OM System 的 WiFi OPC 协议做了实现）；
    // 离开本页自动停流，并把相机切回播放模式，好让监视轮询继续收照片。
    LaunchedEffect(ui.connected, ui.model) {
        if (ui.connected && vm.canLiveView()) vm.startLiveView() else vm.stopLiveView()
    }
    DisposableEffect(Unit) {
        onDispose { vm.stopLiveView() }
    }

    // —— 屏幕常亮（#96）：拍照页期间系统不休眠，退出本页恢复 ——
    DisposableEffect(activity) {
        val win = activity?.window
        win?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // —— 页面方向：竖屏锁定（这是很早就定死的约定，保持不变）——
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // —— 只转取景画面（#98 澄清后的正确做法）——
    // 横握手机时，把相机推来的横幅画面在原位转正（横屏持机时画面明显更大），
    // rotationZ = 360 - 持机角。这个持机角同时也是参数标签/刻度文字的旋转角度源。
    var frameRotation by remember { mutableStateOf(0f) }
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                // 迟滞：与当前档位中心角（0/90/180/270）相差超过 55° 才允许切换，
                // 避免手机稍微转个角度（或在档位边界附近）画面就来回翻（用户反馈灵敏度过高）
                val center = when (frameRotation) { 0f -> 0; 90f -> 90; 180f -> 180; else -> 270 }
                val diff = Math.abs(((orientation - center + 180 + 360) % 360) - 180)
                if (diff <= 55) return
                // 持机角归到 0/90/180/270，再换算成画面需要的顺时针补正角
                frameRotation = when (orientation) {
                    in 45..134 -> 270f
                    in 135..224 -> 180f
                    in 225..314 -> 90f
                    else -> 0f
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    // 参数标签（ISO/快门/光圈/白平衡/顶部信息卡）与刻度文字随手机持机方向旋转，
    // 与变焦滑块同一角度源（旧代码曾硬编码 0 导致文字不转，用户反馈恢复）
    val rotAngle = frameRotation

    // 半透明卡片底色（相机取景风格）
    val cardBg = Color.Black.copy(alpha = 0.42f)

    // 当前来源是否支持实时取景（只在连接状态变化时重算一次）
    val lvCanLive = remember(ui.connected, ui.model) { vm.canLiveView() }

    // —— 相机实时状态（直读相机 get_camprop，不是等照片 EXIF）——
    val props by vm.cameraProps.collectAsStateWithLifecycle()
    val capacity by vm.capacity.collectAsStateWithLifecycle()
    val zoomHint by vm.zoomHint.collectAsStateWithLifecycle()

    // 在本页期间轮询相机状态；离开本页停掉
    LaunchedEffect(ui.connected, lvCanLive) {
        if (ui.connected && lvCanLive) vm.startPropsPolling() else vm.stopPropsPolling()
    }

    // —— 进页先用图库最新一张**原图**的 EXIF 预填参数卡 ——
    // 光圈在 OPC 属性表里没有实时接口（只能从照片 EXIF 读），不预填的话一进页面
    // 光圈/ISO/快门全是「—」，要等这次连接收到新照片才有值——这就是
    // 「拍照页进去不显示光圈大小」的根因。参数卡空着才补，不会盖掉本次会话的新数据。
    LaunchedEffect(Unit) {
        if (vm.cameraSettings.value.isEmpty()) {
            withContext(Dispatchers.IO) {
                PhotoStore.listImported(context)
                    .firstOrNull { !it.ai }
                    ?.let { p -> runCatching { readExif(p.file) }.getOrNull() }
                    ?.let { CameraSettingsHolder.applyExif(it) }
            }
        }
    }
    // 手动变焦镜头的说明只弹一次
    LaunchedEffect(zoomHint) {
        val h = zoomHint ?: return@LaunchedEffect
        snackbar.showSnackbar(h)
        vm.clearZoomHint()
    }

    // 点屏对焦的 AF 框：出现约 0.7 秒后自动消失
    var afBox by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(afBox) {
        if (afBox != null) {
            delay(700)
            afBox = null
        }
    }
    // 「全部参数」面板（把相机能读到的属性整张列出来）
    var showAllProps by remember { mutableStateOf(false) }

    // 顶部 4 项：值取自相机属性表，「能不能改」也由相机自己说了算
    // （标了 getset 且给出可选值才做成可点，见 TopParam）
    val topParams = remember(props, settings.focal) {
        listOf(
            TopParam("模式", props.map["takemode"], "—"),
            TopParam("曝光补偿", props.map["expcomp"], "—"),
            TopParam("焦距", props.map["focalvalue"], settings.focal),
            TopParam("驱动", props.map["drivemode"], "—")
        )
    }

    // 参数滑块：点参数卡打开；再点屏幕任意处关闭
    var sheetParam by remember { mutableStateOf<ParamKind?>(null) }

    // 顶部「可调」项：点开在这排卡片**下方**弹刻度条（与底部同一套刻度，互不遮挡）
    var topParam by remember { mutableStateOf<TopParam?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ——— 取景画面（最底层）：相机推来的实时画面 + 三分辅助线 ———
        // frameRotation：横握手机时只把画面转正（铺得更满），辅助线与页面其余元素不转
        // 取景握手期间 liveView.frame 还没到，用 lastFrame（上一帧占位）顶上，避免黑屏空窗感
        Viewfinder(frame = liveView.frame ?: lastFrame, rotation = frameRotation)

        // 点屏层：点空白处关闭参数滑块；点取景画面 = 点屏对焦
        // key 必须是 Unit：之前以 liveView.frame 为 key，取景每来一帧（约 10fps）就重启一次
        // 手势协程，进行中的点击被频繁打断——这就是「对焦时灵时不灵」「点空白关不掉刻度条」的根因。
        // frame/sheetParam/topParam/frameRotation 都在回调里实时读，不需要进 key。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        // 有刻度条开着时，第一次点屏先把它收起来（避免误触发对焦）
                        if (sheetParam != null || topParam != null) {
                            sheetParam = null
                            topParam = null
                            return@detectTapGestures
                        }
                        val f = liveView.frame ?: return@detectTapGestures
                        if (!lvCanLive) return@detectTapGestures
                        // 画面按 ContentScale.Fit 铺（graphicsLayer 只转显示、布局矩形不变），
                        // 可能有留黑边：先把点击点映射到「画面内」的归一化坐标（0..1），
                        // 相机要的是它自己那套画面坐标系，换算交给 ViewModel
                        val vw = size.width.toFloat()
                        val vh = size.height.toFloat()
                        val fit = minOf(vw / f.width.toFloat(), vh / f.height.toFloat())
                        val dw = f.width * fit
                        val dh = f.height * fit
                        val left = (vw - dw) / 2f
                        val top = (vh - dh) / 2f
                        // 画面被转了 frameRotation（0/90/180/270）且 90/270 时等比放大了 s：
                        // 先把点击点绕中心逆旋转、再除以 s，回到「未旋转」的显示矩形里做归一化，
                        // 否则横握时对焦点会打错位置。显示变换：screen = center + s·R·(img偏移)
                        val s = fitScale(vw, vh, f.width, f.height, frameRotation)
                        val cx = left + dw / 2f
                        val cy = top + dh / 2f
                        val dx = pos.x - cx
                        val dy = pos.y - cy
                        val theta = Math.toRadians(frameRotation.toDouble())
                        val cos = kotlin.math.cos(theta).toFloat()
                        val sin = kotlin.math.sin(theta).toFloat()
                        val ox = (dx * cos + dy * sin) / s
                        val oy = (-dx * sin + dy * cos) / s
                        val nx = (ox / dw + 0.5f).coerceIn(0f, 1f)
                        val ny = (oy / dh + 0.5f).coerceIn(0f, 1f)
                        afBox = pos
                        vm.focusAt(nx, ny) { msg ->
                            if (msg != null) scope.launch { snackbar.showSnackbar(msg) }
                        }
                    }
                }
        )

        // 点屏对焦的 AF 框（在取景画面之上、其它 UI 之下，0.7 秒后自动消失）
        // 尺寸 28dp：接近相机菜单里「AF 目标」小框的观感，只做定位提示、不糊画面
        afBox?.let { p ->
            val half = with(LocalDensity.current) { 14.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt()) }
                    .size(28.dp)
                    .border(1.dp, Color(0xFF8CE054), RoundedCornerShape(3.dp))
            )
        }

        // ——— 取景状态提示 / 重试 ———
        // 必须放在上面的「点屏层」之后：点屏层铺满全屏且会消费点击，
        // 放在它下面的话「重试」按钮永远点不到。
        if (liveView.frame == null) {
            LiveViewHint(
                lv = liveView,
                connected = ui.connected,
                canLiveView = vm.canLiveView(),
                onRetry = { vm.startLiveView() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // 让开顶部「标题 + 状态 + 参数行」；顶部刻度条展开时（更高）再往下让一档
                    .padding(top = if (topParam != null) 292.dp else 186.dp, start = 24.dp, end = 24.dp)
            )
        }

        // —— 手机端水平仪已移除：相机自带的水平仪参数手机读不到，手机加速度计的水平仪
        // 对相机取景没有意义，放在画面正中央反而挡视野，故按需求去掉。

        // ——— 底部参数卡：只放「可调」项（ISO / 快门 / 光圈 / 白平衡）———
        // 只读项（模式 / 曝光补偿 / 焦距 / 驱动）已挪到页面顶部；两类分开后刻度条
        // 与可调行同属一列、永远排在它正上方，不再出现「刻度盖住标签」的问题。
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 112.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // —— 变焦滑轨：像手机相机一样「推拉」变焦（只对电动变焦镜头有效）——
            if (ui.connected && lvCanLive) {
                // 圆钮显示**真实焦距（mm）**：focalvalue 是相机报的变焦位置编码
                // （电动变焦镜头多为 1/10 mm，如 140→14mm），编码不在合理范围就退回
                // EXIF 的真实物理焦距——用户反馈过不该显示 5.6 这种非 mm 数值。
                val focal = focalMmText(props.raw("focalvalue")) ?: settings.focal
                ZoomSlider(
                    focal = focal,
                    // 随手机横竖旋转（与取景画面同一角度源；旧代码传恒 0 的 rotAngle，永远不转）
                    rotation = frameRotation,
                    onPress = { tele -> vm.zoomPress(tele) },
                    onRelease = { vm.zoomRelease() }
                )
                Spacer(Modifier.height(10.dp))
            }

            // —— 刻度条：点哪张卡就在这行「上方」展开，与标签行同列，天然不遮挡 ——
            sheetParam?.let { sp ->
                ParamPopover(
                    kind = sp,
                    settings = settings,
                    rotation = rotAngle,
                    props = props,
                    onChange = { iso, aperture, shutter ->
                        vm.setCameraSetting(iso = iso, aperture = aperture, shutter = shutter)
                    },
                    onWbChange = { wb ->
                        vm.setCameraSetting(whiteBalance = wb)
                    },
                    onPropChange = { name, raw, label ->
                        onPropChangeDebounced(name, raw, label)
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            // —— 可调行：ISO / 快门 / 光圈 / 白平衡（点一下弹出刻度条）——
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ParamChip("ISO", props.value("isospeedvalue") ?: settings.iso, Modifier.weight(1f), cardBg, rotation = rotAngle) { topParam = null; sheetParam = toggleParam(sheetParam, ParamKind.ISO) }
                ParamChip("快门", props.value("shutspeedvalue") ?: settings.shutter, Modifier.weight(1f), cardBg, rotation = rotAngle) { topParam = null; sheetParam = toggleParam(sheetParam, ParamKind.SHUTTER) }
                ParamChip("光圈", settings.aperture, Modifier.weight(1f), cardBg, rotation = rotAngle) { topParam = null; sheetParam = toggleParam(sheetParam, ParamKind.APERTURE) }
                ParamChip("白平衡", props.value("wbvalue") ?: settings.wbLabel(), Modifier.weight(1f), cardBg, rotation = rotAngle) { topParam = null; sheetParam = toggleParam(sheetParam, ParamKind.WB) }
            }
            Spacer(Modifier.height(6.dp))
            // 「全部参数」文字入口已按需求去掉（入口挪到顶部参数行末端的小图标）
        }

        // ——— 快门：屏幕正下方中央（随窗口旋转，即物理底部、大拇指可达）———
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) {
            ShutterButton(onShot = { fireShutter(vm, scope, snackbar) })
        }

        // ——— 顶部 UI（退出 / 图库 / 标题），随窗口旋转 ———
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = 10.dp, start = 14.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { onExit() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "退出",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        // —— 右上角：「全部参数」+「图库」两个圆形图标并列 ——
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { topParam = null; sheetParam = null; showAllProps = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = "全部参数",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onGoGallery() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = "图库",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(top = 56.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(
                "拍照",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    !ui.connected -> "未连接 · 参数取自相机照片"
                    liveView.status == LiveViewStatus.STREAMING ->
                        "已连接 · ${ui.model.ifBlank { "相机" }} · 取景中：点画面即对焦，快门由相机拍摄"
                    lvCanLive -> "已连接 · ${ui.model.ifBlank { "相机" }} · 遥控快门可用（取景未连接）"
                    else -> "已连接 · ${ui.model.ifBlank { "相机" }}（该机型不支持手机取景）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            // —— 顶部只读/可调项：模式 / 曝光补偿 / 焦距 / 驱动 ——
            // 能不能改由相机自己说（属性的 getset 标记 + 可选值列表）：能改的做成可点、
            // 点开在这排卡片**下方**弹刻度条（与底部同一套效果）；不能改的仍是纯信息。
            if (ui.connected) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    topParams.forEach { t ->
                        MetaChip(
                            label = t.label,
                            value = t.value,
                            modifier = Modifier.weight(1f),
                            rotation = rotAngle,
                            editable = t.editable,
                            onClick = {
                                // 与底部弹出的刻度条互斥，免得两把刻度同时挂在屏幕上
                                sheetParam = null
                                topParam = if (topParam?.label == t.label) null else t
                            }
                        )
                    }
                }
                topParam?.let { t ->
                    Spacer(Modifier.height(8.dp))
                    PropSlider(
                        label = t.label,
                        options = t.options,
                        currentIndex = t.currentIndex,
                        rotation = rotAngle,
                        onPick = { i ->
                            propWriteJob?.cancel()
                            propWriteJob = scope.launch {
                                delay(120)
                                vm.setProp(t.name, t.rawForIndex(i), t.label) { msg ->
                                    if (msg != null) scope.launch { snackbar.showSnackbar(msg) }
                                }
                            }
                        }
                    )
                }
            }
        }

        // 「全部参数」面板：把相机能读到的属性整张列出来
        if (showAllProps) {
            AllPropsPanel(
                props = props,
                capacity = capacity,
                onClose = { showAllProps = false }
            )
        }

        // 提示浮层
        AppSnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )

        // 快门回放覆盖层：刚拍的照片盖住「切播放模式收图」时取景短暂断开的空窗，
        // 像真相机回放约 1.5s；回放图还没拉到时显示「正在保存到图库…」过渡（#104），
        // 两种形态都让用户明确知道「在保存」，不会误以为拍完就掉线。
        if (shotPreview != null || shotSaving) {
            val bmp = shotPreview
            if (bmp != null) {
                LaunchedEffect(bmp) {
                    // 最短回放 1.2s（真相机手感）；之后**等取景真正恢复推流再退场**（最多再等 4s），
                    // 不再是固定 1.5s 一刀切——以前回放关了、取景还没回来，中间会有一下黑屏断层（#114）
                    delay(1200)
                    withTimeoutOrNull(4000) {
                        vm.liveView.first { it.status == LiveViewStatus.STREAMING }
                    }
                    vm.clearShotPreview()
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "刚拍摄的照片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        "已拍摄",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                            .padding(top = 8.dp)
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        Text("正在保存到图库…", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 点同一张卡 = 关闭气泡；点别的卡 = 切到那张 */
private fun toggleParam(current: ParamKind?, next: ParamKind): ParamKind? =
    if (current == next) null else next

private fun fireShutter(
    vm: TransferViewModel,
    scope: CoroutineScope,
    snackbar: SnackbarHostState
) {
    // USB 快门很快；奥林巴斯 WiFi 快门要多轮 HTTP（切模式→快门→切回），结果异步回来
    vm.triggerCapture { ok, message ->
        scope.launch {
            snackbar.showSnackbar(
                when {
                    ok -> "已遥控拍摄，正在自动保存到图库…"
                    message != null -> message
                    else -> "相机不支持远程快门，请在相机上按下快门，照片会自动出现在「监视」"
                }
            )
        }
    }
}

private enum class ParamKind { ISO, APERTURE, SHUTTER, WB }

/**
 * 画面在容器内经过 [rotation] 旋转后的最终等比缩放。
 * 0/180° → 1（原 Fit 大小）；90/270° → 旋转后以 min(容器宽/画面高, 容器高/画面宽) 放大，
 * 让转正的画面尽量铺满屏幕、又不变形不裁切（#109）。点屏对焦的坐标映射用同一个函数反算。
 */
private fun fitScale(containerW: Float, containerH: Float, frameW: Int, frameH: Int, rotation: Float): Float {
    if (frameW <= 0 || frameH <= 0 || containerW <= 0f || containerH <= 0f) return 1f
    val fit = minOf(containerW / frameW, containerH / frameH)
    val dw = frameW * fit
    val dh = frameH * fit
    val r = (((rotation.roundToInt() % 360) + 360) % 360)
    return if (r == 90 || r == 270) minOf(containerW / dh, containerH / dw) else 1f
}

/**
 * 取景画面：相机推来的实时画面铺满全屏，上面叠三分辅助线。
 * 中心十字已移除——水平仪也在屏幕正中央，两者叠在一起互相干扰、也读不清。
 */
@Composable
private fun Viewfinder(frame: Bitmap?, rotation: Float = 0f) {
    // 容器像素尺寸：算「旋转后再等比放大多少」要用（#109）
    var container by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF05060A))
            .onSizeChanged { container = it }
    ) {
        frame?.let {
            val s = fitScale(
                container.width.toFloat(), container.height.toFloat(),
                it.width, it.height, rotation
            )
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "实时取景画面",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    // 只转画面本身：横屏持机时画面转正；90/270 再等比放大铺满容器（不变形不裁切）
                    .graphicsLayer {
                        rotationZ = rotation
                        scaleX = s
                        scaleY = s
                    }
            )
        }
        val line = Color.White.copy(alpha = 0.22f)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val t = 1.dp.toPx()
            drawLine(line, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = t)
            drawLine(line, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), strokeWidth = t)
            drawLine(line, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = t)
            drawLine(line, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), strokeWidth = t)
        }
    }
}

/**
 * 取景状态条：没有画面时告诉用户卡在哪一层，并给一个重试入口。
 *
 * 奥林巴斯的取景是「相机把画面用 RTP 推到手机 UDP 端口」，链路比普通 HTTP 长
 * （命令列表 → 切拍摄模式 → 开推流 → 收包），所以失败原因要分开讲，不然只能干瞪眼。
 */
@Composable
private fun LiveViewHint(
    lv: LiveViewUi,
    connected: Boolean,
    canLiveView: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title: String
    val detail: String
    var retryable = false

    when {
        !connected -> {
            title = "未连接相机"
            detail = "先到「相机连接」连上相机（奥林巴斯走 WiFi），再回到这里就能看到实时取景画面。"
        }
        !canLiveView -> {
            title = "这台相机不支持手机取景"
            detail = "实时取景目前只对接了奥林巴斯 / OM System 的 WiFi 协议；" +
                "USB 与无线 SD 卡连接只支持传照片和接收新拍的照片。"
        }
        lv.status == LiveViewStatus.STARTING -> {
            title = "正在取景…"
            detail = lv.message.ifBlank { "正在向相机请求画面" }
        }
        lv.status == LiveViewStatus.ERROR -> {
            title = "取景失败"
            detail = lv.message
            retryable = true
        }
        else -> {
            title = "取景未开始"
            detail = "点下方按钮重新请求画面。"
            retryable = true
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (lv.status == LiveViewStatus.STARTING) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (retryable) {
            TextButton(onClick = onRetry) {
                Text("重试取景", color = Color.White)
            }
        }
    }
}

/** 快门：参照手机原生相机——半透明白色色块（白色圆环 + 半透明白色填充），无图标。
 *  位置由父层 BottomCenter 决定（随窗口旋转停在物理底部，即充电口侧、大拇指可达）。 */
@Composable
private fun ShutterButton(onShot: () -> Unit) {
    Box(
        Modifier
            .size(86.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.28f))
            .border(4.dp, Color.White, CircleShape)
            .clickable { onShot() }
    )
}

/**
 * 把相机的变焦位置编码翻成 mm 显示。
 *
 * `focalvalue` 在 OPC 里是电动变焦镜头的变焦位置，多数固件按 **1/10 mm** 编码
 * （140→14mm、420→42mm）。编码在 80~1000 这个物理焦距合理范围才换算；
 * 是别的奇怪编码（下标、步进值等）就返回 null，让调用方退回 EXIF 的真实焦距，
 * 绝不把看不懂的数值直接摆到界面上。
 */
private fun focalMmText(raw: String?): String? {
    val v = raw?.trim()?.toDoubleOrNull() ?: return null
    if (v < 80.0 || v > 1000.0) return null
    val mm = v / 10.0
    return if (mm % 1.0 == 0.0) "${mm.toInt()}mm" else "$mm"
}

/**
 * 变焦滑轨：像手机相机那样「推拉」变焦（#126）。
 *
 * 一条胶囊滑轨，W（广角）在左、T（长焦）在右，拇指圆钮居中显示当前焦距：
 * 按住往右推 = 连续拉近、往左拉 = 连续拉远，推得越偏变焦持续进行，
 * 拉回中间（或松手）就停。方向切换会先停再反向，避免指令打架。
 * 只对电动变焦镜头有效；手动变焦环镜头相机会忽略指令，界面随后给出提示。
 */
@Composable
private fun ZoomSlider(
    focal: String,
    rotation: Float,
    onPress: (tele: Boolean) -> Unit,
    onRelease: () -> Unit
) {
    val trackW = 190.dp
    val thumbSize = 44.dp
    val density = LocalDensity.current
    val maxPx = with(density) { ((trackW - thumbSize) / 2).toPx() } * 0.92f
    val deadZone = with(density) { 6.dp.toPx() }

    var dragging by remember { mutableStateOf(false) }
    var thumbX by remember { mutableStateOf(0f) }        // 拇指相对中心的偏移（px）
    var dir by remember { mutableStateOf<Boolean?>(null) } // 当前变焦方向（null=停）

    fun setDirection(tele: Boolean?) {
        if (tele == dir) return
        if (dir != null) onRelease()   // 反向/停止前先把相机当前的变焦移动停掉
        if (tele != null) onPress(tele)
        dir = tele
    }

    val thumbAnim by animateIntAsState(
        targetValue = if (dragging) thumbX.roundToInt().coerceIn(-maxPx.roundToInt(), maxPx.roundToInt()) else 0,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "zoomThumb"
    )

    Box(
        Modifier
            .width(trackW)
            .height(thumbSize)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDrag = { change, drag ->
                        change.consume()
                        thumbX = (thumbX + drag.x).coerceIn(-maxPx, maxPx)
                        setDirection(
                            when {
                                thumbX > deadZone -> true
                                thumbX < -deadZone -> false
                                else -> null
                            }
                        )
                    },
                    onDragEnd = {
                        dragging = false
                        thumbX = 0f
                        setDirection(null)
                    },
                    onDragCancel = {
                        dragging = false
                        thumbX = 0f
                        setDirection(null)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 中线刻度 + 两端 W / T 标记
        Box(
            Modifier
                .width(1.dp)
                .height(16.dp)
                .background(Color.White.copy(alpha = 0.35f))
        )
        Text(
            "W",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .graphicsLayer { rotationZ = rotation }
        )
        Text(
            "T",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .graphicsLayer { rotationZ = rotation }
        )
        // 拇指：显示当前焦距，跟手推拉，松手弹回中间
        Box(
            Modifier
                .offset { IntOffset(thumbAnim, 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (dragging) 0.28f else 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.40f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                focal,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { rotationZ = rotation }
            )
        }
    }
}

/**
 * 「全部参数」面板：把相机能读到的属性整张列出来。
 *
 * 上排是整理过的常用项（中文标签、枚举已翻译）；下面附上相机报告的全部原始属性——
 * 机型不同能读到的项目不一样（比如艺术滤镜、数码远距增距只在部分机型有），
 * 全列出来用户才知道这台相机到底给了什么。带「可写」标记的是 OPC 允许手机改的项。
 */
@Composable
private fun AllPropsPanel(
    props: CameraProps,
    capacity: String,
    onClose: () -> Unit
) {
    val curated = remember(props) { props.toRows() }
    val all = remember(props) { props.map.values.sortedBy { it.name } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Color(0xFF14161B))
                // 面板内部吞掉点击，别让点按穿透到底部遮罩把面板关了
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "相机参数 · 实时读取",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (curated.isEmpty()) {
                    Text(
                        "相机还没返回可读参数。若刚连上，等几秒让它读一次；" +
                            "仍为空就到「相机连接」页点「相机诊断」，看看 get_camprop 接口是否可用。",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                } else {
                    curated.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { row ->
                                PropCell(row.label, row.value, Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (capacity.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    PropCell("卡剩余可拍", "$capacity 张", Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "相机报告的属性（原始值）",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                all.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            propLabel(p.name),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            p.label(),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (p.settable) {
                            Text(
                                " 可写",
                                color = Color(0xFF8CE054),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 面板里的一格参数 */
@Composable
private fun PropCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 顶部参数小卡：模式 / 曝光补偿 / 焦距 / 驱动。
 *
 * 两种形态由相机决定（见 [TopParam]）：相机能写（getset + 有可选值）的做成**可点**——
 * 底色稍亮、值后面带一个 ▾，点开在下面弹刻度条；不能写的保持纯信息展示。
 */
@Composable
private fun MetaChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    editable: Boolean = false,
    onClick: () -> Unit = {}
) {
    Column(
        modifier
            .then(if (rotation != 0f) Modifier.graphicsLayer { rotationZ = rotation } else Modifier)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (editable) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.32f)
            )
            .then(if (editable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 5.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
        Spacer(Modifier.height(1.dp))
        Text(
            if (editable) "$value ▾" else value,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = Color.White,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * 顶部一项参数：直接拽着相机给的 [CameraProp]，所以
 * 「能不能改」（settable + 有可选值）和「下标怎么换算回相机值」都用相机自己的数据，
 * 不靠代码里写死机型规则 —— 同一台相机换到 A/S/M 后可写项会变，这里也就跟着变。
 */
private data class TopParam(
    val label: String,
    val prop: CameraProp?,
    /** 相机读不到时的兜底值（焦距可以退回照片 EXIF） */
    val fallback: String
) {
    /** OPC 属性名（写回相机时用），如 takemode / expcomp */
    val name: String get() = prop?.name.orEmpty()

    val value: String
        get() = prop?.label()?.takeIf { it.isNotBlank() && it != "—" } ?: fallback

    val options: List<String> get() = prop?.options.orEmpty()

    val currentIndex: Int get() = prop?.optionIndex ?: 0

    /** 可调 = 相机标了可写 + 给了可选值（镜头焦距这类只读项自然为 false） */
    val editable: Boolean get() = prop != null && prop.settable && prop.options.isNotEmpty()

    fun rawForIndex(index: Int): String = prop?.rawForIndex(index).orEmpty()
}

/** 档位数 ≤ 这个值就用与底部一致的刻度条；再多（如曝光补偿 31 档）改用横向滚动的档位条 */
private const val SLIDER_MAX_OPTIONS = 12

/**
 * 顶部可调项的刻度条。与底部参数卡复用同一个 [CameraStyleSlider]，所以观感完全一致。
 * 选项特别多时平铺会把数字挤成一团，那种情况改横向滚动条，滑动选取、点一下即写入相机。
 */
@Composable
private fun PropSlider(
    label: String,
    options: List<String>,
    currentIndex: Int,
    rotation: Float,
    onPick: (Int) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (options.size <= SLIDER_MAX_OPTIONS) {
            CameraStyleSlider(
                values = options,
                currentIndex = currentIndex,
                rotation = rotation,
                onIndexChange = onPick
            )
        } else {
            // 档位多时横向滚动选择；打开时把当前值滚到**正中**（#115），
            // 选项变化（轮询刷新）时也跟着居中，不再从表头找起
            BoxWithConstraints {
                val listState = rememberLazyListState()
                LaunchedEffect(currentIndex, options.size) {
                    if (options.isNotEmpty()) {
                        listState.scrollToItem(currentIndex.coerceIn(0, options.size - 1))
                    }
                }
                LazyRow(
                    state = listState,
                    // 两侧留白 = 半屏减半个档位宽，让选中的那档落在视觉中心
                    contentPadding = PaddingValues(horizontal = (maxWidth - 48.dp) / 2),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    itemsIndexed(options) { i, v ->
                        val sel = i == currentIndex
                        Text(
                            v,
                            fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) Color.Black else Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Color.White else Color.White.copy(alpha = 0.12f))
                                .clickable { onPick(i) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底部「可调」参数小卡：ISO / 快门 / 光圈 / 白平衡。点一下在它上方展开刻度条。
 * （只读项用顶部更紧凑的 [MetaChip]，两类分开摆，互不遮挡。）
 */
@Composable
private fun ParamChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    cardBg: Color = Color.Black.copy(alpha = 0.42f),
    rotation: Float = 0f,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .then(if (rotation != 0f) Modifier.graphicsLayer { rotationZ = rotation } else Modifier)
            .then(modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
    }
}

/**
 * 参数刻度条：iOS EV 风档位（一排圆点 + 当前档倒三角 + 下方数值）。
 * 位置由调用方决定 —— 现在紧贴在底部「可调行」上方、同一个 Column 里，
 * 所以无论刻度数值旋转后多高，都只会把可调行往下挤，不会盖住它。点屏幕任意处关闭。
 */
@Composable
private fun ParamPopover(
    kind: ParamKind,
    settings: CameraSettings,
    rotation: Float = 0f,
    props: CameraProps? = null,
    onChange: (iso: String?, aperture: String?, shutter: String?) -> Unit,
    onWbChange: (String) -> Unit = {},
    onPropChange: (name: String, raw: String, label: String) -> Unit = { _, _, _ -> }
) {
    // —— 相机可写时优先用相机自己的可选值（#105）：选中即 set_camprop 写进相机，
    //    不再只是改手机端记录。相机读不到/不可写时才退回下面的本地预设（纯记录）。
    val camProp: CameraProp? = when (kind) {
        ParamKind.ISO -> props?.map?.get("isospeedvalue")
        ParamKind.SHUTTER -> props?.map?.get("shutspeedvalue")
        ParamKind.WB -> props?.map?.get("wbvalue")
        // F 值不在 OPC 属性表里（只能从照片 EXIF 读），光圈保持本地记录
        ParamKind.APERTURE -> null
    }?.takeIf { it.settable && it.options.isNotEmpty() }
    if (camProp != null) {
        val cn = when (kind) {
            ParamKind.ISO -> "ISO"
            ParamKind.SHUTTER -> "快门"
            ParamKind.WB -> "白平衡"
            else -> kind.name
        }
        PropSlider(
            label = "$cn · 写入相机",
            options = camProp.options,
            currentIndex = camProp.optionIndex,
            rotation = rotation,
            onPick = { i ->
                onPropChange(camProp.name, camProp.rawForIndex(i), cn)
                // 本地记录同步一份，断开连接时界面不至于显示旧值
                when (kind) {
                    ParamKind.ISO -> onChange(camProp.options.getOrNull(i), null, null)
                    ParamKind.SHUTTER -> onChange(null, null, camProp.options.getOrNull(i))
                    else -> {}
                }
            }
        )
        return
    }

    // 白平衡：单行预设 + （自定义时）色温刻度
    if (kind == ParamKind.WB) {
        val kelvin = settings.whiteBalance.toIntOrNull()
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            WbPresetGrid(current = settings.whiteBalance, rotation = rotation, onPick = onWbChange)
            if (kelvin != null) {
                Spacer(Modifier.height(6.dp))
                val idx = WB_KELVIN.indexOfFirst { it.toIntOrNull() == kelvin }
                    .let { if (it < 0) WB_KELVIN.size / 2 else it }
                CameraStyleSlider(
                    values = WB_KELVIN,
                    currentIndex = idx,
                    rotation = rotation,
                    onIndexChange = { newIdx -> onWbChange(WB_KELVIN[newIdx]) }
                )
            }
        }
        return
    }

    // 档位定义。光圈覆盖常见镜头全档位（含 f22——用户镜头能收到 f22，旧表只到 11）。
    // 注意：WB 已在上面 return，这里的 when 已被编译器判定为穷尽，不能再写 else（K2 会告警）
    val levels: List<String> = when (kind) {
        ParamKind.ISO -> listOf("100", "200", "400", "800", "1600", "3200", "6400")
        ParamKind.APERTURE -> listOf("1.2", "1.4", "1.8", "2", "2.8", "4", "5.6", "8", "11", "16", "22")
        ParamKind.SHUTTER -> listOf("1/2000", "1/500", "1/125", "1/30", "1/8", "1/2", "2\"")
        ParamKind.WB -> emptyList()
    }

    // 当前档位索引（匹配当前值；不匹配则取中间档）。
    // 匹配策略：
    // 1) 文本精确匹配（"8" == "8"）；
    // 2) 数值吸附：从当前值里抠出数字，吸附到**数值最接近**的档位——
    //    EXIF 光圈可能是 f/7.6（部分镜头报实际值）、f/8.0，档位表是标准 F 系，
    //    按对数距离找最近档，保证箭头永远指在「最像的那个档」上而不是落到中间档
    //    （用户看到的「标签 F8、箭头指 4」就是匹配落空退到了中间档）。
    val currentIndex: Int = run {
        val cur = when (kind) {
            ParamKind.ISO -> settings.iso.trim()
            ParamKind.APERTURE -> settings.aperture.trim()
            ParamKind.SHUTTER -> settings.shutter.trim()
            ParamKind.WB -> ""
        }
        val exact = levels.indexOfFirst { it.equals(cur, ignoreCase = true) }
        if (exact >= 0) exact else {
            val curNum = Regex("\\d+(?:\\.\\d+)?").find(cur)?.value?.toDoubleOrNull()
            if (curNum != null && curNum > 0.0) {
                levels.map { it.toDoubleOrNull() ?: 0.0 }
                    .withIndex()
                    .minByOrNull { (_, v) -> kotlin.math.abs(kotlin.math.ln(v) - kotlin.math.ln(curNum)) }
                    ?.index
                    ?: -1
            } else -1
        }.let { if (it >= 0) it else levels.size / 2 }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        // 直接铺开刻度（无顶部数值标签）；刻度贴近下方参数卡
        CameraStyleSlider(
            values = levels,
            currentIndex = currentIndex,
            rotation = rotation,
            onIndexChange = { newIdx ->
                when (kind) {
                    ParamKind.ISO -> onChange(levels[newIdx], null, null)
                    ParamKind.APERTURE -> onChange(null, "f/${levels[newIdx]}", null)
                    ParamKind.SHUTTER -> onChange(null, null, levels[newIdx])
                    ParamKind.WB -> {}
                }
            }
        )
    }
}

/**
 * 白平衡预设：参照手机相机，一行平铺（1 行 7 格）。
 * 顺序按需求固定：阴天、阴影、荧光灯、自动、白炽灯、日光、自定义。
 * 每格用色点表示色温倾向；「自定义」用空心圈，点开后出现色温刻度（Kelvin），可滑动 / 点选。
 */
private val WB_PRESETS = listOf(
    "cloudy" to "阴天",
    "shade" to "阴影",
    "fluorescent" to "荧光灯",
    "auto" to "自动",
    "tungsten" to "白炽灯",
    "daylight" to "日光",
    "custom" to "自定义"
)

private val WB_COLORS = mapOf(
    "auto" to Color(0xFFFFFFFF),
    "daylight" to Color(0xFFFFD27F),
    "cloudy" to Color(0xFF9FC8FF),
    "tungsten" to Color(0xFFFF9A52),
    "fluorescent" to Color(0xFFB6FF9C),
    "shade" to Color(0xFF7FA8FF)
)

/** 自定义白平衡刻度：色温 Kelvin（2000–10000），滑动 / 点选改变数值 */
private val WB_KELVIN = listOf(
    "2000", "3000", "4000", "5000", "6000", "7000", "8000", "9000", "10000"
)

private const val WB_CUSTOM_DEFAULT = "5000"

/**
 * 白平衡预设行：单行平铺 7 格（阴天/阴影/荧光灯/自动/白炽灯/日光/自定义）。
 * - 选中态：暗色底 + 预设色描边（不用白底），文字加粗白色。
 * - 「自定义」选中 = 当前值为数字（Kelvin）；点它首次进入时默认 5000K。
 * - rotation：随手机物理方向旋转「文字」（刻度/色点不转），与档位条刻度旋转一致。
 */
@Composable
private fun WbPresetGrid(
    current: String,
    rotation: Float = 0f,
    onPick: (String) -> Unit
) {
    val isCustom = current.toIntOrNull() != null
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        WB_PRESETS.forEach { (key, label) ->
            val isCustomCell = key == "custom"
            val sel = if (isCustomCell) isCustom else key == current
            val dot = WB_COLORS[key] ?: Color.White
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                    .then(
                        if (sel) Modifier.border(1.5.dp, dot, RoundedCornerShape(10.dp))
                        else Modifier
                    )
                    .clickable {
                        if (isCustomCell) {
                            // 首次进入自定义 → 默认 5000K；已在自定义则保持当前值
                            if (!isCustom) onPick(WB_CUSTOM_DEFAULT)
                        } else {
                            onPick(key)
                        }
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCustomCell) {
                        // 自定义：空心圈标识（无固定色温）
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    } else {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dot)
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        label,
                        color = if (sel) Color.White else Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = if (rotation != 0f) Modifier.graphicsLayer { rotationZ = rotation } else Modifier
                    )
                }
            }
        }
    }
}

/**
 * iOS EV 风档位条：N 个等距圆点（当前档位放大 + 上方白色倒三角指示），
 * 每个刻度下方显示对应数值（当前档位高亮）。支持「点选」与「拖动」两种操作。
 * 圆点用 Canvas 画在 (i+0.5)*w/n 处，数值用等量 Row 文本对齐到同一位置。
 * 注意：刻度（圆点/三角）恒定不转，只有数值文字按 rotation 旋转（横握手机时数字立着读）。
 */
@Composable
private fun CameraStyleSlider(
    values: List<String>,
    currentIndex: Int,
    rotation: Float = 0f,
    onIndexChange: (Int) -> Unit
) {
    val n = values.size.coerceAtLeast(2)
    var shownIndex by remember { mutableStateOf(currentIndex) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(currentIndex) { if (!dragging) shownIndex = currentIndex }
    fun pick(i: Int) {
        if (i != shownIndex) {
            shownIndex = i
            onIndexChange(i)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(68.dp)            // 52→68，给数字旋转 90° 后的纵向高度留出空间
            .padding(horizontal = 6.dp)
            // ① 点击选择：detectTapGestures 会 consume 掉 down，
            //    否则事件会冒泡到外层「点屏幕任意处关闭气泡」的探测器，一点就把气泡关掉了。
            .pointerInput(n) {
                fun idx(off: Offset): Int {
                    val f = off.x / size.width * n - 0.5f
                    return f.roundToInt().coerceIn(0, n - 1)
                }
                detectTapGestures { offset -> pick(idx(offset)) }
            }
            // ② 拖动选择：detectDragGestures 的 onDragStart 必须越过 touch slop 才触发，
            //    所以「点一下不动」它根本不会回调 —— 点击选中全靠上面 ① 兜底。两者互不冲突。
            .pointerInput(n) {
                fun idx(off: Offset): Int {
                    val f = off.x / size.width * n - 0.5f
                    return f.roundToInt().coerceIn(0, n - 1)
                }
                detectDragGestures(
                    onDragStart = { dragging = true; pick(idx(it)) },
                    onDrag = { change, _ ->
                        change.consume()
                        pick(idx(change.position))
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                )
            }
    ) {
        // 圆点 + 当前档位倒三角
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            // 圆点位置：上移到 0.25h 处，让圆点/三角与底部数字（旋转 90° 后变高）之间留出明确间距
            val dotY = size.height * 0.25f
            repeat(n) { i ->
                val x = (i + 0.5f) * w / n
                val isCur = i == shownIndex
                drawCircle(
                    color = if (isCur) Color.White else Color.White.copy(alpha = 0.4f),
                    radius = (if (isCur) 4.dp else 2.dp).toPx(),
                    center = Offset(x, dotY)
                )
                if (isCur) {
                    val s = 5.dp.toPx()
                    val top = dotY - 11.dp.toPx()
                    drawPath(
                        Path().apply {
                            moveTo(x, top + s)
                            lineTo(x - s, top)
                            lineTo(x + s, top)
                            close()
                        },
                        Color.White
                    )
                }
            }
        }
        // 刻度数值：等量列，与圆点位置对齐（Box 居中，不依赖 TextAlign）。
        // bottom 14dp：数字旋转 90° 后会向下探出，留余量避免压到下方参数卡
        // 注意：n 是几何列数（≥2），values 可能只有 1 项（如个别固件 expcomp 只回一个枚举值），
        // 这里必须 getOrElse 兜底，否则 values[1] 越界 → 点开参数卡当场闪退。
        Row(
            Modifier.fillMaxSize().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            repeat(n) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .then(
                            if (rotation != 0f) Modifier.graphicsLayer { rotationZ = rotation }
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        values.getOrElse(i) { "" },
                        fontSize = if (i == shownIndex) 11.sp else 9.sp,
                        color = if (i == shownIndex) Color.White else Color.White.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }
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
