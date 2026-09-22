package com.station1921.pixelcam.beauty

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 液化变形基元。所有坐标单位与作用的 Mat 一致（像素）。
 */
sealed class WarpOp(open val cx: Float, open val cy: Float, open val r: Float) {

    abstract fun scaled(k: Float): WarpOp

    /** 局部缩放：正值把像素由中心向外推（放大），负值向内收（缩小） */
    data class Magnify(
        override val cx: Float,
        override val cy: Float,
        override val r: Float,
        val strength: Float
    ) : WarpOp(cx, cy, r) {
        override fun scaled(k: Float) = Magnify(cx * k, cy * k, r * k, strength)
    }

    /** 局部拖拽：半径内的像素沿 (tx, ty) 平移，到边缘平滑衰减为 0 */
    data class Drag(
        override val cx: Float,
        override val cy: Float,
        override val r: Float,
        val tx: Float,
        val ty: Float
    ) : WarpOp(cx, cy, r) {
        override fun scaled(k: Float) = Drag(cx * k, cy * k, r * k, tx * k, ty * k)
    }
}

/**
 * 液化引擎：瘦脸 / V 脸 / 大眼 / 瘦鼻 全部由它实现。
 *
 * 位移场本身是平滑的，所以在 1/4 分辨率上先把位移算好、再双线性放大到全尺寸，
 * 精度几乎无损，计算量却只有全尺寸的 1/16。
 */
object Liquify {

    private const val MAP_SCALE = 4

    fun apply(src: Mat, ops: List<WarpOp>): Mat {
        if (ops.isEmpty()) return src

        val w = src.cols()
        val h = src.rows()
        val mw = (w + MAP_SCALE - 1) / MAP_SCALE
        val mh = (h + MAP_SCALE - 1) / MAP_SCALE

        val dx = FloatArray(mw * mh)
        val dy = FloatArray(mw * mh)

        // 每个操作只遍历自己的包围盒，避免全图扫描
        for (op in ops) {
            if (op.r <= 0f) continue
            val x0 = max(0, floor((op.cx - op.r) / MAP_SCALE).toInt())
            val x1 = min(mw - 1, ceil((op.cx + op.r) / MAP_SCALE).toInt())
            val y0 = max(0, floor((op.cy - op.r) / MAP_SCALE).toInt())
            val y1 = min(mh - 1, ceil((op.cy + op.r) / MAP_SCALE).toInt())
            if (x0 > x1 || y0 > y1) continue

            val r2 = op.r * op.r
            val isMagnify = op is WarpOp.Magnify
            val strength = (op as? WarpOp.Magnify)?.strength ?: 0f
            val tx = (op as? WarpOp.Drag)?.tx ?: 0f
            val ty = (op as? WarpOp.Drag)?.ty ?: 0f

            for (y in y0..y1) {
                val py = y * MAP_SCALE.toFloat()
                val rowBase = y * mw
                for (x in x0..x1) {
                    val vx = x * MAP_SCALE.toFloat() - op.cx
                    val vy = py - op.cy
                    val d2 = vx * vx + vy * vy
                    if (d2 >= r2) continue
                    val t = 1f - d2 / r2
                    val k = t * t // 二次衰减，边缘过渡更自然
                    val i = rowBase + x
                    if (isMagnify) {
                        dx[i] += vx * strength * k
                        dy[i] += vy * strength * k
                    } else {
                        dx[i] += tx * k
                        dy[i] += ty * k
                    }
                }
            }
        }

        // remap 的语义是 dst(q) = src(map(q))，因此反向采样要用 q - delta
        val bx = FloatArray(mw * mh)
        val by = FloatArray(mw * mh)
        for (i in 0 until mw * mh) {
            bx[i] = (i % mw) * MAP_SCALE.toFloat() - dx[i]
            by[i] = (i / mw) * MAP_SCALE.toFloat() - dy[i]
        }

        val smallX = Mat(mh, mw, CvType.CV_32F)
        val smallY = Mat(mh, mw, CvType.CV_32F)
        smallX.put(0, 0, bx)
        smallY.put(0, 0, by)

        val mapX = Mat()
        val mapY = Mat()
        Imgproc.resize(smallX, mapX, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.resize(smallY, mapY, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        smallX.release()
        smallY.release()

        val dst = Mat()
        Imgproc.remap(src, dst, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        mapX.release()
        mapY.release()
        return dst
    }

    /**
     * 把美型参数翻译成液化操作。
     * [scale] 用于在缩略图上预览时按比例缩放所有几何量。
     */
    fun buildOps(faces: List<FaceInfo>, p: BeautyParams, scale: Float = 1f): List<WarpOp> {
        if (!p.needsReshape) return emptyList()

        val raw = ArrayList<WarpOp>()
        for (f in faces) {
            val fw = f.face.width
            val fh = f.face.height
            val top = f.face.top
            val left = f.face.left
            val right = f.face.right

            // 瘦脸：颧骨下方两侧向中线收
            if (p.faceSlim > 0f) {
                val m = p.faceSlim / 100f
                val y = top + fh * 0.62f
                val tx = fw * 0.13f * m
                raw += WarpOp.Drag(left, y, fw * 0.45f, tx, 0f)
                raw += WarpOp.Drag(right, y, fw * 0.45f, -tx, 0f)
            }

            // V 脸：收下颌角并微微上提
            if (p.vFace > 0f) {
                val m = p.vFace / 100f
                val y = top + fh * 0.82f
                val tx = fw * 0.10f * m
                val ty = -fh * 0.035f * m
                raw += WarpOp.Drag(left + fw * 0.08f, y, fw * 0.34f, tx, ty)
                raw += WarpOp.Drag(right - fw * 0.08f, y, fw * 0.34f, -tx, ty)
            }

            // 大眼：以眼睛中心做局部放大
            if (p.bigEye > 0f) {
                val m = p.bigEye / 100f
                for (eye in listOfNotNull(f.leftEye, f.rightEye)) {
                    val r = max(eye.width, eye.height) * 1.15f
                    raw += WarpOp.Magnify(eye.cx, eye.cy, r, 0.42f * m)
                }
            }

            // 瘦鼻：两侧鼻翼向内收
            if (p.noseSlim > 0f) {
                val m = p.noseSlim / 100f
                f.nose?.let { n ->
                    val tx = n.width * 0.22f * m
                    raw += WarpOp.Drag(n.left, n.cy, n.width * 1.25f, tx, 0f)
                    raw += WarpOp.Drag(n.right, n.cy, n.width * 1.25f, -tx, 0f)
                }
            }
        }
        return if (scale == 1f) raw else raw.map { it.scaled(scale) }
    }
}
