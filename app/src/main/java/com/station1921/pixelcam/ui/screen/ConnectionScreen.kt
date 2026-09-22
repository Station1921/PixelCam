package com.station1921.pixelcam.ui.screen

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.station1921.pixelcam.data.AutoBeautyConfig
import com.station1921.pixelcam.data.AutoBeautyPrefs
import com.station1921.pixelcam.data.BeautyPresetStore
import com.station1921.pixelcam.data.AutoReceiveConfig
import com.station1921.pixelcam.data.AutoReceivePrefs
import com.station1921.pixelcam.data.isIgnoringBatteryOptimizations
import com.station1921.pixelcam.service.AutoReceiveService
import com.station1921.pixelcam.transfer.CameraDiagnostics
import com.station1921.pixelcam.transfer.ConnectionProfile
import com.station1921.pixelcam.transfer.ConnectionProfilePrefs
import com.station1921.pixelcam.transfer.ConnType
import com.station1921.pixelcam.transfer.QrCodeParser
import com.station1921.pixelcam.transfer.TransferStats
import com.station1921.pixelcam.transfer.WifiAp
import com.station1921.pixelcam.transfer.WifiConnector
import com.station1921.pixelcam.transfer.WifiPasswordStore
import com.station1921.pixelcam.transfer.WifiQrParser
import com.station1921.pixelcam.transfer.hasWifiScanPermission
import com.station1921.pixelcam.transfer.wifiScanPermissions
import com.station1921.pixelcam.ui.TransferViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 把扫描到的热点列表存成字符串，供 rememberSaveable 跨重组/切页保留 */
private val WifiApListSaver = Saver<List<WifiAp>, String>(
    save = { list ->
        list.joinToString("\n") { ap ->
            listOf(ap.ssid, ap.bssid, ap.level.toString(), ap.secure.toString(), ap.caps)
                .joinToString("\u0001")
        }
    },
    restore = { str ->
        if (str.isBlank()) emptyList()
        else str.split("\n").mapNotNull { line ->
            val p = line.split("\u0001")
            if (p.size < 5) null
            else WifiAp(
                ssid = p[0],
                bssid = p[1],
                level = p[2].toIntOrNull() ?: 0,
                secure = p[3].toBoolean(),
                caps = p[4]
            )
        }
    }
)

/** 底部 4 个主 tab 之一：相机连接（首页） */
@Composable
fun ConnectionScreen(
    vm: TransferViewModel,
    wifi: WifiConnector,
    onGoMonitor: () -> Unit,
    onImported: () -> Unit
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var connTab by remember { mutableStateOf(0) } // 0=WiFi 1=USB 2=SD
    // 扫描结果跨 tab 切换保留（Compose 销毁重建时 remember 会丢，这里用 Saveable）
    var aps by rememberSaveable(stateSaver = WifiApListSaver) { mutableStateOf(emptyList()) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var showPwd by remember { mutableStateOf<WifiAp?>(null) }
    var pwd by remember { mutableStateOf("") }
    var connectingAp by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var scanHint by remember { mutableStateOf<String?>(null) }
    var permHint by remember { mutableStateOf<String?>(null) }
    var qrScanOpen by remember { mutableStateOf(false) }
    var qrHint by remember { mutableStateOf<String?>(null) }
    var diagOpen by remember { mutableStateOf(false) }
    var diagText by remember { mutableStateOf("") }
    var diagLoading by remember { mutableStateOf(false) }
    // 已连接的热点名。真正的状态源是 ui.wifiSsid（存在 ViewModel 里，切 tab 不会丢），
    // 这里只是本页内的即时反馈。
    var connectedSsid by rememberSaveable { mutableStateOf<String?>(null) }
    val shownSsid = ui.wifiSsid.ifBlank { connectedSsid ?: "" }

    var autoCfg by remember { mutableStateOf(AutoBeautyPrefs.load(context)) }
    var recvCfg by remember { mutableStateOf(AutoReceivePrefs.load(context)) }

    // 已保存的自定义修图预设（进入首页重新组合时刷新；从修图页保存后回到这里即生效）
    val customPresets = remember { BeautyPresetStore.loadAll(context) }

    // 当弹出密码输入框时，自动填入该 SSID 上次成功连接的密码
    LaunchedEffect(showPwd) {
        val ap = showPwd
        if (ap != null && pwd.isBlank()) {
            pwd = WifiPasswordStore.get(context, ap.ssid)
        }
    }

    // 页面可见期间持续同步系统当前热点（#111/#124）：
    // - 相机 WiFi 断了/换了 → 立刻清掉「已连上热点」状态，扫描卡回到未连接态。
    //   注意不能只看 currentSsid()：WifiNetworkSpecifier 连的相机热点断开后，
    //   connectionInfo 在很多机型上会残留旧 SSID 永不变化（#124 的根因），
    //   所以以「系统网络表里 WiFi 网络是否还活着」为主、SSID 比对为辅。
    // - 进程重启（rememberSaveable 也丢了）时，凭持久化的连接档案恢复显示
    LaunchedEffect(Unit) {
        while (isActive) {
            val wifiAlive = wifi.isWifiNetworkAlive()
            val now = if (wifiAlive) wifi.currentSsid() else null
            // WiFi 断了/换了热点：本页状态和 ViewModel 里的热点名**一起清**，
            // 并把整个连接复位成「未连接」——首页热点卡必须立刻回到「扫描 WiFi」的最初状态。
            // 之前只清本页 connectedSsid、留着 vm 的 wifiSsid，卡片就永远赖着不走（反复反馈的根因）。
            if (connectedSsid != null && (!wifiAlive || (now != null && now != connectedSsid))) {
                connectedSsid = null
                vm.noteWifiSsid("")
                // 探测进行中就不动全局（等探测自己失败收场），其余情况直接复位
                if (!ui.busy) vm.disconnect()
            }
            // 只有「系统明确报告当前 SSID 就是保存的那个」才恢复已连接显示。
            // 旧写法 `now == null` 也恢复——WiFi 一断，清掉的状态隔几秒又被弹回来，
            // 表现为「卡片清了又还」。SSID 读不到时宁可不多显示。
            if (connectedSsid == null && wifiAlive && now != null) {
                val savedProfile = ConnectionProfilePrefs.load(context)
                if (savedProfile.type == ConnType.WIFI_SD &&
                    savedProfile.ssid.isNotBlank() &&
                    now == savedProfile.ssid
                ) {
                    connectedSsid = savedProfile.ssid
                    // 进程重启后 NetworkHolder 已空，补一次绑定，保证探测/下载走相机 WiFi
                    runCatching { wifi.bindCurrentWifi() }
                }
            }
            delay(3000)
        }
    }

    // 高清人脸解析模型：已内置进安装包，用户可自主开关（离线推理，无需下载）
    var useParser by remember { mutableStateOf(autoCfg.useParser) }
    val onUseParser: (Boolean) -> Unit = {
        useParser = it
        autoCfg = AutoBeautyPrefs.save(context, autoCfg.copy(useParser = it))
    }
    val recvState by AutoReceiveService.state.collectAsStateWithLifecycle()
    // 相机卡片上的两个数字：相机相册张数 + 本次收到张数（界面导入与后台接收都计入）
    val stats by TransferStats.state.collectAsStateWithLifecycle()
    var batteryIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // Android 13+ 需要通知权限，否则常驻通知不显示（服务本身照常运行）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论是否授权都继续启动服务，只是通知可见性不同 */ }

    // 注意：Kotlin 局部函数必须先声明后使用，下面这些要放在所有调用它们的 launcher 之前

    /** 连接结果处理：成功提示并保存密码，失败提示原因 */
    fun onConnectResult(ok: Boolean, message: String?, ssid: String, password: String) {
        connectingAp = false
        if (ok) {
            showPwd = null
            connectError = null
            connectedSsid = ssid
            vm.noteWifiSsid(ssid)
            scanHint = "已连上 $ssid，正在查找照片服务…"
            WifiPasswordStore.put(context, ssid, password)
            ConnectionProfilePrefs.save(
                context,
                ConnectionProfile(type = ConnType.WIFI_SD, ssid = ssid, password = password)
            )
            // 本机网关、同网段地址、各厂商常见默认地址一起探，
            // 奥林巴斯在 192.168.0.10、SD 卡在网关，只探一个必然漏
            vm.probeWifiSdAuto(wifi.candidateHosts(), ssid)
        } else {
            scanHint = null
            connectError = message ?: "连接失败，请检查密码后重试"
        }
    }

    /**
     * 连接一个热点。
     *
     * 密码优先用之前连成功过的那份：有记录就直接连、不再弹密码框；
     * 只有失败（密码改过或被记错）才把密码框摆出来，预填密码让用户改。
     *
     * @param prefillOnFail 失败时是否用本次尝试的密码回填输入框。
     *        从列表/二维码发起时为 true（输入框里可能是上次的旧值）；
     *        从密码框里点「连接」时为 false，否则会把用户刚改好的密码又冲掉。
     * @param auto 自动连接（#108，扫描后见已保存热点自动发起）：失败不弹密码框，
     *        只落一行提示，免得用户没操作就弹出输入框吓人。
     */
    fun connectAp(ap: WifiAp, password: String, prefillOnFail: Boolean = true, auto: Boolean = false) {
        connectingAp = true
        connectError = null
        scanHint = if (auto) "发现已保存的相机热点 ${ap.ssid}，正在自动连接…" else "正在连接 ${ap.ssid}…"
        wifi.requestConnect(ap.ssid, password) { ok, msg ->
            onConnectResult(ok, msg, ap.ssid, password)
            if (!ok) {
                if (auto) {
                    connectError = "自动连接 ${ap.ssid} 失败：${msg ?: "请手动选择该热点重试"}"
                } else {
                    if (prefillOnFail) pwd = password
                    showPwd = ap
                }
            }
        }
    }

    /** 热点的密码：优先用二维码里带的，其次用上次连成功保存的 */
    fun passwordFor(ssid: String, fromQr: String): String =
        fromQr.ifBlank { WifiPasswordStore.get(context, ssid) }

    /**
     * 点热点列表里的一项。
     * 连成功过的热点直接连（不再弹密码框）；只有没记录时才让用户输密码。
     */
    fun onApPicked(ap: WifiAp) {
        if (!ap.secure) {
            connectAp(ap, "")
            return
        }
        val saved = WifiPasswordStore.get(context, ap.ssid)
        if (saved.isNotBlank()) {
            connectAp(ap, saved)
        } else {
            pwd = ""
            connectError = null
            showPwd = ap
        }
    }

    /** 解析到 WiFi 二维码后：有可用密码就直接连，没有才弹密码框 */
    fun applyParsedQr(parsed: WifiQrParser.Result) {
        val saved = WifiPasswordStore.get(context, parsed.ssid)
        val target = aps.find { it.ssid == parsed.ssid } ?: WifiAp(
            ssid = parsed.ssid,
            bssid = "",
            level = 4,
            secure = parsed.password.isNotBlank() || saved.isNotBlank(),
            caps = ""
        )
        qrHint = null
        if (!target.secure) {
            connectAp(target, "")
            return
        }
        val password = passwordFor(parsed.ssid, parsed.password)
        if (password.isNotBlank()) {
            connectAp(target, password)
        } else {
            pwd = ""
            connectError = null
            showPwd = target
        }
    }

    // 从相册选二维码图片（扫码页里的兜底入口，也用于没有相机权限时）
    val qrGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = QrCodeParser.parseUri(context, uri)
            if (parsed == null) {
                // 扫码页此时已关闭，提示落到面板上
                scanHint = "未从该图片中识别到 WiFi 二维码，请换一张重试"
                return@launch
            }
            applyParsedQr(parsed)
        }
    }

    fun doScan() {
        if (scanning) return
        scanning = true
        aps = emptyList()
        scope.launch {
            aps = wifi.scan()
            scanning = false
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            permHint = null
            doScan()
        } else {
            permHint = "需要「附近 WiFi」权限才能扫描相机热点"
        }
    }

    fun ensureScan() {
        if (hasWifiScanPermission(context)) {
            permHint = null
            doScan()
        } else {
            permLauncher.launch(wifiScanPermissions())
        }
    }

    // —— 自动扫描（#108）：进入本页就自动扫一次，不用再手动点「扫描」——
    // 点「扫描」按钮仍然是手动刷新列表（ensureScan → doScan）。
    LaunchedEffect(Unit) { ensureScan() }

    // —— 自动连接（#108）：扫到了保存过密码的相机热点就直接连，不用再手动点。
    // 只挑「连成功过、存了密码」的热点，且信号最强优先；同一个 SSID 一次会话只自动试一次，
    // 避免密码改过后陷入「失败→重扫→再连」的循环；正在连接/已连接时跳过。
    var autoTried by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(aps) {
        if (aps.isEmpty()) return@LaunchedEffect
        if (connectingAp || connectedSsid != null || ui.connected) return@LaunchedEffect
        if (qrScanOpen) return@LaunchedEffect
        val candidate = aps
            .filter { WifiPasswordStore.get(context, it.ssid).isNotBlank() }
            .maxByOrNull { it.level }
            ?: return@LaunchedEffect
        if (candidate.ssid in autoTried) return@LaunchedEffect
        autoTried = autoTried + candidate.ssid
        connectAp(candidate, WifiPasswordStore.get(context, candidate.ssid), auto = true)
    }

    /**
     * 扫到奥林巴斯私有连接码（OI2.3…）后的流程。
     *
     * 这种码是奥林巴斯自有加密格式，SSID/密码解不出来，但热点特征很稳：
     * SSID 形如 `E-M10MKIV-P-BJGB23326`。所以直接搜附近热点配对：
     * 唯一命中就直接连（有保存密码不弹框），没命中给明确提示。
     */
    fun connectOlympusFromQr() {
        scanHint = "已识别为奥林巴斯相机二维码，正在查找相机热点…"
        scope.launch {
            if (!hasWifiScanPermission(context)) {
                scanHint = null
                permLauncher.launch(wifiScanPermissions())
                return@launch
            }
            val found = wifi.scan()
            aps = found
            val cands = found.filter { WifiQrParser.looksLikeOlympusAp(it.ssid) }
            when {
                cands.size == 1 -> onApPicked(cands[0])
                cands.isEmpty() -> scanHint =
                    "没搜到奥林巴斯热点。请确认相机还停在「设备连接」界面，再点「扫码连接」重试，" +
                        "或直接在下方列表点选相机热点。"
                else -> scanHint =
                    "发现多个疑似奥林巴斯热点，请从下方列表点选（名字带 -P- 的通常就是）"
            }
        }
    }

    /** 打开内嵌实时扫码页（相机权限在页内按需申请，拒绝也能从相册选图） */
    fun startQrScan() {
        scanHint = null
        qrHint = null
        qrScanOpen = true
    }

    /** 重探照片服务：先确保进程绑在相机 WiFi 上，再把候选主机全探一遍 */
    val retryProbe: () -> Unit = {
        runCatching { wifi.bindCurrentWifi() }
        vm.probeWifiSdAuto(wifi.candidateHosts(), shownSsid.ifBlank { null })
    }

    /**
     * 相机诊断：把候选主机与关键端点逐条探一遍，结果可以直接复制出来。
     * 「连上了却找不到服务」这种只能在真机上定位的问题，靠它一次说清卡在哪一层。
     */
    val runDiagnostics: () -> Unit = {
        diagOpen = true
        diagLoading = true
        diagText = ""
        runCatching { wifi.bindCurrentWifi() }
        scope.launch {
            diagText = runCatching { CameraDiagnostics.report(context, wifi.candidateHosts()) }
                .getOrElse { "诊断执行失败：${it.message ?: it.javaClass.simpleName}" }
            diagLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(22.dp))
        Text(
            "相机连接",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "连接相机后即可接收照片、自动美化并存入图库",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))

        // 连接方式的顶部切换（大号分段按钮）
        SegmentedBar(
            items = listOf("相机 WiFi", "USB", "无线 SD 卡"),
            selected = connTab,
            onSelect = { connTab = it }
        )

        Spacer(Modifier.height(18.dp))

        // 已连接相机的大卡片：放到最上面，连接成功后一眼可见，其它卡片顺移其下
        if (ui.connected) {
            CameraCard(
                model = ui.model.ifBlank { "已连接相机" },
                cameraTotal = stats.cameraTotal,
                received = stats.received,
                lastName = stats.lastName,
                background = recvState.running,
                watching = ui.watching,
                onOpenMonitor = onGoMonitor
            )
            Spacer(Modifier.height(16.dp))
        }

        when (connTab) {
            0 -> WifiPanel(
                ui = ui,
                scanning = scanning,
                aps = aps,
                permHint = permHint,
                scanHint = scanHint,
                connectedSsid = shownSsid.ifBlank { null },
                connecting = connectingAp,
                onScan = { ensureScan() },
                onScanQr = { startQrScan() },
                onPick = { ap -> onApPicked(ap) },
                onDisconnect = {
                    connectedSsid = null
                    wifi.unbind()
                    vm.disconnect()
                },
                onRetryProbe = retryProbe,
                onDiagnose = runDiagnostics,
                onSettings = { context.startActivity(wifi.openWifiSettings()) },
                onClearHint = { permHint = null },
                onClearScanHint = { scanHint = null }
            )
            1 -> UsbPanel(vm = vm, ui = ui, onRefresh = { vm.refreshUsbDevices() })
            2 -> SdPanel(
                ui = ui,
                scanning = scanning,
                aps = aps,
                permHint = permHint,
                scanHint = scanHint,
                connectedSsid = shownSsid.ifBlank { null },
                connecting = connectingAp,
                onScan = { ensureScan() },
                onScanQr = { startQrScan() },
                onPick = { ap -> onApPicked(ap) },
                onDisconnect = {
                    connectedSsid = null
                    wifi.unbind()
                    vm.disconnect()
                },
                onRetryProbe = retryProbe,
                onDiagnose = runDiagnostics,
                onSettings = { context.startActivity(wifi.openWifiSettings()) },
                onClearHint = { permHint = null },
                onClearScanHint = { scanHint = null }
            )
        }

        Spacer(Modifier.height(20.dp))

        // AI 自动美化
        AutoBeautyCard(
            cfg = autoCfg,
            useParser = useParser,
            onUseParser = onUseParser,
            onToggle = {
                autoCfg = AutoBeautyPrefs.save(context, autoCfg.copy(enabled = it))
            },
            onStrength = {
                autoCfg = AutoBeautyPrefs.save(context, autoCfg.copy(strength = it))
            },
            onFullRes = {
                autoCfg = AutoBeautyPrefs.save(context, autoCfg.copy(fullRes = it))
            },
            onQuality = {
                autoCfg = AutoBeautyPrefs.save(context, autoCfg.copy(quality = it))
            },
            presetName = autoCfg.presetName,
            onPresetName = {
                autoCfg = AutoBeautyPrefs.save(context, autoCfg.copy(presetName = it))
            },
            customPresets = customPresets
        )

        Spacer(Modifier.height(16.dp))

        // 后台常驻接收
        AutoReceiveCard(
            cfg = recvCfg,
            state = recvState,
            batteryIgnored = batteryIgnored,
            onToggle = { on ->
                recvCfg = AutoReceivePrefs.save(context, AutoReceiveConfig(on))
                if (on) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    AutoReceiveService.start(context)
                } else {
                    AutoReceiveService.stop(context)
                }
            },
            onBatterySettings = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        )

        Spacer(Modifier.height(24.dp))

        // 版本标识：一眼确认手机上跑的是哪个构建（设置里看不到包名版本时尤其有用）
        val pkgInfo = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        }
        Text(
            "PixelCam v${pkgInfo?.versionName ?: "?"} (${pkgInfo?.longVersionCode ?: 0})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }

    // 密码弹窗（带连接中状态、失败提示、成功自动关闭并保存密码）
    if (showPwd != null) {
        val ap = showPwd!!
        AlertDialog(
            onDismissRequest = { if (!connectingAp) showPwd = null },
            title = { Text("连接 ${ap.ssid}") },
            text = {
                Column {
                    Text(
                        "输入该热点密码以连接相机/SD 卡",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = {
                            pwd = it
                            connectError = null
                        },
                        label = { Text("密码") },
                        singleLine = true,
                        enabled = !connectingAp,
                        isError = connectError != null,
                        supportingText = connectError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !connectingAp,
                    onClick = { connectAp(ap, pwd, prefillOnFail = false) }
                ) {
                    Text(if (connectingAp) "连接中…" else "连接")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !connectingAp,
                    onClick = { showPwd = null; connectError = null }
                ) { Text("取消") }
            }
        )
    }

    // 内嵌实时扫码页：全屏、自己起相机预览，ZXing 离线解码，不依赖 Google Play 服务
    if (qrScanOpen) {
        Dialog(
            onDismissRequest = { qrScanOpen = false; qrHint = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            QrScanView(
                hint = qrHint,
                onDecoded = { raw ->
                    val parsed = WifiQrParser.parse(raw)
                    when {
                        parsed != null -> {
                            qrScanOpen = false
                            applyParsedQr(parsed)
                        }
                        // 奥林巴斯私有连接码（OI2.3…）：解不出密码，改为直接配对相机热点
                        WifiQrParser.isOlympusQr(raw) -> {
                            qrScanOpen = false
                            connectOlympusFromQr()
                        }
                        else -> qrHint = "这不是 WiFi 二维码：${raw.take(60)}"
                    }
                },
                onPickGallery = {
                    qrScanOpen = false
                    qrGalleryLauncher.launch(arrayOf("image/*"))
                },
                onClose = { qrScanOpen = false; qrHint = null }
            )
        }
    }

    // 相机诊断：把每个候选地址、每个关键端点的探测结果列出来，可一键复制
    if (diagOpen) {
        AlertDialog(
            onDismissRequest = { if (!diagLoading) diagOpen = false },
            title = { Text("相机诊断") },
            text = {
                Column(Modifier.heightIn(max = 400.dp)) {
                    if (diagLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "正在逐个探测候选地址与端点…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                diagText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !diagLoading && diagText.isNotBlank(),
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        cb?.setPrimaryClip(ClipData.newPlainText("pixelcam-diag", diagText))
                        diagOpen = false
                        scanHint = "诊断结果已复制到剪贴板，粘贴发回即可定位问题"
                    }
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !diagLoading,
                    onClick = { diagOpen = false }
                ) { Text("关闭") }
            }
        )
    }
}

// ——————————— 顶部大号分段切换 ———————————

@Composable
private fun SegmentedBar(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val total = items.size
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, label ->
                val active = i == selected
                Box(
                    modifier = Modifier
                        .weight(1f / total)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { onSelect(i) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

// ——————————— WiFi / SD 面板 ———————————

@Composable
private fun WifiPanel(
    ui: com.station1921.pixelcam.ui.TransferUi,
    scanning: Boolean,
    aps: List<WifiAp>,
    permHint: String?,
    scanHint: String?,
    connectedSsid: String?,
    connecting: Boolean,
    onScan: () -> Unit,
    onScanQr: () -> Unit,
    onPick: (WifiAp) -> Unit,
    onDisconnect: () -> Unit,
    onRetryProbe: () -> Unit,
    onDiagnose: () -> Unit,
    onSettings: () -> Unit,
    onClearHint: () -> Unit,
    onClearScanHint: () -> Unit
) {
    val ctx = LocalContext.current
    // 连上相机热点（照片服务已就绪，或已成功连上热点但仍在探测）都算「已连接」，
    // 此时不再显示扫描入口，除非用户点断开 / 系统切到别的网络
    val wifiConnected = ui.connected || connectedSsid != null
    if (wifiConnected) {
        ConnectedWifiCard(
            ssid = connectedSsid ?: "已连接相机",
            label = if (ui.connected) ui.model.ifBlank { "WiFi 照片服务已就绪" }
            else "已连上热点，正在探测照片服务…",
            onDisconnect = onDisconnect
        )
        // 热点连上了但没探测到照片服务：给一个重试入口（相机切到播放模式后直接重试）
        if (!ui.connected) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (ui.busy) "正在探测照片服务…"
                    else "还没找到照片服务。奥林巴斯机型请把相机切到「播放/回放」模式后重试；" +
                        "仍不行就点下面的「相机诊断」看探测结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (!ui.busy) {
                    TextButton(onClick = onRetryProbe) { Text("重新探测") }
                }
            }
        }
    } else {
        BigButton(
            icon = Icons.Filled.SignalWifi4Bar,
            title = if (scanning) "扫描中…" else "扫描相机 WiFi 热点",
            desc = "列出附近热点，连过的点一下就直接连",
            enabled = !scanning && !connecting,
            onClick = onScan
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onScanQr,
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("扫码连接（实时扫码）")
        }
        if (connecting) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在连接热点…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    scanHint?.let {
        Spacer(Modifier.height(8.dp))
        HintCard(text = it, onClose = onClearScanHint)
    }
    permHint?.let {
        Spacer(Modifier.height(10.dp))
        HintCard(text = it, onClose = onClearHint)
    }
    if (!wifiConnected) Spacer(Modifier.height(12.dp))

    if (!wifiConnected && aps.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height((aps.size.coerceAtMost(5) * 60).dp),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(aps, key = { it.ssid + it.bssid }) { ap ->
                ApRow(
                    ap = ap,
                    saved = ap.secure && WifiPasswordStore.get(ctx, ap.ssid).isNotBlank(),
                    onClick = { onPick(ap) }
                )
            }
        }
    } else if (!wifiConnected && !scanning) {
        Spacer(Modifier.height(8.dp))
        Text(
            "未扫描到热点。若已连上相机热点却搜不到，可点下方按钮到系统 WiFi 设置确认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSettings) { Text("打开系统 WiFi 设置") }
    }

    if (ui.error != null) {
        Spacer(Modifier.height(10.dp))
        HintCard(text = ui.error!!, onClose = {})
    }
    DiagnosticsButton(onDiagnose)
}

@Composable
private fun SdPanel(
    ui: com.station1921.pixelcam.ui.TransferUi,
    scanning: Boolean,
    aps: List<WifiAp>,
    permHint: String?,
    scanHint: String?,
    connectedSsid: String?,
    connecting: Boolean,
    onScan: () -> Unit,
    onScanQr: () -> Unit,
    onPick: (WifiAp) -> Unit,
    onDisconnect: () -> Unit,
    onRetryProbe: () -> Unit,
    onDiagnose: () -> Unit,
    onSettings: () -> Unit,
    onClearHint: () -> Unit,
    onClearScanHint: () -> Unit
) {
    val ctx = LocalContext.current
    Text(
        "无线 SD 卡（FlashAir / ez Share 等）通过自身 WiFi 把照片发到手机。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    val wifiConnected = ui.connected || connectedSsid != null
    if (wifiConnected) {
        ConnectedWifiCard(
            ssid = connectedSsid ?: "已连接 SD 卡热点",
            label = if (ui.connected) ui.model.ifBlank { "SD 卡照片服务已就绪" }
            else "已连上热点，正在探测照片服务…",
            onDisconnect = onDisconnect
        )
        if (!ui.connected) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (ui.busy) "正在探测照片服务…" else "还没找到照片服务，可稍等卡片就绪后重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (!ui.busy) {
                    TextButton(onClick = onRetryProbe) { Text("重新探测") }
                }
            }
        }
    } else {
        BigButton(
            icon = Icons.Filled.SdStorage,
            title = if (scanning) "扫描中…" else "扫描 SD 卡热点",
            desc = "连上卡片热点后自动探测照片服务",
            enabled = !scanning && !connecting,
            onClick = onScan
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onScanQr,
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("扫码连接（实时扫码）")
        }
        if (connecting) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在连接热点…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    scanHint?.let {
        Spacer(Modifier.height(8.dp))
        HintCard(text = it, onClose = onClearScanHint)
    }
    permHint?.let {
        Spacer(Modifier.height(10.dp))
        HintCard(text = it, onClose = onClearHint)
    }
    if (!wifiConnected) Spacer(Modifier.height(12.dp))
    if (!wifiConnected && aps.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height((aps.size.coerceAtMost(5) * 60).dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(aps, key = { it.ssid + it.bssid }) { ap ->
                ApRow(
                    ap = ap,
                    saved = ap.secure && WifiPasswordStore.get(ctx, ap.ssid).isNotBlank(),
                    onClick = { onPick(ap) }
                )
            }
        }
    } else if (!wifiConnected && !scanning) {
        Spacer(Modifier.height(8.dp))
        Text(
            "若已连上 SD 卡热点，也可直接点「探测」自动查找照片服务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = onSettings) { Text("WiFi 设置") }
        }
    }
    if (ui.error != null) {
        Spacer(Modifier.height(10.dp))
        HintCard(text = ui.error!!, onClose = {})
    }
    DiagnosticsButton(onDiagnose)
}

/** 「相机诊断」入口：位置固定在面板底部，任何时候都能点 */
@Composable
private fun DiagnosticsButton(onClick: () -> Unit) {
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("相机诊断")
    }
}

@Composable
private fun ApRow(ap: WifiAp, saved: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (ap.secure) Icons.Filled.Lock else Icons.Filled.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ap.ssid, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(
                when {
                    !ap.secure -> "开放网络 · 点击直接连接"
                    saved -> "已保存密码 · 点击直接连接"
                    else -> "加密 · 点选后输入密码"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 信号格
        Row {
            repeat(ap.level.coerceIn(1, 4)) {
                Box(
                    Modifier
                        .size(width = 4.dp, height = (6 + it * 4).dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

// ——————————— 已连接 WiFi/相机信息卡片 ———————————

/**
 * 已连接 WiFi 提示条：刻意做得比相机卡片小一号、弱对比——
 * 连上之后真正的视觉主角是下方的相机卡片，这里只提供热点名和断开入口。
 */
@Composable
private fun ConnectedWifiCard(
    ssid: String,
    label: String,
    onDisconnect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.SignalWifi4Bar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ssid,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDisconnect, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "断开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ——————————— USB 面板 ———————————

@Composable
private fun UsbPanel(
    vm: TransferViewModel,
    ui: com.station1921.pixelcam.ui.TransferUi,
    onRefresh: () -> Unit
) {
    BigButton(
        icon = Icons.Filled.Usb,
        title = if (ui.busy) "连接中…" else "刷新 USB 设备",
        desc = "用 OTG 线连接相机后点此",
        enabled = !ui.busy,
        onClick = onRefresh
    )
    Spacer(Modifier.height(12.dp))
    val devices by vm.usbDevices.collectAsStateWithLifecycle()
    if (devices.isEmpty()) {
        Text(
            "未检测到 USB 相机。请检查 OTG 线，并在相机菜单里把连接模式设为「MTP/PTP」或「电脑连接」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            devices.forEach { dev ->
                val name = dev.productName ?: dev.deviceName ?: "USB 设备"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.connectUsb(dev) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Usb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                }
            }
        }
    }
    if (ui.error != null) {
        Spacer(Modifier.height(10.dp))
        HintCard(text = ui.error!!, onClose = {})
    }
}

// ——————————— 相机卡片 ———————————

@Composable
private fun CameraCard(
    model: String,
    cameraTotal: Int,
    received: Int,
    lastName: String,
    background: Boolean,
    watching: Boolean,
    onOpenMonitor: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(model, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        when {
                            background -> "已连接 · 后台常驻接收中"
                            watching -> "已连接 · 实时接收中"
                            else -> "已连接 · 点「打开监视」开始接收"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                StatBlock("$cameraTotal", "相机相册", Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                StatBlock("$received", "本次收到", Modifier.weight(1f))            }
            Text(
                when {
                    lastName.isNotBlank() -> "最近收到：$lastName"
                    else -> "相机每拍一张，这里 3 秒内就会 +1 并存入图库"
                },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onOpenMonitor,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("打开监视")
            }
        }
    }
}

/**
 * 卡片内的统计块：与「打开监视」按钮同宽平铺（weight 等分，左右边距相等）。
 * 底色用白色半透明叠在卡片底色上，不再是突兀的深色小块。
 */
@Composable
private fun StatBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
        )
    }
}

// ——————————— AI 自动美化卡片 ———————————

@Composable
private fun AutoBeautyCard(
    cfg: AutoBeautyConfig,
    useParser: Boolean,
    onUseParser: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onStrength: (Int) -> Unit,
    onFullRes: (Boolean) -> Unit,
    onQuality: (Int) -> Unit,
    presetName: String?,
    onPresetName: (String?) -> Unit,
    customPresets: List<com.station1921.pixelcam.beauty.BeautyPreset>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("AI 自动美化", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                Switch(checked = cfg.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "开启后，从相机接收的照片会在手机本地离线分析亮度与人脸，按所选方案自动美化，原图保留以便对比。" +
                    "全程在设备端完成，不上传、也不调用任何云端大模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (cfg.enabled) {
                Spacer(Modifier.height(12.dp))
                // —— 美化方案：AI 智能（按画面）或用户保存的自定义预设 ——
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = presetName == null,
                        onClick = { onPresetName(null) },
                        label = { Text("AI 智能（按画面）") },
                        shape = RoundedCornerShape(10.dp)
                    )
                    customPresets.forEach { preset ->
                        FilterChip(
                            selected = presetName == preset.name,
                            onClick = { onPresetName(preset.name) },
                            label = { Text(preset.name) },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
                // 选了自定义预设：直接按该预设参数一键美化，强度档不再生效
                if (presetName == null) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("轻", "标准", "明显").forEachIndexed { i, label ->
                            FilterChip(
                                selected = i == cfg.strength,
                                onClick = { onStrength(i) },
                                label = { Text(label) },
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已选择自定义预设「$presetName」：将按该预设的全部参数（磨皮 / 调色 / 风光…）一键美化，强度档不生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("原分辨率输出", fontSize = 14.sp)
                        Text(
                            "关闭时压缩到 2048 长边；开启则输出原图分辨率，更清晰但更慢、更耗内存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = cfg.fullRes, onCheckedChange = onFullRes)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("输出质量", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (cfg.quality >= 100) "无损 ${cfg.quality}" else "质量 ${cfg.quality}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(6.dp))
                ParamSlider(
                    value = cfg.quality.toFloat(),
                    range = 70f..100f,
                    onValueChange = { onQuality((it + 0.5f).toInt()) }
                )
                Text(
                    "70 省空间 ←→ 100 无损",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ——— 高清人脸解析模型（已内置，离线推理）———
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("高清人脸解析模型", fontSize = 14.sp)
                        Text(
                            "已内置离线模型（约 50MB）：开启后用像素级皮肤掩码，只磨皮肤、不碰眼眉嘴唇；" +
                                "关闭则用传统肤色椭圆掩码。全程本地推理，不联网。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = useParser, onCheckedChange = onUseParser)
                }
            }
        }
    }
}

// ——————————— 后台常驻接收卡片 ———————————

@Composable
private fun AutoReceiveCard(
    cfg: AutoReceiveConfig,
    state: com.station1921.pixelcam.service.AutoReceiveState,
    batteryIgnored: Boolean,
    onToggle: (Boolean) -> Unit,
    onBatterySettings: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("后台常驻接收", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                Switch(checked = cfg.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "开启后，切到任何页面、退回桌面甚至锁屏，相机新拍的照片都会自动下载并存入图库，" +
                    "通知栏会有一条常驻提示（耗电与流量相应增加，不用时请关掉）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (cfg.enabled) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state.connected) "状态：${state.status}"
                    else "状态：${state.status}（先在上方连上相机）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (state.saved > 0) {
                    Text(
                        "本次已自动保存 ${state.saved} 张${if (state.last.isNotBlank()) " · 最近 ${state.last}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (batteryIgnored) "已加入电池优化白名单 ✓"
                        else "建议加入电池优化白名单，否则锁屏一段时间后接收会被系统停掉",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (!batteryIgnored) {
                        TextButton(onClick = onBatterySettings) { Text("去设置") }
                    }
                }
            }
        }
    }
}

// ——————————— 小部件 ———————————

@Composable
private fun BigButton(
    icon: ImageVector,
    title: String,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HintCard(text: String, onClose: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x26FF6B6B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onClose != {}) {
                TextButton(onClick = onClose) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val trackColor = if (checked) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = if (checked) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    val offset by androidx.compose.animation.core.animateDpAsState(
        if (checked) 16.dp else 0.dp, label = "sw"
    )
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp)
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(28.dp)
                .background(thumbColor, RoundedCornerShape(50))
        )
    }
}
