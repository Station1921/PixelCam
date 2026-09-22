package com.station1921.pixelcam.transfer

import kotlinx.coroutines.sync.Mutex

/**
 * 当前已连接的照片来源：UI（ViewModel）与「后台常驻接收」服务共用同一条连接。
 *
 * 为什么不放 ViewModel 里：
 * 后台服务必须独立于界面存活，但它不能自己再开一条连接——
 * MTP/USB 同一设备不允许并发打开，FTP/HTTP 也会把相机端的会话挤掉。
 * 所以把「来源 + 访问锁 + 已见照片集合」抽到这里，两边共用：
 * 谁先看到新照片谁处理，不会重复下载、也不会互相打断。
 */
object SourceHolder {

    /** 串行化对 [source] 的访问：MTP/FTP 都是单连接 */
    val lock = Mutex()

    @Volatile
    var source: PhotoSource? = null

    @Volatile
    var label: String = ""

    /** 已经见过的照片 id（连接当下的存量照片全部算已知，之后出现的才算新增） */
    private val known = HashSet<String>()

    @Synchronized
    fun attach(src: PhotoSource, existing: Collection<String>) {
        known.clear()
        known.addAll(existing)
        label = src.label
        source = src
    }

    @Synchronized
    fun detach() {
        source = null
        label = ""
        known.clear()
    }

    /** 登记一张照片；返回 true 表示这张之前没见过（即新增） */
    @Synchronized
    fun markFresh(id: String): Boolean = known.add(id)

    /**
     * 回滚：把某张从「已知」集合里移除。
     * 后台常驻接收中，[markFresh] 标记在先、[download] 在后；
     * 若 download 失败（如 USB 句柄失效），必须把 id 退回，否则后面每一轮差分都把它判为「已知」，
     * 这张照片就永远收不回来。调用方在下载失败分支用。
     */
    @Synchronized
    fun unmark(id: String) {
        known.remove(id)
    }

    @Synchronized
    fun markKnown(id: String) {
        known.add(id)
    }
}
