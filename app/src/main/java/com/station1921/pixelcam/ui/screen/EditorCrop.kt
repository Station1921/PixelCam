package com.station1921.pixelcam.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪编辑器：显示当前预览图并叠加裁剪框。
 *
 * [aspect] 为当前图像的宽高比（w/h）。
 * [initialCrop] 为初始裁剪矩形，单位 0..1（相对整张显示图像，全图 = RectF(0,0,1,1)）。
 * 拖动四角缩放、框内移动；比例可锁定。确认后经 [onApply] 把单位矩形写回。
 */
@Composable
fun CropEditor(
    image: Bitmap?,
    aspect: Float,
    initialCrop: android.graphics.RectF,
    onCancel: () -> Unit,
    onApply: (android.graphics.RectF) -> Unit,
    modifier: Modifier = Modifier
) {
    // 裁剪矩形存成单位 0..1
    var crop by remember { mutableStateOf(initialCrop) }
    var ratio by remember { mutableStateOf<Float?>(null) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    // 图像在 box 内 fit 后的内容区（像素）
    fun contentRect(w: Int, h: Int): Rect {
        val bw = w.toFloat(); val bh = h.toFloat()
        return if (aspect >= bw / bh) {
            val ih = bw / aspect
            Rect(0f, (bh - ih) / 2f, bw, (bh + ih) / 2f)
        } else {
            val iw = bh * aspect
            Rect((bw - iw) / 2f, 0f, (bw + iw) / 2f, bh)
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { box = it }
        ) {
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(aspect, ratio) {
                        val slop = 28f
                        val minW = 0.12f // 单位最小宽
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val c = contentRect(box.width, box.height)
                            val u = Rect(
                                c.left + crop.left * c.width,
                                c.top + crop.top * c.height,
                                c.left + crop.right * c.width,
                                c.top + crop.bottom * c.height
                            )
                            val corner = cornerAt(down.position, u, slop)
                            val inside = u.contains(down.position)
                            if (corner == null && !inside) return@awaitEachGesture

                            drag(down.id) { change ->
                                val p = change.position
                                if (corner != null) {
                                    val anchor = oppositeCorner(corner, u)
                                    val nr = rectFromAnchor(
                                        anchor, p, ratio,
                                        c.width, c.height, minW * c.width
                                    )
                                    crop = toUnit(nr, c)
                                } else {
                                    val d = change.position - change.previousPosition
                                    val shifted = Rect(
                                        u.left + d.x, u.top + d.y,
                                        u.right + d.x, u.bottom + d.y
                                    )
                                    crop = toUnit(clampRect(shifted, c), c)
                                }
                                change.consume()
                            }
                        }
                    }
            ) {
                if (box != IntSize.Zero) {
                    val c = contentRect(box.width, box.height)
                    val u = Rect(
                        c.left + crop.left * c.width,
                        c.top + crop.top * c.height,
                        c.left + crop.right * c.width,
                        c.top + crop.bottom * c.height
                    )
                    drawCropOverlay(c, u)
                }
            }
        }

        // ——— 底部控制条 ———
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(top = 6.dp, bottom = 8.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ratioChips().forEach { (label, r) ->
                    RatioChip(
                        label = label,
                        selected = ratio == r,
                        onClick = {
                            ratio = r
                            val c = contentRect(box.width, box.height)
                            crop = centeredForRatio(
                                r, c.width, c.height,
                                crop.left, crop.top, crop.right, crop.bottom
                            )
                        }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("取消")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "拖动四角缩放 · 框内移动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onApply(crop) }) {
                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("应用")
                }
            }
        }
    }
}

// ——————————————————— 纯函数几何 ———————————————————

private fun ratioChips(): List<Pair<String, Float?>> = listOf(
    "自由" to null,
    "1:1" to 1f,
    "3:4" to 3f / 4f,
    "4:3" to 4f / 3f,
    "9:16" to 9f / 16f,
    "16:9" to 16f / 9f
)

@Composable
private fun RatioChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

private enum class CropCorner { TL, TR, BL, BR }

private fun cornerAt(p: Offset, r: androidx.compose.ui.geometry.Rect, slop: Float): CropCorner? {
    val corners = listOf(
        CropCorner.TL to Offset(r.left, r.top),
        CropCorner.TR to Offset(r.right, r.top),
        CropCorner.BL to Offset(r.left, r.bottom),
        CropCorner.BR to Offset(r.right, r.bottom)
    )
    return corners.minByOrNull { (it.second - p).getDistance() }
        ?.takeIf { (it.second - p).getDistance() <= slop }
        ?.first
}

private fun oppositeCorner(corner: CropCorner, r: androidx.compose.ui.geometry.Rect): Offset = when (corner) {
    CropCorner.TL -> Offset(r.right, r.bottom)
    CropCorner.TR -> Offset(r.left, r.bottom)
    CropCorner.BL -> Offset(r.right, r.top)
    CropCorner.BR -> Offset(r.left, r.top)
}

/** 以固定角 [a] 为锚点、[p] 为对角目标，构造（可选锁定比例）矩形并夹在内容区内 */
private fun rectFromAnchor(
    a: Offset,
    p: Offset,
    ratio: Float?,
    wMax: Float,
    hMax: Float,
    minSide: Float
): androidx.compose.ui.geometry.Rect {
    val wRaw = abs(p.x - a.x)
    val hRaw = abs(p.y - a.y)
    var w = wRaw
    var h = hRaw
    if (ratio != null) {
        w = max(wRaw, hRaw * ratio)
        h = w / ratio
    }
    if (w < minSide) {
        w = minSide
        h = if (ratio != null) w / ratio else h
    }
    if (h < minSide) {
        h = minSide
        w = if (ratio != null) h * ratio else w
    }
    // 整体收缩到内容区内
    if (w > wMax) {
        w = wMax
        if (ratio != null) h = w / ratio
    }
    if (h > hMax) {
        h = hMax
        if (ratio != null) w = h * ratio
    }
    // 方向：从锚点指向拖动点
    val sx = if (p.x >= a.x) 1f else -1f
    val sy = if (p.y >= a.y) 1f else -1f
    var left = if (sx > 0) a.x else a.x - w
    var top = if (sy > 0) a.y else a.y - h
    if (left < 0) left = 0f
    if (top < 0) top = 0f
    if (left + w > wMax) left = wMax - w
    if (top + h > hMax) top = hMax - h
    return androidx.compose.ui.geometry.Rect(left, top, left + w, top + h)
}

private fun clampRect(
    r: androidx.compose.ui.geometry.Rect,
    c: androidx.compose.ui.geometry.Rect
): androidx.compose.ui.geometry.Rect {
    val w = r.width; val h = r.height
    var left = r.left
    var top = r.top
    if (left < c.left) left = c.left
    if (top < c.top) top = c.top
    if (left + w > c.right) left = c.right - w
    if (top + h > c.bottom) top = c.bottom - h
    return androidx.compose.ui.geometry.Rect(left, top, left + w, top + h)
}

private fun toUnit(r: androidx.compose.ui.geometry.Rect, c: androidx.compose.ui.geometry.Rect): android.graphics.RectF =
    android.graphics.RectF(
        (r.left - c.left) / c.width,
        (r.top - c.top) / c.height,
        (r.right - c.left) / c.width,
        (r.bottom - c.top) / c.height
    )

/** 切换比例时，保持原框中心、用新比例生成尽量大的框 */
private fun centeredForRatio(
    ratio: Float?,
    wMax: Float,
    hMax: Float,
    oL: Float,
    oT: Float,
    oR: Float,
    oB: Float
): android.graphics.RectF {
    val cx = ((oL + oR) / 2f).coerceIn(0f, 1f) * wMax
    val cy = ((oT + oB) / 2f).coerceIn(0f, 1f) * hMax
    var w = wMax * 0.9f
    var h = wMax * 0.9f
    if (ratio != null) {
        w = wMax * 0.9f
        h = w / ratio
        if (h > hMax * 0.9f) {
            h = hMax * 0.9f
            w = h * ratio
        }
    } else {
        w = wMax * 0.9f
        h = hMax * 0.9f
    }
    var left = cx - w / 2f
    var top = cy - h / 2f
    if (left < 0) left = 0f
    if (top < 0) top = 0f
    if (left + w > wMax) left = wMax - w
    if (top + h > hMax) top = hMax - h
    return android.graphics.RectF(left / wMax, top / hMax, (left + w) / wMax, (top + h) / hMax)
}

// ——————————————————— 绘制 ———————————————————

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropOverlay(
    content: androidx.compose.ui.geometry.Rect,
    u: androidx.compose.ui.geometry.Rect
) {
    val dim = Color(0, 0, 0, 150)
    val grid = Color.White.copy(alpha = 0.5f)
    val frame = Color.White

    // 内容区外全暗
    drawRect(color = Color(0, 0, 0, 190))
    // 内容区内、裁剪框外四块遮罩
    drawRect(color = dim, topLeft = Offset(content.left, content.top), size = androidx.compose.ui.geometry.Size(u.left - content.left, content.height))
    drawRect(color = dim, topLeft = Offset(u.right, content.top), size = androidx.compose.ui.geometry.Size(content.right - u.right, content.height))
    drawRect(color = dim, topLeft = Offset(u.left, content.top), size = androidx.compose.ui.geometry.Size(u.width, u.top - content.top))
    drawRect(color = dim, topLeft = Offset(u.left, u.bottom), size = androidx.compose.ui.geometry.Size(u.width, content.bottom - u.bottom))

    // 井字参考线
    for (i in 1..2) {
        val x = u.left + u.width * i / 3f
        drawLine(grid, Offset(x, u.top), Offset(x, u.bottom), strokeWidth = 1.dp.toPx())
        val y = u.top + u.height * i / 3f
        drawLine(grid, Offset(u.left, y), Offset(u.right, y), strokeWidth = 1.dp.toPx())
    }

    // 边框
    drawRect(frame, topLeft = u.topLeft, size = u.size, style = Stroke(width = 1.5.dp.toPx()))

    // 四角手柄
    val hs = 14.dp.toPx()
    val thick = 3.dp.toPx()
    val corners = listOf(
        Offset(u.left, u.top), Offset(u.right, u.top),
        Offset(u.left, u.bottom), Offset(u.right, u.bottom)
    )
    corners.forEach { c ->
        val s = androidx.compose.ui.geometry.Size(hs, thick)
        // 画 L 形：水平 + 垂直
        val hDir = if (c.x == u.left) 1f else -1f
        val vDir = if (c.y == u.top) 1f else -1f
        drawRect(frame, topLeft = Offset(min(c.x, c.x + hDir * hs), c.y), size = androidx.compose.ui.geometry.Size(hs, thick))
        drawRect(frame, topLeft = Offset(c.x, min(c.y, c.y + vDir * hs)), size = androidx.compose.ui.geometry.Size(thick, hs))
    }
}
