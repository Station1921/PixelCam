package com.station1921.pixelcam.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相机诊断。
 *
 * 「连上了相机热点，却说找不到照片服务」这类问题，成因可能有很多层：
 * 地址不对（奥林巴斯在 192.168.0.10，不是网关）、进程没绑定到相机网络（socket 走了蜂窝）、
 * 相机不在播放模式、协议端点被拒……本机无法重现用户现场的相机，所以干脆做成一键探测：
 * 把每个候选主机、每个关键端点的 HTTP 状态码与响应片段都列出来，
 * 用户复制这份报告发回来，就能直接定位到具体是哪一层出了问题。
 */
object CameraDiagnostics {

    private data class Probe(
        val label: String,
        val path: String,
        val ua: String? = null,
        val timeoutMs: Int = 4000
    )

    private val PROBES = listOf(
        Probe("奥林巴斯·机型", "/get_caminfo.cgi", OLYMPUS_UA, 2500),
        Probe("奥林巴斯·命令列表", "/get_commandlist.cgi", OLYMPUS_UA, 2500),
        Probe("奥林巴斯·切播放", "/switch_cammode.cgi?mode=play", OLYMPUS_UA, 3000),
        Probe("奥林巴斯·列图/DCIM", "/get_imglist.cgi?DIR=/DCIM", OLYMPUS_UA, 6000),
        Probe("FlashAir·列表", "/command.cgi?op=100&DIR=/DCIM", null, 4000)
    )

    suspend fun report(context: Context, hosts: List<String>): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val connector = WifiConnector(app)

        val sb = StringBuilder()
        sb.appendLine("PixelCam 相机诊断")
        sb.appendLine("时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        sb.appendLine()
        sb.appendLine("【网络环境】")
        sb.appendLine("  热点 SSID  ：" + (wifi?.connectionInfo?.ssid?.trim('"') ?: "未知"))
        sb.appendLine("  本机 IP    ：" + (connector.localIp() ?: "未知"))
        sb.appendLine("  网关       ：" + (connector.gatewayHost() ?: "未知"))
        val bound = NetworkHolder.network
        sb.appendLine(
            "  已绑定网络 ：" +
                (bound?.toString() ?: "未绑定（socket 会走默认路由，192.168.x.x 可能不可达）")
        )
        val wifiNets = cm?.let { m ->
            m.allNetworks.count { n ->
                m.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } ?: 0
        sb.appendLine("  系统 WiFi 网络数：$wifiNets")
        sb.appendLine("  候补主机   ：" + hosts.joinToString(", "))
        sb.appendLine()

        for (host in hosts) {
            if (host.isBlank()) continue
            sb.appendLine("【主机 $host】")
            val reach = httpGetResult("http://$host/", 1500)
            if (reach.code == 0) {
                sb.appendLine("  地址不通：" + reach.error)
                sb.appendLine()
                continue
            }
            sb.appendLine("  " + pad("根页面") + "GET / → HTTP ${reach.code} · ${reach.body.length} 字节")
            for (p in PROBES) {
                val r = httpGetResult("http://$host${p.path}", p.timeoutMs, p.ua, 1024)
                sb.appendLine("  " + pad(p.label) + "GET ${p.path} → " + describe(r))
            }
            sb.appendLine()
        }
        sb.appendLine("（把以上全部内容复制发回即可定位问题）")
        sb.toString()
    }

    private fun describe(r: HttpResult): String {
        if (r.code == 0) return "失败 · ${r.error}"
        val body = r.body.trim().replace(Regex("\\s+"), " ").take(160)
        return "HTTP ${r.code}" + if (body.isEmpty()) " · (空响应)" else " · $body"
    }

    private fun pad(s: String): String = s.padEnd(20, ' ')
}
