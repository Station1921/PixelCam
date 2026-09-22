package com.station1921.pixelcam.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 奥林巴斯 / OM System 的实时取景（LiveView）。
 *
 * 协议（OPC，与官方 OI.Share 一致，据 OPC Communication Protocol 1.0a 与各家开源实现核对）：
 *
 * 1. `switch_cammode.cgi?mode=rec&lvqty=<宽x高>` —— 取景只在**拍摄模式**下提供；
 *    `lvqty` 把取景画面的尺寸告诉相机（可选值来自 `get_commandlist.cgi` 里
 *    `switch_cammode → rec → lvqty` 的枚举）。
 * 2. `exec_takemisc.cgi?com=startliveview&port=<手机 UDP 端口>` —— 之后相机把画面
 *    **以 RTP 包主动推到手机的 UDP 端口**。注意这是单向推流、**不是 HTTP 拉流**，
 *    必须自己开 DatagramSocket 收（最容易踩的坑）。
 * 3. 收包 → 解析 RTP 头 → 收齐一帧（Marker 位置位）→ 校验 JPEG 头尾 FFD8/FFD9 → 解码成 Bitmap。
 * 4. 结束：`exec_takemisc.cgi?com=stopliveview`，再切回 `mode=play`——
 *    列图接口只在播放模式下被接受，监视轮询（以及快门后回放照片）都靠它。
 *
 * 时序上必须先开好 UDP 端口再发 startliveview，否则最开始的几帧会丢失。
 */
class OlympusLiveView(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val lvqty: String = DEFAULT_LVQTY
) {

    companion object {
        /** 与 OI.Share / 各家实现一致的默认推流端口 */
        const val DEFAULT_PORT = 40000
        /** 取景画面尺寸默认值（E-M 系列普遍支持） */
        const val DEFAULT_LVQTY = "0640x0480"

        private const val SO_TIMEOUT_MS = 1500
        /** 从「相机答应推流」到「收到第一帧」的容忍时间；超了基本可以断定推流没到手机 */
        private const val FIRST_FRAME_TIMEOUT_MS = 12_000
        private const val JPEG_SOI0 = 0xFF
        private const val JPEG_SOI1 = 0xD8
        private const val JPEG_EOI0 = 0xFF
        private const val JPEG_EOI1 = 0xD9

        /**
         * 命令列表按相机 IP 缓存：取景每次重连都要走一遍握手（命令列表 → 切拍摄模式 → 推流），
         * 而命令列表在单次相机连接里是固定的，缓存掉能省一次 HTTP 往返，重进拍照页取景更快（#127）。
         * 断开相机时由 ViewModel 调 [clearCommandListCache] 清掉，避免换机/重连后用到过期列表。
         */
        private val commandListCache = mutableMapOf<String, HttpResult>()

        @Synchronized
        fun clearCommandListCache(host: String? = null) {
            if (host == null) commandListCache.clear() else commandListCache.remove(host)
        }
    }

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var stopped = false

    /** 相机是否接受过 startliveview（决定收尾时要不要发 stopliveview） */
    @Volatile
    private var streaming = false

    /**
     * 建立取景流：查能力 → 开 UDP 端口 → 切拍摄模式 → 请相机推流。
     * 失败抛 [IllegalStateException]，message 可以直接显示给用户。
     */
    fun start() {
        stopped = false

        val cl = commandListCache[host] ?: run {
            val fetched =
                runCatching { httpGetResult("http://$host/get_commandlist.cgi", 4000, OLYMPUS_UA) }
                    .getOrNull()
            fetched?.also { commandListCache[host] = it }
            fetched
        } ?: throw IllegalStateException("联系不上相机，请确认还连着相机 WiFi")
        if (cl.code == 0) throw IllegalStateException("联系不上相机：${cl.error ?: "网络不可达"}")
        if (!cl.ok) throw IllegalStateException("相机没有响应（HTTP ${cl.code}）")
        if (!cl.body.contains("startliveview", ignoreCase = true)) {
            throw IllegalStateException("这台机型不支持实时取景（命令列表里没有 startliveview）")
        }
        val qty = pickLvqty(cl.body)

        // ① 先把 UDP 端口开好、并绑定到相机 WiFi，再让相机推流。
        //    顺序不能反：先发 startliveview 再开端口，最开始的几帧就丢了。
        val s = DatagramSocket(null)
        // 手机同时连着有外网的蜂窝/家里 WiFi 时，收流 socket 也要钉在相机网络上
        NetworkHolder.network?.let { runCatching { it.bindSocket(s) } }
        try {
            s.reuseAddress = true
            s.soTimeout = SO_TIMEOUT_MS
            s.bind(InetSocketAddress(port))
        } catch (e: Exception) {
            runCatching { s.close() }
            throw IllegalStateException(
                "本机 UDP 端口 $port 打不开：${e.message ?: e.javaClass.simpleName}"
            )
        }
        socket = s

        // ② 切拍摄模式（带上取景尺寸；个别固件不认 lvqty，去掉再试一次）
        val rec = runCatching {
            httpGetResult("http://$host/switch_cammode.cgi?mode=rec&lvqty=$qty", 6000, OLYMPUS_UA)
        }.getOrNull()
        if (rec == null || !rec.ok) {
            val retry = runCatching {
                httpGetResult("http://$host/switch_cammode.cgi?mode=rec", 6000, OLYMPUS_UA)
            }.getOrNull()
            if (retry == null || !retry.ok) {
                throw IllegalStateException(
                    "相机切不到拍摄模式（HTTP ${rec?.code ?: 0}），取景画面拿不到"
                )
            }
        }

        // ③ 请相机开始往本机 UDP 端口推流
        val lv = runCatching {
            httpGetResult("http://$host/exec_takemisc.cgi?com=startliveview&port=$port", 5000, OLYMPUS_UA)
        }.getOrNull() ?: throw IllegalStateException("相机没有接受取景指令（请求超时）")
        if (!lv.ok) throw IllegalStateException("相机拒绝了取景指令（HTTP ${lv.code}）")
        streaming = true
    }

    /**
     * 收流循环。每收到一帧完整 JPEG 就解码一次并交给 [onFrame]。
     * 只有「相机答应推流但一帧都没到」才抛异常（这种情况多半是机型不支持或推流被拦）。
     */
    suspend fun receive(onFrame: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IllegalStateException("取景流未建立")
        val buf = ByteArray(65_535)
        val frame = ByteArrayOutputStream(256 * 1024)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

        var prevSeq = -1
        var assembling = false
        var lastFrameAt = System.currentTimeMillis()
        // 诊断计数：出错时把「一个包都没收到」和「收到包但拼不出画面」分开报，
        // 前者是推流没到/被掐断，后者是包到了但帧格式和预期不符
        var pktCount = 0
        var goodFrames = 0

        while (currentCoroutineContext().isActive) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                s.receive(pkt)
            } catch (e: SocketTimeoutException) {
                if (System.currentTimeMillis() - lastFrameAt > FIRST_FRAME_TIMEOUT_MS) {
                    throw IllegalStateException(
                        when {
                            pktCount == 0 ->
                                "相机的取景流没有到达手机（一个 UDP 包都没收到）。" +
                                    "可能是取景刚建立就被后台接收打断（本版已修复），请退出拍照页重进再试一次；" +
                                    "若反复出现，这台机型的推流可能被网络拦下了。"
                            goodFrames == 0 ->
                                "收到相机 $pktCount 个取景数据包，但拼不出完整画面（帧格式与预期不符）。"
                            else ->
                                "取景流中断（此前已收到 $goodFrames 帧，之后 ${FIRST_FRAME_TIMEOUT_MS / 1000} 秒无数据）。" +
                                    "相机可能被其它操作切回了播放模式，请重试。"
                        }
                    )
                }
                continue
            } catch (e: Exception) {
                // socket 被 stop()/interrupt() 关掉 → 正常收工
                if (stopped || s.isClosed) return@withContext
                throw e
            }

            pktCount++
            val len = pkt.length
            if (len < 12) continue

            // —— RTP 头 ——
            val b0 = buf[0].toInt() and 0xFF
            val hasPadding = (b0 and 0x20) != 0
            val hasExt = (b0 and 0x10) != 0
            val csrcCount = b0 and 0x0F
            val marker = (buf[1].toInt() and 0x80) != 0
            val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            var off = 12 + csrcCount * 4
            if (hasExt && len >= off + 4) {
                val extWords = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
                off += 4 + extWords * 4
            }
            var end = len
            if (hasPadding) end -= (buf[len - 1].toInt() and 0xFF)
            if (off < 0 || off >= end || end > len) continue

            if (!assembling) {
                frame.reset()
                assembling = true
            }
            // 序列号断了说明中间丢包，这一帧拼出来必然是花的，丢掉从头来
            if (prevSeq >= 0 && ((prevSeq + 1) and 0xFFFF) != seq) frame.reset()
            frame.write(buf, off, end - off)
            prevSeq = seq

            if (!marker) continue

            // Marker 位置位 = 一帧结束。不按 RTP 头偏移切 payload，而是按 JPEG
            // 头尾**内容**找帧：不同固件在扩展头上的偏移有出入（标准从 12 字节起、
            // 部分机型多 2 字节），严格按偏移裁会把帧头裁坏——包明明到了，
            // 却拼出一张永远非法的 JPEG，表象和「收不到流」一模一样
            val bytes = frame.toByteArray()
            frame.reset()
            assembling = false
            prevSeq = -1

            val jpeg = locateJpeg(bytes)
            if (jpeg != null) {
                val bmp = runCatching {
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                }.getOrNull()
                if (bmp != null) {
                    goodFrames++
                    lastFrameAt = System.currentTimeMillis()
                    onFrame(bmp)
                }
            }
        }
    }

    /** 在拼好的帧数据里按 SOI/EOI 魔数圈出合法 JPEG；找不到返回 null */
    private fun locateJpeg(bytes: ByteArray): ByteArray? {
        if (bytes.size < 8) return null
        var soi = -1
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == JPEG_SOI0.toByte() && bytes[i + 1] == JPEG_SOI1.toByte()) {
                soi = i
                break
            }
        }
        if (soi < 0) return null
        var eoi = -1
        for (j in bytes.size - 2 downTo soi + 3) {
            if (bytes[j] == JPEG_EOI0.toByte() && bytes[j + 1] == JPEG_EOI1.toByte()) {
                eoi = j
                break
            }
        }
        if (eoi < 0) return null
        return if (soi == 0 && eoi == bytes.size - 2) bytes
        else bytes.copyOfRange(soi, eoi + 2)
    }

    /** 立刻让阻塞在 recv 上的 [receive] 退出（收尾的 HTTP 请求由 [stop] 负责） */
    fun interrupt() {
        stopped = true
        runCatching { socket?.close() }
    }

    /** 停流并切回播放模式（幂等，可在协程取消的 finally 里放心调） */
    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        socket = null
        if (streaming) {
            streaming = false
            runCatching { httpGetResult("http://$host/exec_takemisc.cgi?com=stopliveview", 3000, OLYMPUS_UA) }
            // 列图接口只在播放模式下工作，必须切回去，否则监视轮询会一直拿不到照片
            runCatching { httpGetResult("http://$host/switch_cammode.cgi?mode=play", 3000, OLYMPUS_UA) }
        }
    }

    /** 从命令列表里挑一个可用的取景尺寸；挑不到就用默认值 */
    private fun pickLvqty(commandList: String): String {
        val available = Regex("<lvqty>\\s*([0-9]{3,4}x[0-9]{3,4})\\s*</lvqty>", RegexOption.IGNORE_CASE)
            .findAll(commandList).map { it.groupValues[1] }.toList()
        if (available.isEmpty()) return lvqty
        val preferred = listOf("0640x0480", "0320x0240", "1280x0960", "1024x0768", "0640x0424")
        return preferred.firstOrNull { available.contains(it) } ?: available.first()
    }
}
