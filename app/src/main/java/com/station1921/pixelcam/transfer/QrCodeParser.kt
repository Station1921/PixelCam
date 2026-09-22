package com.station1921.pixelcam.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 纯离线二维码解析（ZXing），不依赖 Google Play 服务。
 * 支持三条入口：图片 URI、Bitmap、以及相机预览帧的亮度平面（YUV 的 Y 平面）。
 */
object QrCodeParser {

    private fun reader() = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    /** 从 URI（相册/拍照返回）读取并解析二维码。 */
    fun parseUri(context: Context, uri: Uri?): WifiQrParser.Result? {
        if (uri == null) return null
        val bmp = loadBitmap(context, uri) ?: return null
        return parseBitmap(bmp)
    }

    /** 直接从 Bitmap 解析二维码。 */
    fun parseBitmap(bitmap: Bitmap): WifiQrParser.Result? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val raw = decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))))
        return raw?.let { WifiQrParser.parse(it) }
    }

    /**
     * 从相机预览帧的亮度平面解析二维码（实时扫码用）。
     *
     * @param data   Y 平面数据，长度正好是 width*height（行间无 padding，调用方负责把
     *               CameraX 的 rowStride 对齐问题处理掉）
     */
    fun parseLuminance(data: ByteArray, width: Int, height: Int): WifiQrParser.Result? {
        val raw = decodeLuminance(data, width, height) ?: return null
        return WifiQrParser.parse(raw)
    }

    /** 与 [parseLuminance] 同源，但返回二维码原始文本（提示/诊断用） */
    fun decodeLuminance(data: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || data.size < width * height) return null
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        return decode(BinaryBitmap(HybridBinarizer(source)))
    }

    private fun decode(binary: BinaryBitmap): String? =
        runCatching { reader().decode(binary).text }.getOrNull()

    /** 从相册/Content URI 加载图片，过大时采样到 1920 长边以内，避免 OOM。 */
    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return runCatching {
            // 1) 先探测尺寸
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null

            // 2) 计算采样率，保证长边 ≤ 1920
            val long = maxOf(options.outWidth, options.outHeight)
            var sample = 1
            while (long / sample > 1920) sample *= 2

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            }
        }.getOrNull()
    }
}
