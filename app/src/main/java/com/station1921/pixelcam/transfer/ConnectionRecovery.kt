package com.station1921.pixelcam.transfer

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.station1921.pixelcam.data.PhotoStore

/**
 * 进程被杀、[com.station1921.pixelcam.service.AutoReceiveService] 经 START_STICKY 重建后，
 * 用持久化的 [ConnectionProfile] 把相机连接恢复出来。
 *
 * 设计要点：
 * - 不重新弹系统联网确认框：OS 层 WiFi 关联在 App 进程死亡后仍然保留，
 *   只要 OS 还连着相机热点，我们直接把进程重新绑定到这条网络即可；
 *   OS 已断开（相机 WiFi 休眠/用户切走）时静默返回 false，等用户在 App 内重连。
 * - USB 同理：设备仍插着且已授权就直接重开会话；否则等下一次插入广播。
 */
suspend fun recoverConnection(context: Context): Boolean {
    val profile = ConnectionProfilePrefs.load(context)
    if (profile.type == ConnType.NONE) return false
    val cacheDir = PhotoStore.remoteCacheDir(context)

    return when (profile.type) {
        ConnType.WIFI_SD -> recoverWifiSd(context, profile, cacheDir)
        ConnType.FTP -> recoverFtp(profile, cacheDir)
        ConnType.USB -> recoverUsb(context, cacheDir)
        else -> false
    }
}

private suspend fun recoverWifiSd(
    context: Context,
    profile: ConnectionProfile,
    cacheDir: java.io.File
): Boolean {
    val app = context.applicationContext
    val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

    // OS 必须仍连着相机热点；否则后台无法静默重连（也不该弹确认框）
    val ssidNow = wifi.connectionInfo?.ssid?.trim('"')
    if (ssidNow != profile.ssid) return false

    // 找到相机 WiFi 对应的 Network 并把进程绑定过去（避免 socket 走错网络）
    val net = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    if (net != null) {
        runCatching { cm.bindProcessToNetwork(net) }
        NetworkHolder.network = net
    }

    // 主机地址：优先用网关（相机热点几乎都是它），其次常见默认地址
    val gateway = WifiConnector(app).gatewayHost().takeIf { !it.isNullOrBlank() } ?: "flashair"
    val hosts = listOf(gateway) + COMMON_CAMERA_HOSTS
    val src = hosts.firstNotNullOfOrNull { h ->
        runCatching { probeWifiHost(h, cacheDir) }.getOrNull()
    } ?: return false
    attachRecovered(src)
    return true
}

private suspend fun recoverFtp(
    profile: ConnectionProfile,
    cacheDir: java.io.File
): Boolean {
    val src = FtpSource(
        host = profile.ftpHost,
        port = profile.ftpPort,
        user = profile.ftpUser.ifBlank { "anonymous" },
        pass = profile.ftpPass,
        root = profile.ftpPath.ifBlank { "/" },
        cacheDir = cacheDir
    )
    attachRecovered(src)
    return true
}

private suspend fun recoverUsb(
    context: Context,
    cacheDir: java.io.File
): Boolean {
    val app = context.applicationContext
    val usb = app.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
    val device = UsbCameras.find(app).firstOrNull() ?: return false
    if (!UsbCameras.hasPermission(app, device)) return false
    val src = UsbCameras.open(app, device) ?: return false
    attachRecovered(src)
    return true
}

/** 试拉一次列表确认连接可用，可用则写入 [SourceHolder]（与 UI 共用同一条连接） */
private suspend fun attachRecovered(src: PhotoSource) {
    val photos = runCatching { src.list() }.getOrNull() ?: return
    SourceHolder.attach(src, photos.map { it.id })
}
