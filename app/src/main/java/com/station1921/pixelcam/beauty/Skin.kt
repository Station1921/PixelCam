package com.station1921.pixelcam.beauty

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min

/**
 * 皮肤相关处理：肤色掩码、磨皮、美白/红润、祛痘祛斑。
 *
 * 约定：输入输出均为 BGR（OpenCV 默认通道顺序）、CV_8UC3。
 * 每个函数在 amount<=0 时直接返回原对象，方便流水线按需跳过。
 */
object Skin {

    // ——————————————————— 肤色掩码 ———————————————————

    /**
     * 生成 0~255 的肤色掩码。
     * 先按 YCrCb 的肤色范围粗筛，有人脸时再把范围压到脸部椭圆内，
     * 这样既不会把背景里的米黄色物体当成皮肤，也不会漏掉脸边缘。
     */
    fun buildMask(bgr: Mat, faces: List<FaceInfo>, useParser: Boolean = true): Mat {
        // 端侧人脸解析：像素级皮肤掩码，用户开启且模型可用时优先（眉/眼/唇零误伤）
        if (useParser && FaceParser.available()) {
            val parsed = FaceParser.parse(bgr)
            if (parsed != null) {
                val mask = if (faces.isNotEmpty()) {
                    val region = Mat.zeros(bgr.size(), CvType.CV_8UC1)
                    for (f in faces) {
                        Imgproc.ellipse(
                            region,
                            Point(f.face.cx.toDouble(), f.face.cy.toDouble()),
                            Size(f.face.width * 0.62, f.face.height * 0.72),
                            0.0, 0.0, 360.0, Scalar(255.0), -1
                        )
                    }
                    Core.bitwise_and(parsed, region, parsed)
                    region.release()
                    parsed
                } else {
                    parsed
                }
                // 羽化边缘，避免磨皮区域出现明显接缝
                val sigma = max(3.0, min(bgr.cols(), bgr.rows()) * 0.006)
                Imgproc.GaussianBlur(mask, mask, Size(0.0, 0.0), sigma)
                return mask
            }
        }

        // 回退方案：YCrCb 肤色 + 脸椭圆（原来的做法，已扣掉五官）
        val ycc = Mat()
        Imgproc.cvtColor(bgr, ycc, Imgproc.COLOR_BGR2YCrCb)

        val mask = Mat()
        Core.inRange(ycc, Scalar(50.0, 133.0, 77.0), Scalar(250.0, 175.0, 128.0), mask)
        ycc.release()

        if (faces.isNotEmpty()) {
            val region = Mat.zeros(bgr.size(), CvType.CV_8UC1)
            for (f in faces) {
                Imgproc.ellipse(
                    region,
                    Point(f.face.cx.toDouble(), f.face.cy.toDouble()),
                    Size(f.face.width * 0.60, f.face.height * 0.68),
                    0.0, 0.0, 360.0,
                    Scalar(255.0),
                    -1
                )
            }
            Core.bitwise_and(mask, region, mask)
            region.release()

            // 扣掉眼睛/眉毛/嘴唇，避免磨皮把五官糊掉（原先只有祛痘做了这一步）
            val exclude = exclusionMask(bgr.size(), faces)
            Core.subtract(mask, exclude, mask)
            exclude.release()
        }

        // 羽化边缘，避免磨皮区域出现明显接缝
        val sigma = max(3.0, min(bgr.cols(), bgr.rows()) * 0.006)
        Imgproc.GaussianBlur(mask, mask, Size(0.0, 0.0), sigma)
        return mask
    }

    // ——————————————————— 磨皮 ———————————————————

    /**
     * 保边磨皮：dst = src + (base - src) * (strength * mask)
     *
     * base 来自双边滤波，能抹掉毛孔和细纹又留住五官轮廓。
     * 滤波在 1/4 尺寸上完成再放大——皮肤是低频信息，降采样不影响观感，
     * 但速度能快一个数量级（双边滤波的代价随像素数增长很快）。
     */
    fun smooth(bgr: Mat, mask: Mat, amount: Float): Mat {
        if (amount <= 0f) return bgr
        val a = (amount / 100f).coerceIn(0f, 1f)

        val small = Mat()
        Imgproc.resize(bgr, small, Size(), 0.25, 0.25, Imgproc.INTER_AREA)
        val smallBlur = Mat()
        val sigma = 10.0 + a * 55.0
        Imgproc.bilateralFilter(small, smallBlur, 9, sigma * 1.8, sigma)
        small.release()

        val base = Mat()
        Imgproc.resize(smallBlur, base, bgr.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        smallBlur.release()

        val w = Mat()
        mask.convertTo(w, CvType.CV_32F, a / 255.0)
        val w3 = Mat()
        Core.merge(listOf(w, w, w), w3)
        w.release()

        val src32 = Mat()
        bgr.convertTo(src32, CvType.CV_32F)
        val base32 = Mat()
        base.convertTo(base32, CvType.CV_32F)
        base.release()

        val delta = Mat()
        Core.subtract(base32, src32, delta)
        Core.multiply(delta, w3, delta)
        val out32 = Mat()
        Core.add(src32, delta, out32)

        // 补回一点高频，避免"塑料脸"
        val hf = Mat()
        Core.subtract(src32, base32, hf)
        src32.release()
        base32.release()
        delta.release()
        Core.multiply(hf, w3, hf)
        w3.release()

        val restored = Mat()
        Core.addWeighted(out32, 1.0, hf, 0.14, 0.0, restored)
        out32.release()
        hf.release()

        val out = Mat()
        restored.convertTo(out, CvType.CV_8U)
        restored.release()
        return out
    }

    // ——————————————————— 美白 / 红润 ———————————————————

    /** 在肤色掩码范围内提亮并轻微去饱和；ruddy 额外增加红润度。 */
    fun tone(bgr: Mat, mask: Mat, whitenAmount: Float, ruddyAmount: Float): Mat {
        if (whitenAmount <= 0f && ruddyAmount <= 0f) return bgr

        val ycc = Mat()
        Imgproc.cvtColor(bgr, ycc, Imgproc.COLOR_BGR2YCrCb)
        val ch = ArrayList<Mat>(3)
        Core.split(ycc, ch)
        ycc.release()

        val w = Mat()
        mask.convertTo(w, CvType.CV_32F, 1.0 / 255.0)

        if (whitenAmount > 0f) {
            val a = (whitenAmount / 100f).coerceIn(0f, 1f)
            val y = Mat()
            ch[0].convertTo(y, CvType.CV_32F)
            val add = Mat()
            Core.multiply(w, Scalar(a * 30.0), add)
            Core.add(y, add, y)
            y.convertTo(ch[0], CvType.CV_8U)
            y.release()
            add.release()

            // 顺带把色度往中性拉一点，提亮后才不会发黄发脏
            // 注意：pullToNeutral 会返回新 Mat，被替换的原始通道必须先 release，
            // 否则每拖一次美白条就泄漏两块通道内存。
            val c1 = pullToNeutral(ch[1], 128.0, w, a * 0.22)
            ch[1].release()
            ch[1] = c1
            val c2 = pullToNeutral(ch[2], 128.0, w, a * 0.22)
            ch[2].release()
            ch[2] = c2
        }

        if (ruddyAmount > 0f) {
            val a = (ruddyAmount / 100f).coerceIn(0f, 1f)
            val cr = Mat()
            ch[1].convertTo(cr, CvType.CV_32F)
            val add = Mat()
            Core.multiply(w, Scalar(a * 16.0), add)
            Core.add(cr, add, cr)
            cr.convertTo(ch[1], CvType.CV_8U)
            cr.release()
            add.release()
        }

        w.release()
        val merged = Mat()
        Core.merge(ch, merged)
        ch.forEach { it.release() }

        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_YCrCb2BGR)
        merged.release()
        return out
    }

    /** out = v - (v - neutral) * k * w，即按掩码比例把通道值拉向 neutral */
    private fun pullToNeutral(src: Mat, neutral: Double, w: Mat, k: Double): Mat {
        val v = Mat()
        src.convertTo(v, CvType.CV_32F)
        val d = Mat()
        Core.subtract(v, Scalar(neutral), d)
        val kw = Mat()
        Core.multiply(w, Scalar(k), kw)
        Core.multiply(d, kw, d)
        Core.subtract(v, d, v)
        val out = Mat()
        v.convertTo(out, CvType.CV_8U)
        v.release()
        d.release()
        kw.release()
        return out
    }

    // ——————————————————— 祛痘祛斑 ———————————————————

    /**
     * 思路：斑点相对周围皮肤是"局部暗区"。
     * 用原图减去中值滤波结果得到局部对比，低于阈值的暗区即为候选瑕疵，
     * 再用 OpenCV 的 inpaint（TELEA 算法）把该区域用周围纹理填掉。
     */
    fun removeBlemishes(bgr: Mat, skinMask: Mat, faces: List<FaceInfo>, amount: Float): Mat {
        if (amount <= 0f) return bgr
        val a = (amount / 100f).coerceIn(0f, 1f)

        val shortEdge = min(bgr.cols(), bgr.rows())
        val k = max(3, (shortEdge / 110) * 2 + 1) // 中值滤波核必须为奇数

        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val med = Mat()
        Imgproc.medianBlur(gray, med, k)

        // 转到 16 位再相减，否则 CV_8U 会在 0 处截断，负的差异全丢了
        val g16 = Mat()
        med.convertTo(g16, CvType.CV_16S)
        med.release()

        val s16 = Mat()
        gray.convertTo(s16, CvType.CV_16S)
        gray.release()

        val diff = Mat()
        Core.subtract(s16, g16, diff)
        s16.release()
        g16.release()

        val threshold = 8.0 + (1.0 - a) * 24.0 // 强度越高，越浅的暗斑也算瑕疵
        val spots = Mat()
        Core.compare(diff, Scalar(-threshold), spots, Core.CMP_LT)
        diff.release()

        Core.bitwise_and(spots, skinMask, spots)

        val exclude = exclusionMask(bgr.size(), faces)
        val notEx = Mat()
        Core.bitwise_not(exclude, notEx)
        exclude.release()
        Core.bitwise_and(spots, notEx, spots)
        notEx.release()

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(spots, spots, Imgproc.MORPH_OPEN, kernel)
        Imgproc.dilate(spots, spots, kernel)
        kernel.release()

        val out = Mat()
        val radius = (shortEdge / 220.0).coerceIn(2.0, 9.0)
        Photo.inpaint(bgr, spots, out, radius, Photo.INPAINT_TELEA)
        spots.release()
        return out
    }

    /** 眼睛、眉毛、嘴唇都是深/细节区域，磨皮与祛痘都不该碰它们 */
    private fun exclusionMask(size: Size, faces: List<FaceInfo>): Mat {
        val m = Mat.zeros(size, CvType.CV_8UC1)
        for (f in faces) {
            for (eye in listOfNotNull(f.leftEye, f.rightEye)) {
                val px = eye.width * 0.5f
                val py = eye.height * 0.9f
                // 眼睛本体
                Imgproc.rectangle(
                    m,
                    Point((eye.left - px).toDouble(), (eye.top - py).toDouble()),
                    Point((eye.right + px).toDouble(), (eye.bottom + py).toDouble()),
                    Scalar(255.0), -1
                )
                // 眉毛在眼睛正上方，一并排除，避免被磨皮糊掉
                val browH = eye.height * 0.9f
                Imgproc.rectangle(
                    m,
                    Point((eye.left - px).toDouble(), (eye.top - py - browH).toDouble()),
                    Point((eye.right + px).toDouble(), (eye.top - py).toDouble()),
                    Scalar(255.0), -1
                )
            }
            val mo = f.mouth
            if (mo != null) {
                val px = mo.width * 0.5f
                val py = mo.height * 0.9f
                Imgproc.rectangle(
                    m,
                    Point((mo.left - px).toDouble(), (mo.top - py).toDouble()),
                    Point((mo.right + px).toDouble(), (mo.bottom + py).toDouble()),
                    Scalar(255.0), -1
                )
            }
        }
        return m
    }
}
