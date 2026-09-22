package com.station1921.pixelcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.station1921.pixelcam.MainActivity
import com.station1921.pixelcam.R
import com.station1921.pixelcam.data.AutoBeauty
import com.station1921.pixelcam.data.AutoBeautyPrefs
import com.station1921.pixelcam.data.AutoReceiveConfig
import com.station1921.pixelcam.data.AutoReceivePrefs
import com.station1921.pixelcam.data.PhotoStore
import com.station1921.pixelcam.data.TransferActivity
import com.station1921.pixelcam.data.resolveBeautyPreset
import com.station1921.pixelcam.transfer.ConnectionProfilePrefs
import com.station1921.pixelcam.transfer.ConnType
import com.station1921.pixelcam.transfer.OlympusSource
import com.station1921.pixelcam.transfer.PhotoSource
import com.station1921.pixelcam.transfer.SourceHolder
import com.station1921.pixelcam.transfer.TransferStats
import com.station1921.pixelcam.transfer.NetworkHolder
import com.station1921.pixelcam.transfer.UsbMtpSource
import com.station1921.pixelcam.transfer.isVideoName
import com.station1921.pixelcam.transfer.recoverConnection
import com.station1921.pixelcam.util.CameraSettingsHolder
import com.station1921.pixelcam.util.readExif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** 后台常驻接收的运行状态（界面据此显示「已自动保存 N 张」等） */
data class AutoReceiveState(
    val running: Boolean = false,
    val connected: Boolean = false,
    /** 本次服务启动以来自动保存的张数 */
    val saved: Int = 0,
    val last: String = "",
    val status: String = "",
    /** 后台自动美化成功张数 */
    val beautyDone: Int = 0,
    /** 后台自动美化失败张数（解码/渲染/模型异常等，已计入通知） */
    val beautyFailed: Int = 0,
    /** RAW 因无法解码而跳过美化的张数 */
    val beautySkippedRaw: Int = 0
)

/**
 * 后台常驻接收：连上相机后，新拍的照片自动下载并存入图库，
 * 切到任何页面（包括退回桌面、锁屏）都继续接收。
 *
 * 实现要点：
 * - 前台服务 + 常驻通知（Android 8+ 后台服务必须前台化，否则几分钟就被杀）；
 * - 与 [com.station1921.pixelcam.ui.TransferViewModel] 共用 [SourceHolder] 里的同一条连接，
 *   不重复打开设备；
 * - 与 UI 共用同一套「已见照片」集合，谁先发现新照片谁下载，不会重复入库；
 * - 持 PARTIAL_WAKE_LOCK，保证息屏后轮询不会被 CPU 休眠掐断；
 * - 开关由用户在「相机连接」页手动控制（[AutoReceivePrefs]），默认关闭。
 */
class AutoReceiveService : Service() {

    companion object {
        private const val CHANNEL_ID = "pixelcam_auto_receive"
        private const val NOTI_ID = 2101
        /** 与监视页一致的轮询间隔 */
        private const val POLL_MS = 3000L
        private const val ACTION_STOP = "com.station1921.pixelcam.action.STOP_AUTO_RECEIVE"
        private const val WAKE_TAG = "PixelCam:AutoReceive"
        /** 兜底：极端情况下 6 小时后自动放开唤醒锁，避免永远不放 */
        private const val WAKE_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        private val _state = MutableStateFlow(AutoReceiveState())
        val state = _state.asStateFlow()

        fun start(context: Context) {
            if (_state.value.running) return
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, AutoReceiveService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AutoReceiveService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cm: ConnectivityManager? = null
    private var lastNotiText: String? = null

    /**
     * 仅服务存活（App 已退出、只剩后台接收）时，也能感知相机 USB 插拔：
     * 拔掉 → 清空来源并提示；插入 → 在来源为空时尝试自恢复（与 UI 端不重复）。
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (SourceHolder.source is UsbMtpSource) {
                        SourceHolder.detach()
                        update(connected = false, status = "USB 已断开，重新连接后继续")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (SourceHolder.source == null) {
                        scope.launch { runCatching { recoverConnection(this@AutoReceiveService) } }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cm = ContextCompat.getSystemService(this, ConnectivityManager::class.java)
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTI_ID,
            notification("等待相机连接…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        _state.value = AutoReceiveState(running = true, status = "等待相机连接…")
        // 注册 USB 插拔监听，使「仅服务存活」时也能感知相机连接变化并自恢复
        runCatching {
            ContextCompat.registerReceiver(
                this, usbReceiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        scope.launch { pollLoop() }
        // 统计清零，并实时把美化统计同步进状态、刷新通知（避免失败/RAW 静默无反馈）
        AutoBeauty.resetStats()
        scope.launch {
            AutoBeauty.stats.collect { s ->
                _state.value = _state.value.copy(
                    beautyDone = s.done,
                    beautyFailed = s.failed,
                    beautySkippedRaw = s.skippedRaw
                )
                refreshNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏「停止接收」：把开关也一起关掉，避免被 START_STICKY 重新拉起
        if (intent?.action == ACTION_STOP) {
            AutoReceivePrefs.save(this, AutoReceiveConfig(false))
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ——————————————— 轮询与下载 ———————————————

    private suspend fun pollLoop() {
        while (scope.isActive) {
            // 用户在设置里关掉开关后立即停服
            if (!AutoReceivePrefs.load(this).enabled) {
                stopSelf()
                return
            }
            val src = SourceHolder.source
            if (src == null) {
                // 进程被系统回收后，SourceHolder 随之清空。凭持久化的连接档案自行重连（③）：
                // 只要 OS 仍连着相机 WiFi / 相机仍插着 USB，就无需重新打开 App 或重新弹联网确认框。
                val profile = ConnectionProfilePrefs.load(this)
                if (AutoReceivePrefs.load(this).enabled && profile.type != ConnType.NONE) {
                    val recovered = runCatching { recoverConnection(this) }.getOrElse { false }
                    if (recovered) {
                        update(connected = true, status = "已重连 ${SourceHolder.label}，正在监视新照片")
                    } else {
                        releaseWakeLock()
                        // ⑦ 没有相机连接时解绑进程网络，恢复默认路由，避免 App 长期卡在无外网网络
                        runCatching { cm?.bindProcessToNetwork(null) }
                        update(connected = false, status = recoverWaitMessage(profile))
                    }
                } else {
                    releaseWakeLock()
                    runCatching { cm?.bindProcessToNetwork(null) }
                    update(connected = false, status = "等待相机连接…")
                }
            } else {
                acquireWakeLock()
                if (src is UsbMtpSource) {
                    // ⑦ USB/OTG 源不走网络，确保进程走默认路由
                    runCatching { cm?.bindProcessToNetwork(null) }
                } else {
                    // ⑦ WiFi 源：服务独立于 UI 维持「进程 → 相机 WiFi」绑定，
                    // 这样即便界面已切走 / 进程重启，后台接收也不会因为 UI 的绑定被释放而连不上相机
                    NetworkHolder.network?.let { runCatching { cm?.bindProcessToNetwork(it) } }
                }
                // 拍照页正在实时取景时不能轮询：取景要求相机停在拍摄模式，而列图（list）
                // 会先用 switch_cammode?mode=play 把相机拉回播放模式——那等于把取景流掐断。
                // 取景结束（相机回到播放模式）后下一轮自动继续，不会漏掉刚拍的照片。
                if ((src as? OlympusSource)?.liveViewRunning == true) {
                    update(connected = true, status = "拍照页取景中，接收暂缓（退出取景后自动继续）")
                } else {
                    update(connected = true, status = "已连接 ${SourceHolder.label}，正在监视新照片")
                    val ok = runCatching { receiveNew(src) }.getOrElse { false }
                    // ⑥ 相机 WiFi 已断开/休眠：list 完全失败（USB 源不在此列，其单张失败已回滚重试）
                    if (!ok && src !is UsbMtpSource) {
                        update(connected = false, status = "相机 WiFi 已断开 · 请到相机连接页重连")
                    }
                }
            }
            delay(POLL_MS)
        }
    }

    /**
     * 扫一轮 → 差分出新照片 → 逐张下载（按需跑 AI 自动美化）。
     * @return true 表示成功拉到列表（无论有无新照片）；false 表示 list 完全失败
     *         （相机 WiFi 断开/休眠时触发，供上层改为「已断开」状态，见 ⑥）
     */
    private suspend fun receiveNew(src: PhotoSource): Boolean {
        val scanned = SourceHolder.lock.withLock {
            // 拿到锁后必须再查一次取景标记：pollLoop 顶部的检查可能已经「过期」——
            // 检查时取景还没起、等拿到锁时取景流已经在推了，此时列图会先把相机
            // 切回播放模式，恰好把刚建立的取景流掐断（取景反复断流的一个根因）
            if ((src as? OlympusSource)?.liveViewRunning == true) return true
            val quick = runCatching { src.listRecent() }.getOrNull()
            quick?.takeIf { it.isNotEmpty() } ?: runCatching { src.list() }.getOrNull()
        } ?: return false
        // 首页卡片上的「相机相册」张数由每一轮扫描结果刷新（服务在跑时界面自己也轮询不到，
        // 因为它会把新照片都收走、而且取景/监视可能并没开着）
        TransferStats.setCameraTotal(scanned.size)

        // markFresh 返回 true 说明这张之前没见过
        val fresh = scanned.filter { SourceHolder.markFresh(it.id) }
            .sortedBy { it.modified }
        if (fresh.isEmpty()) return true

        val auto = AutoBeautyPrefs.load(this)
        for (photo in fresh) {
            val dest = PhotoStore.importTarget(this, photo.name)
            TransferActivity.markTransferring(photo.name)
            val ok = SourceHolder.lock.withLock {
                runCatching {
                    src.download(photo, dest) { pct -> TransferActivity.updateTransfer(photo.name, pct) }
                }.isSuccess
            }
            if (!ok) {
                TransferActivity.markTransferDone(photo.name)
                dest.delete()
                // 回滚：download 失败（如 USB 句柄失效）必须把这张退回「未知」，
                // 下一轮轮询才能重试；否则它已被 markFresh 记为已知，将永远收不回来
                SourceHolder.unmark(photo.id)
                continue
            }
            // 传输完成后必须移除占位卡，否则卡片会停在 100% 不消失（#143）
            TransferActivity.markTransferDone(photo.name)
            // 界面卡片上的「本次收到」：后台收的也算，否则卡片数字永远不动
            TransferStats.addReceived(photo.name)
            // 拍照页参数卡（ISO/光圈/快门/焦段）由新照片的 EXIF 驱动：后台接收是
            // 主要收图路径，不在这里解析的话参数卡就一直是连接时的旧值
            if (!isVideoName(photo.name)) {
                runCatching { readExif(dest) }.getOrNull()?.let { CameraSettingsHolder.applyExif(it) }
            }
            // AI 自动美化沿用用户在首页配置的开关与强度（产出「_美化」副本，原图保留）。
            // 视频无法走 Bitmap 美化流程，仅对图片执行；RAW 在 enqueue 内部识别并跳过计数，
            // 这里只是非阻塞入队，真正的美化在后台限流队列里异步跑，不拖慢轮询
            if (auto.enabled && !isVideoName(photo.name)) {
                AutoBeauty.enqueue(this, dest, auto.strength, auto.fullRes, auto.quality, auto.useParser, resolveBeautyPreset(this, auto.presetName))
            }
            _state.value = _state.value.copy(
                saved = _state.value.saved + 1,
                last = photo.name
            )
            update(
                connected = true,
                status = "已自动保存 ${_state.value.saved} 张 · 最近 ${photo.name}"
            )
        }
        return true
    }

    // ——————————————— 通知 ———————————————

    private fun update(connected: Boolean, status: String) {
        _state.value = _state.value.copy(connected = connected, status = status)
        refreshNotification()
    }

    /** 依据当前状态拼出通知文案（含美化成功/失败/RAW 跳过等统计，避免异常被静默吞掉） */
    private fun buildNotiText(s: AutoReceiveState): String {
        // 「保存 N 张」用全局计数（界面收图 + 后台接收两条路径都往 TransferStats 累加）。
        // 之前用服务自己维护的 s.saved，只数服务自己下载的那部分——拍照页/监视页收的图
        // 不在内，于是出现「美化 7 张却只保存 2 张」这种口径打架的数字（#125）。
        val received = TransferStats.state.value.received
        val base = if (received > 0) "已保存到图库 $received 张" else s.status
        val extra = buildList {
            if (s.beautyDone > 0) add("美化 ${s.beautyDone} 张")
            if (s.beautyFailed > 0) add("${s.beautyFailed} 张美化失败")
            if (s.beautySkippedRaw > 0) add("${s.beautySkippedRaw} 张RAW未美化")
        }
        return if (extra.isEmpty()) base else "$base · ${extra.joinToString(" · ")}"
    }

    private fun refreshNotification() {
        val text = buildNotiText(_state.value)
        if (text != lastNotiText) {
            lastNotiText = text
            pushNotification(text)
        }
    }

    /** 自恢复暂时无法完成时的等待提示（因 OS 已断开相机连接 / 设备未插入等） */
    private fun recoverWaitMessage(profile: com.station1921.pixelcam.transfer.ConnectionProfile): String =
        when (profile.type) {
            ConnType.WIFI_SD -> "相机 WiFi 未连接 · 请在 App 内重连"
            ConnType.USB -> "等待相机 USB 连接…"
            ConnType.FTP -> "等待 FTP 服务器…"
            else -> "等待相机连接…"
        }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 11,
            Intent(this, AutoReceiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_receive)
            .setContentTitle("后台接收照片")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, "停止接收", stopIntent)
            .build()
    }

    private fun pushNotification(text: String) {
        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTI_ID, notification(text)) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台接收照片", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "相机新照片自动下载时的常驻提示"
                    setShowBadge(false)
                }
        )
    }

    // ——————————————— 唤醒锁 ———————————————

    /**
     * 息屏后系统会停掉 CPU 调度，轮询随即停摆——那样「锁屏也能收」就是空话。
     * 所以在服务存活期间持一个 PARTIAL_WAKE_LOCK（只保 CPU，不亮屏）。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            val pm = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
            runCatching {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                    setReferenceCounted(false)
                    acquire(WAKE_TIMEOUT_MS)
                }
            }
        }
        // WiFi 来源还要额外持 WifiLock，否则息屏后系统会把 WiFi 切省电甚至断开，
        // 轮询照样收不到——USB/OTG 源不走 WiFi，不必持有，顺手释放
        if (SourceHolder.source !is UsbMtpSource) acquireWifiLock() else releaseWifiLock()
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = ContextCompat.getSystemService(this, WifiManager::class.java) ?: return
        runCatching {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        releaseWifiLock()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(usbReceiver) }
        releaseWakeLock()
        // ⑦ 服务停止时解绑进程网络，恢复默认路由，避免 App 进程残留卡在无外网网络
        runCatching { cm?.bindProcessToNetwork(null) }
        // 服务被系统/用户杀掉时，把残留传输/美化占位卡一并清掉，避免图库出现"100% 不消失"卡片
        TransferActivity.clear()
        _state.value = AutoReceiveState()
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }
}
