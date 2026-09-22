package com.station1921.pixelcam.transfer

import android.content.Context
import android.util.Base64

/**
 * 记录用户成功连接过的 WiFi 热点密码，避免重复输入。
 * 以 SSID 的 UTF-8 Base64 为 key，防止 SSID 含特殊字符导致 SharedPreferences key 非法。
 */
object WifiPasswordStore {

    private const val FILE = "wifi_passwords"

    private fun key(ssid: String): String {
        val raw = ssid.toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun get(context: Context, ssid: String): String {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(key(ssid), "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun put(context: Context, ssid: String, password: String) {
        if (ssid.isBlank()) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(key(ssid), password)
            .apply()
    }

    fun remove(context: Context, ssid: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(key(ssid))
            .apply()
    }
}
