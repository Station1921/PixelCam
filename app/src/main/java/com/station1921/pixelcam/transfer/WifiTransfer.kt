package com.station1921.pixelcam.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.max

// ——————————————————— 通用工具 ———————————————————

internal fun joinPath(dir: String, name: String): String =
    if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

internal fun computeSample(w: Int, h: Int, target: Int): Int {
    val long = max(w, h)
    // 与 BitmapIo 同款修正：保证长边 ≤ target，而不是 ≤ 2×target
    var sample = 1
    while (long / sample > target) sample *= 2
    return sample
}

internal fun decodeThumb(file: File, target: Int = 360): Bitmap? {
    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, probe)
    val o = BitmapFactory.Options().apply {
        inSampleSize = computeSample(probe.outWidth, probe.outHeight, target)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, o)
}

/** 边下边写，带百分比回调；先写 .part 再改名，避免半截文件被当成完整文件 */
internal fun copyWithProgress(
    input: InputStream,
    dest: File,
    total: Long,
    onProgress: (Int) -> Unit
) {
    dest.parentFile?.mkdirs()
    val tmp = File(dest.parentFile, dest.name + ".part")
    try {
        input.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
    } finally {
        if (tmp.exists()) tmp.delete()
    }
    onProgress(100)
}

internal fun safeFileName(key: String, name: String): String {
    val tag = key.filter { it.isLetterOrDigit() }.take(24)
    val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return if (tag.isEmpty()) clean else "${tag}__$clean"
}

/**
 * 远端缓存的唯一文件名。
 *
 * 不要用 [safeFileName]：它把 key 过滤成字母数字后只取前 24 个字符，
 * 而奥林巴斯的路径（/DCIM/100OLYMP/PA220001.JPG）过滤后正好超过 24 个字符，
 * 末尾被截掉后**只差最后一位的两个文件会撞名**，缓存互相覆盖导致取到错图。
 * 这里改用稳定哈希打头（String.hashCode 由 Java 规范固定，跨版本一致）。
 */
internal fun cacheFileName(kind: String, id: String): String {
    val h = id.hashCode().toUInt().toString(16).padStart(8, '0')
    val tail = id.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
    return "${kind}_$h" + if (tail.isEmpty()) "" else "_$tail"
}

// ——————————————————— FTP（相机 WiFi / 无线传输器） ———————————————————

/**
 * 走 FTP 拉取相机里的照片。
 * 多数相机的无线传输套件、以及不少带 WiFi 的老机型都支持 FTP 推送/拉取。
 */
class FtpSource(
    private val host: String,
    private val port: Int = 21,
    private val user: String = "anonymous",
    private val pass: String = "",
    private val root: String = "/",
    private val cacheDir: File
) : PhotoSource {

    private val client = FTPClient()

    override val id: String get() = "ftp://$host:$port$root"
    override val label: String get() = "FTP $host"

    private fun ensureConnected(): FTPClient {
        if (client.isConnected) return client
        client.controlEncoding = "UTF-8"
        client.connectTimeout = 8000
        client.connect(host, port)
        if (!client.login(user, pass)) error("FTP 登录失败：$host")
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        if (root.isNotBlank() && root != "/") client.changeWorkingDirectory(root)
        return client
    }

    override suspend fun list(): List<RemotePhoto> = withContext(Dispatchers.IO) {
        val c = ensureConnected()
        val out = ArrayList<RemotePhoto>()
        collect(c, root.ifBlank { "/" }, out, 0)
        out.sortedByDescending { it.modified }
    }

    private fun collect(c: FTPClient, dir: String, out: MutableList<RemotePhoto>, depth: Int) {
        if (depth > 4) return
        val files: Array<FTPFile> = runCatching { c.listFiles(dir) }.getOrNull() ?: return
        for (f in files) {
            val name = f.name ?: continue
            if (name == "." || name == "..") continue
            val path = joinPath(dir, name)
            when {
                f.isDirectory -> collect(c, path, out, depth + 1)
                f.isFile && isMediaName(name) -> out += RemotePhoto(
                    id = path,
                    name = name,
                    size = f.size,
                    modified = runCatching { f.timestamp.timeInMillis }.getOrDefault(0L)
                )
            }
        }
    }

    private fun local(photo: RemotePhoto): File =
        File(cacheDir, safeFileName(photo.id, photo.name))

    /** FTP 没有缩略图协议，所以整文件只下一次，之后从本地缓存解码 */
    private suspend fun cache(photo: RemotePhoto, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val f = local(photo)
            if (f.exists() && f.length() == photo.size && photo.size > 0) return@withContext f
            val c = ensureConnected()
            val stream = c.retrieveFileStream(photo.id)
                ?: error("无法读取：${photo.name}")
            try {
                copyWithProgress(stream, f, photo.size, onProgress)
            } finally {
                runCatching { c.completePendingCommand() }
            }
            f
        }

    override suspend fun thumbnail(photo: RemotePhoto): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching { decodeThumb(cache(photo)) }.getOrNull()
        }

    override suspend fun preview(photo: RemotePhoto, edge: Int): Bitmap? =
        withContext(Dispatchers.IO) { runCatching { decodeThumb(cache(photo), edge) }.getOrNull() }

    override suspend fun download(
        photo: RemotePhoto,
        dest: File,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val cached = runCatching { cache(photo, onProgress) }.getOrNull()
        if (cached != null) {
            dest.parentFile?.mkdirs()
            cached.copyTo(dest, overwrite = true)
        } else {
            val c = ensureConnected()
            val stream = c.retrieveFileStream(photo.id) ?: error("无法读取：${photo.name}")
            try {
                copyWithProgress(stream, dest, photo.size, onProgress)
            } finally {
                runCatching { c.completePendingCommand() }
            }
        }
        onProgress(100)
    }

    override fun close() {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { client.disconnect() }
    }
}

// ——————————————————— FlashAir / ez Share 等无线 SD 卡 ———————————————————

/**
 * 东芝 FlashAir 无线 SD 卡。
 * 文件列表走 command.cgi?op=100，下载直接取原始路径。
 * 日期是 FAT 打包格式，需要自己解。
 */
class FlashAirSource(
    private val host: String,
    private val cacheDir: File
) : PhotoSource {

    private val base = "http://$host"

    override val id: String get() = "flashair://$host"
    override val label: String get() = "FlashAir $host"

    override suspend fun list(): List<RemotePhoto> = withContext(Dispatchers.IO) {
        val out = ArrayList<RemotePhoto>()
        walk("/DCIM", out, 0)
        out.sortedByDescending { it.modified }
    }

    private fun walk(dir: String, out: MutableList<RemotePhoto>, depth: Int) {
        if (depth > 3) return
        val body = runCatching { httpGet("$base/command.cgi?op=100&DIR=$dir") }.getOrNull()
            ?: return
        for (line in body.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("WLANSD_FILELIST")) continue
            val p = t.split(",")
            if (p.size < 3) continue

            val parent = p[0]
            val name = p[1]
            val size = p[2].toLongOrNull() ?: 0L
            val attr = p.getOrNull(3)?.toIntOrNull() ?: 0
            val date = p.getOrNull(4)?.toIntOrNull() ?: 0
            val time = p.getOrNull(5)?.toIntOrNull() ?: 0

            if ((attr and 0x10) != 0) {
                walk(joinPath(parent, name), out, depth + 1)
            } else if (isMediaName(name)) {
                out += RemotePhoto(
                    id = joinPath(parent, name),
                    name = name,
                    size = size,
                    modified = fatDateTime(date, time)
                )
            }
        }
    }

    private fun fatDateTime(date: Int, time: Int): Long {
        if (date == 0) return 0L
        val year = ((date shr 9) and 0x7F) + 1980
        val month = ((date shr 5) and 0x0F) - 1
        val day = date and 0x1F
        val hour = (time shr 11) and 0x1F
        val minute = (time shr 5) and 0x3F
        val second = (time and 0x1F) * 2
        return Calendar.getInstance().run {
            set(year, month, day, hour, minute, second)
            timeInMillis
        }
    }

    override suspend fun thumbnail(photo: RemotePhoto): Bitmap? =
        withContext(Dispatchers.IO) { runCatching { decodeThumb(cache(photo)) }.getOrNull() }

    override suspend fun preview(photo: RemotePhoto, edge: Int): Bitmap? =
        withContext(Dispatchers.IO) { runCatching { decodeThumb(cache(photo), edge) }.getOrNull() }

    override suspend fun download(photo: RemotePhoto, dest: File, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val cached = runCatching { cache(photo, onProgress) }.getOrNull()
            if (cached != null) {
                dest.parentFile?.mkdirs()
                cached.copyTo(dest, overwrite = true)
            } else {
                val (stream, len) = httpStream("$base${photo.id}")
                copyWithProgress(stream, dest, len.toLong(), onProgress)
            }
            onProgress(100)
        }

    private fun cache(photo: RemotePhoto, onProgress: (Int) -> Unit = {}): File {
        val f = File(cacheDir, safeFileName(photo.id, photo.name))
        if (f.exists() && f.length() > 0) return f
        val (stream, len) = httpStream("$base${photo.id}")
        copyWithProgress(stream, f, len.toLong(), onProgress)
        return f
    }

    override fun close() = Unit
}

/**
 * ez Share 等以标准 HTTP 目录列表形式暴露文件的无线 SD 卡。
 * 直接解析 HTML 里的 <a href="..."> 递归下钻。
 */
class HttpListingSource(
    private val host: String,
    private val cacheDir: File
) : PhotoSource {

    private val base = "http://$host"
    private val link = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    override val id: String get() = "http://$host"
    override val label: String get() = "$host"

    override suspend fun list(): List<RemotePhoto> = withContext(Dispatchers.IO) {
        val out = ArrayList<RemotePhoto>()
        walk("/", out, 0)
        out.sortedByDescending { it.modified }
    }

    private fun walk(dir: String, out: MutableList<RemotePhoto>, depth: Int) {
        if (depth > 3) return
        val body = runCatching { httpGet("$base$dir") }.getOrNull() ?: return
        for (m in link.findAll(body)) {
            val href = m.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("?") || href.contains("://")) continue
            if (href.startsWith("/") || href.startsWith("../")) continue

            val name = href.substringAfterLast('/')
            if (href.endsWith("/")) {
                walk(if (dir.endsWith("/")) "$dir$href" else "$dir/$href", out, depth + 1)
            } else if (isMediaName(name)) {
                val path = if (dir.endsWith("/")) "$dir$href" else "$dir/$href"
                out += RemotePhoto(id = path, name = name, size = 0L)
            }
        }
    }

    override suspend fun thumbnail(photo: RemotePhoto): Bitmap? =
        withContext(Dispatchers.IO) { runCatching { decodeThumb(cache(photo)) }.getOrNull() }

    override suspend fun preview(photo: RemotePhoto, edge: Int): Bitmap? =
        withContext(Dispatchers.IO) { runCatching { decodeThumb(cache(photo), edge) }.getOrNull() }

    override suspend fun download(photo: RemotePhoto, dest: File, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val cached = runCatching { cache(photo, onProgress) }.getOrNull()
            if (cached != null) {
                dest.parentFile?.mkdirs()
                cached.copyTo(dest, overwrite = true)
            } else {
                val (stream, len) = httpStream("$base${photo.id}")
                copyWithProgress(stream, dest, len.toLong(), onProgress)
            }
            onProgress(100)
        }

    private fun cache(photo: RemotePhoto, onProgress: (Int) -> Unit = {}): File {
        val f = File(cacheDir, safeFileName(photo.id, photo.name))
        if (f.exists() && f.length() > 0) return f
        val (stream, len) = httpStream("$base${photo.id}")
        copyWithProgress(stream, f, len.toLong(), onProgress)
        return f
    }

    override fun close() = Unit
}

// ——————————————————— HTTP 基础能力 ———————————————————

/**
 * 打开 HTTP 连接时优先使用已绑定的相机 WiFi [Network]。
 * 避免手机同时连相机热点（无互联网）和蜂窝时，socket 默认走蜂窝导致 192.168.x.x 不可达。
 */
internal fun openHttpConnection(url: String): HttpURLConnection {
    val network = NetworkHolder.network
    val urlObj = URL(url)
    val conn = if (network != null) {
        network.openConnection(urlObj) as HttpURLConnection
    } else {
        urlObj.openConnection() as HttpURLConnection
    }
    return conn
}

internal fun httpGet(url: String, timeoutMs: Int = 8000, userAgent: String? = null): String {
    val conn = openHttpConnection(url)
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
    return try {
        conn.inputStream.bufferedReader().readText()
    } finally {
        conn.disconnect()
    }
}

internal fun httpStream(
    url: String,
    timeoutMs: Int = 15000,
    userAgent: String? = null
): Pair<InputStream, Int> {
    val conn = openHttpConnection(url)
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs * 2
    if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
    conn.connect()
    val code = conn.responseCode
    if (code !in 200..299) {
        conn.disconnect()
        error("HTTP $code：$url")
    }
    return conn.inputStream to conn.contentLength
}

// ——————————————————— 探测用 HTTP（带状态码） ———————————————————

/**
 * 奥林巴斯 / OM System 的 OI.Share（OPC）协议要求带这个 User-Agent。
 * 官方 OI.Share 与各家开源实现都固定发它，缺了它部分机型会直接拒答。
 */
internal const val OLYMPUS_UA = "OI.Share v2"

/**
 * 探测结果。与 [httpGet] 的区别：
 * - 非 2xx 不抛异常，而是把状态码带回来。奥林巴斯出错时会用**非标准状态码**（如 520）
 *   返回 XML 错误体，不保留状态码就没法判断到底是「地址不通」「协议不对」还是「模式不对」。
 * - 只读前 [maxBytes] 字节，避免把一张原图读进内存。
 */
internal data class HttpResult(val code: Int, val body: String, val error: String? = null) {
    val ok: Boolean get() = code in 200..299
}

internal fun httpGetResult(
    url: String,
    timeoutMs: Int = 4000,
    userAgent: String? = null,
    maxBytes: Int = 8192
): HttpResult {
    val conn = runCatching { openHttpConnection(url) }.getOrElse {
        return HttpResult(0, "", it.message ?: it.javaClass.simpleName)
    }
    return try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
        conn.connect()
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        HttpResult(code, stream?.let { readCapped(it, maxBytes) } ?: "")
    } catch (e: Exception) {
        HttpResult(0, "", e.message ?: e.javaClass.simpleName)
    } finally {
        runCatching { conn.disconnect() }
    }
}

private fun readCapped(input: InputStream, cap: Int): String {
    val buf = ByteArray(cap)
    var n = 0
    input.use { inp ->
        while (n < cap) {
            val r = inp.read(buf, n, cap - n)
            if (r <= 0) break
            n += r
        }
    }
    return String(buf, 0, n, Charsets.UTF_8)
}

/**
 * 带 XML 正文的 POST。
 *
 * 奥林巴斯 OPC 的**写**接口（`set_camprop.cgi?com=set&propname=X`）只认 POST，
 * 正文形如 `<set><value>200</value></set>`——用 GET 会被相机当成非法请求。
 * 仍然走 [openHttpConnection]，保证请求从相机 WiFi（而不是蜂窝）出去。
 */
internal fun httpPostXml(
    url: String,
    xml: String,
    timeoutMs: Int = 4000,
    userAgent: String? = null,
    maxBytes: Int = 4096
): HttpResult {
    val conn = runCatching { openHttpConnection(url) }.getOrElse {
        return HttpResult(0, "", it.message ?: it.javaClass.simpleName)
    }
    return try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Content-Type", "text/xml")
        val bytes = xml.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        HttpResult(code, stream?.let { readCapped(it, maxBytes) } ?: "")
    } catch (e: Exception) {
        HttpResult(0, "", e.message ?: e.javaClass.simpleName)
    } finally {
        runCatching { conn.disconnect() }
    }
}

/**
 * 该地址上有没有 HTTP 服务。
 *
 * 相机热点的 HTTP 服务响应在毫秒级，1.2 秒足够区分「这台主机的地址不通」。
 * 之所以要这道预检：候选主机有好几个，若不预检，一台不通的主机就要把后面所有
 * CGI 端点全部超时一遍（十几秒），逐个探测会慢到不可接受。
 */
internal fun hostReachable(host: String): Boolean {
    if (host.isBlank()) return false
    return httpGetResult("http://$host/", 1200).code != 0
}

/**
 * 奥林巴斯 / OM System（OI.Share 的 OPC 协议）。
 * 特征端点：get_commandlist.cgi（命令列表 XML）、get_caminfo.cgi（机型名）。
 * 机型处于拍摄模式时可能只答部分命令，这时先切到播放模式再试一次。
 */
private fun olympusProbe(host: String, cacheDir: File): OlympusSource? {
    if (olympusAlive(host)) return OlympusSource(host, cacheDir)
    runCatching { httpGetResult("http://$host/switch_cammode.cgi?mode=play", 2000, OLYMPUS_UA) }
    return if (olympusAlive(host)) OlympusSource(host, cacheDir) else null
}

private fun olympusAlive(host: String): Boolean {
    val cmd = runCatching { httpGetResult("http://$host/get_commandlist.cgi", 2500, OLYMPUS_UA) }
        .getOrNull()
    // 正常返回形如 <?xml …?><commandlist><cmd …/></commandlist>。
    // 不写死根标签名：不同机型/固件用过 <commandlist> 与 <cmdlist> 两种写法。
    if (cmd != null && cmd.ok && cmd.body.contains('<') && cmd.body.contains("cmd", ignoreCase = true)) {
        return true
    }
    val info = runCatching { httpGetResult("http://$host/get_caminfo.cgi", 2000, OLYMPUS_UA) }
        .getOrNull()
    return info != null && info.ok && info.body.isNotBlank()
}

/** 探测一个地址是不是可用的照片源，返回最合适的实现 */
suspend fun probeWifiHost(host: String, cacheDir: File): PhotoSource? = withContext(Dispatchers.IO) {
    if (!hostReachable(host)) return@withContext null

    // 奥林巴斯 / OM System：OI.Share 协议
    olympusProbe(host, cacheDir)?.let { return@withContext it }

    // FlashAir 有专属的 command.cgi，先认它
    val flashAir = runCatching {
        val r = httpGetResult("http://$host/command.cgi?op=100&DIR=/DCIM", 4000)
        r.ok && r.body.startsWith("WLANSD_FILELIST")
    }.getOrDefault(false)
    if (flashAir) return@withContext FlashAirSource(host, cacheDir)

    // 其余按普通 HTTP 目录列表处理（ez Share 等）
    val listing = runCatching {
        val r = httpGetResult("http://$host/", 4000)
        r.ok && r.body.contains("<a ", ignoreCase = true)
    }.getOrDefault(false)
    if (listing) return@withContext HttpListingSource(host, cacheDir)

    null
}

/** 依次探测多个候选主机，返回第一个可用的照片源 */
suspend fun probeAnyHost(hosts: List<String>, cacheDir: File): PhotoSource? {
    for (h in hosts) {
        if (h.isBlank()) continue
        val found = runCatching { probeWifiHost(h, cacheDir) }.getOrNull()
        if (found != null) return found
    }
    return null
}
