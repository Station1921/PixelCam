package com.station1921.pixelcam.transfer

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * 奥林巴斯 / OM System 相机在自建 WiFi 热点模式下暴露的 OI.Share HTTP API（OPC 协议）。
 *
 * 地址：相机自身就是 DHCP 服务器，HTTP 服务固定在 `http://192.168.0.10`（**不是**网关地址）。
 * 测试机型：OM-D E-M10 Mark IV 等支持 OI.Share 的机型。
 *
 * 协议要点（据 OPC Communication Protocol 1.0a 与各家开源实现核对）：
 * - `get_commandlist.cgi` 返回命令列表 XML，是识别机型的特征端点；请求需带 `User-Agent: OI.Share v2`。
 * - `get_caminfo.cgi` 返回机型名。
 * - `switch_cammode.cgi?mode=play` 切到播放/回放模式：列图命令只在播放模式下被接受，
 *   拍摄模式下会返回非标准状态码 520 + XML 错误体。
 * - `get_imglist.cgi?DIR=<目录>` 返回**纯文本**，形如：
 *   ```
 *   VER_100
 *   /DCIM/100OLYMP,PC150001.JPG,7746678,0,17807,41094
 *   ```
 *   字段依次为 目录 / 文件名 / 字节数 / 属性 / 日期 / 时间；目录项属性位 0x10。
 * - 缩略图 `get_thumbnail.cgi?DIR=<完整文件路径>`，中等尺寸 `get_resizeimg.cgi?DIR=<完整路径>&size=N`，
 *   原图直接取 `http://host<完整路径>`。
 */
class OlympusSource(
    val host: String,
    private val cacheDir: File
) : PhotoSource {

    private val base = "http://$host"

    /** 机型名（get_caminfo 取到才有），用于界面显示 */
    @Volatile
    private var model: String = ""

    /**
     * 实时取景是否正在进行（由界面在起/停 [OlympusLiveView] 时置位）。
     *
     * 为什么相机这边要知道：列图/快门收尾都会把相机切回播放模式，
     * 而取景流只在拍摄模式下存在——取景中切模式等于把取景掐断。
     * 所有「切回播放模式」的入口都要先看这个标记。
     */
    @Volatile
    var liveViewRunning: Boolean = false

    /**
     * 当前取景会话的归属标记（就是那个 [OlympusLiveView] 实例本身）。
     *
     * 只凭 [liveViewRunning] 一个布尔值不够：「停了立刻又起」时，旧会话的收尾协程
     * 会晚于新会话的开始执行，旧的无脑清标记会把新会话的取景保护抹掉，
     * 后台服务随即恢复轮询、把相机切回播放模式——表现为取景频繁断流、重进才好。
     * 有了归属标记，旧会话只能清自己的。
     */
    @Volatile
    var liveViewOwner: Any? = null

    override val id: String get() = "olympus://$host"

    override val label: String
        get() = if (model.isBlank()) "Olympus $host" else "$model（Olympus）"

    private fun get(path: String, timeoutMs: Int = 6000, maxBytes: Int = 1 shl 20): HttpResult =
        httpGetResult("$base$path", timeoutMs, OLYMPUS_UA, maxBytes)

    /**
     * 切到播放/回放模式。返回一句人类可读的结果，供诊断面板使用。
     *
     * 取景保护：取景流只在拍摄模式下存在，取景中把相机切回播放模式等于掐断取景。
     * 列图（list）、快门收尾等所有调用方都从这里过，守在这里一处就全覆盖了——
     * 这是之前「拍照页取景经常断、重进才好」的直接原因（后台轮询恰好在取景开始
     * 后拿到锁，先检查过标记、再进来切模式，窗口期内把流杀了）。
     */
    fun enterPlayMode(): String {
        if (liveViewRunning) return "取景中，跳过切回播放模式"
        val r = get("/switch_cammode.cgi?mode=play", 3000, 4096)
        return when {
            r.code == 0 -> "失败（${r.error}）"
            r.ok -> "HTTP ${r.code} 成功"
            else -> "HTTP ${r.code}" + if (r.body.isNotBlank()) " · ${r.body.trim().take(80)}" else ""
        }
    }

    /** 取机型名（拿不到就留空，不影响使用） */
    private fun readModel() {
        if (model.isNotBlank()) return
        val r = runCatching { get("/get_caminfo.cgi", 2500, 4096) }.getOrNull() ?: return
        if (!r.ok || r.body.isBlank()) return
        val body = r.body.trim()
        model = if (body.startsWith("<")) {
            Regex("<model>\\s*([^<]+?)\\s*</model>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim().orEmpty()
        } else {
            body.lineSequence().firstOrNull()?.trim().orEmpty().take(48)
        }
    }

    // ——————————————— 相机状态 / 对焦 / 变焦（OPC）———————————————

    /**
     * 读相机当前状态表（曝光、ISO、快门、白平衡、焦距、驱动模式…）。
     *
     * `get_camprop.cgi?com=desc&propname=desclist` 一次请求拿全：值 + 是否可写 + 可选值。
     * 只读接口，**不切模式**——所以取景中也能安全调用（切模式会把取景流掐断）。
     * 失败返回 null，界面维持上一份数据。
     */
    suspend fun readProps(): CameraProps? = withContext(Dispatchers.IO) {
        runCatching { get("/get_camprop.cgi?com=desc&propname=desclist", 4000, 128 shl 10) }
            .getOrNull()
            ?.takeIf { it.ok && it.body.isNotBlank() }
            ?.let { CameraProps.parse(it.body) }
    }

    /**
     * 触摸对焦：把对焦点交给相机并让它真正合焦（OI.Share 的「点哪儿对哪儿」）。
     *
     * 参数格式 `point=XXXXxYYYY`（各 4 位、左补零），坐标是**取景画面坐标**——
     * 与 `switch_cammode` 的 lvqty 同一尺度（例如 lvqty=0640x0480 时画面中心是 0320x0240）。
     *
     * 两步才稳（这是「点屏对焦时灵时不灵」的根因修复）：
     * 1. `assignafframe` —— 把 AF 框挪到 tapped 点（只挪框，相机不立刻合焦）；
     * 2. `takeready` —— 让相机真的在 AF 框上**合焦**（半按对焦，不触发快门）。
     * 旧版只发 `assignafframe`，相机是否合焦完全看它自己脸色，所以「有时灵有时不灵」。
     * 两步都发、任一失败就重试一次，命中率从「看运气」变成「基本必中」。
     */
    suspend fun focusAt(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        val px = x.coerceAtLeast(0).coerceAtMost(9999)
        val py = y.coerceAtLeast(0).coerceAtMost(9999)
        val point = String.format("%04dx%04d", px, py)
        var ok = false
        // 三次尝试、退避递增。对焦「时灵时不灵」多是取景流/轮询恰好占着相机、
        // 命令被吞：重试是唯一解，另外部分固件只认裸 takeready（不带 point），
        // 第二次尝试换用它兜底。
        for (attempt in 0..2) {
            val assign = runCatching {
                get("/exec_takemotion.cgi?com=assignafframe&point=$point", 4000, 4096)
            }.getOrNull()
            // assignafframe 挪框成功后 takeready 让它合焦；带 point 失败时换裸 takeready 再试
            val ready = runCatching {
                get("/exec_takemotion.cgi?com=takeready&point=$point", 4000, 4096)
            }.getOrNull()
                ?: if (attempt > 0) runCatching {
                    get("/exec_takemotion.cgi?com=takeready", 4000, 4096)
                }.getOrNull() else null
            ok = (assign != null && assign.ok) && (ready != null && ready.ok)
            if (!ok && attempt > 0) {
                // 换裸 takeready 再补一枪（前面带 point 的 ready 已失败）
                val bare = runCatching {
                    get("/exec_takemotion.cgi?com=takeready", 4000, 4096)
                }.getOrNull()
                ok = (assign != null && assign.ok) && (bare != null && bare.ok)
            }
            if (ok) break
            delay(200L * (attempt + 1))
        }
        ok
    }

    /**
     * 电动变焦。`move` 取 `telemove`（拉近）/ `widemove`（拉远）/ `off`（停）。
     *
     * 只对**电动变焦镜头**有效（如 M.Zuiko 14-42mm EZ、12-50mm EZ）；
     * 普通手动变焦环镜头（12-40mm PRO 之类）相机不执行这个指令，
     * 表现是焦距读数不变——界面据此提示「请转动镜头变焦环」。
     */
    suspend fun zoom(move: String): Boolean = withContext(Dispatchers.IO) {
        val r = runCatching { get("/exec_takemisc.cgi?com=ctrlzoom&move=$move", 4000, 4096) }
            .getOrNull()
        r != null && r.ok
    }

    /** 卡剩余可拍张数（有的固件直接返回数字，有的带提示文字，原样取回） */
    suspend fun unusedCapacity(): String? = withContext(Dispatchers.IO) {
        runCatching { get("/get_unusedcapacity.cgi", 3000, 1024) }
            .getOrNull()
            ?.takeIf { it.ok && it.body.isNotBlank() }
            ?.body?.trim()?.lineSequence()?.firstOrNull()?.trim()?.take(32)
    }

    /**
     * 写一项相机属性（OPC `set_camprop`）——拍照页顶部那些「可调」项走这里。
     *
     * 格式（据官方 OI.Share 抓包与 OMD.py 等开源实现核对）：**POST**
     * `/set_camprop.cgi?com=set&propname=<name>`，正文 `<set><value>…</value></set>`。
     * 写接口只认 POST，用 GET 会被当成非法请求。
     *
     * 另外相机要求**在拍摄模式下才能读写设置**：不在取景时（相机停在播放模式）
     * 先切一次 rec，写完再切回 play，免得把监视轮询卡在拍摄模式里列不出图。
     *
     * @param value 既可能是数字下标（多数枚举属性的固件行为），也可能是字面值，
     *        由调用方按 [CameraProp.rawForIndex] 决定；这里只负责发出去。
     * @return true 表示相机接受了这次写入
     */
    suspend fun setProp(name: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val prop = name.trim()
        if (prop.isEmpty()) return@withContext false

        val wasStreaming = liveViewRunning
        if (!wasStreaming) {
            // rec 模式是写设置的前提；带 lv_qty 部分机型不认就退回不带参数的写法
            val rec = runCatching { get("/switch_cammode.cgi?mode=rec&lv_qty=0640x0480", 5000) }.getOrNull()
            if (rec == null || !rec.ok) {
                runCatching { get("/switch_cammode.cgi?mode=rec", 5000) }
            }
        }

        val r = runCatching {
            httpPostXml(
                "$base/set_camprop.cgi?com=set&propname=$prop",
                "<set><value>$value</value></set>",
                4000, OLYMPUS_UA
            )
        }.getOrNull()

        // 不在取景时把相机放回播放模式，否则列图一直失败、监视页/自动接收都停摆
        if (!wasStreaming) runCatching { enterPlayMode() }

        r != null && r.ok
    }

    // ——————————————— WiFi 遥控快门（OPC 协议）———————————————

    /**
     * 遥控按下相机快门。
     *
     * 流程（与 OI.Share / 开源实现一致）：
     * 1. `get_commandlist.cgi` 确认机型支持遥控拍摄（有 exec_takemotion 命令）；
     * 2. `switch_cammode.cgi?mode=rec` 切到拍摄模式（带 lv_qty 取景分辨率，部分机型不认就去掉重试）；
     * 3. `exec_takemisc.cgi?com=startliveview` 起一次取景流（部分机型先起流才接受快门，不看流内容）；
     * 4. `exec_takemotion.cgi?com=starttake` 触发快门；
     * 5. 停流并**切回 play 模式**——列图接口只在播放模式下可用，监视轮询要靠它发现新照片。
     *
     * 失败时抛 [IllegalStateException]，message 可直接给用户看。
     */
    suspend fun remoteCapture(): Boolean = withContext(Dispatchers.IO) {
        // 正在实时取景：相机已经在拍摄模式、取景流也开着，能力检查在起流时做过、
        // 模式也不用切——直接发快门指令，少两轮 HTTP，快门更跟手
        val streaming = liveViewRunning
        if (!streaming) {
            val cl = runCatching { get("/get_commandlist.cgi", 4000) }.getOrNull()
                ?: throw IllegalStateException("联系不上相机，请确认还连着相机 WiFi")
            if (!cl.ok) throw IllegalStateException("相机没有响应（HTTP ${cl.code}）")
            if (!cl.body.contains("exec_takemotion", ignoreCase = true)) {
                throw IllegalStateException("这台机型不支持 WiFi 遥控快门")
            }

            val rec = runCatching { get("/switch_cammode.cgi?mode=rec&lv_qty=0640x0480", 6000) }.getOrNull()
            if (rec == null || !rec.ok) {
                val retry = runCatching { get("/switch_cammode.cgi?mode=rec", 6000) }.getOrNull()
                if (retry == null || !retry.ok) {
                    throw IllegalStateException("相机切不到拍摄模式（HTTP ${rec?.code ?: 0}），可能正在被手机端占用")
                }
            }
            // 起一次取景流再拍：部分机型必须先 startliveview 才接受 starttake；流内容不读，拍完即停
            runCatching { get("/exec_takemisc.cgi?com=startliveview&port=40000", 4000) }
        }

        // 先让相机合焦再按快门：OPC 的 starttake 在没合焦时可能 HTTP 成功但相机实际不出片
        //（用户看起来就是「快门没动」）。takeready 失败不阻塞，继续尝试快门。
        runCatching { get("/exec_takemotion.cgi?com=takeready", 5000) }

        // starttake 偶发被相机拒绝（正忙/对焦中）：隔 600ms 补一枪，两次都失败才报错
        var take = runCatching { get("/exec_takemotion.cgi?com=starttake", 10000) }.getOrNull()
            ?: throw IllegalStateException("快门指令超时")
        if (!take.ok) {
            delay(600)
            take = runCatching { get("/exec_takemotion.cgi?com=starttake", 10000) }.getOrNull()
                ?: throw IllegalStateException("快门指令超时")
            if (!take.ok) {
                throw IllegalStateException("相机拒绝了快门指令（HTTP ${take.code}），可能没合焦或正在处理上一张")
            }
        }

        // 留一点写卡时间；完整接收交给监视轮询
        delay(if (streaming) 400 else 1200)
        if (!streaming) {
            runCatching { get("/exec_takemisc.cgi?com=stopliveview", 3000) }
            enterPlayMode()
        }
        true
    }

    override suspend fun list(): List<RemotePhoto> = withContext(Dispatchers.IO) {
        readModel()
        enterPlayMode()

        val out = LinkedHashMap<String, RemotePhoto>()
        // 先试 DIR=（空）：部分机型一次返回整卡列表，每行自带所在目录，不必逐目录递归
        parseList("/get_imglist.cgi?DIR=", out)
        if (out.isEmpty()) walk("/DCIM", out, 0)
        // 兜底：某些固件在 DIR=/DCIM 下不返回子目录项，按 DCF 目录号直接找一遍
        if (out.isEmpty()) scanDirs(out)

        out.values.sortedByDescending { it.modified }
    }

    /** 按 DCF 编号扫 /DCIM/100OLYMP … /DCIM/199OLYMP（只在常规路径一无所获时才走） */
    private fun scanDirs(out: MutableMap<String, RemotePhoto>) {
        for (n in 100..199) {
            parseList("/get_imglist.cgi?DIR=/DCIM/${n}OLYMP", out)
            if (out.isNotEmpty()) return
        }
    }

    /**
     * 监视专用快速路径：只扫**编号最大的非空 DCF 目录**（新照片必然落在当期目录）。
     *
     * 以前没实现这个方法，监视每 3 秒一轮都在跑整卡全量 + 切播放模式——
     * 刚拍完相机还在写卡时最容易超时/拿到不完整列表，新图迟迟上不了监视页。
     * 现在每轮只有一次 get_imglist；全量对齐仍由调用方按周期强制执行。
     */
    override suspend fun listRecent(): List<RemotePhoto>? = withContext(Dispatchers.IO) {
        readModel()
        enterPlayMode()
        for (n in 199 downTo 100) {
            val out = LinkedHashMap<String, RemotePhoto>()
            parseList("/get_imglist.cgi?DIR=/DCIM/${n}OLYMP", out)
            if (out.isNotEmpty()) return@withContext out.values.sortedByDescending { it.modified }
        }
        null   // 一个目录都没扫到（空卡/路径异常）→ 返回 null，调用方回退全量
    }

    private fun walk(dir: String, out: MutableMap<String, RemotePhoto>, depth: Int) {
        if (depth > 3) return
        val dirs = ArrayList<String>()
        parseList("/get_imglist.cgi?DIR=$dir", out) { dirs += it }
        dirs.forEach { walk(it, out, depth + 1) }
    }

    /**
     * 解析 get_imglist 返回的纯文本列表。
     * 行格式：`目录,文件名,字节数,属性,日期(FAT),时间(FAT)`，首行是 `VER_100`。
     *
     * 目录项（属性位 0x10）只通过 [onDir] 抛给调用方下钻，**不放进 [out]**——
     * 否则目录会被当成照片显示在列表里。
     */
    private fun parseList(
        path: String,
        out: MutableMap<String, RemotePhoto>,
        onDir: ((String) -> Unit)? = null
    ) {
        val r = runCatching { get(path, 6000) }.getOrNull() ?: return
        if (!r.ok || r.body.isBlank()) return
        for (line in r.body.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("VER_")) continue
            val p = t.split(",")
            if (p.size < 6) continue

            val parent = p[0].trim()
            val name = p[1].trim()
            if (name.isEmpty()) continue
            val size = p[2].trim().toLongOrNull() ?: 0L
            val attr = p[3].trim().toIntOrNull() ?: 0
            val date = p[4].trim().toIntOrNull() ?: 0
            val time = p[5].trim().toIntOrNull() ?: 0
            val full = joinPath(parent, name)

            if ((attr and 0x10) != 0) {
                onDir?.invoke(full)
            } else if (isMediaName(name)) {
                out[full] = RemotePhoto(
                    id = full,
                    name = name,
                    size = size,
                    modified = olympusDateTime(date, time)
                )
            }
        }
    }

    private fun olympusDateTime(date: Int, time: Int): Long {
        if (date == 0) return 0L
        val year = ((date shr 9) and 0x7F) + 1980
        val month = ((date shr 5) and 0x0F) - 1
        val day = date and 0x1F
        val hour = (time shr 11) and 0x1F
        val minute = (time shr 5) and 0x3F
        val second = (time and 0x1F) * 2
        return Calendar.getInstance().run {
            // 必须先清零：Calendar.set 不重置毫秒字段，getInstance 当下的随机毫秒
            // 会混进每张照片的时间里，同一秒内的新旧排序就变成掷骰子
            clear()
            set(year, month, day, hour, minute, second)
            timeInMillis
        }
    }

    // ——————————————— 取图 ———————————————

    /**
     * 缓存键：id + 文件大小。
     *
     * 只用 id（= 卡上路径）当键有个致命坑：相机上「清除全部/格式化」后 DCF 编号**从 1 重新计数**，
     * 新拍的照片文件名会和很久之前的旧图一模一样（/DCIM/100OLYMP/PC180001.JPG）。
     * 旧缓存一命中，监视页显示的就是上一任文件的内容——这就是
     * 「出现很久之前的旧图、新拍的图反而不出现、监视的图不是刚拍那张」的根因。
     * 把文件大小拼进键里，同路径不同内容自然拿到不同的缓存文件。
     */
    private fun cacheKey(kind: String, photo: RemotePhoto) = cacheFileName(kind, "${photo.id}#${photo.size}")

    /** 相机自带的缩略图接口，比整张原图快一个数量级 */
    override suspend fun thumbnail(photo: RemotePhoto): Bitmap? =
        withContext(Dispatchers.IO) {
            val path = "/get_thumbnail.cgi?DIR=${photo.id}"
            val f = fetchToCache("thumb", cacheKey("thumb", photo), path, 8000)
                ?: return@withContext null
            decodeThumb(f)?.let { return@withContext it }
            // 缓存文件解不开 = 半张/坏图（历史上中断下载的遗留）。
            // 删掉立刻重拉一次，否则每次命中同一坏文件、这张卡永远黑着。
            runCatching { f.delete() }
            fetchToCache("thumb", cacheKey("thumb", photo), path, 8000)
                ?.let { runCatching { decodeThumb(it) }.getOrNull() }
        }

    override suspend fun preview(photo: RemotePhoto, edge: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val size = edge.coerceIn(320, 2048)
            val path = "/get_resizeimg.cgi?DIR=${photo.id}&size=$size"
            val f = fetchToCache("prev$size", cacheKey("prev$size", photo), path, 15000)
                ?: return@withContext null
            decodeThumb(f, edge)?.let { return@withContext it }
            runCatching { f.delete() }
            fetchToCache("prev$size", cacheKey("prev$size", photo), path, 15000)
                ?.let { runCatching { decodeThumb(it, edge) }.getOrNull() }
        }

    override suspend fun download(
        photo: RemotePhoto,
        dest: File,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 原图直取，不走缓存（否则一张原图会长期占用缓存目录）
        val (stream, len) = httpStream("$base${photo.id}", 20000, OLYMPUS_UA)
        copyWithProgress(stream, dest, len.toLong(), onProgress)
        onProgress(100)
    }

    /**
     * 把相机返回的（缩略/中等尺寸）JPEG 落到缓存文件，命中即复用。
     *
     * 下载必须先写 `.part` 临时文件、完整后才改名——以前直接写最终文件，
     * 中途断连会留下「半张 JPEG」，之后每次 `存在且非空` 都命中这个坏文件，
     * 解码永远失败 → 这张卡在监视页**永久黑**（图库没事，因为原图下载不走缓存）。
     */
    private fun fetchToCache(
        kind: String,
        id: String,
        path: String,
        timeoutMs: Int
    ): File? {
        val f = File(cacheDir, id)
        if (f.exists() && f.length() > 0) return f
        val r = runCatching { httpStream("$base$path", timeoutMs, OLYMPUS_UA) }.getOrNull() ?: return null
        val (stream, len) = r
        return runCatching {
            val tmp = File(cacheDir, f.name + ".part")
            tmp.delete()   // 清掉上次中断可能残留的半个文件
            copyWithProgress(stream, tmp, len.toLong()) { }
            if (tmp.length() <= 0L) {
                tmp.delete()
                return@runCatching null
            }
            f.delete()
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return@runCatching null
            }
            f
        }.getOrNull()
    }

    override fun close() = Unit
}
