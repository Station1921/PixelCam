package com.station1921.pixelcam.beauty

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF

/**
 * 构图（基础编辑）：旋转 / 镜像 / 裁剪。
 *
 * 与美化参数分开管理语义，但作为 [BeautyParams] 的一个字段随渲染通道一起走。
 * 应用顺序固定：**先镜像 → 再旋转 → 最后裁剪**。
 * 所有 UI 都以「旋转+镜像之后、裁剪之前」的画布为参考系展示。
 */
data class Geometry(
    /** 顺时针 90° 的次数，0..3 */
    val rotateQuarter: Int = 0,
    /** 水平镜像（自拍视角） */
    val mirror: Boolean = false,
    /** 裁剪区域，单位化 0..1，相对旋转+镜像后的画布；null 表示不裁剪 */
    val crop: RectF? = null
) {
    val isIdentity: Boolean
        get() = rotateQuarter == 0 && !mirror && crop == null

    companion object {
        val IDENTITY = Geometry()

        /** 按 [Geometry] 对位图做一次无损几何变换。返回新图，[src] 不会被回收。 */
        fun apply(src: Bitmap, g: Geometry): Bitmap {
            var cur = src
            var owns = false

            fun replace(next: Bitmap) {
                if (owns && cur !== src) cur.recycle()
                cur = next
                owns = true
            }

            // 1. 水平镜像
            if (g.mirror) {
                val out = Bitmap.createBitmap(cur.width, cur.height, Bitmap.Config.ARGB_8888)
                val m = Matrix().apply {
                    postScale(-1f, 1f)
                    postTranslate(cur.width.toFloat(), 0f)
                }
                Canvas(out).drawBitmap(cur, m, null)
                replace(out)
            }

            // 2. 旋转（顺时针 90° × n）
            if (g.rotateQuarter % 4 != 0) {
                val n = (g.rotateQuarter % 4 + 4) % 4
                val swap = n % 2 == 1
                val w = cur.width
                val h = cur.height
                val outW = if (swap) h else w
                val outH = if (swap) w else h
                val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.rotate((n * 90).toFloat(), outW / 2f, outH / 2f)
                c.drawBitmap(cur, (outW - w) / 2f, (outH - h) / 2f, null)
                replace(out)
            }

            // 3. 裁剪
            val crop = g.crop
            if (crop != null) {
                val w = cur.width
                val h = cur.height
                val left = (crop.left * w).toInt().coerceIn(0, w - 1)
                val top = (crop.top * h).toInt().coerceIn(0, h - 1)
                val right = (crop.right * w).toInt().coerceIn(left + 1, w)
                val bottom = (crop.bottom * h).toInt().coerceIn(top + 1, h)
                val out = Bitmap.createBitmap(cur, left, top, right - left, bottom - top)
                replace(out)
            }

            return cur
        }
    }
}
