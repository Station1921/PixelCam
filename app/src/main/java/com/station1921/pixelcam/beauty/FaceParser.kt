package com.station1921.pixelcam.beauty

import com.station1921.pixelcam.PixelCamApp
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 端侧人脸解析：用 OpenCV DNN 跑 face-parsing ONNX 模型，
 * 输出像素级语义分割，取「皮肤」类生成精确掩码。
 *
 * 模型已打包进安装包（assets/face_parsing.onnx）：
 *   yakhyo/face-parsing 的 resnet18.onnx（第三方转存到 HuggingFace 的版本，
 *   权重来源明确为 yakhyo/face-parsing），与下方预处理完全兼容。
 * 该模型：输入 512×512；归一化 (x/255 - mean)/std（mean=[.485,.456,.406] std=[.229,.224,.225]）；
 * 输出 [1,C,H,W]，CelebAMask-HQ 19 类，皮肤类编号 = 1。
 *
 * 通过 [available] 判断模型是否就绪（打包后始终可用），由 [BeautyEngine] 据用户开关决定是否启用；
 * 若不可用（如未来移除资产）则自动回退到传统肤色椭圆掩码。全程离线，不联网。
 */
object FaceParser {

    const val MODEL_FILE_NAME = "face_parsing.onnx"
    private const val INPUT = 512
    private val MEAN = doubleArrayOf(0.485, 0.456, 0.406) // RGB
    private val STD = doubleArrayOf(0.229, 0.224, 0.225)  // RGB
    private const val SKIN_CLASS = 1

    @Volatile
    private var net: Net? = null

    @Volatile
    private var failed = false

    /** 已就绪：模型已内置进安装包（assets），始终可用 */
    fun available(): Boolean {
        if (net != null) return true
        return try {
            PixelCamApp.instance.assets.list("")?.contains(MODEL_FILE_NAME) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /** 模型替换或设置变更后调用，清空缓存让下次推理重新加载 */
    fun reset() {
        net = null
        failed = false
    }

    @Synchronized
    private fun ensureNet(): Net? {
        if (net != null) return net
        if (failed) return null
        try {
            val file = assetToFile(MODEL_FILE_NAME, MODEL_FILE_NAME)
            if (file == null) {
                failed = true
                return null
            }
            val n = Dnn.readNetFromONNX(file.absolutePath)
            n.setPreferableTarget(Dnn.DNN_TARGET_CPU)
            net = n
        } catch (t: Throwable) {
            failed = true
            net = null
        }
        return net
    }

    /** 返回 0/255 单通道皮肤掩码（已羽化、与输入同尺寸），失败返回 null */
    fun parse(bgr: Mat): Mat? {
        val n = ensureNet() ?: return null
        return try {
            // BGR -> RGB
            val rgb = Mat()
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
            Imgproc.resize(rgb, rgb, Size(INPUT.toDouble(), INPUT.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

            // 归一化 (x/255 - mean)/std
            val f = Mat()
            rgb.convertTo(f, CvType.CV_32F, 1.0 / 255.0)
            val chans = ArrayList<Mat>(3)
            Core.split(f, chans)
            for (i in 0..2) {
                Core.subtract(chans[i], Scalar(MEAN[i]), chans[i])
                Core.divide(chans[i], Scalar(STD[i]), chans[i])
            }
            Core.merge(chans, f)
            chans.forEach { it.release() }
            rgb.release()

            // HWC(已归一化) -> NCHW blob；通道已是 RGB，无需再 swap
            val blob = Dnn.blobFromImage(
                f, 1.0, Size(INPUT.toDouble(), INPUT.toDouble()),
                Scalar(0.0, 0.0, 0.0), false, false
            )
            f.release()
            n.setInput(blob)
            val out = n.forward() // [1, C, H, W]
            blob.release()

            val mask = postprocess(out, INPUT, INPUT)
            out.release()
            if (mask == null) return null

            val up = Mat()
            Imgproc.resize(mask, up, bgr.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            mask.release()
            val sigma = max(3.0, min(bgr.cols(), bgr.rows()) * 0.006)
            Imgproc.GaussianBlur(up, up, Size(0.0, 0.0), sigma)
            up
        } catch (t: Throwable) {
            null
        }
    }

    /** out: [1, C, H, W] 浮点；逐像素 argmax，取 skin 类二值掩码 [H, W]（CV_8U 0/255） */
    private fun postprocess(out: Mat, h: Int, w: Int): Mat? {
        val c = out.size(1)
        if (c <= 0 || out.size(2) != h || out.size(3) != w) return null
        val r = out.reshape(c, h * w) ?: return null
        val rows = h * w

        val best = Mat()
        val bestIdx = Mat(rows, 1, CvType.CV_32F)
        Core.extractChannel(r, best, 0)
        bestIdx.setTo(Scalar(0.0))

        for (k in 1 until c) {
            val ch = Mat()
            Core.extractChannel(r, ch, k)
            val gt = Mat()
            Core.compare(ch, best, gt, Core.CMP_GT) // 255 表示该通道更大
            ch.copyTo(best, gt) // 更新最大值
            val kMat = Mat(gt.size(), CvType.CV_32F, Scalar(k.toDouble()))
            kMat.copyTo(bestIdx, gt) // 记录 argmax 索引
            ch.release()
            gt.release()
            kMat.release()
        }

        val skin = Mat()
        Core.compare(bestIdx, Scalar(SKIN_CLASS.toDouble()), skin, Core.CMP_EQ) // 255 表示皮肤类
        best.release()
        bestIdx.release()
        val out8 = Mat()
        skin.convertTo(out8, CvType.CV_8U)
        skin.release()
        return out8
    }

    /** 把 assets 里的模型解到 cache（首次），OpenCV 原生层需要真实文件路径 */
    private fun assetToFile(assetName: String, targetName: String): File? {
        return try {
            val app = PixelCamApp.instance
            val target = File(app.cacheDir, targetName)
            if (!target.exists()) {
                app.assets.open(assetName).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            if (target.exists()) target else null
        } catch (_: Throwable) {
            null
        }
    }
}
