package com.station1921.pixelcam.beauty

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 离线「AI 智能优化」：不联网、不跑大模型，
 * 仅通过画面亮度统计 + 人脸占比这两项信号，推算一组自然的参数档。
 *
 * 产出不包含构图（调用方负责保留当前 geo）与滤镜（滤镜与参数互相覆盖，交给用户后续再选）。
 */
object AiEnhance {

    /**
     * 根据 [src]（构图前原图）与当前画布的人脸结果估算参数。
     * 有人脸 → 走「人像美化」分支（亮度/对比优化 + 按人脸占比给修脸力度）。
     * 无人脸（风光照 / 纯风景）→ 走「风光增强」分支，按画面统计自适应
     *   夜景提亮 / 去雾 / 清晰增强，让 AI 修图也能适配风景题材。
     */
    fun estimate(src: Bitmap, faces: List<FaceInfo>): BeautyParams {
        val mean = meanLuminance(src)

        if (faces.isNotEmpty()) {
            val brightness = when {
                mean < 62f -> 32f
                mean < 92f -> 20f
                mean < 112f -> 11f
                mean > 198f -> -16f
                mean > 168f -> -7f
                else -> 0f
            }
            val contrast = when {
                mean < 96f -> 12f
                mean > 175f -> -5f
                else -> 5f
            }
            // 人脸占画幅比例 → 决定修脸力度（半身/大头 1.0，多人小脸收敛）
            val ratio = run {
                val maxLen = faces.maxOf { max(it.face.width, it.face.height) }
                val ref = max(src.width, src.height)
                (maxLen / ref).coerceIn(0f, 1f)
            }
            val k = when {
                ratio >= 0.35f -> 1f
                ratio >= 0.18f -> 0.8f
                else -> 0.55f
            }
            return BeautyParams(
                smooth = 44f * k, whiten = 34f * k, blemish = 48f * k, ruddy = 12f * k,
                faceSlim = 26f * k, vFace = 18f * k, bigEye = 16f * k, noseSlim = 12f * k,
                brightness = brightness, contrast = contrast, saturation = 5f, sharpen = 14f,
                filterId = 0
            )
        }

        // —— 风光 / 非人像分支：自适应风光增强 ——
        val std = lumaStd(src)   // 亮度标准差：越低越「灰平 / 有雾感」
        // 夜景 / 欠曝 → 夜景提亮
        val lowLight = when {
            mean < 75f -> 55f
            mean < 100f -> 35f
            else -> 0f
        }
        // 雾天：整体偏亮且对比低（灰蒙感）→ 去雾
        val dehaze = when {
            mean > 150f && std < 32f -> 50f
            mean > 170f && std < 42f -> 35f
            else -> 0f
        }
        // 清晰增强：默认给轻量（通透感主要靠局部对比，不给重锐化——
        // 之前 42~55 配合强度档放大后锐化过高，暗部噪纹被放大、阴影发黑，用户反馈差）
        val clarity = when {
            std < 38f -> 30f
            else -> 22f
        }
        return BeautyParams(
            lowLight = lowLight,
            dehaze = dehaze,
            clarity = clarity,
            brightness = when {
                mean < 62f -> 16f
                mean > 195f -> -10f
                else -> 0f
            },
            contrast = if (std < 35f) 6f else 3f,
            saturation = 8f,
            sharpen = 0f,   // clarity 已含锐化，避免双重锐化
            filterId = 0
        )
    }

    /** 缩到长边 ≤128 后按 Rec.601 亮度加权平均（0..255）。 */
    private fun meanLuminance(src: Bitmap): Float {
        val long = max(src.width, src.height)
        val sc = if (long > 128) 128f / long else 1f
        val w = (src.width * sc).toInt().coerceAtLeast(1)
        val h = (src.height * sc).toInt().coerceAtLeast(1)
        val sample =
            if (w == src.width && h == src.height) src
            else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        sample.getPixels(px, 0, w, 0, 0, w, h)
        if (sample !== src) sample.recycle()

        var acc = 0L
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            acc += 299L * r + 587L * g + 114L * b
        }
        return acc.toFloat() / (w * h) / 1000f
    }

    /** 缩到长边 ≤128 后按 Rec.601 亮度标准差（0..~128），用于判断雾感 / 平淡 / 对比。 */
    private fun lumaStd(src: Bitmap): Float {
        val long = max(src.width, src.height)
        val sc = if (long > 128) 128f / long else 1f
        val w = (src.width * sc).toInt().coerceAtLeast(1)
        val h = (src.height * sc).toInt().coerceAtLeast(1)
        val sample =
            if (w == src.width && h == src.height) src
            else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        sample.getPixels(px, 0, w, 0, 0, w, h)
        if (sample !== src) sample.recycle()

        var acc = 0.0
        var acc2 = 0.0
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (299L * r + 587L * g + 114L * b) / 1000.0
            acc += y
            acc2 += y * y
        }
        val n = (w * h).toDouble()
        val mean = acc / n
        val variance = acc2 / n - mean * mean
        return sqrt(max(variance, 0.0)).toFloat()
    }
}
