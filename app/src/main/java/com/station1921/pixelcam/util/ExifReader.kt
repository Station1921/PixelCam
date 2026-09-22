package com.station1921.pixelcam.util

import android.media.ExifInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 从相机照片的 EXIF 读出拍摄参数，作为「拍照页参数卡」的数据来源。
 *
 * 关键点：本 App 连的是外置相机（WiFi/USB SD），没有走系统 Camera2，
 * 拿不到实时参数流；但相机写卡的 JPEG 都带 EXIF，里面就有 ISO / 光圈 / 快门 / 焦段。
 * 收到一张新照片就顺手解析一次，参数卡就显示「相机真实的当前设置」，
 * 而不是让用户手填。焦段是镜头物理值，只读不写。
 *
 * 全部 best-effort：RAW / 异常 / 无 EXIF 都返回 null，UI 维持占位「—」。
 */
data class CameraSettings(
    val iso: String = "—",
    val aperture: String = "—",
    val shutter: String = "—",
    val focal: String = "—",
    /**
     * 白平衡（备忘值，默认自动）：
     * - 预设键：auto/daylight/cloudy/tungsten/fluorescent/shade
     * - 自定义：纯数字串表示色温 Kelvin（如 "5000"）
     */
    val whiteBalance: String = "auto"
) {
    fun isEmpty() = iso == "—" && aperture == "—" && shutter == "—" && focal == "—"

    /** 白平衡 → 中文标签（与手机相机一致）；自定义色温显示为「5000K」 */
    fun wbLabel(): String {
        // 自定义：数值串即色温 Kelvin
        whiteBalance.toIntOrNull()?.let { return "${it}K" }
        return when (whiteBalance) {
            "daylight" -> "日光"
            "cloudy" -> "阴天"
            "tungsten" -> "白炽灯"
            "fluorescent" -> "荧光灯"
            "shade" -> "阴影"
            else -> "自动"
        }
    }
}

/** 解析 "a/b" 或 "3.5" 形式的数值；不是有理数就按小数处理 */
private fun parseRational(s: String?): Double? {
    if (s.isNullOrBlank()) return null
    return try {
        if (s.contains('/')) {
            val (a, b) = s.split('/', limit = 2)
            val den = b.toDouble()
            if (den == 0.0) null else a.toDouble() / den
        } else {
            s.toDouble()
        }
    } catch (_: Exception) {
        null
    }
}

/** 快门：曝光时间（秒）→ 友好串。1/200s、2s 等 */
private fun formatShutter(t: Double): String = when {
    t >= 1.0 -> {
        val i = t.toInt()
        if (Math.abs(t - i) < 0.01) "${i}s" else String.format("%.1fs", t)
    }
    t > 0.0 -> "1/${Math.round(1.0 / t)}s"
    else -> "—"
}

/** 光圈：F 值 → f/2.8 */
private fun formatAperture(f: Double): String {
    if (f <= 0.0) return "—"
    val s = String.format("%.1f", f)
    return "f/$s"
}

fun readExif(file: File): CameraSettings? {
    val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull() ?: return null

    val isoRaw = exif.getAttribute(ExifInterface.TAG_ISO)
        ?: exif.getAttribute("PhotographicSensitivity")
    val iso = isoRaw?.toIntOrNull()?.toString() ?: "—"

    val aperture = formatAperture(parseRational(exif.getAttribute(ExifInterface.TAG_F_NUMBER)) ?: 0.0)

    val shutter = formatShutter(parseRational(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)) ?: 0.0)

    val focalMm = parseRational(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
    val focal = if (focalMm != null && focalMm > 0.0) "${Math.round(focalMm)}mm" else "—"

    val result = CameraSettings(iso, aperture, shutter, focal)
    return if (result.isEmpty()) null else result
}

/**
 * 拍照页「参数卡」的全局共享持有者。
 *
 * 为什么不放在 ViewModel 里：收照片的有**两条路径**——界面里的导入，和后台常驻接收服务。
 * 之前只有界面导入才解析 EXIF，后台自动收的照片不更新参数卡，
 * 表现为「开了后台接收后 ISO 一栏经常拿不到/不刷新」。
 * 现在谁收到照片谁往这里写，ViewModel 只是把这条流暴露给界面。
 */
object CameraSettingsHolder {

    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    /** 用新照片的 EXIF 刷新参数；白平衡是用户备忘值，EXIF 拿不到具体预设，原样保留 */
    fun applyExif(s: CameraSettings) {
        _settings.value = s.copy(whiteBalance = _settings.value.whiteBalance)
    }

    /** 拍照页滑块/预设覆盖某项（焦段只读自 EXIF，不在这里改） */
    fun setSetting(
        iso: String? = null,
        aperture: String? = null,
        shutter: String? = null,
        whiteBalance: String? = null
    ) {
        val cur = _settings.value
        _settings.value = CameraSettings(
            iso = iso ?: cur.iso,
            aperture = aperture ?: cur.aperture,
            shutter = shutter ?: cur.shutter,
            focal = cur.focal,
            whiteBalance = whiteBalance ?: cur.whiteBalance
        )
    }
}
