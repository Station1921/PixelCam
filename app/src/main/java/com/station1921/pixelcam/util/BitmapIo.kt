package com.station1921.pixelcam.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.max

/**
 * 按目标边长解码图片，并在必要时根据 EXIF 方向纠正。
 *
 * 相机拍的 JPG 十有八九带 EXIF orientation，不处理的话
 * 竖拍照片会横着显示，人脸检测也会跟着失效。
 */
object BitmapIo {

    fun decode(file: File, targetEdge: Int): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null

        val long = max(probe.outWidth, probe.outHeight)
        // 让输出长边 ≤ targetEdge：inSampleSize 只支持 2 的幂，
        // 直接按 long/sample > target 步进，避免 8000px 大图因整除边界
        // 而 sample 停留在 1（那样会整图解码、轻松吃爆内存）。
        var sample = 1
        while (long / sample > targetEdge) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return applyExif(file, raw)
    }

    private fun applyExif(file: File, bitmap: Bitmap): Bitmap {
        val degrees = when (readOrientation(file)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun readOrientation(file: File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * 视频首帧缩略图：相机视频在相册网格里也该有个画面而不是空白。
     * 取第 1 帧并按目标边长等比缩小；失败（不支持格式/损坏）返回 null。
     */
    fun videoFrame(file: File, targetEdge: Int = 256): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.frameAtTime ?: return null
            val long = max(frame.width, frame.height)
            val sample = (long / targetEdge).coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, frame.width / sample, frame.height / sample, true)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
