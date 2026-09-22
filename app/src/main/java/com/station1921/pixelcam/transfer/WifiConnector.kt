package com.station1921.pixelcam.transfer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 扫描到的一个 WiFi 热点 */
data class WifiAp(
    val ssid: String,
    val bssid: String,
    /** 信号强度 0~4 格 */
    val level: Int,
    val secure: Boolean,
    /** 原始 capabilities，用于判断是否 WPA3 */
    val caps: String
)

/** 当前系统版本下扫描 WiFi 所需要的权限 */
fun wifiScanPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

fun hasWifiScanPermission(context: Context): Boolean =
    wifiScanPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * 相机 WiFi 热点的扫描 / 连接 / 探测。
 *
 * Android 10（API 29）起 `WifiManager.addNetwork` 之类的接口已被禁用，应用无法静默连 WiFi。
 * 官方给替代方案是 [WifiNetworkSpecifier] + [ConnectivityManager.requestNetwork]：
 * 由系统弹窗征求用户同意，成功后我们再把进程绑定到这个网络——
 * 这样既完成了连接，也解决了「连着没外网的相机热点，socket 却走了蜂窝数据」这个经典坑。
 */
class WifiConnector(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifi: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val cm: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun isWifiEnabled(): Boolean = wifi?.isWifiEnabled == true

    /** 当前已连接热点的名字（去掉系统加的引号） */
    fun currentSsid(): String? {
        val raw = wifi?.connectionInfo?.ssid ?: return null
        val s = raw.trim('"')
        return s.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    /**
     * 是否还存在**活跃的 WiFi 网络**（不依赖 connectionInfo）。
     *
     * 为什么不用 currentSsid 判断断连：`WifiNetworkSpecifier` 连的相机热点在断开后
     * （相机关机），`connectionInfo.ssid` 在不少机型上会**残留旧 SSID** 一直不变，
     * 靠「SSID 变没变」永远判断不出断连。这里直接问 ConnectivityManager：
     * 系统网络表里还有没有 WiFi 传输的网络——相机一关机，那条网络立刻消失，最可靠。
     */
    fun isWifiNetworkAlive(): Boolean {
        val manager = cm ?: return false
        return manager.allNetworks.any {
            runCatching {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(false)
        }
    }

    /** 当前 WiFi 的网关地址——相机热点几乎都是它（192.168.x.1） */
    fun gatewayHost(): String? = intToIp(wifi?.dhcpInfo?.gateway)

    /** 本机在相机热点上分到的地址 */
    fun localIp(): String? = intToIp(wifi?.dhcpInfo?.ipAddress)

    private fun intToIp(v: Int?): String? {
        if (v == null || v == 0) return null
        return listOf(
            v and 0xff,
            (v shr 8) and 0xff,
            (v shr 16) and 0xff,
            (v shr 24) and 0xff
        ).joinToString(".")
    }

    /**
     * 候选相机主机地址，按「最可能命中」排序：
     * 各厂商常见默认地址（含奥林巴斯的 192.168.0.10）→ 本机网关 → 本机同网段的 .1 / .10。
     *
     * 注意奥林巴斯：它的 HTTP 服务跑在**相机自己** 192.168.0.10 上，而相机同时是 DHCP 服务器，
     * 报文里的网关不一定是 .10，所以只探测网关会必然错过它。
     */
    fun candidateHosts(): List<String> {
        val out = LinkedHashSet<String>()
        out += COMMON_CAMERA_HOSTS
        gatewayHost()?.let { out += it }
        val ip = wifi?.dhcpInfo?.ipAddress ?: 0
        if (ip != 0) {
            val a = ip and 0xff
            val b = (ip shr 8) and 0xff
            val c = (ip shr 16) and 0xff
            out += "$a.$b.$c.1"
            out += "$a.$b.$c.10"
        }
        return out.filter { it.isNotBlank() }
    }

    /** 打开系统 WiFi 设置页，让用户手动连热点 */
    fun openWifiSettings(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 扫描周边热点。
     *
     * Android 9+ 对前台应用有节流（约 2 分钟 4 次），超频后 startScan 会返回 false，
     * 此时我们仍然把上一次的缓存结果返回，UI 上提示稍后重试即可。
     */
    suspend fun scan(timeoutMs: Long = 9000): List<WifiAp> = withContext(Dispatchers.Main) {
        val manager = wifi ?: return@withContext emptyList()
        if (!hasWifiScanPermission(appContext)) return@withContext emptyList()

        suspendCancellableCoroutine { cont ->
            var resumed = false
            fun finish(list: List<WifiAp>) {
                if (resumed) return
                resumed = true
                runCatching { appContext.unregisterReceiver(receiverRef) }
                cont.resume(list)
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    finish(readResults(manager))
                }
            }
            receiverRef = receiver
            appContext.registerReceiver(
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            )

            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }

            // startScan 失败（节流/定位未开）时也要返回已有结果，别把 UI 卡在转圈
            val started = runCatching { manager.startScan() }.getOrDefault(false)
            if (!started) finish(readResults(manager))

            // 兜底超时：某些机型不广播扫描结果
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                finish(readResults(manager))
            }, timeoutMs)
        }
    }

    private var receiverRef: BroadcastReceiver? = null

    private fun readResults(manager: WifiManager): List<WifiAp> {
        val results: List<ScanResult> = runCatching { manager.scanResults }.getOrNull() ?: emptyList()
        return results
            .mapNotNull { r ->
                val ssid = r.SSID?.trim('"')?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WifiAp(
                    ssid = ssid,
                    bssid = r.BSSID ?: "",
                    level = runCatching { WifiManager.calculateSignalLevel(r.level, 5) }
                        .getOrDefault(0),
                    secure = r.capabilities?.contains("WPA") == true ||
                        r.capabilities?.contains("WEP") == true,
                    caps = r.capabilities ?: ""
                )
            }
            .distinctBy { it.ssid }
            .sortedByDescending { it.level }
    }

    /**
     * 请求连接指定热点（会弹系统确认框）。
     * 成功后自动把进程绑定到该网络，保证后续的 FTP/HTTP 请求真的走相机 WiFi。
     */
    fun requestConnect(
        ssid: String,
        password: String,
        onResult: (ok: Boolean, message: String?) -> Unit
    ) {
        val manager = cm
        if (manager == null) {
            onResult(false, "系统不支持网络请求接口")
            return
        }
        release()

        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotBlank()) {
            // WPA3 用 setWpa3Passphrase（API 29+），其余走 WPA2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) specBuilder.setWpa2Passphrase(password)
            else specBuilder.setWpa2Passphrase(password)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // 相机热点没有外网能力，必须移除 INTERNET，否则系统会认为网络不可用而立刻断开
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        var done = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (done) return
                done = true
                // 记下这个 Network，后台常驻接收服务也要用它把进程绑定到相机 WiFi
                NetworkHolder.network = network
                runCatching { manager.bindProcessToNetwork(network) }
                onResult(true, null)
            }

            // 相机 WiFi 断连（相机关机/热点休眠/走远）：及时解绑，
            // 避免 App 进程长期卡在一个已经不存在、且没有外网的网络上
            override fun onLost(network: Network) {
                if (NetworkHolder.network == network) {
                    NetworkHolder.network = null
                    runCatching { manager.bindProcessToNetwork(null) }
                }
            }

            override fun onUnavailable() {
                if (done) return
                done = true
                onResult(false, "系统未允许连接该热点，请到系统 WiFi 设置里手动连接")
            }
        }
        callback = cb
        runCatching { manager.requestNetwork(request, cb, 45_000) }
            .onFailure { onResult(false, "发起连接失败：${it.message}") }
    }

    /**
     * 用户若是在系统 WiFi 设置里手动连的相机热点（没走 [requestConnect]），
     * 进程并没有绑定到那条网络，socket 会走蜂窝导致 192.168.x.x 不可达。
     * 这里把当前已连的 WiFi 找出来补一次绑定。
     */
    fun bindCurrentWifi(): Boolean {
        val manager = cm ?: return false
        val net = manager.allNetworks.firstOrNull { n ->
            manager.getNetworkCapabilities(n)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return false
        NetworkHolder.network = net
        return runCatching { manager.bindProcessToNetwork(net) }.getOrDefault(false)
    }

    /** 取消未完成的连接请求并解除进程网络绑定 */
    fun release() {
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
    }

    fun unbind() {
        NetworkHolder.network = null
        runCatching { cm?.bindProcessToNetwork(null) }
    }

    // ——————————————— 相机 WiFi 断连监听（首页实时反映）———————————————

    private var connReceiver: BroadcastReceiver? = null

    /**
     * 盯住「当前是否还连在相机热点 [ssid] 上」：相机关机 / 走远 / 用户切到别的 WiFi，
     * 当前 SSID 就会不再是 [ssid]，立刻回调 [onLost]。
     *
     * 为什么用 SSID 广播而不是只靠 [ConnectivityManager.NetworkCallback]：[requestConnect]
     * 的回调只对「本应用请求并绑定的那条网络」生效；用户在系统设置里手动连的相机热点
     * 没有这条回调，只能靠监听 SSID 变化兜底。
     */
    fun watchConnection(ssid: String, onLost: () -> Unit) {
        stopWatchConnection()
        val target = ssid.trim()
        connReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                // 当前连着的 SSID 一旦不是相机热点，就认为断连（null / 别的 WiFi 都算）
                if (currentSsid()?.trim() != target) onLost()
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        runCatching { appContext.registerReceiver(connReceiver, filter) }
    }

    /** 取消相机 WiFi 断连监听 */
    fun stopWatchConnection() {
        connReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        connReceiver = null
    }
}
