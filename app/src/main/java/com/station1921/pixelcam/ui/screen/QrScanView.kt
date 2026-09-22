package com.station1921.pixelcam.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.station1921.pixelcam.transfer.QrCodeParser
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App 内嵌实时扫码。
 *
 * 为什么自己起相机而不是调系统相机拍照：系统相机拍回来再解析要多一步、还要用户对准按快门，
 * 实时预览才是扫码该有的体验。代价是需要 CAMERA 权限——所以只在打开本页时申请，
 * 且拒绝后仍可从相册选图兜底（[onPickGallery]）。
 *
 * 解码用 ZXing 直接吃预览帧的亮度平面（YUV 的 Y 平面），不依赖 Google Play 服务，全离线。
 */
@Composable
fun QrScanView(
    hint: String?,
    onDecoded: (String) -> Unit,
    onPickGallery: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val busy = remember { AtomicBoolean(false) }
    val done = remember { AtomicBoolean(false) }
    // 分析线程解出二维码后要回到主线程改状态（Compose 状态只能在主线程写）
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var denied by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        denied = !granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            runCatching { executor.shutdown() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val view = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    previewView = view
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = runCatching { providerFuture.get() }.getOrNull()
                        if (provider == null) return@addListener
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(view.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                        analysis.setAnalyzer(executor) { image ->
                            // 解码期间丢帧：一次解码几十毫秒，不丢帧会让预览发卡
                            if (done.get()) {
                                image.close()
                                return@setAnalyzer
                            }
                            if (busy.compareAndSet(false, true)) {
                                try {
                                    val data = image.luminance()
                                    if (data != null) {
                                        val text = QrCodeParser.decodeLuminance(
                                            data, image.width, image.height
                                        )
                                        // 只认第一张成功的二维码，避免连续回调刷屏
                                        if (!text.isNullOrBlank() && done.compareAndSet(false, true)) {
                                            mainHandler.post { onDecoded(text) }
                                        }
                                    }
                                } catch (_: Throwable) {
                                    // 单帧失败无所谓，下一帧继续
                                } finally {
                                    busy.set(false)
                                    image.close()
                                }
                            } else {
                                image.close()
                            }
                        }
                        runCatching {
                            provider.unbindAll()
                            camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                }
            )
        }

        // 取景框：四周压暗、中间留亮窗 + 四角括号，让「对准」这件事一目了然
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height) * 0.68f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val mask = Color(0x99000000)
            drawRect(mask, Offset.Zero, Size(size.width, top))
            drawRect(mask, Offset(0f, top + side), Size(size.width, size.height - top - side))
            drawRect(mask, Offset(0f, top), Size(left, side))
            drawRect(mask, Offset(left + side, top), Size(size.width - left - side, side))

            val stroke = 5.dp.toPx()
            val arm = side * 0.16f
            val accent = Color(0xFF7C6CFF)
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(accent, Offset(x, y), Offset(x + dx * arm, y), stroke)
                drawLine(accent, Offset(x, y), Offset(x, y + dy * arm), stroke)
            }
            corner(left, top, 1f, 1f)
            corner(left + side, top, -1f, 1f)
            corner(left, top + side, 1f, -1f)
            corner(left + side, top + side, -1f, -1f)
        }

        // 顶部：标题 + 手电筒 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "扫码连接",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "对准相机屏幕上的连接二维码（标准 WiFi 码和奥林巴斯 OI 码均可）",
                    color = Color(0xCCFFFFFF),
                    fontSize = 13.sp
                )
            }
            IconButton(onClick = {
                torchOn = !torchOn
                runCatching { camera?.cameraControl?.enableTorch(torchOn) }
            }) {
                Icon(
                    if (torchOn) Icons.Filled.FlashOff else Icons.Filled.FlashOn,
                    contentDescription = "补光",
                    tint = Color.White
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }

        // 底部：提示 + 相册兜底
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                when {
                    denied -> "未获得相机权限，无法实时扫码"
                    hint != null -> hint
                    else -> "识别成功会自动连接；光线不足时可开补光"
                },
                color = if (hint != null || denied) Color(0xFFFFB4AB) else Color(0xCCFFFFFF),
                fontSize = 13.sp
            )
            if (denied) {
                Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
            }
            OutlinedButton(
                onClick = onPickGallery,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("从相册选二维码图片", color = Color.White)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 取 Y（亮度）平面并压掉行间 padding，得到严格 width*height 的灰度数组 */
private fun ImageProxy.luminance(): ByteArray? {
    if (planes.isEmpty()) return null
    val plane = planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return null
    val out = ByteArray(w * h)
    buffer.rewind()
    if (rowStride == w) {
        buffer.get(out, 0, minOf(out.size, buffer.remaining()))
        return out
    }
    val row = ByteArray(rowStride)
    var written = 0
    for (y in 0 until h) {
        val len = minOf(rowStride, buffer.remaining())
        if (len <= 0) break
        buffer.get(row, 0, len)
        val n = minOf(w, len)
        System.arraycopy(row, 0, out, written, n)
        written += n
    }
    return out
}
