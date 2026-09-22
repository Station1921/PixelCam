package com.station1921.pixelcam.transfer

import java.util.Locale

/**
 * 解析标准 WiFi 配置二维码。
 * 格式：WIFI:S:<SSID>;T:WPA;P:<password>;H:true;;
 * 分号、冒号、反斜杠在值内需用反斜杠转义。
 */
object WifiQrParser {

    data class Result(val ssid: String, val password: String, val hidden: Boolean)

    fun parse(raw: String?): Result? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (!trimmed.uppercase(Locale.ROOT).startsWith("WIFI:")) return null

        val map = parseFields(trimmed.substringAfter("WIFI:"))
        val ssid = unescape(map["S"] ?: return null)
        if (ssid.isBlank()) return null
        val password = unescape(map["P"] ?: "")
        val hidden = map["H"]?.uppercase(Locale.ROOT) == "TRUE"
        return Result(ssid, password, hidden)
    }

    /** 按未转义的分号拆分字段，再按第一个冒号拆 key/value */
    private fun parseFields(payload: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val sb = StringBuilder()
        var i = 0
        while (i <= payload.length) {
            val c = if (i < payload.length) payload[i] else ';'
            if (c == '\\' && i + 1 < payload.length) {
                sb.append(payload[i + 1])
                i += 2
                continue
            }
            if (c == ';') {
                val part = sb.toString()
                sb.clear()
                val colon = part.indexOf(':')
                if (colon > 0) {
                    out[part.substring(0, colon)] = part.substring(colon + 1)
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return out
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1])
                i += 2
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    // ——————————————— 奥林巴斯 OI.Share 私有连接码 ———————————————

    /**
     * 识别奥林巴斯相机屏幕上的连接二维码。
     *
     * OI.Share 的「设备连接」二维码不是标准 WIFI: 格式，内容形如：
     * `OI2.3.R1J-JLNA1G1UMPU**+Z,W/YZ-X3X,UMPU**+Z,%+*+/-`
     * 这是奥林巴斯自有的加密编码（SSID/密码均不可离线还原），本 App 解不出密码，
     * 但**能认出它是奥林巴斯码**——识别后走「查找相机热点 → 引导连接」的流程。
     */
    fun isOlympusQr(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val t = raw.trim()
        return t.startsWith("OI2.") || t.startsWith("OI.OLYMPUS")
    }

    /** 奥林巴斯热点 SSID 特征：如 `E-M10MKIV-P-BJGB23326`、含 OLYMPUS / OM-System 等 */
    private val OLYMPUS_SSID = Regex("(?i)(olympus|om-system|omds|e-m\\d|tg-\\d|stylus|pen-[a-z])|-p-[a-z0-9]{4,}")

    /** 判断一个热点名是不是奥林巴斯相机热点 */
    fun looksLikeOlympusAp(ssid: String): Boolean = OLYMPUS_SSID.containsMatchIn(ssid)
}
