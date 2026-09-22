package com.station1921.pixelcam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.atan2
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 可缩放/平移的单张大图（监视页 / 大图预览 / 编辑页复用）。
 *
 * 手势完全自管，避免官方 transformable 的阻尼与默认中心缩放问题：
 * - 双指捏合：以两指中心为锚点缩放。
 * - 单指拖动：放大后 1:1 跟手、无阻尼（视口坐标，delta 直接加到 offset）。
 * - 双击：以点击处为锚点放大到 2.5×，再双击复位。
 * - 放大态自动钳制在图片边界内，不露黑边。
 * - 长按 [onLongPress] 触发（如编辑页看原图），松手回调 [onLongPressEnd]。
 *
 * 关键点：手势挂在外层「未变换」Box 上，指针坐标即视口坐标，与 offset 同坐标系，
 * 不会因 graphicsLayer 逆变换导致基准漂移（旧版抖动/阻尼的根因）。
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onLongPressEnd: (() -> Unit)? = null,
    // 放大态拖到图片边缘继续拖、超过阈值时回调，参数 +1=下一张 / -1=上一张。
    // 监视页大图在 HorizontalPager 里，放大态手势被本组件消费、Pager 收不到，
    // 由这里翻页；图库查看器自带同款逻辑不用这个回调。
    onEdgeSwipe: ((Int) -> Unit)? = null
) {
    val scale = remember { mutableStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val rotation = remember { mutableStateOf(0f) }
    val sizeState = remember { mutableStateOf(IntSize.Zero) }

    val clampOffset: (Float, Offset) -> Offset = { s, o ->
        if (bitmap == null || s <= 1.001f || sizeState.value.width == 0) {
            Offset.Zero
        } else {
            val vw0 = sizeState.value.width.toFloat()
            val vh0 = sizeState.value.height.toFloat()
            val b = bitmap!!
            val fit0 = minOf(vw0 / b.width, vh0 / b.height)
            val dw = b.width * fit0
            val dh = b.height * fit0
            // 旋转 90°/270° 时，屏幕可见区域映射回「未旋转坐标系」是一个宽高互换、
            // 缩小 k 倍的居中窗口；钳制必须在这个窗口内做，旋转后超出屏幕的内容才拖得出来
            val r = ((rotation.value % 360f) + 360f) % 360f
            val q90 = r > 45f && r < 135f
            val q270 = r > 225f && r < 315f
            val k = if (q90 || q270) minOf(vw0 / dh, vh0 / dw) else 1f
            val vw = if (q90 || q270) vh0 / k else vw0
            val vh = if (q90 || q270) vw0 / k else vh0
            val wx0 = (vw0 - vw) / 2f
            val wy0 = (vh0 - vh) / 2f
            val cx = (vw0 - dw) / 2f
            val cy = (vh0 - dh) / 2f
            val sw = dw * s
            val sh = dh * s
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

    // 屏幕坐标 → 未旋转坐标系。旋转 90°/270° 后内层 offset 的位移会被外层旋转「带着转」，
    // 手势输入必须先映射回未旋转视口，拖动方向才与手指一致（旋转后左右拖变上下的根源）。
    fun toUnrot(pt: Offset): Offset {
        val vw0 = sizeState.value.width.toFloat()
        val vh0 = sizeState.value.height.toFloat()
        val b = bitmap
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

    // 未旋转坐标系里的「位移向量」→ 屏幕位移向量（toUnrot 的逆变换）。
    // 边缘翻页判定取换算后的屏幕横向分量：旋转 90°/270° 后两套坐标横纵互换，
    // 直接用图片坐标系的 x 会把「上下拖」误判成翻页。
    fun toScreenDelta(d: Offset): Offset {
        val vw0 = sizeState.value.width.toFloat()
        val vh0 = sizeState.value.height.toFloat()
        val b = bitmap
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
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                val pis = this
                coroutineScope {
                    pis.awaitPointerEventScope {
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
                        // 边缘翻页：放大态拖到边缘后「还想继续拖」的屏幕横向分量累积
                        var overScroll = 0f
                        var edgeFired = false
                        var lastTapTime = 0L
                        var lastTapPos = Offset.Zero
                        var longJob: Job? = null
                        var longFired = false
                        var singleJob: Job? = null

                        fun cancelLong() {
                            longJob?.cancel()
                            longJob = null
                        }

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
                            val pressed = event.changes.filter { it.pressed }

                            if (pressed.isEmpty()) {
                                if (wasDown) {
                                    cancelLong()
                                    val wasLong = longFired
                                    longFired = false
                                                            wasDown = false
                                prevCentroid = null
                                prevDist = 0f
                                prevCount = 0
                                prevAngle = 0f
                                twistConsumed = false
                                twistAccum = 0f
                                overScroll = 0f
                                edgeFired = false
                                if (wasLong) {
                                        onLongPressEnd?.invoke()
                                    } else if (!moved) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapTime < 300 &&
                                            (downPos - lastTapPos).getDistance() < 60f
                                        ) {
                                            // 双击 → 缩放（并复位旋转），取消待触发的单击
                                            singleJob?.cancel()
                                            singleJob = null
                                            toggleZoomAt(downPos)
                                            lastTapTime = 0
                                        } else {
                                            lastTapTime = now
                                            lastTapPos = downPos
                                            // 延迟 300ms 再判定为单击：避免与双击冲突
                                            singleJob?.cancel()
                                            singleJob = launch {
                                                delay(300)
                                                onTap?.invoke()
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
                                // 单指按下起长按计时：到时仍按住且未移动 → 触发
                                if (onLongPress != null && pressed.size == 1) {
                                    longJob = launch {
                                        delay(420)
                                        longFired = true
                                        onLongPress.invoke()
                                    }
                                }
                            }

                            when {
                            pressed.size >= 2 -> {
                                moved = true
                                cancelLong()
                                longFired = false
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
                                        val r = newScale / oldScale
                                        scale.value = newScale
                                        offset.value = clampOffset(
                                            newScale,
                                            centroid * (1f - r) + offset.value * r
                                        )
                                    }
                                    val pan = centroid - (prevCentroid ?: centroid)
                                    if (pan != Offset.Zero) {
                                        offset.value = clampOffset(scale.value, offset.value + pan)
                                    }
                                }
                                // 双指扭转旋转：限制为 90° 步进。阈值看「整段手势的累计扭转角」，
                                // 累计扭过 30° 才触发（阈值过低的用户体验是「太灵敏」），按方向
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
                                    if ((p - downPos).getDistance() > 10f) {
                                        moved = true
                                        cancelLong()
                                    }
                                    if (scale.value > 1.001f) {
                                        if (prevCount < 2 && prevCentroid != null) {
                                            val target = offset.value + (p - prevCentroid!!)
                                            val clamped = clampOffset(scale.value, target)
                                            offset.value = clamped
                                            // 拖到边缘继续拖：把「还想继续拖」的量转回屏幕位移、
                                            // 按横向分量累积，超过阈值（屏宽 30%）翻一张，
                                            // 方向与图库查看器一致（右拖=上一张，左拖=下一张）
                                            if (onEdgeSwipe != null && !edgeFired) {
                                                val overX = toScreenDelta(target - clamped).x
                                                if (overX != 0f) {
                                                    overScroll += overX
                                                    val threshold =
                                                        (sizeState.value.width * 0.30f)
                                                            .coerceAtLeast(140f)
                                                    if (overScroll > threshold) {
                                                        onEdgeSwipe.invoke(-1)
                                                        edgeFired = true
                                                    } else if (overScroll < -threshold) {
                                                        onEdgeSwipe.invoke(1)
                                                        edgeFired = true
                                                    }
                                                } else {
                                                    overScroll = 0f
                                                }
                                            }
                                        }
                                        prevCentroid = p
                                        prevCount = 1
                                        pressed[0].consume()
                                    } else {
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
        if (bitmap != null) {
            // 外层 Box 承载「双指捏合旋转」：绕视口中心旋转，不影响内部捏合缩放/平移的坐标系。
            // 旋转到 90°/270° 时补一个适配缩放：图片按未旋转姿态 Fit 后，横图竖转会被
            // 限制在原宽度里显得很小——这里把旋转后的包围盒重新撑到视口能容纳的最大尺寸。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = rotation.value
                        val r = ((rotation.value % 360f) + 360f) % 360f
                        val rotated = (r > 45f && r < 135f) || (r > 225f && r < 315f)
                        val vw = sizeState.value.width.toFloat()
                        val vh = sizeState.value.height.toFloat()
                        if (rotated && vw > 0f && vh > 0f) {
                            val fit0 = minOf(vw / bitmap.width, vh / bitmap.height)
                            val dw = bitmap.width * fit0
                            val dh = bitmap.height * fit0
                            if (dw > 0f && dh > 0f) {
                                val k = minOf(vw / dh, vh / dw)
                                scaleX = k
                                scaleY = k
                            }
                        }
                    }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { sizeState.value = it }
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale.value
                            scaleY = scale.value
                            translationX = offset.value.x
                            translationY = offset.value.y
                        }
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}
