package com.station1921.pixelcam.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.station1921.pixelcam.beauty.AiEnhance
import com.station1921.pixelcam.beauty.BeautyEngine
import com.station1921.pixelcam.beauty.BeautyParams
import com.station1921.pixelcam.beauty.BeautyPreset
import com.station1921.pixelcam.beauty.DEFAULT_LOOK
import com.station1921.pixelcam.beauty.FaceAnalyze
import com.station1921.pixelcam.beauty.FaceInfo
import com.station1921.pixelcam.beauty.Geometry
import com.station1921.pixelcam.beauty.SuperRes
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import com.station1921.pixelcam.data.AutoBeautyPrefs
import com.station1921.pixelcam.data.BeautyPresetStore
import com.station1921.pixelcam.data.PhotoStore
import com.station1921.pixelcam.util.BitmapIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 预览边长：再大就拖不动滑块了 */
        private const val PREVIEW_EDGE = 1280
        /** 导出边长：兼顾画质与内存，50MP 的图也不会 OOM */
        private const val EXPORT_EDGE = 4096
        /** 撤销/重做最大步数 */
        private const val MAX_HISTORY = 30
    }

    private var source: File? = null

    /** 解码后的原始预览图（未构图、未美颜），永不在此之上做几何 */
    private var preview: Bitmap? = null

    /** 构图（旋转/镜像/裁剪）后的画布；构图恒等时直接引用 [preview] */
    private var base: Bitmap? = null

    /** [base] 对应的构图参数，用于判断是否需要重建画布 */
    private var appliedGeo: Geometry = Geometry.IDENTITY

    /** 对 [base] 的人脸检测结果（构图变化才重测，拖滑块直接复用） */
    private var faces: List<FaceInfo> = emptyList()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    /** 构图后、美颜前的图：长按对比不因旋转/裁剪而跳变 */
    private val _original = MutableStateFlow<Bitmap?>(null)
    val original: StateFlow<Bitmap?> = _original.asStateFlow()

    private val _rendered = MutableStateFlow<Bitmap?>(null)
    val rendered: StateFlow<Bitmap?> = _rendered.asStateFlow()

    private val _params = MutableStateFlow(BeautyParams())
    val params: StateFlow<BeautyParams> = _params.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _aiBusy = MutableStateFlow(false)
    val aiBusy: StateFlow<Boolean> = _aiBusy.asStateFlow()

    private val _faceCount = MutableStateFlow(0)
    val faceCount: StateFlow<Int> = _faceCount.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /**
     * 本次打开会话的起点参数：一律为零（打开即原图）。
     * 编辑器「未保存调整」判定：当前 params != baseline。保存调整成功后前移。
     */
    private val _baseline = MutableStateFlow<BeautyParams?>(null)
    val baseline: StateFlow<BeautyParams?> = _baseline.asStateFlow()

    /**
     * AI 一键优化的"开关"状态：
     * - [aiResult]    = 最近一次 AI 优化产出的参数（若与当前 params 相同 → chip 呈已开启态）
     * - [aiPrevious]  = 开启前参数，chip 再次点击时回到它（即"取消 AI 优化"）
     */
    private var aiResult: BeautyParams? = null
    private var aiPrevious: BeautyParams? = null
    private val _aiParams = MutableStateFlow<BeautyParams?>(null)
    val aiParams: StateFlow<BeautyParams?> = _aiParams.asStateFlow()

    /** 用户已保存的自定义预设（编辑器内「预设」面板展示，保存/删除后刷新） */
    private val _customPresets = MutableStateFlow<List<BeautyPreset>>(emptyList())
    val customPresets: StateFlow<List<BeautyPreset>> = _customPresets.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * CONFLATED 通道：拖动滑块时只有最新参数会被保留，
     * 渲染任务不会排队堆积，松手后立刻得到最终结果。
     */
    private val renderChannel = Channel<BeautyParams>(Channel.CONFLATED)

    /**
     * 串行化「换图 / 构图重建」与「渲染」两类对共享状态（preview / base / faces）的访问。
     */
    private val gate = Mutex()

    // ————— 撤销 / 重做历史 —————

    private val undoStack = ArrayDeque<BeautyParams>()
    private val redoStack = ArrayDeque<BeautyParams>()

    /** 滑块手势起点：一次完整拖动（down→up）只记一步撤销 */
    private var dragBase: BeautyParams? = null

    /** 裁剪会话起点：确认前的一切中间变化（含临时撤裁）不算历史 */
    private var cropSessionBase: BeautyParams? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            for (p in renderChannel) {
                val rendered = gate.withLock { renderTo(p) }
                // 等待门闩期间可能发生了换图+参数重置，丢弃这帧过期结果
                if (rendered != null && _params.value == p) {
                    _rendered.value = rendered
                }
            }
        }
    }

    // ————— 数据装载 —————

    fun load(file: File) {
        source = file
        _name.value = file.name
        // 读入已保存的自定义预设，供「预设」面板展示
        _customPresets.value = BeautyPresetStore.loadAll(getApplication())
        // 先清空上一张开过的图，避免进入编辑页时先闪出旧图、再跳到新图（图片残留）
        _rendered.value = null
        _original.value = null
        _busy.value = true
        undoStack.clear()
        redoStack.clear()
        dragBase = null
        cropSessionBase = null
        aiResult = null
        aiPrevious = null
        _aiParams.value = null
        syncHistoryFlags()
        viewModelScope.launch(Dispatchers.Default) {
            gate.withLock {
                _busy.value = true
                val bmp = BitmapIo.decode(file, PREVIEW_EDGE)
                if (bmp == null) {
                    _busy.value = false
                    _message.value = "无法解码这张图片：${file.name}"
                    return@withLock
                }

                val oldPreview = preview
                val oldBase = base
                preview = bmp
                base = bmp
                appliedGeo = Geometry.IDENTITY
                faces = FaceAnalyze.detect(bmp)
                _faceCount.value = faces.size

                // 起点参数一律为零：打开图片看到的就是原图本身，
                // 想套基础观感点预设里的「自然」（= DEFAULT_LOOK）
                val startParams = BeautyParams()

                _original.value = bmp
                _rendered.value = bmp

                _params.value = startParams
                _baseline.value = startParams
                // 必须显式投递渲染通道：渲染循环只消费 renderChannel
                renderChannel.trySend(startParams)

                // 旧图画布与旧预览回收（构图独立位图单独回收）
                if (oldBase != null && oldBase !== oldPreview) oldBase.recycle()
                oldPreview?.recycle()
                _busy.value = false
            }
        }
    }

    // ————— 参数 / 构图操作 —————

    /** 滑块拖动中的连续更新：不产生历史，由 [beginEdit]/[endEdit] 负责一次手势记一步 */
    fun updateParams(p: BeautyParams) {
        _params.value = p
        renderChannel.trySend(p)
    }

    /** 滑块手势开始：记录拖动前的状态 */
    fun beginEdit() {
        if (dragBase == null) dragBase = _params.value
    }

    /** 滑块手势结束：若值真的变了，把拖动前的状态推入撤销栈 */
    fun endEdit() {
        val base = dragBase ?: return
        dragBase = null
        if (base != _params.value) {
            pushUndo(base)
        }
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_params.value)
        trimHistory()
        _params.value = s
        renderChannel.trySend(s)
        syncHistoryFlags()
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_params.value)
        trimHistory()
        _params.value = s
        renderChannel.trySend(s)
        syncHistoryFlags()
    }

    /** 供离散操作（归零等）在变化前显式入栈一步 */
    fun pushUndoSnapshot() {
        pushUndo(_params.value)
    }

    private fun pushUndo(s: BeautyParams) {
        undoStack.addLast(s)
        trimHistory()
        // 一旦产生新分支，作废之前的重做方向
        redoStack.clear()
        syncHistoryFlags()
    }

    private fun syncHistoryFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    /** 两个历史栈统一封顶 */
    private fun trimHistory() {
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        while (redoStack.size > MAX_HISTORY) redoStack.removeFirst()
    }

    // ————— 离散动作（每个动作一个撤销步） —————

    /** 预设：只覆盖美化部分，保留当前构图 */
    fun applyPreset(preset: BeautyPreset) {
        val cur = _params.value
        val next = preset.params.copy(geo = cur.geo)
        if (next == cur) return
        pushUndo(cur)
        _params.value = next
        renderChannel.trySend(next)
    }

    /** 从持久化层刷新自定义预设列表（进入编辑页 / 保存 / 删除后调用） */
    fun refreshPresets(context: android.content.Context) {
        _customPresets.value = BeautyPresetStore.loadAll(context)
    }

    /**
     * 把当前所有调整保存为自定义预设（命名 [name]）。
     * 构图不入库（预设套用到别图无意义），套用时由 [applyPreset] 保留目标图构图。
     * 没有任何调整（全部为 0）时拒绝保存，返回 false。
     */
    fun savePreset(context: android.content.Context, name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        val p = _params.value.copy(geo = Geometry.IDENTITY)
        if (p.isIdentity) return false
        BeautyPresetStore.upsert(context, BeautyPreset(clean, p))
        refreshPresets(context)
        return true
    }

    /** 删除一个自定义预设 */
    fun deletePreset(context: android.content.Context, name: String) {
        BeautyPresetStore.delete(context, name)
        refreshPresets(context)
    }

    /** 滤镜（id=0 为原图/清除） */
    fun applyFilter(id: Int) {
        val cur = _params.value
        if (cur.filterId == id) return
        pushUndo(cur)
        _params.value = cur.copy(filterId = id)
        renderChannel.trySend(_params.value)
    }

    /** 标题栏「重置」：美化全部归零（回到刚打开时的原图状态），保留用户的构图 */
    fun resetBeauty() {
        val cur = _params.value
        val next = BeautyParams().copy(geo = cur.geo)
        if (next == cur) return
        pushUndo(cur)
        _params.value = next
        renderChannel.trySend(next)
    }

    fun rotateCW() {
        val cur = _params.value
        val next = cur.copy(geo = cur.geo.copy(rotateQuarter = (cur.geo.rotateQuarter + 1) % 4))
        if (next == cur) return
        pushUndo(cur)
        _params.value = next
        renderChannel.trySend(next)
    }

    fun mirrorH() {
        val cur = _params.value
        val next = cur.copy(geo = cur.geo.copy(mirror = !cur.geo.mirror))
        pushUndo(cur)
        _params.value = next
        renderChannel.trySend(next)
    }

    /** 一键清空旋转/镜像/裁剪 */
    fun resetGeometry() {
        val cur = _params.value
        val next = cur.copy(geo = Geometry.IDENTITY)
        if (next == cur) return
        pushUndo(cur)
        _params.value = next
        renderChannel.trySend(next)
    }

    // ————— 裁剪会话 —————

    /**
     * 进入裁剪：暂记当前状态并把已应用的旧裁剪撤掉，让预览回到全画布。
     * 取消/确认前的一切中间变化（包括这次临时撤裁）都不产生历史。
     */
    fun beginCropSession() {
        if (cropSessionBase != null) return
        cropSessionBase = _params.value
        val cur = _params.value
        if (cur.geo.crop != null) {
            _params.value = cur.copy(geo = cur.geo.copy(crop = null))
            renderChannel.trySend(_params.value)
        }
    }

    /** 确认裁剪 [rect]（相对全画布 0..1）：整体算一个撤销步 */
    fun confirmCrop(rect: RectF) {
        val base = cropSessionBase ?: return
        cropSessionBase = null
        val next = base.copy(geo = base.geo.copy(crop = rect))
        if (next != base) {
            pushUndo(base)
            _params.value = next
            renderChannel.trySend(next)
        } else {
            // 框回原样：无声恢复即可，不留历史
            _params.value = base
            renderChannel.trySend(base)
        }
    }

    /** 取消裁剪：无声恢复到会话开始前 */
    fun cancelCropSession() {
        val base = cropSessionBase ?: return
        cropSessionBase = null
        _params.value = base
        renderChannel.trySend(base)
    }

    // ————— AI 智能优化 —————

    /**
     * 离线一键优化：亮度统计 + 人脸占比推一组自然参数。
     * 保留构图，清掉滤镜（避免两套颜色叠加打架）；作为一步撤销。
     *
     * 可当作开关使用：若当前参数正是上一次 AI 优化的结果，再点一次 = 取消，
     * 直接回到优化前的参数（而不是再算一遍）。
     */
    fun aiEnhance() {
        if (_aiBusy.value) return
        val cur = _params.value
        // 处于"AI 已优化"态 → 取消，回到开启前的参数
        if (aiResult != null && cur == aiResult) {
            val prev = aiPrevious ?: cur
            aiResult = null
            aiPrevious = null
            _aiParams.value = null
            if (prev != cur) {
                pushUndo(cur)
                _params.value = prev
                renderChannel.trySend(prev)
            }
            return
        }
        val src = preview ?: return
        _aiBusy.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                gate.withLock {
                    val c = _params.value
                    val estimate = AiEnhance.estimate(src, faces)
                    val next = estimate.copy(geo = c.geo)
                    if (next != c) {
                        pushUndo(c)
                        _params.value = next
                        renderChannel.trySend(next)
                        aiResult = next
                        aiPrevious = c
                        _aiParams.value = next
                    } else {
                        aiResult = null
                        aiPrevious = null
                        _aiParams.value = null
                    }
                }
            } finally {
                _aiBusy.value = false
            }
        }
    }

    /**
     * 神经超分（Real-ESRGAN ×4）：对当前画面做 4 倍放大并增强细节，替换整张画布。
     * 超分是放大重采样、非连续参数，故不进撤销栈（撤销只追踪 [BeautyParams]）；
     * 几何已在超分图上烘焙，故把 geo 复位为 IDENTITY，避免后续重复几何变换。
     * 模型未内置或推理失败时给出提示、画布保持不变。
     */
    fun applySuperRes() {
        if (_busy.value) return
        val cur = _rendered.value ?: _original.value ?: base ?: return
        if (!SuperRes.available()) {
            _message.value = "超分模型未内置，无法使用"
            return
        }
        _busy.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                gate.withLock {
                    val bmp = _rendered.value ?: _original.value ?: base ?: return@withLock
                    val srcMat = Mat()
                    Utils.bitmapToMat(bmp, srcMat)
                    val bgr = Mat()
                    Imgproc.cvtColor(srcMat, bgr, Imgproc.COLOR_RGBA2BGR)
                    val out = SuperRes.enhance(bgr)
                    if (out === bgr) {
                        // 失败：enhance 返回原输入引用
                        _message.value = "超分失败，请重试"
                        bgr.release(); srcMat.release()
                        return@withLock
                    }
                    val outRgba = Mat()
                    Imgproc.cvtColor(out, outRgba, Imgproc.COLOR_BGR2RGBA)
                    val outBmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(outRgba, outBmp)

                    // 替换预览/画布为超分结果
                    val oldPreview = preview
                    val oldBase = base
                    preview = outBmp
                    base = outBmp
                    appliedGeo = Geometry.IDENTITY
                    faces = FaceAnalyze.detect(outBmp)
                    _faceCount.value = faces.size
                    _original.value = outBmp
                    _rendered.value = outBmp
                    val next = _params.value.copy(geo = Geometry.IDENTITY)
                    _params.value = next
                    _baseline.value = next
                    renderChannel.trySend(next)

                    // 回收旧位图，避免泄漏
                    if (oldBase != null && oldBase !== oldPreview) oldBase.recycle()
                    oldPreview?.recycle()
                    bgr.release(); out.release(); outRgba.release(); srcMat.release()
                    _message.value = "已超分放大 ×4"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    // ————— 渲染 —————

    /**
     * 渲染一帧：先按 p.geo 重建构图画布（含人脸重测），再做美化。
     * 在 gate 内调用，天然串行。
     */
    /** 是否启用内置人脸解析模型（与首页 AI 美化共享同一开关） */
    private val useParser: Boolean get() = AutoBeautyPrefs.load(getApplication()).useParser

    private fun renderTo(p: BeautyParams): Bitmap? {
        val src = preview ?: return null
        if (p.geo != appliedGeo) rebuildBase(p.geo)
        val canvas = base ?: return null
        return if (p.isIdentity) canvas else BeautyEngine.render(canvas, p, faces, useParser)
    }

    /** 构图参数变化：对原预览图做几何变换，得到新画布并重新检测人脸 */
    private fun rebuildBase(g: Geometry) {
        val src = preview ?: return
        val oldBase = base
        val newBase = if (g.isIdentity) src else Geometry.apply(src, g)
        base = newBase
        faces = FaceAnalyze.detect(newBase)
        _faceCount.value = faces.size
        appliedGeo = g
        _original.value = newBase
        if (oldBase != null && oldBase !== src && oldBase !== newBase) {
            oldBase.recycle()
        }
    }

    // ————— 导出 —————

    suspend fun export(quality: Int): Uri? {
        val file = source ?: return null
        val p = _params.value
        val g = p.geo
        _exporting.value = true
        return try {
            withContext(Dispatchers.IO) {
                val full = BitmapIo.decode(file, EXPORT_EDGE) ?: return@withContext null
                val canvas = if (g.isIdentity) full else Geometry.apply(full, g)
                try {
                    // 全尺寸下按构图后画布重新检测，保证强度与预览一致
                    val f = FaceAnalyze.detect(canvas)
                    val out = BeautyEngine.render(canvas, p, f, useParser)
                    try {
                        PhotoStore.saveToGallery(
                            getApplication(),
                            out,
                            PhotoStore.timestampName(),
                            quality
                        )
                    } finally {
                        if (out !== canvas) out.recycle()
                    }
                } finally {
                    if (canvas !== full) canvas.recycle()
                    full.recycle()
                }
            }
        } finally {
            _exporting.value = false
        }
    }

    /**
     * 「保存调整」：全尺寸按当前参数烘焙，原子覆盖当前源文件（原地，不新增列表条目）。
     * 成功后标记该文件——下次打开以零美化起步；首页缩略图因文件内容更新而自动刷新。
     */
    suspend fun saveAdjust(quality: Int = 95): Boolean {
        val file = source ?: return false
        if (_exporting.value) return false
        _exporting.value = true
        return try {
            val ok = withContext(Dispatchers.IO) {
                val p = _params.value
                val g = p.geo
                val full = BitmapIo.decode(file, EXPORT_EDGE) ?: return@withContext false
                val canvas = if (g.isIdentity) full else Geometry.apply(full, g)
                try {
                    val f = FaceAnalyze.detect(canvas)
                    val out = BeautyEngine.render(canvas, p, f, useParser)
                    try {
                        val tmp = File(file.parentFile, file.name + ".tmp")
                        val wrote = tmp.outputStream().use { stream ->
                            out.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                        }
                        if (!wrote) {
                            tmp.delete()
                            return@withContext false
                        }
                        if (!tmp.renameTo(file)) {
                            tmp.copyTo(file, overwrite = true)
                            tmp.delete()
                        }
                        true
                    } finally {
                        if (out !== canvas) out.recycle()
                    }
                } finally {
                    if (canvas !== full) canvas.recycle()
                    full.recycle()
                }
            }
            if (ok) {
                // 原地烘焙完成：打「美化」标记；若落盘的正是 AI 优化结果，再打「AI美化」
                PhotoStore.markEdited(getApplication(), file)
                if (aiResult == _params.value) {
                    PhotoStore.markAi(getApplication(), file)
                }
                // 已烘焙入库：会话基线前移到当前参数，此后直接退出不再误报"未保存"
                _baseline.value = _params.value
            }
            ok
        } finally {
            _exporting.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        renderChannel.close()
        val p = preview
        val b = base
        if (b != null && b !== p) b.recycle()
        preview?.recycle()
        preview = null
        base = null
    }
}
