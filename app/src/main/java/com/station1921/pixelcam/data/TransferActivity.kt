package com.station1921.pixelcam.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局「正在传输 / 正在美化」任务登记处。
 *
 * 拍照后一张照片要先下载（传输）再自动美化，两步各要几秒。
 * 图库 / 监视页据此立刻显示占位卡 + 美化队列，让用户一拍完就看到
 * 「已经有照片在传 / 在美化」，而不是干等图库里凭空冒出一张；
 * 任务完成（或失败）就从这里移除，对应占位卡消失、真实照片出现。
 *
 * 这是一个进程级单例：拍照页（ViewModel）、后台接收服务、图库页、监视页共用同一份状态。
 */
object TransferActivity {

    /** 任务所处阶段 */
    enum class Phase { TRANSFER, BEAUTY }

    /** 一条在途任务：文件名 + 阶段 + 进度（0..100；-1 表示不确定，如美化中） */
    data class Task(
        val name: String,
        val phase: Phase,
        val progress: Int = 0
    )

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    @Synchronized
    private fun upsert(name: String, phase: Phase, progress: Int) {
        val list = _tasks.value.toMutableList()
        val i = list.indexOfFirst { it.name == name && it.phase == phase }
        if (i >= 0) list[i] = list[i].copy(progress = progress)
        else list += Task(name, phase, progress)
        _tasks.value = list
    }

    @Synchronized
    private fun remove(name: String, phase: Phase) {
        val next = _tasks.value.filter { !(it.name == name && it.phase == phase) }
        if (next.size != _tasks.value.size) _tasks.value = next
    }

    /** 开始下载某张（传输阶段，进度 0） */
    fun markTransferring(name: String) = upsert(name, Phase.TRANSFER, 0)

    /** 更新下载进度（0..100） */
    fun updateTransfer(name: String, progress: Int) =
        upsert(name, Phase.TRANSFER, progress.coerceIn(0, 100))

    /** 下载完成 */
    fun markTransferDone(name: String) = remove(name, Phase.TRANSFER)

    /** 进入自动美化队列（进度不确定，用转圈表示） */
    fun markBeauty(name: String) = upsert(name, Phase.BEAUTY, -1)

    /** 美化完成（或失败） */
    fun markBeautyDone(name: String) = remove(name, Phase.BEAUTY)

    /** 清空（断开 / 退出时调用，避免残留占位） */
    @Synchronized
    fun clear() {
        if (_tasks.value.isNotEmpty()) _tasks.value = emptyList()
    }
}
