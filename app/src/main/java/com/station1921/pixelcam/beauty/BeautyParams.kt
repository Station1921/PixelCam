package com.station1921.pixelcam.beauty

/**
 * 一套完整的美化参数。
 *
 * 取值范围约定：
 *  - 美肤 / 美型 / 锐化 / 暗角：0 ~ 100，0 表示不处理
 *  - 亮度 / 对比度 / 饱和度 / 色温 / 高光 / 阴影：-100 ~ 100，0 表示不处理
 */
data class BeautyParams(

    // —— 美肤 ——
    val smooth: Float = 0f,   // 磨皮
    val whiten: Float = 0f,   // 美白
    val blemish: Float = 0f,  // 祛痘祛斑
    val ruddy: Float = 0f,    // 红润

    // —— 美型 ——
    val faceSlim: Float = 0f, // 瘦脸
    val vFace: Float = 0f,    // V 脸（收下颌）
    val bigEye: Float = 0f,   // 大眼
    val noseSlim: Float = 0f, // 瘦鼻

    // —— 调色 ——
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val highlights: Float = 0f, // 高光：只动画面亮部（压高光 / 提亮高光）
    val shadows: Float = 0f,    // 阴影：只动画面暗部（提暗部 / 压暗部）
    val sharpen: Float = 0f,
    val vignette: Float = 0f,

    val filterId: Int = 0,

    // —— 风光增强（全局、与构图/人脸无关）——
    val lowLight: Float = 0f,  // 夜景提亮
    val dehaze: Float = 0f,    // 去雾
    val clarity: Float = 0f,   // 清晰增强

    // —— 构图（旋转/镜像/裁剪），渲染前由 ViewModel 先行应用到画布 ——
    val geo: Geometry = Geometry()
) {
    /** 是否需要人脸检测结果（构图不在此列） */
    val needsFace: Boolean
        get() = smooth > 0f || whiten > 0f || blemish > 0f || ruddy > 0f || needsReshape

    /** 是否需要做液化变形 */
    val needsReshape: Boolean
        get() = faceSlim > 0f || vFace > 0f || bigEye > 0f || noseSlim > 0f

    /** 美化部分是否等于完全不处理（忽略构图：构图先于渲染已在画布上生效） */
    val isIdentity: Boolean
        get() = smooth <= 0f && whiten <= 0f && blemish <= 0f && ruddy <= 0f &&
                faceSlim <= 0f && vFace <= 0f && bigEye <= 0f && noseSlim <= 0f &&
                brightness == 0f && contrast == 0f && saturation == 0f &&
                temperature == 0f && highlights == 0f && shadows == 0f &&
                sharpen <= 0f && vignette <= 0f && filterId == 0 &&
                lowLight <= 0f && dehaze <= 0f && clarity <= 0f

    /** 是否命中某个预设（只比较美化部分，构图不影响高亮） */
    fun matchesPreset(preset: BeautyPreset): Boolean =
        copy(geo = Geometry.IDENTITY) == preset.params

    /** 套用预设但保留当前构图（预设本身不带构图） */
    fun withPreset(preset: BeautyPreset): BeautyParams =
        preset.params.copy(geo = geo)
}

// ————————————————————— 预设 —————————————————————

data class BeautyPreset(val name: String, val params: BeautyParams)

/**
 * 默认观感（对标像素蛋糕"导入即自然"）：温和磨皮 + 中性微白 + 轻锐化找回质感。
 * 与下方"自然"预设保持同值，导入/重置后预设条会自动高亮"自然"。
 */
val DEFAULT_LOOK: BeautyParams = BeautyParams(
    smooth = 28f, whiten = 15f, blemish = 35f, ruddy = 8f,
    faceSlim = 15f, bigEye = 10f,
    brightness = 5f, contrast = 5f, saturation = 3f, sharpen = 10f
)

/** 预设只覆盖修脸与调色，不动构图；用户可在预设基础上继续微调。 */
val DEFAULT_PRESETS: List<BeautyPreset> = listOf(
    BeautyPreset("原图", BeautyParams()),

    BeautyPreset("自然", DEFAULT_LOOK),

    BeautyPreset(
        "奶油肌",
        BeautyParams(
            smooth = 58f, whiten = 34f, blemish = 55f, ruddy = 16f,
            faceSlim = 26f, vFace = 18f, bigEye = 18f, noseSlim = 14f,
            brightness = 6f, contrast = 2f, saturation = -4f, temperature = 6f,
            sharpen = 8f, filterId = 1
        )
    ),

    BeautyPreset(
        "网红脸",
        BeautyParams(
            smooth = 72f, whiten = 46f, blemish = 70f, ruddy = 12f,
            faceSlim = 46f, vFace = 40f, bigEye = 38f, noseSlim = 30f,
            brightness = 8f, contrast = 4f, saturation = 6f, sharpen = 26f,
            filterId = 2
        )
    ),

    BeautyPreset(
        "证件照",
        BeautyParams(
            smooth = 26f, whiten = 22f, blemish = 60f,
            faceSlim = 10f,
            brightness = 6f, contrast = 8f, saturation = -4f, sharpen = 30f
        )
    ),

    BeautyPreset(
        "胶片感",
        BeautyParams(
            smooth = 40f, whiten = 12f, blemish = 35f,
            brightness = 2f, contrast = 14f, saturation = -10f, temperature = 8f,
            vignette = 26f, filterId = 3
        )
    ),

    BeautyPreset(
        "日系清透",
        BeautyParams(
            smooth = 48f, whiten = 44f, blemish = 45f, ruddy = 6f,
            faceSlim = 22f, vFace = 16f, bigEye = 14f,
            brightness = 10f, contrast = -8f, saturation = -14f, temperature = -6f,
            filterId = 4
        )
    ),

    BeautyPreset(
        "黑白肖像",
        BeautyParams(
            smooth = 44f, whiten = 8f, blemish = 65f,
            contrast = 20f, saturation = -100f, sharpen = 26f, vignette = 32f,
            filterId = 5
        )
    )
)
