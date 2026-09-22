package com.station1921.pixelcam.beauty

import android.graphics.Bitmap
import org.opencv.core.Mat

/**
 * 美化流水线。
 *
 * 处理顺序是有讲究的：
 *   1. 先做几何变形（瘦脸/大眼/V脸/瘦鼻）——位置变了，后面才按新位置算皮肤区域
 *   2. 再做皮肤相关（祛痘 → 磨皮 → 美白），三者共用同一张肤色掩码，只算一次
 *   3. 最后做全局调色、滤镜、暗角
 *
 * 关于人脸检测：它比较耗时，所以由调用方在 ViewModel 里按"当前正在编辑的位图"
 * 缓存一份结果传进来，拖滑块时不会反复检测。
 * 传入的 [faces] 坐标必须与 [src] 处于同一坐标系。
 */
object BeautyEngine {

    fun render(
        src: Bitmap,
        params: BeautyParams,
        faces: List<FaceInfo>,
        useParser: Boolean = true
    ): Bitmap {
        if (params.isIdentity) return src
        if (!OpenCv.ensureLoaded()) return src

        var cur: Mat = src.toBgr()

        // ——— 1. 液化变形 ———
        if (params.needsReshape && faces.isNotEmpty()) {
            val ops = Liquify.buildOps(faces, params)
            if (ops.isNotEmpty()) {
                val warped = Liquify.apply(cur, ops)
                cur.release()
                cur = warped
            }
        }

        // ——— 2. 皮肤处理（共用一张掩码）———
        val needSkin = params.blemish > 0f || params.smooth > 0f ||
            params.whiten > 0f || params.ruddy > 0f
        var mask: Mat? = null
        if (needSkin && faces.isNotEmpty()) {
            mask = Skin.buildMask(cur, faces, useParser)
        }

        if (params.blemish > 0f && mask != null) {
            val next = Skin.removeBlemishes(cur, mask, faces, params.blemish)
            if (next !== cur) {
                cur.release()
                cur = next
            }
        }
        if (params.smooth > 0f && mask != null) {
            val next = Skin.smooth(cur, mask, params.smooth)
            if (next !== cur) {
                cur.release()
                cur = next
            }
        }
        if ((params.whiten > 0f || params.ruddy > 0f) && mask != null) {
            val next = Skin.tone(cur, mask, params.whiten, params.ruddy)
            if (next !== cur) {
                cur.release()
                cur = next
            }
        }
        mask?.release()

        // ——— 3. 全局调色 ———
        val graded = ColorGrade.adjust(cur, params)
        if (graded !== cur) {
            cur.release()
            cur = graded
        }

        // ——— 3.5 风光增强（夜景提亮 / 去雾 / 清晰增强，全局、与人脸无关）———
        if (params.lowLight > 0f || params.dehaze > 0f || params.clarity > 0f) {
            val enhanced = SceneEnhance.enhance(cur, params.lowLight, params.dehaze, params.clarity)
            if (enhanced !== cur) {
                cur.release()
                cur = enhanced
            }
        }

        // ——— 4. 滤镜 ———
        val filtered = Filters.apply(cur, params.filterId)
        if (filtered !== cur) {
            cur.release()
            cur = filtered
        }

        // ——— 5. 暗角 ———
        val vignetted = ColorGrade.vignette(cur, params.vignette)
        if (vignetted !== cur) {
            cur.release()
            cur = vignetted
        }

        val out = cur.toBitmap()
        cur.release()
        return out
    }
}
