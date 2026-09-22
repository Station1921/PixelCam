package com.station1921.pixelcam.data

import android.content.Context
import android.util.Log
import com.station1921.pixelcam.data.TransferActivity
import com.station1921.pixelcam.beauty.AiEnhance
import com.station1921.pixelcam.beauty.BeautyEngine
import com.station1921.pixelcam.beauty.BeautyParams
import com.station1921.pixelcam.beauty.FaceAnalyze
import com.station1921.pixelcam.beauty.Geometry
import com.station1921.pixelcam.beauty.OpenCv
import com.station1921.pixelcam.transfer.MediaKind
import com.station1921.pixelcam.transfer.mediaKindOf
import com.station1921.pixelcam.util.BitmapIo
import com.station1921.pixelcam.util.copyExif
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.min

/**
 * AI 自动美化的用户配置：开关 + 强度 + 是否原分辨率输出 + 输出质量。
 * 强度：0 轻 / 1 标准 / 2 明显。
 * fullRes：true 时按原图分辨率输出（更清晰但更慢、更耗内存）。
 * quality：JPEG 输出质量（70–100），与原分辨率相互独立，默认 100（无损）。
 * useParser：是否启用内置的端侧人脸解析模型（已打包进安装包，离线推理）。
 *   开启后用像素级皮肤掩码，眉/眼/唇零误伤；关闭则用传统肤色椭圆掩码。
 * presetName：用户选中的「自定义预设」名称（见 [BeautyPresetStore]）。
 *   为空 = 走原来的 AI 自适应（按画面分析 + 强度档）；非空 = 直接套用该预设的全部参数，
 *   不再叠加 AI 估算、也不套强度系数（预设本身已经是一组确定的参数）。
 */
data class AutoBeautyConfig(
    val enabled: Boolean = false,
    val strength: Int = 1,
    val fullRes: Boolean = false,
    val quality: Int = 100,
    val useParser: Boolean = true,
    val presetName: String? = null
)

object AutoBeautyPrefs {
    private const val FILE = "auto_beauty"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STRENGTH = "strength"
    private const val KEY_FULLRES = "full_res"
    private const val KEY_QUALITY = "quality"
    private const val KEY_USE_PARSER = "use_parser"
    private const val KEY_PRESET_NAME = "preset_name"

    /** 输出质量取值区间（过低会明显糊，封顶 100 即 JPEG 近乎无损） */
    private const val QUALITY_MIN = 70
    private const val QUALITY_MAX = 100

    fun load(context: Context): AutoBeautyConfig {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return AutoBeautyConfig(
            enabled = sp.getBoolean(KEY_ENABLED, false),
            strength = sp.getInt(KEY_STRENGTH, 1).coerceIn(0, 2),
            fullRes = sp.getBoolean(KEY_FULLRES, false),
            quality = sp.getInt(KEY_QUALITY, 92).coerceIn(QUALITY_MIN, QUALITY_MAX),
            useParser = sp.getBoolean(KEY_USE_PARSER, true),
            presetName = sp.getString(KEY_PRESET_NAME, null)
        )
    }

    fun save(context: Context, config: AutoBeautyConfig): AutoBeautyConfig {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_STRENGTH, config.strength.coerceIn(0, 2))
            .putBoolean(KEY_FULLRES, config.fullRes)
            .putInt(KEY_QUALITY, config.quality.coerceIn(QUALITY_MIN, QUALITY_MAX))
            .putBoolean(KEY_USE_PARSER, config.useParser)
            .putString(KEY_PRESET_NAME, config.presetName)
            .apply()
        return config
    }
}

/** 把首页配置里的预设名解析成实际参数；名为空或找不到时返回 null（走 AI 自适应） */
fun resolveBeautyPreset(context: Context, presetName: String?): BeautyParams? {
    if (presetName.isNullOrBlank()) return null
    return BeautyPresetStore.byName(context, presetName)
}

/** 后台自动美化的运行统计快照（供前台通知展示，避免失败/RAW 静默无反馈） */
data class BeautyStats(val done: Int = 0, val failed: Int = 0, val skippedRaw: Int = 0)

/**
 * AI 自动美化流水线：把一张已导入的照片按「亮度 + 人脸占比」智能配参
 * （与修图页 AI 优化同源），再按用户所选强度整体缩放磨皮/五官力度。
 * 产出「_美化」副本放进导入列表（原图保留，便于对比/重调）。
 *
 * 后台接收场景下，美化通过 [enqueue] 进入**异步队列**执行：
 * 轮询线程只负责下载，不再被串行美化阻塞；队列由 [WORKERS] 个常驻 worker 协程消费，
 * 天然把并发限制在 [WORKERS] 路，避免连拍时占满 CPU 或多张同时解码引发 OOM。
 */
object AutoBeauty {

    private const val TAG = "AutoBeauty"
    /** 自动处理的工作分辨率——预览足够清晰，处理速度也可控 */
    private const val WORK_EDGE = 2048
    /** 后台美化队列的常驻消费协程数：即最大并发美化路数（连拍时避免占满 CPU / 引发 OOM） */
    private const val WORKERS = 2

    /** 异步处理队列：轮询只管下载，美化在后台由固定 worker 限流跑（见 [enqueue]） */
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("AutoBeautyQueue"))
    private val queue = Channel<BeautyTask>(Channel.UNLIMITED)

    /** 运行期统计：供前台通知展示，避免「失败 / RAW 跳过」静默无反馈 */
    private val _processed = MutableStateFlow(0)
    private val _failed = MutableStateFlow(0)
    private val _skippedRaw = MutableStateFlow(0)
    val stats: StateFlow<BeautyStats> = combine(_processed, _failed, _skippedRaw) { d, f, r ->
        BeautyStats(d, f, r)
    }.stateIn(queueScope, SharingStarted.Eagerly, BeautyStats())

    private data class BeautyTask(
        val context: Context,
        val src: File,
        val name: String,
        val strength: Int,
        val fullRes: Boolean = false,
        val quality: Int = 100,
        val useParser: Boolean = true,
        val overrideParams: BeautyParams? = null
    )

    init {
        // 拉起固定数量的消费协程，每个协程串行处理自己领到的任务，
        // 因此同时进行的美化最多 WORKERS 路，不会阻塞轮询线程
        repeat(WORKERS) { queueScope.launch { worker() } }
    }

    private suspend fun worker() {
        for (task in queue) {
            // 占位卡已在 enqueue 时登记（入队即显示、总数即确定），
            // 这里只在收尾移除：开始时再登记会把总数翻倍、且「已完成」永远追不上
            runCatching { beautify(task.context, task.src, task.strength, task.fullRes, task.quality, task.useParser, task.overrideParams) }
                .onSuccess { if (it != null) _processed.value += 1 }
                .onFailure { e ->
                    Log.w(TAG, "AI 自动美化失败：${task.src.name}", e)
                    _failed.value += 1
                }
            TransferActivity.markBeautyDone(task.name)
        }
    }

    /** 重置统计计数（每次后台接收服务启动时调用，避免跨会话累计） */
    fun resetStats() {
        _processed.value = 0
        _failed.value = 0
        _skippedRaw.value = 0
    }

    /**
     * 非阻塞入队：轮询线程调用后立刻返回，美化在后台限流队列中异步执行，
     * 不会再拖慢「下载 → 下一轮轮询」的节奏。
     * RAW 无法被 Bitmap 解码，直接计入「跳过」且不入队（原图保留，不再静默吞掉）。
     */
    fun enqueue(
        context: Context,
        src: File,
        strength: Int,
        fullRes: Boolean = false,
        quality: Int = 100,
        useParser: Boolean = true,
        overrideParams: BeautyParams? = null
    ) {
        if (mediaKindOf(src.name) == MediaKind.RAW) {
            Log.i(TAG, "RAW 不参与自动美化（无法解码），已跳过：${src.name}")
            _skippedRaw.value += 1
            return
        }
        queue.trySend(BeautyTask(context.applicationContext, src, src.name, strength, fullRes, quality, useParser, overrideParams))
        // 入队即登记占位卡 + 计入批次总数：图库立刻显示「排队中」，且
        // 进度 = 已完成/总入队数 从第一张完成起就开始真实上涨（此前在 worker
        // 开始处理时才登记，单张串行节奏下「已完成」恒为 0，进度卡永远 0%）
        TransferActivity.markBeauty(src.name)
    }

    /** 强度档 → 皮肤/调色力度系数（磨皮、美白、祛痘、提亮、锐化等随档放大） */
    private fun factorOf(strength: Int): Float = when (strength) {
        0 -> 0.55f  // 轻：接近原图，只轻微提气色
        2 -> 1.45f  // 明显：磨皮美白更足
        else -> 1f  // 标准
    }

    /**
     * 形变类（瘦脸/大眼/V脸/瘦鼻）力度封顶因子：即便选「明显」档，
     * 几何形变也不会随皮肤系数一起放大到 1.45，避免人脸被拉得过度失真。
     */
    private const val DEFORM_CAP = 1f

    /** 单张美化最长耗时：超时即视为失败计入统计，避免单张卡死拖垮接收/预览 */
    private const val BEAUTY_TIMEOUT_MS = 60_000L

    /**
     * 对 [src] 做自动美化，成功返回新生成的文件；无法处理返回 null。
     * 无人脸时只做画面优化（亮度/锐化），不强行美型。
     *
     * 该方法是「纯」实现，不触碰统计计数——计数由队列 worker 负责，
     * 这样修图页手动调用也不会污染后台自动美化的统计。
     *
     * [fullRes] 为 true 时按原图分辨率解码输出（更清晰但更慢、更耗内存）；
     * 默认走 [WORK_EDGE] 工作分辨率以兼顾速度。
     * [quality] 为 JPEG 输出质量（70–100），与原分辨率相互独立。
     */
    suspend fun beautify(
        context: Context,
        src: File,
        strength: Int,
        fullRes: Boolean = false,
        quality: Int = 100,
        useParser: Boolean = true,
        overrideParams: BeautyParams? = null
    ): File? =
        withContext(Dispatchers.IO) {
            if (!OpenCv.ensureLoaded()) return@withContext null

            withTimeout(BEAUTY_TIMEOUT_MS) {
                val edge = if (fullRes) Int.MAX_VALUE else WORK_EDGE
                val bmp = BitmapIo.decode(src, edge) ?: return@withTimeout null
                try {
                    val faces = FaceAnalyze.detect(bmp)
                    // 用户选了「自定义预设」：直接套用预设的全部参数（构图忽略，按当前图），
                    // 不走 AI 估算、不套强度系数——预设本身就是一组确定参数。
                    // 否则走原来的 AI 自适应：按画面亮度/人脸配参，再按强度档整体缩放。
                    val params = if (overrideParams != null) {
                        overrideParams.copy(geo = Geometry.IDENTITY)
                    } else {
                        val est = AiEnhance.estimate(bmp, faces)
                        val f = factorOf(strength)
                        // 形变类单独封顶：标准档及更强都维持标准形变，不会因「明显」而过度
                        val df = min(f, DEFORM_CAP)
                        if (f == 1f) est else est.copy(
                            // 皮肤/调色随强度档放大
                            smooth = (est.smooth * f).coerceIn(0f, 100f),
                            whiten = (est.whiten * f).coerceIn(0f, 100f),
                            blemish = (est.blemish * f).coerceIn(0f, 100f),
                            ruddy = (est.ruddy * f).coerceIn(0f, 100f),
                            // 形变类封顶，防止「明显」档瘦脸/大眼过度失真
                            faceSlim = (est.faceSlim * df).coerceIn(0f, 100f),
                            vFace = (est.vFace * df).coerceIn(0f, 100f),
                            bigEye = (est.bigEye * df).coerceIn(0f, 100f),
                            noseSlim = (est.noseSlim * df).coerceIn(0f, 100f),
                            // 风光增强同样随强度档缩放（轻→弱、明显→强）
                            lowLight = (est.lowLight * f).coerceIn(0f, 100f),
                            dehaze = (est.dehaze * f).coerceIn(0f, 100f),
                            clarity = (est.clarity * f).coerceIn(0f, 100f)
                        )
                    }
                    if (params.isIdentity) return@withTimeout null

                    val out = BeautyEngine.render(bmp, params, faces, useParser)
                    if (out === bmp) return@withTimeout null

                    val dest = PhotoStore.importTarget(context, "${src.nameWithoutExtension}_美化.jpg")
                    val saved = dest.outputStream().use { stream ->
                        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 100), stream)
                    }
                    out.recycle()
                    if (saved) {
                        // 预设方案也视为「AI 自动美化」产物（首页统一角标）；
                        // 若预设本身等于 AI 估算结果（理论上不会），这里仍打 AI 标记不影响。
                        PhotoStore.markAi(context, dest)
                        // 保留原始拍摄参数（ISO/光圈/快门/焦段），否则副本的 EXIF 丢失、
                        // 首页展示的拍摄参数全空
                        runCatching { copyExif(src, dest) }
                        dest
                    } else null
                } finally {
                    bmp.recycle()
                }
            }
        }
}
