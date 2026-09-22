package com.station1921.pixelcam.util

import android.media.ExifInterface
import java.io.File

/**
 * 把 [source] 的 EXIF 复制到 [dest]。
 *
 * 为什么需要：AI 自动美化走 Bitmap 重编码（compress JPEG），会丢掉原始 EXIF；
 * 而首页/浏览页展示的 ISO / 光圈 / 快门 / 焦段正是读自 EXIF（见 [readExif]），
 * 不拷贝的话美化副本这四个参数全会变空。原图始终保留，这里只补副本。
 *
 * 逐条 best-effort 复制常用拍摄参数 TAG（含 GPS / 时间 / 机型），
 * 个别类型写不进就跳过，不影响其余字段。覆盖 [readExif] 用到的全部字段。
 */
private val COPY_TAGS = arrayOf(
    ExifInterface.TAG_ORIENTATION,
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_SOFTWARE,
    ExifInterface.TAG_ISO,
    "PhotographicSensitivity",
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_APERTURE,
    "ShutterSpeed",
    ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_EXPOSURE_PROGRAM,
    "ExposureBiasValue",
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_IMAGE_WIDTH,
    ExifInterface.TAG_IMAGE_LENGTH
)

fun copyExif(source: File, dest: File) {
    val srcExif = runCatching { ExifInterface(source.absolutePath) }.getOrNull() ?: return
    val dstExif = runCatching { ExifInterface(dest.absolutePath) }.getOrNull() ?: return
    var changed = false
    for (tag in COPY_TAGS) {
        val v = srcExif.getAttribute(tag) ?: continue
        runCatching { dstExif.setAttribute(tag, v) }.onSuccess { changed = true }
    }
    if (changed) runCatching { dstExif.saveAttributes() }
}
