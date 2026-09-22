package com.station1921.pixelcam.data

import android.content.Context
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * 后台常驻接收的用户配置。
 *
 * 默认**关闭**：常驻前台服务会持续轮询相机、保持 CPU 唤醒，属于明显的耗电行为，
 * 因此必须像「AI 自动美化」一样由用户手动打开才生效。
 */
data class AutoReceiveConfig(val enabled: Boolean = false)

object AutoReceivePrefs {
    private const val FILE = "auto_receive"
    private const val KEY_ENABLED = "enabled"

    fun load(context: Context): AutoReceiveConfig = AutoReceiveConfig(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    )

    fun save(context: Context, config: AutoReceiveConfig): AutoReceiveConfig {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .apply()
        return config
    }
}

/**
 * 是否已加入电池优化白名单。
 * 国内 ROM（小米/华为/OPPO 等）默认会在锁屏或后台一段时间后杀掉进程，
 * 不加入白名单「后台常驻接收」基本保不住。
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = ContextCompat.getSystemService(context, PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
