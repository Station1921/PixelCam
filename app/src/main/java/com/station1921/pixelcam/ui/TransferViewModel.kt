package com.station1921.pixelcam.ui

import android.app.Application
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.station1921.pixelcam.data.AutoBeauty
import com.station1921.pixelcam.data.AutoBeautyPrefs
import com.station1921.pixelcam.data.PhotoStore
import com.station1921.pixelcam.data.TransferActivity
import com.station1921.pixelcam.data.resolveBeautyPreset
import com.station1921.pixelcam.transfer.FlashAirSource
import com.station1921.pixelcam.transfer.FtpSource
import com.station1921.pixelcam.transfer.COMMON_CAMERA_HOSTS
import com.station1921.pixelcam.transfer.CameraProps
import com.station1921.pixelcam.transfer.OlympusLiveView
import com.station1921.pixelcam.transfer.OlympusSource
import com.station1921.pixelcam.transfer.PhotoSource
import com.station1921.pixelcam.transfer.RemotePhoto
import com.station1921.pixelcam.transfer.ConnectionProfile
import com.station1921.pixelcam.transfer.ConnectionProfilePrefs
import com.station1921.pixelcam.transfer.ConnType
import com.station1921.pixelcam.transfer.SourceHolder
import com.station1921.pixelcam.transfer.TransferStats
import com.station1921.pixelcam.transfer.UsbCameras
import com.station1921.pixelcam.transfer.UsbMtpSource
import com.station1921.pixelcam.transfer.WifiConnector
import com.station1921.pixelcam.transfer.isVideoName
import com.station1921.pixelcam.transfer.probeAnyHost
import com.station1921.pixelcam.transfer.probeWifiHost
import com.station1921.pixelcam.util.CameraSettings
import com.station1921.pixelcam.util.CameraSettingsHolder
import com.station1921.pixelcam.util.readExif
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.roundToInt

enum class TransferMode { USB, FTP, WIFI_SD }

/** 实时取景（拍照页取景画面）的状态 */
enum class LiveViewStatus {
    /** 未开启 */
    OFF,

    /** 正在向相机请求画面 */
    STARTING,

    /** 正在收画面 */
    STREAMING,

    /** 当前来源不支持（非奥林巴斯 WiFi） */
    UNSUPPORTED,

    /** 失败（附原因，界面直接显示） */
    ERROR
}

/** 拍照页的取景画面状态：一帧 Bitmap + 一句可读的状态说明 */
data class LiveViewUi(
    val status: LiveViewStatus = LiveViewStatus.OFF,
    val message: String = "",
    val frame: Bitmap? = null
)

data class TransferUi(
    val mode: TransferMode = TransferMode.USB,
    val photos: List<RemotePhoto> = emptyList(),
    val selected: Set<String> = emptySet(),
    val thumbs: Map<String, Bitmap> = emptyMap(),
    val status: String = "",
    val busy: Boolean = false,
    val importing: Boolean = false,
    /** 正在单独保存（导入）的照片 id 集合：让监视页「保存到图库」可按图独立进行，互不阻塞 */
    val importingIds: Set<String> = emptySet(),
    val progress: Float = 0f,
    val error: String? = null,
    val connected: Boolean = false,

    // —— 已连接相机信息（首页卡片用）——
    /** 连接成功时相机/来源的名称（型号） */
    val model: String = "",
    /** 当前已传输（已导入来源）的照片总数，用于卡片上的「传输 N 张」 */
    val photoCount: Int = 0,
    /**
     * 已连上的相机热点名。
     * 放在 ViewModel 而不是界面的 remember 里：界面切到底部其它 tab 时会被销毁重建，
     * 页面内的状态会随之丢失，表现为「切回来就像掉线了」。
     */
    val wifiSsid: String = "",

    // —— 监视（即拍即看）——
    val watching: Boolean = false,
    /** 监视期间新出现的照片，最新在前 */
    val live: List<RemotePhoto> = emptyList(),
    val liveThumbs: Map<String, Bitmap> = emptyMap(),
    val liveSelected: Set<String> = emptySet(),
    /** 当前大图预览（监视页） */
    val livePreview: Bitmap? = null,
    val livePreviewId: String? = null,
    val scanning: Boolean = false
)

class TransferViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 监视轮询间隔：3 秒一轮，相机写完卡到手机看到基本无感 */
        private const val POLL_MS = 3000L
        /** 每隔这么多轮强制全量扫一次，兜住「新照片落在未监控目录」的情况 */
        private const val FULL_SCAN_EVERY = 12
        /** 监视页大图预览的长边 */
        private const val PREVIEW_EDGE = 1600

        /** 拍照页实时参数的刷新间隔（get_camprop 是轻量只读请求，2.5 秒够用） */
        private const val PROPS_MS = 2500L
    }

    /**
     * 当前照片来源。放在 [SourceHolder] 里而不是本 ViewModel 字段：
     * 后台常驻服务（[com.station1921.pixelcam.service.AutoReceiveService]）要独立于界面
     * 使用同一条连接——MTP/USB 不允许同一设备并发打开，FTP/HTTP 重复连接也会互相挤掉会话。
     */
    private val source: PhotoSource? get() = SourceHolder.source
    private var thumbJob: Job? = null

    /**
     * 串行化对 [source] 的访问：MTP/FTP 都是单连接，
     * 监视轮询、缩略图加载、下载导入三者必须排队，否则会互相打断。
     * 与后台服务共用同一把锁。
     */
    private val sourceLock = SourceHolder.lock

    /** 监听相机 WiFi 是否还在连着：相机关机 / 走远 / 切了 WiFi 时，首页卡片要立刻翻成「已断开」 */
    private val wifiConnector = WifiConnector(getApplication())

    /** 监视轮询任务；非 null 即表示正在监视 */
    private var watchJob: Job? = null

    private var pollRound = 0

    /** 监视页大图是否自动跟着「最新一张」走；用户手动点开某张后置 false 停止跟随 */
    private var autoPreviewFollow = true

    /** 监视页「刷新」按钮置位：下一轮扫描强制全量（对齐相机相册真实内容、清理已删旧图） */
    private val refreshTick = java.util.concurrent.atomic.AtomicBoolean(false)

    // ——————————————— 实时取景（拍照页画面）———————————————

    /**
     * 取景画面单独一条状态流。
     *
     * 不并进 [TransferUi]：取景是每秒十来帧的持续更新，混在里面会让整个拍照页
     * （参数卡、水平仪、快门…）跟着每帧重组，白白掉帧。
     */
    private val _liveView = MutableStateFlow(LiveViewUi())
    val liveView: StateFlow<LiveViewUi> = _liveView.asStateFlow()

    /**
     * 最近一帧取景画面的缓存：离开拍照页（停流）时不清除，重进拍照页时立刻拿它当占位，
     * 消除「黑屏 → 取景握手 → 出画面」那段视觉空窗，让页面感觉是秒开的（#127）。
     * 真正断开相机（[closeSource]）时才清空，避免把上一台相机的画面留给新连接当占位。
     */
    private val _lastFrame = MutableStateFlow<Bitmap?>(null)
    val lastFrame: StateFlow<Bitmap?> = _lastFrame.asStateFlow()

    /**
     * 快门成功后展示的「刚拍照片」预览。
     * 拍照要切到播放模式收图，取景流会短暂断开几秒——这段空窗直接盖一张刚拍的回放，
     * 体验上像真相机回放 1~2s，不再让人误以为「拍完就掉线」。CaptureScreen 显示约 1.5s 后清掉。
     */
    private val _shotPreview = MutableStateFlow<Bitmap?>(null)
    val shotPreview: StateFlow<Bitmap?> = _shotPreview.asStateFlow()

    /**
     * 收图窗口期标志：快门成功后置 true、收完（或确认收不到）置 false。
     * 回放图还没拉到时，界面用它显示「正在保存到图库…」的过渡层，同样用来盖住断开的取景。
     */
    private val _shotSaving = MutableStateFlow(false)
    val shotSaving: StateFlow<Boolean> = _shotSaving.asStateFlow()

    /** 拍照页回放覆盖层消失后调用，清掉预览图（下次拍照前恢复空白） */
    fun clearShotPreview() { _shotPreview.value = null }

    private var liveViewJob: Job? = null
    private var liveViewSession: OlympusLiveView? = null

    /**
     * 取景会话代号。每次起/停都 +1，收尾逻辑只在「自己还是最新会话」时才改状态——
     * 否则「停了立刻又起」时，旧会话的 finally 会把新会话刚设好的状态覆盖掉。
     */
    private var liveViewGen = 0

    /**
     * 界面当前是否希望保持取景（拍照页在前台 = true）。
     *
     * 与「取景任务是否存在」不是一回事：拍照后要短暂停流去收照片（见 [harvestAfterShot]），
     * 那是内部暂停，不代表用户离开了拍照页。收完是否要恢复取景，看这个标记——
     * 否则用户刚退出拍照页，收完照片又把取景拉起来，相机会停在拍摄模式、
     * 监视轮询也列不出图，照片就再也收不到了。
     */
    @Volatile
    private var liveViewWanted = false

    /** 当前来源能否提供实时取景（目前只有奥林巴斯 / OM System 的 WiFi OPC 协议做了实现） */
    fun canLiveView(): Boolean = SourceHolder.source is OlympusSource

    /**
     * 开始实时取景。
     *
     * 关键时序：取景必须在**拍摄模式**下进行，而列图接口只在**播放模式**下可用，
     * 而且 [OlympusSource.list] 每次都会把相机拉回播放模式——那会把取景流直接掐断。
     * 所以开取景前先停掉监视轮询，退出取景时再切回播放模式。
     */
    fun startLiveView() {
        val src = source
        if (src !is OlympusSource) {
            _liveView.value = LiveViewUi(
                LiveViewStatus.UNSUPPORTED,
                "实时取景目前只支持「相机 WiFi + 奥林巴斯 / OM System」机型。"
            )
            return
        }
        if (liveViewJob != null) return

        liveViewWanted = true
        stopWatch()
        val gen = ++liveViewGen
        _liveView.value = LiveViewUi(LiveViewStatus.STARTING, "正在向相机请求取景画面…")

        liveViewJob = viewModelScope.launch(Dispatchers.IO) {
            val lv = OlympusLiveView(src.host)
            liveViewSession = lv
            try {
                // 必须在碰相机之前就登记「取景中」：登记晚一拍，后台服务/监视轮询
                // 就可能在启动窗口期把相机切回播放模式，推流还没开始就被掐了。
                // 登记后 enterPlayMode 的取景保护全程有效（见 OlympusSource）。
                src.liveViewOwner = lv
                src.liveViewRunning = true
                sourceLock.withLock { lv.start() }
                if (gen == liveViewGen) {
                    _liveView.value = _liveView.value.copy(message = "取景流已建立，等待第一帧…")
                }
                lv.receive { bmp ->
                    if (gen == liveViewGen) {
                        _lastFrame.value = bmp
                        _liveView.value = LiveViewUi(LiveViewStatus.STREAMING, "", bmp)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 兜底到 Throwable：取景解码偶发的 OOM 之类 Error 也不能让它把整个 App 带崩
                if (gen == liveViewGen) {
                    _liveView.value = LiveViewUi(LiveViewStatus.ERROR, e.message ?: "实时取景失败")
                }
            } finally {
                // 取消也要把相机放回播放模式，否则下次列图/监视会一直拿不到照片。
                // 只有「自己还是登记在册的那个会话」才能清标记——停了立刻又起时，
                // 旧会话的收尾不能把新会话的取景保护抹掉（那正是断流反复出现的根因）。
                withContext(NonCancellable) {
                    if (src.liveViewOwner === lv) {
                        src.liveViewOwner = null
                        src.liveViewRunning = false
                    }
                    runCatching { lv.stop() }
                }
                if (gen == liveViewGen) {
                    liveViewSession = null
                    val cur = _liveView.value
                    _liveView.value =
                        if (cur.status == LiveViewStatus.ERROR) cur.copy(frame = null) else LiveViewUi()
                }
            }
        }
    }

    fun stopLiveView(restartWatch: Boolean = true) {
        val had = liveViewJob != null || liveViewSession != null
        liveViewWanted = false
        liveViewGen++
        // 先关 socket，让阻塞在 recv 上的收流循环立刻退出；收尾（切回播放模式）在任务的 finally 里做
        liveViewSession?.interrupt()
        liveViewJob?.cancel()
        liveViewJob = null
        liveViewSession = null
        if (_liveView.value.status != LiveViewStatus.ERROR) _liveView.value = LiveViewUi()
        // 取景期间轮询是停着的（相机在拍摄模式下列不出图），退出取景后要接着收
        if (had && restartWatch && _ui.value.connected) startWatch()
    }

    private val _ui = MutableStateFlow(TransferUi())
    val ui: StateFlow<TransferUi> = _ui.asStateFlow()

    private val _usbDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val usbDevices: StateFlow<List<UsbDevice>> = _usbDevices.asStateFlow()

    /**
     * 拍照页「参数卡」的数据：ISO / 光圈 / 快门 / 焦段。
     * 来源是相机照片的 EXIF（收到新照片时解析一次），不是手填；
     * 焦段是镜头物理值只读，ISO/光圈/快门可由拍照页滑块覆盖（备忘/目标值）。
     *
     * 数据放在 [CameraSettingsHolder]（全局单例）而不是本 ViewModel：
     * 后台常驻服务收到照片后也要解析 EXIF 刷进来——只放这里的话，
     * 开着后台接收时参数卡永远不更新（ISO 一栏「拿不到」的主因）。
     */
    val cameraSettings: StateFlow<CameraSettings> = CameraSettingsHolder.settings

    /** 拍照页滑块/预设覆盖某项参数（焦段只读自 EXIF；白平衡为备忘值，用户提供预设） */
    fun setCameraSetting(
        iso: String? = null,
        aperture: String? = null,
        shutter: String? = null,
        whiteBalance: String? = null
    ) {
        CameraSettingsHolder.setSetting(iso, aperture, shutter, whiteBalance)
    }

    /**
     * 写一项相机属性（拍照页顶部「可调」项的刻度条走这里）。
     *
     * 「能不能改」不靠猜：相机在属性表里自己标了 `getset`（+ 可选值列表），
     * 界面据此决定哪些项可点。写成功后立刻重读一次状态表，卡片马上显示新值
     * （不必等 2.5 秒的轮询）；相机拒绝就回一句提示，不静默失败。
     */
    fun setProp(name: String, value: String, label: String, onResult: (String?) -> Unit) {
        val src = source
        if (src !is OlympusSource) {
            onResult("改相机参数需要「相机 WiFi + 奥林巴斯 / OM System」连接")
            return
        }
        viewModelScope.launch {
            try {
                // 先按「取景流占着相机」的平滑路径试一次：直接 POST set_camprop。
                // 值已经是枚举下标（见 OlympusProps.rawForIndex），相机正常会接受。
                var ok = sourceLock.withLock { src.setProp(name, value) }
                // 个别固件在 UDP 取景流占着时忽略 set_camprop——停流重试一次，写完再拉起取景。
                // 取景只会闪一下，换来的是「拍照页调参数、相机真的跟着变」。
                if (!ok && liveViewWanted && liveViewJob != null) {
                    runCatching { stopLiveView(restartWatch = false) }
                    delay(200)
                    ok = sourceLock.withLock { src.setProp(name, value) }
                    runCatching { startLiveView() }
                }
                if (!ok) {
                    onResult("相机没有接受这次调整（当前拍摄模式下「$label」可能不允许改）")
                    return@launch
                }
                val p = sourceLock.withLock { runCatching { src.readProps() }.getOrNull() }
                if (p != null && !p.isEmpty) _cameraProps.value = p
                _ui.value = _ui.value.copy(status = "已把「$label」调到相机")
            } catch (e: Throwable) {
                // 任何意外（网络/解析/相机返回异常，乃至取景解码 OOM 之类）都化成一句提示，
                // 绝不抛到主线程闪退——尤其曝光补偿这类直接点卡片的操作必须稳。
                runCatching { if (liveViewWanted) startLiveView() }
                onResult("调整「$label」失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ——————————————— 相机实时状态（拍照页参数卡）———————————————

    /**
     * 从相机直接读回来的当前状态（拍摄模式 / ISO / 快门 / 曝光补偿 / 白平衡 / 焦距 / 驱动模式…）。
     *
     * 与 EXIF 的区别：EXIF 要「拍完一张」才有、且只有 4 项；这个走 `get_camprop`
     * 只读接口，取景中也能读（不切模式），所以拍照页能在不按快门的情况下显示实时参数。
     */
    private val _cameraProps = MutableStateFlow(CameraProps.EMPTY)
    val cameraProps: StateFlow<CameraProps> = _cameraProps.asStateFlow()

    /** 卡剩余可拍张数（读得到才有值） */
    private val _capacity = MutableStateFlow("")
    val capacity: StateFlow<String> = _capacity.asStateFlow()

    /** 变焦提示（手动变焦镜头时给一句说明，只提示一次） */
    private val _zoomHint = MutableStateFlow<String?>(null)
    val zoomHint: StateFlow<String?> = _zoomHint.asStateFlow()

    private var propsJob: Job? = null
    private var propsRound = 0

    /** 拍照页在前台时定时刷新相机状态（只读接口，取景中同样安全） */
    fun startPropsPolling() {
        if (propsJob != null) return
        propsJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val src = source
                if (src is OlympusSource) {
                    val p = sourceLock.withLock { runCatching { src.readProps() }.getOrNull() }
                    if (p != null && !p.isEmpty) _cameraProps.value = p
                    // 剩余张数变化慢，隔几轮读一次就够，省得白占相机连接
                    if (propsRound++ % 4 == 0) {
                        sourceLock.withLock { runCatching { src.unusedCapacity() }.getOrNull() }
                            ?.let { _capacity.value = it }
                    }
                }
                delay(PROPS_MS)
            }
        }
    }

    fun stopPropsPolling() {
        propsJob?.cancel()
        propsJob = null
    }

    /**
     * 点屏对焦（奥林巴斯的 `assignafframe`）。
     *
     * 入参是**归一化坐标**（0..1，相对取景画面），这里按当前取景画面尺寸换算成相机
     * 要的画面坐标（与 lvqty 同尺度）。必须在拍摄模式下才有效——取景流开着时正是拍摄模式，
     * 所以点屏对焦和取景是一对；没取景时给用户一句明确提示，而不是静默失败。
     */
    fun focusAt(nx: Float, ny: Float, onResult: (String?) -> Unit) {
        val src = source
        if (src !is OlympusSource) {
            onResult("点屏对焦只支持相机 WiFi（奥林巴斯）连接")
            return
        }
        if (!_liveView.value.let { it.status == LiveViewStatus.STREAMING }) {
            onResult("先让取景画面出来（相机需在拍摄模式）再点屏对焦")
            return
        }
        val frame = _liveView.value.frame
        val w = frame?.width ?: 640
        val h = frame?.height ?: 480
        val x = (nx.coerceIn(0f, 1f) * w).roundToInt()
        val y = (ny.coerceIn(0f, 1f) * h).roundToInt()
        viewModelScope.launch {
            val ok = sourceLock.withLock { runCatching { src.focusAt(x, y) }.getOrDefault(false) }
            onResult(if (ok) null else "对焦指令没被相机接受，请确认相机在拍摄模式并重试")
        }
    }

    // ——————————————— 变焦（电动变焦镜头）———————————————

    private var zoomBefore: String? = null
    private var zoomMisses = 0

    /** 按住变焦键：开始往长焦/广角连续移动；松手由 [zoomRelease] 停 */
    fun zoomPress(tele: Boolean) {
        val src = source as? OlympusSource ?: return
        zoomBefore = _cameraProps.value.raw("focalvalue")
        viewModelScope.launch {
            sourceLock.withLock { runCatching { src.zoom(if (tele) "telemove" else "widemove") } }
        }
    }

    /**
     * 松开变焦键：停流并核对焦距有没有真的变。
     * 普通手动变焦环镜头相机不会执行电动变焦指令，这里连按两次焦距都没动就给一句提示，
     * 免得用户以为 App 坏了。
     */
    fun zoomRelease() {
        val src = source as? OlympusSource ?: return
        viewModelScope.launch {
            sourceLock.withLock { runCatching { src.zoom("off") } }
            val after = sourceLock.withLock { runCatching { src.readProps() }.getOrNull() }
            if (after != null && !after.isEmpty) _cameraProps.value = after
            val now = after?.raw("focalvalue")
            val before = zoomBefore
            zoomBefore = null
            if (before != null && now != null && now == before) {
                zoomMisses++
                if (zoomMisses >= 2) {
                    _zoomHint.value = "变焦指令没有改变焦距：这应该是手动变焦环镜头，" +
                        "请在镜头上转动变焦环；App 只能显示当前焦距。"
                }
            } else {
                zoomMisses = 0
            }
        }
    }

    fun clearZoomHint() {
        _zoomHint.value = null
    }

    /** USB 权限需要在 Activity 里请求，ViewModel 把待授权设备抛出去 */
    private val _permissionRequest = MutableStateFlow<UsbDevice?>(null)
    val permissionRequest: StateFlow<UsbDevice?> = _permissionRequest.asStateFlow()

    fun setMode(mode: TransferMode) {
        closeSource()
        _ui.value = TransferUi(mode = mode, status = statusHint(mode))
    }

    private fun statusHint(mode: TransferMode) = when (mode) {
        TransferMode.USB -> "用 OTG 线把相机接上手机，然后在下方选择设备"
        TransferMode.FTP -> "先连上相机热点，再填写 FTP 地址"
        TransferMode.WIFI_SD -> "连上无线 SD 卡的热点后，点击探测"
    }

    // ——————————————— USB ———————————————

    fun refreshUsbDevices() {
        _usbDevices.value = UsbCameras.find(getApplication())
        if (_usbDevices.value.isEmpty()) {
            _ui.value = _ui.value.copy(status = "没有检测到 USB 设备，请检查 OTG 连接")
        }
    }

    fun connectUsb(device: UsbDevice) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null, status = "正在连接 ${device.productName ?: "设备"}…")
            if (!UsbCameras.hasPermission(getApplication(), device)) {
                _permissionRequest.value = device
                _ui.value = _ui.value.copy(busy = false, status = "等待 USB 授权…")
                return@launch
            }
            openUsb(device)
        }
    }

    /** 用户在系统授权弹窗里点了允许之后回调这里 */
    fun onUsbPermissionGranted(device: UsbDevice) {
        _permissionRequest.value = null
        viewModelScope.launch { openUsb(device) }
    }

    fun onUsbPermissionDenied() {
        _permissionRequest.value = null
        _ui.value = _ui.value.copy(busy = false, error = "USB 授权被拒绝")
    }

    private suspend fun openUsb(device: UsbDevice) {
        val opened = UsbCameras.open(getApplication(), device)
        if (opened == null) {
            _ui.value = _ui.value.copy(
                busy = false,
                error = "无法以 PTP/MTP 方式打开该设备。部分相机需要在菜单里把 USB 连接模式切到「MTP/PTP」或「电脑连接」。"
            )
            return
        }
        attach(opened, opened.label)
        // 持久化连接档案，供后台常驻接收服务在进程被杀后自恢复（③）
        ConnectionProfilePrefs.save(getApplication(), ConnectionProfile(type = ConnType.USB))
    }

    // ——————————————— FTP ———————————————

    fun connectFtp(host: String, port: Int, user: String, pass: String, path: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null, status = "正在连接 $host…")
            val src = FtpSource(
                host = host.trim(),
                port = port,
                user = user.trim().ifBlank { "anonymous" },
                pass = pass,
                root = path.trim().ifBlank { "/" },
                cacheDir = PhotoStore.remoteCacheDir(getApplication())
            )
            attach(src, "FTP $host")
            // 持久化连接档案，供后台常驻接收服务在进程被杀后自恢复（③）
            ConnectionProfilePrefs.save(
                getApplication(),
                ConnectionProfile(
                    type = ConnType.FTP,
                    ftpHost = host.trim(),
                    ftpPort = port,
                    ftpUser = user.trim().ifBlank { "anonymous" },
                    ftpPass = pass,
                    ftpPath = path.trim().ifBlank { "/" }
                )
            )
        }
    }

    // ——————————————— WiFi SD 卡 ———————————————

    fun probeWifiSd(host: String, ssid: String? = null) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null, status = "正在探测 $host…")
            val found = probeWifiHost(host.trim(), PhotoStore.remoteCacheDir(getApplication()))
            if (found == null) {
                _ui.value = _ui.value.copy(
                    busy = false,
                    status = "",
                    error = "在 $host 上没找到可用的照片服务。请点「相机诊断」把探测结果发回来，" +
                        "或确认相机已切到播放/回放模式。"
                )
                return@launch
            }
            attach(found, found.label, ssid)
        }
    }

    // ——————————————— 通用 ———————————————

    /**
     * 自动探测常见相机 WiFi / 无线 SD 卡默认地址。
     *
     * @param hosts 候选主机（默认用固定常见地址表；调用方通常传「本机网关 + 同网段 + 常见地址」
     *              的合并列表，这样连上任何相机热点都能打中）
     * @param ssid  当前连上的热点名，仅用于界面显示
     */
    fun probeWifiSdAuto(hosts: List<String>? = null, ssid: String? = null) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null, status = "正在探测相机…")
            val list = (hosts ?: COMMON_CAMERA_HOSTS).filter { it.isNotBlank() }.distinct()
            val cache = PhotoStore.remoteCacheDir(getApplication())
            val found = probeAnyHost(list, cache)
            if (found != null) {
                attach(found, found.label, ssid)
                return@launch
            }
            _ui.value = _ui.value.copy(
                busy = false,
                status = "",
                error = "已在 ${list.size} 个地址上尝试，没找到可用的照片服务。" +
                    "请点「相机诊断」把探测结果发回来，方便定位。"
            )
        }
    }

    /**
     * 远程触发相机快门，结果异步回调（回调在主线程）。
     *
     * USB 走 PTP 同步指令，很快；奥林巴斯 WiFi 走 OPC 协议（切拍摄模式 → 快门 → 切回播放），
     * 是好几轮网络请求，绝不能在主线程跑，所以统一改为回调风格。
     */
    fun triggerCapture(onResult: (ok: Boolean, message: String?) -> Unit) {
        val src = source
        if (src is OlympusSource) {
            viewModelScope.launch {
                val r = runCatching {
                    // 与监视轮询/缩略图加载共用一把锁，避免同时打相机接口互相干扰
                    sourceLock.withLock { src.remoteCapture() }
                }
                val ok = r.getOrDefault(false)
                // 先回结果让界面马上有反馈，再去把这张照片收下来（收的过程要几秒）
                onResult(ok, r.exceptionOrNull()?.message)
                if (ok) {
                    val saved = runCatching { harvestAfterShot(src) }.getOrDefault(0)
                    if (saved == 0) {
                        // 快门指令被相机收下了，但等了三轮都没等到照片——多半是相机没合焦
                        // 被它自己吞了（HTTP 成功 ≠ 真出片）。必须明说，不然用户以为 App 坏了。
                        _ui.value = _ui.value.copy(
                            status = "快门已发出，但相机没有出片：请看相机屏幕是否提示对焦失败，稍后再拍一张试试"
                        )
                    }
                }
            }
        } else {
            // src?.triggerCapture() 是 Boolean?，先 == true 归一成非空 Boolean，避免 getOrDefault 推断成可空
            val ok = runCatching { src?.triggerCapture() == true }.getOrDefault(false)
            onResult(ok, null)
        }
    }

    /**
     * 拍照后立刻把照片收进图库，不让用户去监视页手动下载。
     *
     * 为什么要暂停取景：列图接口只在**播放模式**下被相机接受，而取景要求相机停在
     * **拍摄模式**——两者互斥。所以「拍完就收」只能让取景短暂中断几秒
     * （与相机自己写卡时的黑屏相当），收完马上恢复。
     *
     * 取景是否能恢复看 [liveViewWanted]：用户若在收的过程中退出了拍照页，就不再拉起取景，
     * 免得相机被留在拍摄模式、监视轮询反而收不到照片。
     *
     * @return 本次收下来的张数
     */
    private suspend fun harvestAfterShot(src: OlympusSource): Int {
        val job = liveViewJob
        val wasStreaming = job != null
        // 关键：在 stopLiveView 把 liveViewWanted 清零之前，先把「用户仍想要取景」的意图记下来。
        // 否则收完照后 finally 里的 liveViewWanted 已被清零，取景就再也不会自动拉起——
        // 表象就是「拍完照取景断掉、要手动点重试」（#95 根因）。
        val wantLive = liveViewWanted
        var ownGen = -1
        if (wasStreaming) {
            stopLiveView(restartWatch = false)
            // 记下「我们自己这次停流」之后的代数；收图期间用户若退出拍照页，
            // onDispose 里的 stopLiveView 会把 liveViewGen 再 ++，这里就检测得出来。
            ownGen = liveViewGen
            runCatching { job.join() }   // 等收尾真正跑完（停流 + 切回播放模式）
        }
        return try {
            var saved = 0
            // 收图窗口期开始：界面立即显示「正在保存到图库…」过渡层，盖住断开的取景（#104）
            _shotSaving.value = true
            // 相机把这张写进卡需要一会儿（大 JPEG 尤其），列图要轮几次等它出现，
            // 否则第一次扫过去还没有、就白白错过这一张
            for (i in 0 until 3) {
                if (saved > 0) break
                delay(if (i == 0) 600 else 900)
                val scanned = sourceLock.withLock { runCatching { src.list() }.getOrNull() } ?: continue
                TransferStats.setCameraTotal(scanned.size)
                val fresh = scanned.filter { SourceHolder.markFresh(it.id) }
                    .sortedBy { it.modified }
                if (fresh.isEmpty()) continue
                // 回放**必须取最新那张**（按拍摄时间降序，再按文件名兜底）：
                // 以前 fresh.first() 取的是列表顺序里最旧的未见文件——万一存在几张开拍前
                // 就没登记过的旧图，回放就会突然跳出很久之前的照片（#114）
                val shot = fresh.maxByOrNull { it.modified } ?: continue
                val bmp = withTimeoutOrNull(5000) {
                    sourceLock.withLock { src.preview(shot, 1024) }
                } ?: sourceLock.withLock { runCatching { src.thumbnail(shot) }.getOrNull() }
                if (bmp != null) _shotPreview.value = bmp
                saved = ingestPhotos(fresh, asyncBeauty = true)
                if (saved > 0) {
                    markIngested(fresh)
                    loadThumbnails(_ui.value.photos)
                    _ui.value = _ui.value.copy(status = "已自动保存 $saved 张到图库")
                }
            }
            saved
        } finally {
            // 收图窗口结束（无论成败）：「正在保存」过渡层退场
            _shotSaving.value = false
            // 拍照页还在前台才恢复取景：wantLive 是停流前的意图（stopLiveView 会把
            // liveViewWanted 清零，不能直接看它）；liveViewGen 未变说明这期间没有
            // 新的停流动作（用户退出拍照页时 onDispose 会停流并 ++ 代数）。
            if (wasStreaming && wantLive && ownGen == liveViewGen) startLiveView()
        }
    }

    /**
     * 把照片收进手机图库：下载 → 刷参数卡 EXIF → 首页计数 → 按需自动美化。
     *
     * [asyncBeauty] = true（轮询 / 拍后自动收的路径）把美化丢进后台限流队列，不阻塞下一轮扫描；
     * 用户手动导入走同步，界面能按张显示进度。
     *
     * 下载失败会把 id 退回「未认领」，下一轮扫描重新尝试 —— 否则这张已被记为已知，
     * 就永远收不回来了。
     *
     * @return 成功张数
     */
    private suspend fun ingestPhotos(picked: List<RemotePhoto>, asyncBeauty: Boolean): Int {
        val src = source ?: return 0
        val app = getApplication<Application>()
        val auto = AutoBeautyPrefs.load(app)
        var ok = 0
        withContext(Dispatchers.IO) {
            for (photo in picked) {
                val name = photo.name
                // 图库占位：先登记「正在传输」，下载完再移除；这样一拍完图库就显示占位卡，
                // 不用等照片实际落到磁盘才冒出来（修复图库「要过段时间才显示」）
                TransferActivity.markTransferring(name)
                val dest = PhotoStore.importTarget(app, name)
                val good = runCatching {
                    sourceLock.withLock { src.download(photo, dest) { pct -> TransferActivity.updateTransfer(name, pct) } }
                }.isSuccess
                if (!good) {
                    dest.delete()
                    SourceHolder.unmark(photo.id)
                    TransferActivity.markTransferDone(name)
                    continue
                }
                ok++
                TransferStats.addReceived(name)
                TransferActivity.markTransferDone(name)
                if (isVideoName(name)) continue
                runCatching { readExif(dest) }.getOrNull()?.let { CameraSettingsHolder.applyExif(it) }
                if (auto.enabled) {
                    // 异步队列：占位卡与批次计数由 enqueue 内部登记（避免重复计数）；
                    // 同步路径：这里自己登记/注销占位
                    val preset = resolveBeautyPreset(app, auto.presetName)
                    if (asyncBeauty) {
                        AutoBeauty.enqueue(app, dest, auto.strength, auto.fullRes, auto.quality, auto.useParser, preset)
                    } else {
                        TransferActivity.markBeauty(name)
                        runCatching {
                            AutoBeauty.beautify(app, dest, auto.strength, auto.fullRes, auto.quality, auto.useParser, preset)
                        }
                        TransferActivity.markBeautyDone(name)
                    }
                }
            }
        }
        return ok
    }

    /** 已收下的照片从「新照片」列表移走、并入相机相册顶部（监视页两张列表都保持正确） */
    private fun markIngested(photos: List<RemotePhoto>) {
        val ids = photos.map { it.id }.toSet()
        _ui.value = _ui.value.copy(
            live = _ui.value.live.filter { it.id !in ids },
            liveThumbs = _ui.value.liveThumbs - ids,
            liveSelected = _ui.value.liveSelected - ids,
            photos = (photos + _ui.value.photos.filter { it.id !in ids })
                .sortedByDescending { it.modified }
        )
    }

    private suspend fun attach(src: PhotoSource, label: String, ssid: String? = null) {
        val result = sourceLock.withLock { runCatching { src.list() } }
        val photos = result.getOrNull()
        if (photos == null) {
            runCatching { src.close() }
            _ui.value = _ui.value.copy(
                busy = false,
                connected = false,
                status = "",
                error = "读取失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            )
            return
        }
        closeSource()
        // 连接当下的存量照片全部登记为「已知」，之后出现的才算新增；
        // 与后台常驻服务共用同一份集合，避免同一张被下载两次
        SourceHolder.attach(src, photos.map { it.id })
        pollRound = 0
        autoPreviewFollow = true
        // 本次连接的计数从头开始（相机相册总数下面按首轮扫描结果给）
        TransferStats.resetSession()
        TransferStats.setCameraTotal(photos.size)
        // label 是在探测阶段取的，那时机型名还没读出来；list() 跑完再取一次才有真正的型号
        val realLabel = src.label.ifBlank { label }
        _ui.value = _ui.value.copy(
            busy = false,
            connected = true,
            photos = photos,
            model = realLabel,
            photoCount = photos.size,
            selected = emptySet(),
            thumbs = emptyMap(),
            status = "${realLabel}：共 ${photos.size} 张照片",
            wifiSsid = ssid ?: _ui.value.wifiSsid,
            live = emptyList(),
            liveThumbs = emptyMap(),
            liveSelected = emptySet(),
            livePreview = null,
            livePreviewId = null
        )
        loadThumbnails(photos)
        // 连上就开始差分轮询：首页卡片上的数量、监视页的新照片都靠它保持实时，
        // 而不是等用户切到监视页才动起来（那样首页计数永远是连接那一刻的快照）
        startWatch()
        // 同时盯住相机 WiFi：相机关机 / 用户切了 WiFi，首页要实时翻成「已断开」，
        // 而不是还停在「连接成功」——这是之前「相机都关机了还显示成功」的根因。
        val ssidNow = (ssid ?: _ui.value.wifiSsid).trim()
        if (ssidNow.isNotBlank()) {
            wifiConnector.watchConnection(ssidNow) {
                // 连网握手期间系统可能冒一次瞬时广播，二次确认避免误判；且只在仍连着时翻状态。
                // 判断「还活着」用网络表（isWifiNetworkAlive）而不是 currentSsid——
                // specifier 网络断开后 connectionInfo 会残留旧 SSID，靠它永远判不出断连。
                if (_ui.value.connected &&
                    (!wifiConnector.isWifiNetworkAlive() || wifiConnector.currentSsid()?.trim() != ssidNow)
                ) {
                    // WiFi 断了就整个回到「未连接」：wifiSsid 一并清掉、连接档案置 NONE。
                    // 只把 connected 翻 false、留着 wifiSsid 的话，首页热点卡会一直挂着——
                    // 「相机都关机了卡片还在」的根因就在这。disconnect() 里 stopWatchConnection
                    // 会注销本监听，属于正常收尾。
                    disconnect()
                }
            }
        }
    }

    private fun loadThumbnails(photos: List<RemotePhoto>) {
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch(Dispatchers.IO) {
            // 只补还没拿到的缩略图：收下新照片后调用它，不会把整本相册的缩略图重拉一遍。
            // 注意：**不能用「列表引用变了就中止」**——scanOnce 每收一张新图都会重建 photos，
            // 旧判断会让补图任务中途夭折，剩下的缩略图永远没人拉（监视页转圈不出的根因）。
            // 改成逐张动态检查：已拿到的跳过，断连（thumbs 被清空后 source 也没了）才退出。
            for (photo in photos) {
                if (source == null) return@launch
                if (photo.id in _ui.value.thumbs) continue
                // 拉失败重试两次（间隔 300ms）：失败就 continue 会让这张图永远黑卡片
                var bmp: android.graphics.Bitmap? = sourceLock.withLock {
                    runCatching { source?.thumbnail(photo) }.getOrNull()
                }
                if (bmp == null) { delay(300); bmp = sourceLock.withLock {
                    runCatching { source?.thumbnail(photo) }.getOrNull()
                } }
                if (bmp == null) { delay(600); bmp = sourceLock.withLock {
                    runCatching { source?.thumbnail(photo) }.getOrNull()
                } }
                if (bmp != null) _ui.value = _ui.value.copy(thumbs = _ui.value.thumbs + (photo.id to bmp))
            }
        }
    }

    fun toggle(id: String) {
        val sel = _ui.value.selected
        _ui.value = _ui.value.copy(
            selected = if (id in sel) sel - id else sel + id
        )
    }

    fun toggleAll() {
        val photos = _ui.value.photos
        val all = photos.map { it.id }.toSet()
        _ui.value = _ui.value.copy(
            selected = if (_ui.value.selected.size == photos.size) emptySet() else all
        )
    }

    /** 导入「照片」页勾选的照片 */
    fun importSelected(onFinished: (Int) -> Unit) {
        val picked = _ui.value.photos.filter { it.id in _ui.value.selected }
        if (picked.isEmpty()) return
        importPhotos(picked) {
            _ui.value = _ui.value.copy(selected = emptySet())
            onFinished(it)
        }
    }

    // ——————————————— 监视（即拍即看）———————————————

    /**
     * 开始轮询：相机每拍一张，约 3 秒内出现在监视页。
     * 走的是「快速探测 + 差分」的通用方案——Android 的 MtpDevice 没有事件回调，
     * 厂商 SDK 又各有各的协议，轮询是唯一对所有连接方式都成立的做法。
     */
    fun startWatch() {
        if (watchJob != null) return
        _ui.value = _ui.value.copy(watching = true)
        watchJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { scanOnce(forceFull = refreshTick.getAndSet(false)) }
                delay(POLL_MS)
            }
        }
    }

    /** 手动刷新（监视页右上角按钮）：下一轮强制全量扫，顺带清掉相机里已不存在的旧图，并恢复跟随最新 */
    fun refreshMonitor() {
        autoPreviewFollow = true
        refreshTick.set(true)
    }

    fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
        _ui.value = _ui.value.copy(watching = false, scanning = false)
    }

    /** 扫一轮：拉列表 → 差分出新照片 → 补缩略图 → 最新一张顶到大图 */
    private suspend fun scanOnce(forceFull: Boolean = false) {
        val src = source ?: return
        _ui.value = _ui.value.copy(scanning = true)
        val forceFull = forceFull || (++pollRound % FULL_SCAN_EVERY) == 0
        var full = false
        val scanned: List<RemotePhoto>? = sourceLock.withLock {
            // 取景中相机在拍摄模式：列图必失败不说，内部还会先切播放模式掐断取景流
            if ((src as? OlympusSource)?.liveViewRunning == true) {
                null
            } else {
                val quick = if (!forceFull) runCatching { src.listRecent() }.getOrNull() else null
                if (quick != null && quick.isNotEmpty()) {
                    quick
                } else {
                    full = true
                    runCatching { src.list() }.getOrNull()
                }
            }
        }
        _ui.value = _ui.value.copy(scanning = false)
        if (scanned == null) return
        // 全量扫的结果就是相机相册的真实张数，首页卡片靠它跟着相机走
        if (full) TransferStats.setCameraTotal(scanned.size)

        // —— 全量扫 = 相机相册的权威快照：把手机这边缓存的旧账对齐（#112）——
        // 相机上删掉的/换卡清掉的图，不能一直挂在监视页上。
        // 另外要识别「同路径换了新文件」：相机格式化/全部清除后 DCF 编号从头计数，
        // 新照片的路径会和很久之前的旧图一模一样、只是文件大小不同——
        // 不替换的话，监视页永远显示上一任文件：表现为「旧图复活、新拍的图不出现」。
        val scannedById = scanned.associateBy { it.id }
        val oldById = (_ui.value.photos.asSequence() + _ui.value.live.asSequence())
            .associateBy { it.id }
        if (full) {
            val staleIds = oldById.keys.filterNot { it in scannedById }.toSet()
            val reusedIds = oldById.keys.filter {
                it in scannedById && scannedById[it]!!.size != oldById[it]!!.size
            }.toSet()
            if (staleIds.isNotEmpty() || reusedIds.isNotEmpty()) {
                fun refresh(list: List<RemotePhoto>) = list.mapNotNull { p ->
                    when {
                        p.id in staleIds -> null                       // 相机上已删除
                        p.id in reusedIds -> scannedById[p.id]         // 同路径换新文件，用相机端最新数据
                        else -> p
                    }
                }
                _ui.value = _ui.value.copy(
                    photos = refresh(_ui.value.photos).sortedByDescending { it.modified },
                    live = refresh(_ui.value.live),
                    thumbs = _ui.value.thumbs - staleIds - reusedIds,
                    liveThumbs = _ui.value.liveThumbs - staleIds - reusedIds
                )
            }
        }

        // markFresh 是「认领」语义：返回 true 表示这一张归我收。
        //
        // 认领之后**必须紧接着下载入库** —— 早期版本只把它摆进监视页、不下载，
        // 而后台常驻接收服务判断「要不要下载」用的也是同一个已见集合，
        // 界面一认领，服务就再也不认领了。于是照片一直躺在相机里，
        // 用户必须切到监视页手动点「保存到图库」——这就是「拍完照不自动进图库」的根因。
        val fresh = scanned.filter { SourceHolder.markFresh(it.id) }
            .sortedByDescending { it.modified }
        // 界面展示用「新照片」：只要这张还没上过屏就算新——
        // 就算它被后台常驻服务抢先认领下载了，监视页也要能看到（以前不显示，#112）。
        // 判定用「路径+大小」：路径相同、大小变了说明相机上换了新文件（DCF 编号复用），
        // 必须当新图处理，否则新拍的照片会被旧条目挡住不显示。
        val knownUi = (_ui.value.photos.asSequence() + _ui.value.live.asSequence())
            .map { it.id to it.size }.toSet()
        val displayNew = scanned.filter { (it.id to it.size) !in knownUi }.sortedByDescending { it.modified }
        if (displayNew.isEmpty()) return

        // 先把新照片摆出来（含缩略图），再去下载：界面立刻有反应，不用干等
        val merged = (displayNew + _ui.value.photos).sortedByDescending { it.modified }
        _ui.value = _ui.value.copy(
            photos = merged,
            live = displayNew + _ui.value.live
        )

        for (p in displayNew) {
            // 缩略图偶发拉取失败（连接忙/超时）就 continue 的话，这张图会永远黑卡片转圈。
            // 重试一次，仍失败才放弃 —— 但照片本身还在列表里，下轮扫描不会再来这里补，
            // 所以失败时也再拖 600ms 补最后一枪，尽量不留黑卡（#99）
            var bmp = sourceLock.withLock { runCatching { src.thumbnail(p) }.getOrNull() }
            if (bmp == null) {
                delay(300)
                bmp = sourceLock.withLock { runCatching { src.thumbnail(p) }.getOrNull() }
            }
            if (bmp == null) {
                delay(600)
                bmp = sourceLock.withLock { runCatching { src.thumbnail(p) }.getOrNull() }
            }
            if (bmp != null) _ui.value = _ui.value.copy(liveThumbs = _ui.value.liveThumbs + (p.id to bmp))
        }

        // 自动预览**跟随最新一张**：拍照后经常是旧图先到、新图过几秒才写完卡，
        // 旧逻辑只在预览为空时打开一次，之后新图到了也不切——监视页停在前一张上，
        // 用户看到的就是「监视的图和刚拍的不是一张」。用户手动点开某张即视为在翻看，停止跟随。
        if (autoPreviewFollow || _ui.value.livePreviewId == null) {
            openPreviewInternal(displayNew.first().id, auto = true)
        }

        // 黑卡补漏：live 里还有缩略图没拿到的（刚拍完相机在写卡，首轮容易超时），
        // 本轮逐个补拉直到补齐——以前每轮只补一张，连拍几张时后面的一直转圈
        val missing = _ui.value.live.filter { it.id !in _ui.value.liveThumbs }
        for (m in missing) {
            val bmp = sourceLock.withLock { runCatching { src.thumbnail(m) }.getOrNull() }
            if (bmp != null) {
                _ui.value = _ui.value.copy(liveThumbs = _ui.value.liveThumbs + (m.id to bmp))
            } else {
                delay(400)   // 拉失败缓一口气再试下一张，别贴着相机连打
            }
        }

        // 自动收进图库（美化丢后台队列，不拖慢下一轮扫描）；
        // 收下来的从「新照片」移走、并入相册顶部，监视页不会积压「待下载」。
        // 只有 UI 亲自认领的（fresh）才由这里下载——被后台服务认领的由服务负责。
        if (fresh.isNotEmpty()) {
            val saved = runCatching { ingestPhotos(fresh, asyncBeauty = true) }.getOrDefault(0)
            if (saved > 0) {
                markIngested(fresh)
                // 新照片入库后相册缩略图还没加载 → 刚拍的图在监视页一直转圈（黑卡片）。
                // 这里补拉一次相册缩略图，让它立刻显示出来。
                loadThumbnails(_ui.value.photos)
                _ui.value = _ui.value.copy(status = "已自动保存 $saved 张到图库")
            }
        }
    }

    /** 按 id 找一张照片：优先新照片，其次相机相册里的（监视页两种都能点开看） */
    private fun photoById(id: String): RemotePhoto? =
        _ui.value.live.firstOrNull { it.id == id }
            ?: _ui.value.photos.firstOrNull { it.id == id }

    /**
     * 用户手动打开预览：点开即视为在主动翻看，自动「跟随最新」就此停止，
     * 不然用户正看 A 图，下一轮扫描又把画面抢到最新一张上。
     */
    fun openPreview(id: String) = openPreviewInternal(id, auto = false)

    private fun openPreviewInternal(id: String, auto: Boolean) {
        val photo = photoById(id) ?: return
        autoPreviewFollow = auto
        _ui.value = _ui.value.copy(livePreviewId = id, livePreview = null)
        viewModelScope.launch(Dispatchers.IO) {
            val src = source ?: return@launch
            val bmp = sourceLock.withLock {
                runCatching { src.preview(photo, PREVIEW_EDGE) }.getOrNull()
            }
            // 期间用户可能又点了别张，只认最后一次选择
            if (_ui.value.livePreviewId == id) {
                _ui.value = _ui.value.copy(livePreview = bmp, livePreviewId = id)
            }
        }
    }

    fun toggleLive(id: String) {
        val sel = _ui.value.liveSelected
        _ui.value = _ui.value.copy(liveSelected = if (id in sel) sel - id else sel + id)
    }

    fun toggleAllLive() {
        val all = _ui.value.live.map { it.id }.toSet()
        _ui.value = _ui.value.copy(
            liveSelected = if (_ui.value.liveSelected.size == _ui.value.live.size) emptySet() else all
        )
    }

    /**
     * 导入单张（大图预览里点「保存到图库」）。
     * 若这张属于监视页的「新照片」，保存成功后从新照片列表里移除，避免重复导入。
     */
    fun importOne(id: String, onFinished: (Int) -> Unit) {
        val photo = photoById(id) ?: return
        // 同一张正在保存中就忽略重复点击；不同照片各自独立，互不占用按钮
        if (id in _ui.value.importingIds) return
        _ui.value = _ui.value.copy(importingIds = _ui.value.importingIds + id)
        importPhotos(listOf(photo)) { n ->
            val done = setOf(id)
            _ui.value = _ui.value.copy(
                importingIds = _ui.value.importingIds - id,
                live = _ui.value.live.filter { it.id !in done },
                liveThumbs = _ui.value.liveThumbs - done,
                liveSelected = _ui.value.liveSelected - id
                // 不再清 livePreview/livePreviewId：保存完当前这张，预览留在原处，
                // 用户可以直接划到下一张继续保存（旧行为把预览一并关掉，等于锁死了按钮）
            )
            onFinished(n)
        }
    }

    /** 导入监视页勾选的照片；成功后从「新照片」列表移除，避免重复导入 */
    fun importLiveSelected(onFinished: (Int) -> Unit) {
        val picked = _ui.value.live.filter { it.id in _ui.value.liveSelected }
        if (picked.isEmpty()) return
        importPhotos(picked) { n ->
            val done = picked.map { it.id }.toSet()
            _ui.value = _ui.value.copy(
                live = _ui.value.live.filter { it.id !in done },
                liveThumbs = _ui.value.liveThumbs - done,
                liveSelected = emptySet(),
                livePreview = null,
                livePreviewId = null
            )
            onFinished(n)
        }
    }

    /**
     * 通用导入：逐张下载到应用私有目录，按需跑 AI 自动美化（产出「_美化」副本，原图保留）。
     * 下载持锁串行，避免和监视轮询抢同一条连接。
     */
    private fun importPhotos(picked: List<RemotePhoto>, onFinished: (Int) -> Unit) {
        val src = source ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(importing = true, progress = 0f, error = null)
            val auto = AutoBeautyPrefs.load(getApplication())
            var ok = 0
            var autoOk = 0
            withContext(Dispatchers.IO) {
                picked.forEachIndexed { index, photo ->
                    val name = photo.name
                    // 监视页「保存到图库」/ 照片页批量导入：同样登记占位，
                    // 图库「进行中」卡片 + 监视页「正在保存」进度都能看到
                    TransferActivity.markTransferring(name)
                    val dest = PhotoStore.importTarget(getApplication(), name)
                    val good = runCatching {
                        sourceLock.withLock { src.download(photo, dest) { pct -> TransferActivity.updateTransfer(name, pct) } }
                    }.isSuccess
                    if (good) {
                        ok++
                        TransferActivity.markTransferDone(name)
                        // 首页卡片的「本次收到」由这里和后台常驻服务共同累加
                        TransferStats.addReceived(name)
                        // 从这张照片的 EXIF 读出真实拍摄参数，喂给拍照页参数卡（共享持有者，后台服务同样写这里）
                        readExif(dest)?.let { CameraSettingsHolder.applyExif(it) }
                        // AI 自动美化：产出「_美化」副本，原图保留
                        if (auto.enabled) {
                            TransferActivity.markBeauty(name)
                            _ui.value = _ui.value.copy(
                                status = "AI 自动美化 ${index + 1}/${picked.size}…"
                            )
                            val preset = resolveBeautyPreset(getApplication(), auto.presetName)
                            val made = runCatching {
                                AutoBeauty.beautify(getApplication(), dest, auto.strength, auto.fullRes, auto.quality, auto.useParser, preset)
                            }.getOrNull()
                            if (made != null) autoOk++
                            TransferActivity.markBeautyDone(name)
                        }
                    } else {
                        TransferActivity.markTransferDone(name)
                    }
                    _ui.value = _ui.value.copy(
                        progress = (index + 1).toFloat() / picked.size,
                        status = "已导入 ${index + 1}/${picked.size}"
                    )
                }
            }
            _ui.value = _ui.value.copy(
                importing = false,
                status = "导入完成：$ok/${picked.size} 张" +
                    if (auto.enabled && autoOk > 0) "，AI 自动美化 $autoOk 张" else ""
            )
            onFinished(ok)
        }
    }

    /**
     * USB 设备被拔下时由 [MainActivity] 的广播回调。
     * 仅当当前来源确为 USB 才处理——否则 WiFi/FTP 源不该被误清。
     * 拔线后把来源置空并刷新 UI 状态，后台常驻接收服务下一轮轮询也会自然显示「等待相机连接」。
     */
    fun onUsbDetached() {
        if (SourceHolder.source !is UsbMtpSource) return
        closeSource()
        TransferActivity.clear()
        TransferStats.clear()
        _ui.value = _ui.value.copy(
            connected = false,
            status = "USB 已断开，重新连接后继续",
            photos = emptyList(),
            model = "",
            photoCount = 0,
            thumbs = emptyMap(),
            selected = emptySet(),
            live = emptyList(),
            liveThumbs = emptyMap(),
            liveSelected = emptySet(),
            livePreview = null,
            livePreviewId = null
        )
    }

    private fun closeSource() {
        stopLiveView(restartWatch = false)
        stopWatch()
        stopPropsPolling()
        thumbJob?.cancel()
        thumbJob = null
        SourceHolder.source?.let { runCatching { it.close() } }
        SourceHolder.detach()
        pollRound = 0
        autoPreviewFollow = true
        // 换来源/断开后，上一台相机的状态表不能留着当新相机的参数显示
        _cameraProps.value = CameraProps.EMPTY
        _capacity.value = ""
        _zoomHint.value = null
        // 断开相机时清掉取景末帧占位与命令列表缓存，避免留给新连接当过期占位（#127）
        _lastFrame.value = null
        OlympusLiveView.clearCommandListCache()
    }

    /**
     * 断开当前 WiFi/远程来源：关闭并清空 [SourceHolder]，界面回到「未连接」，
     * 顶部卡片随之恢复为「扫描热点」（调用方负责解绑进程网络）。
     */
    fun disconnect() {
        closeSource()
        wifiConnector.stopWatchConnection()
        TransferActivity.clear()
        TransferStats.clear()
        _ui.value = _ui.value.copy(
            connected = false,
            busy = false,
            status = "",
            error = null,
            photos = emptyList(),
            model = "",
            photoCount = 0,
            wifiSsid = "",
            thumbs = emptyMap(),
            selected = emptySet(),
            live = emptyList(),
            liveThumbs = emptyMap(),
            liveSelected = emptySet(),
            livePreview = null,
            livePreviewId = null
        )
        ConnectionProfilePrefs.save(getApplication(), ConnectionProfile(type = ConnType.NONE))
    }

    /** 只记下当前连上的热点名，不改动其它状态（连上热点但照片服务还没探到时调用） */
    fun noteWifiSsid(ssid: String) {
        _ui.value = _ui.value.copy(wifiSsid = ssid)
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    override fun onCleared() {
        closeSource()
    }
}
