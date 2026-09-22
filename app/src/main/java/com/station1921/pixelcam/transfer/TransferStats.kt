package com.station1921.pixelcam.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全 App 共享的照片计数（首页相机卡片用）。
 *
 * 为什么单独放一个对象，而不是继续读 ViewModel 的字段：
 * 下载照片的是**两条互不相干**的路径——界面里的「监视 / 导入」，和后台常驻接收服务。
 * 之前卡片上的「已传输照片」取的是连接那一刻的相机相册总数（一个静态快照），
 * 「本次新增」取的是 ViewModel 里 `ui.live.size`，而后台服务打开后新照片都被服务收走了，
 * 界面的差分轮询什么也发现不了 —— 于是卡片上的数字拍完照也不动，看着像坏了。
 *
 * 现在两条路径都往这里累加，界面只认这一处，谁收的都算数。
 */
object TransferStats {

    data class Stats(
        /** 最近一轮扫描看到的**相机相册**照片总数（连接后每轮轮询刷新） */
        val cameraTotal: Int = 0,
        /** 本次连接以来已经下载到手机的张数（界面导入 + 后台接收） */
        val received: Int = 0,
        /** 最近收到的一张文件名 */
        val lastName: String = ""
    )

    private val _state = MutableStateFlow(Stats())
    val state: StateFlow<Stats> = _state.asStateFlow()

    private val lock = Any()

    /** 新一轮扫描看到的相机相册总数 */
    fun setCameraTotal(n: Int) {
        synchronized(lock) {
            if (n != _state.value.cameraTotal) {
                _state.value = _state.value.copy(cameraTotal = n)
            }
        }
    }

    /** 成功下载一张（界面导入、后台常驻接收都调这里） */
    fun addReceived(name: String = "") {
        synchronized(lock) {
            val s = _state.value
            _state.value = s.copy(
                received = s.received + 1,
                lastName = name.ifBlank { s.lastName }
            )
        }
    }

    /** 新的一次连接开始：把「本次收到」清零（相机相册总数留给下一轮扫描覆盖） */
    fun resetSession() {
        synchronized(lock) {
            _state.value = _state.value.copy(received = 0, lastName = "")
        }
    }

    /** 断开连接：全部清零 */
    fun clear() {
        synchronized(lock) { _state.value = Stats() }
    }
}
