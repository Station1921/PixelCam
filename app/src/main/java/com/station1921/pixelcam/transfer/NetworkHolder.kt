package com.station1921.pixelcam.transfer

import android.net.Network

/**
 * 当前相机 WiFi 对应的 [Network] 引用。
 *
 * [WifiConnector] 在用户连上相机热点后写入；后台常驻接收服务
 * （[com.station1921.pixelcam.service.AutoReceiveService]）读它来把进程网络绑定到相机 WiFi，
 * 这样服务在界面已切走 / 进程重启后仍能独立维持绑定，不依赖 UI 的 [WifiConnector] 生命周期。
 *
 * 注意：这是「进程级」绑定（[android.net.ConnectivityManager.bindProcessToNetwork]）。
 * 仅在确实在收相机 WiFi 时持有，USB/OTG 源与未连接时一律解绑（见 AutoReceiveService），
 * 避免 App 进程长期卡在没有外网的相机网络上、影响将来的联网功能。
 */
object NetworkHolder {
    @Volatile
    var network: Network? = null
}
