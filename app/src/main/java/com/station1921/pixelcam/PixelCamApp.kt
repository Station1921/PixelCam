package com.station1921.pixelcam

import android.app.Application
import com.station1921.pixelcam.beauty.OpenCv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PixelCamApp : Application() {

    companion object {
        /** 供无 Context 传参的离线组件（如人脸检测兜底）读取 assets / cacheDir */
        @Volatile
        lateinit var instance: PixelCamApp
            private set

        /** Application 是否已走过 onCreate（人脸检测兜底在启动早期可能被调用前先查一下） */
        fun hasInstance(): Boolean = ::instance.isInitialized
    }

    /** 全应用共享的后台作用域，用于图传、导出等长任务 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // OpenCV 的 .so 有几十 MB，放后台加载，别拖慢冷启动
        appScope.launch { OpenCv.ensureLoaded() }
    }
}
