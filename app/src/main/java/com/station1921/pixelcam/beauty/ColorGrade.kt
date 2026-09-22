package com.station1921.pixelcam.beauty

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** 全局调色：亮度 / 对比度 / 饱和度 / 色温 / 锐化。输入输出均为 BGR。 */
object ColorGrade {

    fun adjust(bgr: Mat, p: BeautyParams): Mat {
        var cur = bgr
        var owned = false

        // 亮度与对比度合并成一次线性变换：out = src * alpha + beta
        if (p.brightness != 0f || p.contrast != 0f) {
            val alpha = 1.0 + (p.contrast / 100f).toDouble() * 0.9
            val beta = (p.brightness / 100f).toDouble() * 60.0
            val next = Mat()
            cur.convertTo(next, -1, alpha, beta)
            if (owned) cur.release()
            cur = next
            owned = true
        }

        // 饱和度：out = gray + (src - gray) * (1 + s)，用加权混合避免 HSV 往返的精度损失
        if (p.saturation != 0f) {
            val s = (p.saturation / 100f).toDouble() * 0.9
            val gray = Mat()
            Imgproc.cvtColor(cur, gray, Imgproc.COLOR_BGR2GRAY)
            val gray3 = Mat()
            Imgproc.cvtColor(gray, gray3, Imgproc.COLOR_GRAY2BGR)
            gray.release()
            val next = Mat()
            Core.addWeighted(cur, 1.0 + s, gray3, -s, 0.0, next)
            gray3.release()
            if (owned) cur.release()
            cur = next
            owned = true
        }

        // 色温：BGR 通道顺序，暖调 = 减蓝加红
        if (p.temperature != 0f) {
            val t = (p.temperature / 100f).toDouble() * 26.0
            val next = Mat()
            Core.add(cur, Scalar(-t, 0.0, t), next)
            if (owned) cur.release()
            cur = next
            owned = true
        }

        // 高光 / 阴影：按亮度分区，只动亮部或暗部，中间调基本不受影响
        if (p.highlights != 0f || p.shadows != 0f) {
            val next = toneRegion(cur, p.highlights, p.shadows)
            if (owned) cur.release()
            cur = next
            owned = true
        }

        // 锐化：非锐化掩蔽 out = src + (src - blur) * amount
        if (p.sharpen > 0f) {
            val a = (p.sharpen / 100f).toDouble() * 1.2
            val blur = Mat()
            Imgproc.GaussianBlur(cur, blur, Size(0.0, 0.0), 2.0)
            val next = Mat()
            Core.addWeighted(cur, 1.0 + a, blur, -a, 0.0, next)
            blur.release()
            if (owned) cur.release()
            cur = next
            owned = true
        }

        return cur
    }

    /** 高光权重起点（亮度 0~1）：高于它算亮部 */
    private const val HI_CUT = 0.5
    /** 高光 / 阴影满档时叠加的最大偏移（0~255 灰阶） */
    private const val HI_SCALE = 80.0
    private const val SH_SCALE = 95.0

    /**
     * 高光 / 阴影分区调整：正值提亮、负值压暗。
     *
     * 用亮度算两张权重图（0~1，带平方平滑过渡，避免分区边界出现硬边）：
     *   高光 w = clamp(2·lum − 1, 0, 1)²   → 只在中高亮部生效
     *   阴影 w = clamp(1 − 2·lum, 0, 1)²   → 只在中低暗部生效
     * 再按权重把偏移量叠加回三个通道：out = src + w_hi·Δhi + w_sh·Δsh。
     */
    private fun toneRegion(bgr: Mat, highlights: Float, shadows: Float): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val lum = Mat()
        gray.convertTo(lum, CvType.CV_32F, 1.0 / 255.0)
        gray.release()

        /** 累加一层权重：delta += w * scale */
        var delta: Mat? = null
        fun accumulate(w: Mat, scale: Double) {
            val d = delta
            if (d == null) {
                val n = Mat()
                Core.multiply(w, Scalar(scale), n)
                delta = n
            } else {
                val n = Mat()
                Core.addWeighted(d, 1.0, w, scale, 0.0, n)
                d.release()
                delta = n
            }
        }

        if (highlights != 0f) {
            val w = Mat()
            Core.multiply(lum, Scalar(2.0), w)
            Core.add(w, Scalar(-2.0 * HI_CUT), w) // (lum − cut)/(1 − cut) → 亮度 1 处为 1
            Core.max(w, Scalar(0.0), w)
            Core.min(w, Scalar(1.0), w)
            val w2 = Mat()
            Core.multiply(w, w, w2) // 平方 → 过渡更柔，中间调几乎不动
            w.release()
            accumulate(w2, (highlights / 100f).toDouble() * HI_SCALE)
            w2.release()
        }
        if (shadows != 0f) {
            val w = Mat()
            Core.multiply(lum, Scalar(-2.0), w)
            Core.add(w, Scalar(2.0 * HI_CUT), w) // 1 − 2·lum → 亮度 0 处为 1
            Core.max(w, Scalar(0.0), w)
            Core.min(w, Scalar(1.0), w)
            val w2 = Mat()
            Core.multiply(w, w, w2)
            w.release()
            accumulate(w2, (shadows / 100f).toDouble() * SH_SCALE)
            w2.release()
        }
        lum.release()

        val d = delta
        if (d == null) return bgr

        val delta3 = Mat()
        Core.merge(listOf(d, d, d), delta3)
        d.release()

        val f = Mat()
        bgr.convertTo(f, CvType.CV_32F)
        val o = Mat()
        Core.add(f, delta3, o)
        f.release()
        delta3.release()

        val out = Mat()
        o.convertTo(out, CvType.CV_8U)
        o.release()
        return out
    }

    /** 径向压暗四角 */
    fun vignette(bgr: Mat, amount: Float): Mat {
        if (amount <= 0f) return bgr
        val a = (amount / 100f).coerceIn(0f, 1f) * 0.65f

        val w = bgr.cols()
        val h = bgr.rows()
        val cx = w / 2f
        val cy = h / 2f
        val maxD = sqrt(cx * cx + cy * cy)

        val buf = FloatArray(w * h)
        for (y in 0 until h) {
            val ny = (y - cy) / maxD
            val row = y * w
            for (x in 0 until w) {
                val nx = (x - cx) / maxD
                buf[row + x] = 1f - a * (nx * nx + ny * ny)
            }
        }

        val mask = Mat(h, w, CvType.CV_32F)
        mask.put(0, 0, buf)
        val mask3 = Mat()
        Core.merge(listOf(mask, mask, mask), mask3)
        mask.release()

        val f = Mat()
        bgr.convertTo(f, CvType.CV_32F)
        val o = Mat()
        Core.multiply(f, mask3, o)
        f.release()
        mask3.release()

        val out = Mat()
        o.convertTo(out, CvType.CV_8U)
        o.release()
        return out
    }
}

// ——————————————————— 曲线工具 ———————————————————

private fun c01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

private fun gamma(v: Float, g: Float): Float = c01(v.pow(g))

private fun liftBlacks(v: Float, l: Float): Float = c01(l + v * (1f - l))

private fun contra(v: Float, c: Float): Float = c01((v - 0.5f) * (1f + c) + 0.5f)

private fun sCurve(v: Float, amt: Float): Float =
    c01(v + amt * (v - 0.5f) * (1f - abs(v * 2f - 1f)))

private fun lum(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

private fun satu(v: Float, l: Float, s: Float): Float = c01(l + (v - l) * s)

// ——————————————————— 滤镜 ———————————————————

/**
 * 一个滤镜 = 「可选的整图去色」+ 「逐通道色调曲线」。
 *
 * 注意 [curve] 只能表达逐通道映射（生成 LUT 时三通道入参恒为同一个 v），
 * 像"黑白"这种需要跨通道混合的效果必须靠 [desat] 完成：
 * desat < 1 时先把图按亮度压向灰度，再走曲线。
 *
 * @param desat 色彩保留度：1 = 原色，0 = 完全去色（灰度）
 */
data class FilterDef(
    val id: Int,
    val name: String,
    val desat: Float = 1f,
    val curve: (Float, Float, Float) -> Triple<Float, Float, Float>
)

/**
 * 全部滤镜都靠程序生成 256 级 LUT，不依赖任何外部 .cube 资源。
 * LUT 只算一次并缓存，之后每次应用就是一次查表，非常快。
 */
object Filters {

    val all: List<FilterDef> = listOf(
        FilterDef(0, "原图") { r, _, _ -> Triple(r, r, r) },

        FilterDef(1, "奶油") { r, g, b ->
            Triple(
                c01(liftBlacks(gamma(r, 0.92f), 0.05f) + 0.030f),
                c01(liftBlacks(gamma(g, 0.94f), 0.05f) + 0.012f),
                c01(liftBlacks(gamma(b, 0.98f), 0.05f) - 0.015f)
            )
        },

        FilterDef(2, "甜美") { r, g, b ->
            Triple(
                c01(gamma(r, 0.90f) + 0.035f),
                c01(gamma(g, 0.95f) + 0.010f),
                c01(gamma(b, 0.97f) + 0.028f)
            )
        },

        FilterDef(3, "胶片") { r, g, b ->
            val sr = sCurve(r, 0.22f)
            val sg = sCurve(g, 0.16f)
            val sb = sCurve(b, 0.20f)
            Triple(
                c01(sr + 0.020f * (1f - sr)),
                c01(sg + 0.004f),
                c01(sb + 0.045f * (1f - sb) - 0.010f * sb)
            )
        },

        FilterDef(4, "日系") { r, g, b ->
            Triple(
                c01(contra(liftBlacks(r, 0.06f), -0.10f)),
                c01(contra(liftBlacks(g, 0.07f), -0.08f) + 0.010f),
                c01(contra(liftBlacks(b, 0.09f), -0.06f) + 0.022f)
            )
        },

        // 真·黑白：先完全去色（曲线做不到跨通道混合），再套轻微冷暖分离
        FilterDef(5, "黑白", desat = 0f) { r, g, b ->
            val l = contra(lum(r, g, b), 0.18f)
            Triple(c01(l * 1.010f), c01(l), c01(l * 0.985f))
        },

        FilterDef(6, "冷白") { r, g, b ->
            Triple(
                c01(gamma(r, 0.95f) - 0.012f),
                c01(gamma(g, 0.95f) + 0.006f),
                c01(gamma(b, 0.92f) + 0.040f)
            )
        },

        FilterDef(7, "复古") { r, g, b ->
            val l = lum(r, g, b)
            Triple(
                c01(satu(r, l, 0.80f) + 0.055f + 0.05f * (1f - r)),
                c01(satu(g, l, 0.82f) + 0.035f),
                c01(satu(b, l, 0.86f) + 0.010f)
            )
        }
    )

    private val cache = ConcurrentHashMap<Int, Mat>()

    fun nameOf(id: Int): String = all.firstOrNull { it.id == id }?.name ?: "原图"

    /** id 为 0（原图）时返回 null，调用方直接跳过 */
    fun lut(id: Int): Mat? {
        if (id == 0) return null
        cache[id]?.let { return it }
        val def = all.firstOrNull { it.id == id } ?: return null
        val m = buildLut(def)
        cache[id] = m
        return m
    }

    /**
     * 应用滤镜：先按 def.desat 去色，再查 LUT 上色调。
     * id=0 且不去色时原样返回 [bgr]（调用方靠引用相等判断"没换对象"）。
     */
    fun apply(bgr: Mat, id: Int): Mat {
        val def = all.firstOrNull { it.id == id }

        var cur = bgr
        var owned = false

        // 去色：向灰度图加权混合，比 HSV 往返更稳、无色偏
        if (def != null && def.desat < 1f) {
            val gray = Mat()
            Imgproc.cvtColor(cur, gray, Imgproc.COLOR_BGR2GRAY)
            val gray3 = Mat()
            Imgproc.cvtColor(gray, gray3, Imgproc.COLOR_GRAY2BGR)
            gray.release()
            val d = def.desat.coerceIn(0f, 1f).toDouble()
            val next = Mat()
            Core.addWeighted(cur, d, gray3, 1.0 - d, 0.0, next)
            gray3.release()
            cur = next
            owned = true
        }

        val table = lut(id) ?: return cur
        val out = Mat()
        Core.LUT(cur, table, out)
        if (owned) cur.release()
        return out
    }

    private fun buildLut(def: FilterDef): Mat {
        val m = Mat(256, 1, CvType.CV_8UC3)
        val buf = ByteArray(256 * 3)
        for (i in 0..255) {
            val v = i / 255f
            val (r, g, b) = def.curve(v, v, v)
            // Mat 的内存布局是 BGR
            buf[i * 3 + 0] = to8(b)
            buf[i * 3 + 1] = to8(g)
            buf[i * 3 + 2] = to8(r)
        }
        m.put(0, 0, buf)
        return m
    }

    private fun to8(v: Float): Byte = (c01(v) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
}
