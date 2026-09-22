package com.station1921.pixelcam.transfer

import android.content.Context
import android.content.SharedPreferences

/**
 * 最近一次成功连上的相机连接方式 + 重连所需凭据。
 *
 * 用途：后台常驻接收服务（[com.station1921.pixelcam.service.AutoReceiveService]）
 * 进程被系统回收后，靠 [START_STICKY] 重启，但静态单例 [SourceHolder] 随之清空。
 * 凭这里持久化的凭据，服务能在不重新打开 App、也不重新弹系统联网确认框的前提下，
 * 自行把同一条相机连接重建出来——只要 OS 层仍连着相机 WiFi / 相机仍插着 USB。
 */
enum class ConnType { NONE, WIFI_SD, FTP, USB }

data class ConnectionProfile(
    val type: ConnType = ConnType.NONE,
    /** 相机 WiFi 热点名（无外网，靠它判断 OS 是否还连着相机） */
    val ssid: String = "",
    /** 相机 WiFi 热点密码（仅用于记录，重连时 OS 已记住，无需再用） */
    val password: String = "",
    // —— FTP 专属 ——
    val ftpHost: String = "",
    val ftpPort: Int = 21,
    val ftpUser: String = "",
    val ftpPass: String = "",
    val ftpPath: String = "/"
)

object ConnectionProfilePrefs {
    private const val FILE = "conn_profile"
    private const val K_TYPE = "type"
    private const val K_SSID = "ssid"
    private const val K_PWD = "pwd"
    private const val K_FTP_HOST = "ftp_host"
    private const val K_FTP_PORT = "ftp_port"
    private const val K_FTP_USER = "ftp_user"
    private const val K_FTP_PASS = "ftp_pass"
    private const val K_FTP_PATH = "ftp_path"

    fun load(context: Context): ConnectionProfile {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val type = when (sp.getString(K_TYPE, "NONE")) {
            "WIFI_SD" -> ConnType.WIFI_SD
            "FTP" -> ConnType.FTP
            "USB" -> ConnType.USB
            else -> ConnType.NONE
        }
        return ConnectionProfile(
            type = type,
            ssid = sp.getString(K_SSID, "") ?: "",
            password = sp.getString(K_PWD, "") ?: "",
            ftpHost = sp.getString(K_FTP_HOST, "") ?: "",
            ftpPort = sp.getInt(K_FTP_PORT, 21),
            ftpUser = sp.getString(K_FTP_USER, "") ?: "",
            ftpPass = sp.getString(K_FTP_PASS, "") ?: "",
            ftpPath = sp.getString(K_FTP_PATH, "/") ?: "/"
        )
    }

    fun save(context: Context, p: ConnectionProfile) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            putString(K_TYPE, p.type.name)
            putString(K_SSID, p.ssid)
            putString(K_PWD, p.password)
            putString(K_FTP_HOST, p.ftpHost)
            putInt(K_FTP_PORT, p.ftpPort)
            putString(K_FTP_USER, p.ftpUser)
            putString(K_FTP_PASS, p.ftpPass)
            putString(K_FTP_PATH, p.ftpPath)
            apply()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
