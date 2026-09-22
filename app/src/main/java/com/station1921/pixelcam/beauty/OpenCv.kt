package com.station1921.pixelcam.beauty

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * OpenCV 初始化与 Bitmap / Mat 互转。
 *
 * initLocal() 会加载打进 APK 的 libopencv_java4.so，
 * 不需要额外安装 OpenCV Manager（那套机制早已废弃）。
 *
 * toBgr / toBitmap 是顶层扩展函数，整个 beauty 包都能直接调用
 * （BeautyEngine 里就是这么用的）。
 */
object OpenCv {

    @Volatile
    private var loaded = false

    /**
     * 首次加载走 @Synchronized：initLocal() 会 dlopen 几十 MB 的 .so，
     * 若两个线程同时首次渲染（例如用户连点两下）各自触发加载，
     * 会在 Mat 构造与 so 初始化之间产生 native 竞态甚至崩溃。
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            loaded = OpenCVLoader.initLocal()
            loaded
        } catch (t: Throwable) {
            false
        }
    }
}

/** Bitmap(ARGB) -> Mat(BGR) */
fun Bitmap.toBgr(): Mat {
    val src = if (config == Bitmap.Config.ARGB_8888) this
    else copy(Bitmap.Config.ARGB_8888, false)

    val rgba = Mat(src.height, src.width, CvType.CV_8UC4)
    Utils.bitmapToMat(src, rgba)
    val bgr = Mat()
    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
    rgba.release()
    return bgr
}

/** Mat(BGR) -> Bitmap(ARGB) */
fun Mat.toBitmap(): Bitmap {
    val rgba = Mat()
    Imgproc.cvtColor(this, rgba, Imgproc.COLOR_BGR2RGBA)
    val bmp = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rgba, bmp)
    rgba.release()
    return bmp
}
