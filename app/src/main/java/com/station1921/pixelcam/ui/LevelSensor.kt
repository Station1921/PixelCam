package com.station1921.pixelcam.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 水平仪状态：原始重力加速度分量（设备坐标系，单位 m/s²）。
 * 不直接算 roll/pitch，因为横屏时「水平」的参照轴会变——
 * 真正的屏幕水平角由 LevelBar 结合当前显示旋转现场算，参看其内 rollScreen()。
 */
data class LevelState(val gx: Float, val gy: Float, val gz: Float)

/**
 * 用加速度计读数（指向设备「上方」的反重力方向）做水平仪。
 * 仅做指数平滑（低通）抑制抖动；不做方向假设，方向交给 LevelBar。
 * 不需要任何权限；SensorManager 在合成上注册，离开页面自动注销。
 */
@Composable
fun rememberLevelState(): LevelState {
    val context = LocalContext.current
    val state = remember { mutableStateOf(LevelState(0f, 0f, 0f)) }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // 指数平滑（低通）状态：加速度计原始值抖动很大，直接显示会一直晃。
        // alpha 越小越稳；这里 0.12 既能跟手又基本不抖。
        var sX = 0.0
        var sY = 0.0
        var sZ = 0.0
        val alpha = 0.12
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.values.size < 3) return
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                // 平滑
                sX = sX * (1 - alpha) + x * alpha
                sY = sY * (1 - alpha) + y * alpha
                sZ = sZ * (1 - alpha) + z * alpha
                // 抖动抑制：变化太小不更新状态，避免亚像素级颤动触发重组
                val cur = state.value
                val nx = sX.toFloat()
                val ny = sY.toFloat()
                val nz = sZ.toFloat()
                if (Math.abs(nx - cur.gx) >= 0.05f ||
                    Math.abs(ny - cur.gy) >= 0.05f ||
                    Math.abs(nz - cur.gz) >= 0.05f
                ) {
                    state.value = LevelState(nx, ny, nz)
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        accel?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm?.unregisterListener(listener) }
    }
    return state.value
}
