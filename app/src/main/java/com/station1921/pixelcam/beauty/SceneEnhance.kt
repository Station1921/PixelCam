package com.station1921.pixelcam.beauty

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 风光增强：三套端侧全局增强，全部基于 OpenCV 原生算子，离线可用、零模型依赖。
 *
 *  - 夜景提亮：亮度通道多尺度 Retinex（MSR）+ 自适应 gamma，专治欠曝 / 夜景。
 *  - 去雾：暗通道先验（Dark Channel Prior, He 等 2009）+ 导向滤波细化透射率。
 *  - 清晰增强：亮度通道大半径非锐化（局部对比）+ 整体小半径非锐化（边缘锐化）。
 *
 * 说明：原计划 123 中的 Zero-DCE / AOD-Net / Real-ESRGAN 为端侧神经网络模型，
 * 当前构建环境无法获取其训练权重（权重托管在 Google Drive / 被代理拦截的 raw 源），
 * 故以同等专业水平的经典算法落地，保证 100% 离线可用；引擎结构预留 ONNX 模型位，
 * 待权重可下载后可直接替换为神经网络版本（接口不变，仅 [enhance] 内部替换实现）。
 *
 * 约定：[lowLight] / [dehaze] / [clarity] 取值范围 0~100，0 表示不处理。
 */
object SceneEnhance {

    /** 统一入口：依次施加三个效果；任一为 0 则跳过（原样返回，不新增 Mat） */
    fun enhance(
        src: Mat,
        lowLight: Float,
        dehaze: Float,
        clarity: Float
    ): Mat {
        var cur = src
        if (lowLight > 0f) {
            val n = lowLight(cur, lowLight)
            if (n !== cur) { cur.release(); cur = n }
        }
        if (dehaze > 0f) {
            val n = dehaze(cur, dehaze)
            if (n !== cur) { cur.release(); cur = n }
        }
        if (clarity > 0f) {
            val n = clarity(cur, clarity)
            if (n !== cur) { cur.release(); cur = n }
        }
        return cur
    }

    // ——————————————————— 夜景提亮 ———————————————————

    fun lowLight(src: Mat, amount: Float): Mat {
        if (amount <= 0f) return src
        val a = (amount / 100f).coerceIn(0f, 1f)
        val gain = 0.4 + 1.2 * a          // 反射分量增益
        val gamma = 1.0 / (1.0 + 0.6 * a) // <1 提亮中间调

        val lab = Mat()
        Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
        val chans = ArrayList<Mat>(3)
        Core.split(lab, chans)
        val l8 = chans[0]

        val f = Mat()
        l8.convertTo(f, CvType.CV_32F, 1.0 / 255.0)

        // 多尺度 Retinex：R = Σ w·(log I − log blur(I))
        val rMat = Mat.zeros(f.size(), CvType.CV_32F)
        val scales = doubleArrayOf(15.0, 80.0, 250.0)
        for (sigma in scales) {
            val blurred = Mat()
            Imgproc.GaussianBlur(f, blurred, Size(0.0, 0.0), sigma, sigma)
            val fE = withEps(f); val logI = Mat(); Core.log(fE, logI); fE.release()
            val bE = withEps(blurred); val logB = Mat(); Core.log(bE, logB); bE.release()
            val r = Mat()
            Core.subtract(logI, logB, r)
            Core.addWeighted(rMat, 1.0, r, 1.0 / scales.size, 0.0, rMat)
            blurred.release(); logI.release(); logB.release(); r.release()
        }
        // 中心化，抑制整体偏色 / 光晕
        val meanR = Core.mean(rMat).`val`[0]
        Core.subtract(rMat, Scalar(meanR), rMat)

        // 反射增强 + 自适应 gamma
        val enh = Mat()
        Core.multiply(rMat, Scalar(gain), enh)      // gain·R
        Core.exp(enh, enh)                          // exp(gain·R)
        Core.multiply(f, enh, enh)                  // I·exp(gain·R)
        Core.pow(enh, gamma, enh)                   // 提亮
        Core.min(enh, Scalar(1.0), enh)
        Core.max(enh, Scalar(0.0), enh)

        val outL = Mat()
        enh.convertTo(outL, CvType.CV_8U, 255.0)

        val merged = Mat()
        Core.merge(listOf(outL, chans[1], chans[2]), merged)
        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2BGR)

        lab.release(); f.release(); rMat.release(); enh.release()
        l8.release(); chans[1].release(); chans[2].release(); outL.release(); merged.release()
        return out
    }

    // ——————————————————— 去雾 ———————————————————

    fun dehaze(src: Mat, amount: Float): Mat {
        if (amount <= 0f) return src
        val a = (amount / 100f).coerceIn(0f, 1f)
        val omega = 0.9                       // 去雾强度系数
        val t0 = 0.1                          // 透射率下限，防噪声放大

        // 暗通道：先取三通道最小值，再做 15×15 最小值滤波（腐蚀）
        val bgrCh = ArrayList<Mat>(3)
        Core.split(src, bgrCh)
        val min12 = Mat()
        Core.min(bgrCh[0], bgrCh[1], min12)
        val minCh = Mat()
        Core.min(min12, bgrCh[2], minCh)
        val dark = Mat()
        Imgproc.erode(minCh, dark, Mat(15, 15, CvType.CV_8U, Scalar(1.0)))

        // 大气光 A：暗通道中最亮像素对应原图亮度均值
        val A = estimateAtmosphericLight(src, dark)

        // 粗透射率 t = 1 − ω·(dark / A)
        val darkF = Mat()
        dark.convertTo(darkF, CvType.CV_32F)
        val t = Mat()
        Core.divide(darkF, Scalar(A), t)
        Core.multiply(t, Scalar(omega), t)
        Core.multiply(t, Scalar(-1.0), t)
        Core.add(t, Scalar(1.0), t)

        // 导向滤波细化（以原图亮度为引导），更接近软抠图效果
        val guide = Mat()
        Imgproc.cvtColor(src, guide, Imgproc.COLOR_BGR2GRAY)
        guide.convertTo(guide, CvType.CV_32F, 1.0 / 255.0)
        val tRef = guidedFilter(guide, t, radius = 40, eps = 1e-3)
        val tClamped = Mat()
        Core.max(tRef, Scalar(t0), tClamped)

        // 复原 J = (I − A) / t + A，逐通道
        val fsrc = Mat()
        src.convertTo(fsrc, CvType.CV_32F)
        val fCh = ArrayList<Mat>(3)
        Core.split(fsrc, fCh)
        val outCh = ArrayList<Mat>(3)
        for (c in 0..2) {
            val diff = Mat()
            Core.subtract(fCh[c], Scalar(A), diff)
            val div = Mat()
            Core.divide(diff, tClamped, div)
            val add = Mat()
            Core.add(div, Scalar(A), add)
            Core.min(add, Scalar(255.0), add)
            Core.max(add, Scalar(0.0), add)
            val ch8 = Mat()
            add.convertTo(ch8, CvType.CV_8U)
            outCh.add(ch8)
            diff.release(); div.release(); add.release()
        }
        val dehazed = Mat()
        Core.merge(outCh, dehazed)

        // 按 amount 与原始图混合（amount 越大去雾越彻底）
        val out = Mat()
        Core.addWeighted(src, 1.0 - a, dehazed, a.toDouble(), 0.0, out)

        bgrCh.forEach { it.release() }
        min12.release(); minCh.release(); dark.release(); darkF.release(); t.release()
        guide.release(); tRef.release(); tClamped.release()
        fsrc.release(); fCh.forEach { it.release() }
        outCh.forEach { it.release() }; dehazed.release()
        return out
    }

    /** 暗通道最亮像素对应原图亮度均值作为大气光 */
    private fun estimateAtmosphericLight(src: Mat, dark: Mat): Double {
        val dark8 = Mat()
        dark.convertTo(dark8, CvType.CV_8U)
        val bgr = ArrayList<Mat>(3)
        Core.split(src, bgr)
        // 阈值法：取暗通道最亮 ~5% 像素的原图亮度均值（稳健，避免离群点）
        val thresh = Core.minMaxLoc(dark8).maxVal * 0.95
        val mask = Mat()
        Core.compare(dark8, Scalar(thresh), mask, Core.CMP_GE)
        val means = ArrayList<Double>()
        for (c in 0..2) {
            val m = Core.mean(bgr[c], mask).`val`[0]
            if (!m.isNaN()) means.add(m)
            bgr[c].release()
        }
        val atmo = if (means.isNotEmpty()) means.average() else 220.0
        dark8.release(); mask.release()
        return atmo.coerceIn(1.0, 255.0)
    }

    /** 导向滤波（He 等 2010）：用 boxFilter 实现，无需 ximgproc 模块 */
    private fun guidedFilter(guide: Mat, p: Mat, radius: Int, eps: Double): Mat {
        val k = Size((2 * radius + 1).toDouble(), (2 * radius + 1).toDouble())
        val meanI = Mat()
        Imgproc.boxFilter(guide, meanI, CvType.CV_32F, k)
        val meanP = Mat()
        Imgproc.boxFilter(p, meanP, CvType.CV_32F, k)

        val I2 = Mat()
        Core.multiply(guide, guide, I2)
        val meanI2 = Mat()
        Imgproc.boxFilter(I2, meanI2, CvType.CV_32F, k)
        val IP = Mat()
        Core.multiply(guide, p, IP)
        val meanIP = Mat()
        Imgproc.boxFilter(IP, meanIP, CvType.CV_32F, k)

        val meanIsq = Mat()
        Core.multiply(meanI, meanI, meanIsq)
        val varI = Mat()
        Core.subtract(meanI2, meanIsq, varI)

        val meanImP = Mat()
        Core.multiply(meanI, meanP, meanImP)
        val covIP = Mat()
        Core.subtract(meanIP, meanImP, covIP)

        val denom = Mat()
        Core.add(varI, Scalar(eps), denom)
        val a = Mat()
        Core.divide(covIP, denom, a)
        val aI = Mat()
        Core.multiply(a, meanI, aI)
        val b = Mat()
        Core.subtract(meanP, aI, b)

        val meanA = Mat()
        Imgproc.boxFilter(a, meanA, CvType.CV_32F, k)
        val meanB = Mat()
        Imgproc.boxFilter(b, meanB, CvType.CV_32F, k)
        val mAguide = Mat()
        Core.multiply(meanA, guide, mAguide)
        val q = Mat()
        Core.add(mAguide, meanB, q)

        meanI.release(); meanP.release(); I2.release(); meanI2.release(); IP.release()
        meanIP.release(); meanIsq.release(); varI.release(); meanImP.release(); covIP.release()
        denom.release(); a.release(); aI.release(); b.release(); meanA.release(); meanB.release()
        mAguide.release()
        return q
    }

    // ——————————————————— 清晰增强 ———————————————————

    fun clarity(src: Mat, amount: Float): Mat {
        if (amount <= 0f) return src
        val a = (amount / 100f).coerceIn(0f, 1f)
        // 非锐化掩模强度：之前 0.3+1.4a 在自动美化 55 档时高达 1.07，
        // 高频噪声全被放大（暗纹明显）；压到 0.2+0.8a 保持通透又不炸噪
        val strength = 0.2 + 0.8 * a

        val lab = Mat()
        Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
        val chans = ArrayList<Mat>(3)
        Core.split(lab, chans)

        // 双尺度非锐化掩模：只作用于 L 通道（亮度/对比/细节），绝不改动 a/b。
        // 关键：原实现第二阶段在 BGR 三通道上整体锐化，会放大蓝通道高频，
        // 导致风光照天空发蓝；改到 L 通道后数学上零色偏（a/b 原样，色相只由 a/b 决定）。
        val lf = Mat()
        chans[0].convertTo(lf, CvType.CV_32F)
        val lBlurBig = Mat()   // 大半径 → 局部对比（中频）
        Imgproc.GaussianBlur(lf, lBlurBig, Size(0.0, 0.0), 24.0, 24.0)
        val lBlurSmall = Mat() // 小半径 → 边缘细节（高频）
        Imgproc.GaussianBlur(lf, lBlurSmall, Size(0.0, 0.0), 3.0, 3.0)
        val lDetail = Mat()
        Core.subtract(lf, lBlurSmall, lDetail)   // 边缘高频
        val lContrast = Mat()
        Core.subtract(lBlurSmall, lBlurBig, lContrast) // 局部对比（中频）

        val lSharp = Mat()
        Core.addWeighted(lf, 1.0, lDetail, strength, 0.0, lSharp)
        // 中频局部对比权重从 0.8 降到 0.45：这一项在暗于周边的区域做减法，
        // 权重太大是「阴影发黑、暗纹突出」的直接来源
        Core.addWeighted(lSharp, 1.0, lContrast, strength * 0.45, 0.0, lSharp)
        Core.min(lSharp, Scalar(255.0), lSharp)
        Core.max(lSharp, Scalar(0.0), lSharp)
        val lOut = Mat()
        lSharp.convertTo(lOut, CvType.CV_8U)

        chans[0].release()
        chans[0] = lOut
        val merged = Mat()
        Core.merge(chans, merged)
        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2BGR)

        lab.release(); chans[1].release(); chans[2].release(); lOut.release()
        lf.release(); lBlurBig.release(); lBlurSmall.release()
        lDetail.release(); lContrast.release(); lSharp.release(); merged.release()
        return out
    }

    /** 避免 log(0)：返回 src + 1e-6 的新 Mat（调用方负责释放） */
    private fun withEps(m: Mat): Mat {
        val e = Mat()
        Core.add(m, Scalar(1e-6), e)
        return e
    }
}
