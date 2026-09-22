package com.station1921.pixelcam.beauty

import android.util.Log
import com.station1921.pixelcam.PixelCamApp
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * 神经超分（Real-ESRGAN ×4，端侧 CPU 推理，OpenCV DNN 后端）。
 *
 * 为什么能跑：本环境本地用 Windows 测 OpenCV 读 ONNX 曾误报 "Can't read ONNX file"，
 * 根因是中文路径（OpenCV C++ 层 fopen 打不开 `E:/办公文件/...`）而非模型问题。
 * App 运行期把 assets 解到 cacheDir，路径无中文，不受此坑影响；且已用同代 OpenCV 4.13
 * 在 ASCII 路径下实测 readNetFromONNX + forward 成功（64→256，输出正确）。
 *
 * ONNX 已修复：补了 Resize 的 roi 常量输入（OpenCV DNN 要求 roi 为真实 blob，
 * 空字符串占位会直接读失败）。文件 = assets/realesrgan_x4.onnx。
 */
object SuperRes {

    private const val TAG = "SuperRes"
    private const val ASSET = "realesrgan_x4.onnx"
    private const val CACHE = "realesrgan_x4.onnx"

    /** 输入最长边上限：4x 后输出 ≤ 4*IN_MAX，避免超大图 OOM */
    private const val IN_MAX = 512

    @Volatile private var net: org.opencv.dnn.Net? = null
    @Volatile private var loadFailed = false

    /** 模型是否已内置（assets 存在），供 UI 决定按钮可用性 */
    fun available(): Boolean {
        if (!PixelCamApp.hasInstance()) return false
        return runCatching { PixelCamApp.instance.assets.list("")?.contains(ASSET) == true }
            .getOrDefault(false)
    }

    private fun ensureNet(): org.opencv.dnn.Net? {
        net?.let { return it }
        if (loadFailed) return null
        if (!OpenCv.ensureLoaded()) { loadFailed = true; return null }
        val file = assetToFile(ASSET, CACHE) ?: run { loadFailed = true; return null }
        net = runCatching { Dnn.readNetFromONNX(file.absolutePath) }
            .getOrElse { e -> Log.w(TAG, "load onnx failed: ${e.message}"); loadFailed = true; null }
        return net
    }

    /**
     * 对 [src]（BGR 3 通道 Mat）做 4x 超分，返回新的 BGR Mat（CV_8U）。
     * 不可用 / 失败返回原 [src] 自身，调用方据此判断是否成功（引用相等即失败）。
     */
    fun enhance(src: Mat): Mat {
        val n = ensureNet() ?: return src
        if (src.empty()) return src
        return runCatching {
            // 统一为 BGR 3 通道
            var bgr = if (src.channels() == 4) {
                val t = Mat(); Imgproc.cvtColor(src, t, Imgproc.COLOR_BGRA2BGR); t
            } else src

            // 限制输入尺寸：过长边缩到 IN_MAX，4x 后输出 ≤ 4*IN_MAX
            val long = maxOf(bgr.cols(), bgr.rows())
            if (long > IN_MAX) {
                val s = IN_MAX.toDouble() / long
                val t = Mat()
                Imgproc.resize(bgr, t, Size((bgr.cols() * s), (bgr.rows() * s)))
                if (bgr !== src) bgr.release()
                bgr = t
            }

            // BGR -> RGB，归一化到 [0,1]
            val rgb = Mat()
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
            val f = Mat()
            rgb.convertTo(f, CvType.CV_32F, 1.0 / 255.0)

            // NCHW blob（已是 RGB，无需 swapRB）
            val blob = Dnn.blobFromImage(f, 1.0, Size(), Scalar(0.0), false, false)
            n.setInput(blob)
            val out = n.forward() // (1,3,H,W) float，范围约 [0,1]

            val h = out.size(2)
            val w = out.size(3)
            val data = FloatArray(out.total().toInt())
            out.get(0, 0, data) // NCHW 线性序：c*H*W + y*W + x

            // 重排为 HWC 的 RGB float 数组（单次 put，避免逐像素分配）
            val hwc = FloatArray(h * w * 3)
            val hw = h * w
            var i = 0
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    hwc[i++] = data[rowOff + x]           // R
                    hwc[i++] = data[hw + rowOff + x]        // G
                    hwc[i++] = data[2 * hw + rowOff + x]    // B
                }
            }

            val rgbOut = Mat(h, w, CvType.CV_32FC3)
            rgbOut.put(0, 0, hwc)
            val rgb8 = Mat()
            rgbOut.convertTo(rgb8, CvType.CV_8U, 255.0)
            val bgrOut = Mat()
            Imgproc.cvtColor(rgb8, bgrOut, Imgproc.COLOR_RGB2BGR)

            blob.release(); f.release(); rgb.release(); rgbOut.release(); rgb8.release(); out.release()
            if (bgr !== src) bgr.release()
            bgrOut
        }.getOrElse { e ->
            Log.w(TAG, "enhance failed: ${e.message}")
            src
        }
    }

    /** 把 assets 里的模型解到 cache（首次），OpenCV 原生层需要真实文件路径 */
    private fun assetToFile(assetName: String, targetName: String): File? {
        if (!PixelCamApp.hasInstance()) return null
        val app = PixelCamApp.instance
        val target = File(app.cacheDir, targetName)
        if (!target.exists()) {
            runCatching {
                app.assets.open(assetName).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        return if (target.exists()) target else null
    }
}
