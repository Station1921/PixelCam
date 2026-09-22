package com.station1921.pixelcam.transfer

/**
 * 奥林巴斯「相机当前状态表」（OPC 的 `get_camprop` 接口）。
 *
 * 为什么用它而不是只靠照片 EXIF：
 * - EXIF 只有拍完才有，且只有 ISO/光圈/快门/焦段这几项；
 * - `get_camprop.cgi?com=desc&propname=desclist` **一次请求**就能拿到相机所有属性的
 *   当前值、是否可写、可选值列表（据 OPC Communication Protocol 1.0a），
 *   拍照页因此可以在不拍照的情况下显示实时参数（拍摄模式 / 曝光补偿 / 白平衡 / 焦距 / 驱动模式…）。
 *
 * 返回的是 XML，形如：
 * ```xml
 * <desc><propname>isospeedvalue</propname><attribute>getset</attribute>
 *       <value>200</value><enum>100 200 400</enum></desc>
 * ```
 * 用正则逐块解析即可，不值得引 XML 解析器（拿不到结构就当空表，功能降级不报错）。
 */
data class CameraProp(
    val name: String,
    /** 是否可写（`getset`）；只读的 `get` 属性界面上不给改 */
    val settable: Boolean,
    /** 当前值（原样，机型不同格式略有差异） */
    val value: String,
    /** 可选值列表（有的机型给英文枚举，有的给数字） */
    val options: List<String>
) {
    /** 原始值是否只是个数字（此时它多半是 [options] 的下标，而不是字面值） */
    val numeric: Boolean get() = value.toIntOrNull() != null

    /**
     * 展示用文本。优先把当前值**按字面**在枚举表里找（OPC 的 value 多半就是字面值，
     * 如快门 "60"、ISO "100"）；找不到再当数字下标取（0 基准优先，1 基准兜底）；
     * 都翻不出来就原样显示（宁可能看，也不能显示错）。
     */
    fun label(): String {
        val v = value.trim()
        if (v.isEmpty()) return "—"
        if (options.isNotEmpty()) {
            val byText = options.indexOfFirst { it.equals(v, ignoreCase = true) }
            if (byText >= 0) return options[byText]
        }
        val idx = v.toIntOrNull()
        if (idx != null && options.isNotEmpty()) {
            // 有的固件 value 是下标：先试 0 基准，再试 1 基准，越界就退回原值
            options.getOrNull(idx)?.let { return it }
            options.getOrNull(idx - 1)?.let { return it }
        }
        return v
    }

    /** 当前值在 [options] 里的列表下标（0-based）；找不到返回 0（兜底，不让界面拿到 -1 越界） */
    val optionIndex: Int
        get() {
            if (options.isEmpty()) return 0
            val v = value.trim()
            // 先按字面值找（快门 "60"、ISO "100" 这类 value 就是字面值，不是下标！）
            val byText = options.indexOfFirst { it.equals(v, ignoreCase = true) }
            if (byText >= 0) return byText
            // 字面值不在枚举里 → value 是下标数字；0 基准优先（set 实测 0 基准），1 基准兜底
            val n = v.toIntOrNull()
            if (n != null) {
                if (n in options.indices) return n
                val m = n - 1
                if (m in options.indices) return m
            }
            return 0
        }

    /**
     * 把列表下标换算成「该写回相机的值」。
     *
     * **OPC 约定：set_camprop 要的是枚举里的字面值（原文），不是下标**——
     * 权威参考实现（ccrome/olympus-omd-remote-control 的 OMD.py）实证：
     * `set_shutter('1"')`、`set_iso(200)` 都是回写枚举原文。
     * 以前回写下标数字，相机把 "1"、"15" 当字面快门（1 秒、15 秒）解释，
     * 表现就是「点快门永远跳到 1″」；ISO/白平衡同理错位。
     * 回写字面值对数字型枚举（ISO "200"）和文本型枚举（快门 '1"'、曝光补偿 '+0.3'）都正确。
     */
    fun rawForIndex(index: Int): String {
        if (options.isEmpty()) return value.trim()
        return options[index.coerceIn(0, options.size - 1)]
    }
}

/** 一张相机状态表；按属性名取值 */
class CameraProps(val map: Map<String, CameraProp>) {

    fun value(name: String): String? = map[name]?.label()?.takeIf { it.isNotBlank() && it != "—" }

    fun raw(name: String): String? = map[name]?.value?.takeIf { it.isNotBlank() }

    val isEmpty: Boolean get() = map.isEmpty()

    companion object {
        val EMPTY = CameraProps(emptyMap())

        /** 解析 `get_camprop?com=desc&propname=desclist` 的 XML */
        fun parse(xml: String): CameraProps {
            if (xml.isBlank()) return EMPTY
            val blocks = Regex("<desc>(.*?)</desc>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { it.groupValues[1] }
            val out = LinkedHashMap<String, CameraProp>()
            for (b in blocks) {
                val name = tag(b, "propname")?.trim().orEmpty()
                if (name.isEmpty()) continue
                val attr = tag(b, "attribute")?.trim().orEmpty()
                val value = tag(b, "value")?.trim().orEmpty()
                val options = tag(b, "enum")?.trim()
                    ?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
                out[name] = CameraProp(
                    name = name,
                    settable = attr.equals("getset", ignoreCase = true),
                    value = value,
                    options = options
                )
            }
            return CameraProps(out)
        }

        private fun tag(body: String, name: String): String? =
            Regex("<$name\\s*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.unescapeXml()

        /** 相机 XML 里的基础实体解码：快门枚举的 `1"` 可能被固件转义成 `1&quot;`，不解码会原样写回导致写参数失败 */
        private fun String.unescapeXml(): String = this
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}

/** 拍照页实时参数区的一项（标签 + 值） */
data class PropRow(val label: String, val value: String)

/**
 * 把相机状态表整理成界面要显示的「实时参数」列表。
 *
 * 只挑用户真正关心的项，并且**有值才显示**——不同机型、不同拍摄模式下能读到的项不一样，
 * 宁缺毋滥，避免一屏「—」。光圈（F 值）不在 OPC 的属性表里，仍然只能来自照片 EXIF。
 */
fun CameraProps.toRows(): List<PropRow> {
    val rows = ArrayList<PropRow>(10)
    fun add(label: String, vararg names: String) {
        val v = names.firstNotNullOfOrNull { value(it) } ?: return
        rows += PropRow(label, v)
    }
    add("模式", "takemode")
    add("ISO", "isospeedvalue")
    add("快门", "shutspeedvalue")
    add("曝光补偿", "expcomp")
    add("白平衡", "wbvalue")
    add("焦距", "focalvalue")
    add("驱动", "drivemode")
    add("滤镜", "artfilter")
    add("数码增距", "digitaltelecon")
    add("降噪", "noisereduction")
    add("色彩", "colortone")
    add("画质", "PixelSet1", "PixelShort")
    return rows
}

/**
 * 把相机属性名翻成中文（「全部参数」面板用）。
 * 认不出来的保持英文原名——总比显示错好。
 */
fun propLabel(name: String): String = when (name.lowercase()) {
    "takemode" -> "拍摄模式"
    "drivemode", "cameradrivemode" -> "驱动模式"
    "focalvalue" -> "焦距（变焦位置）"
    "expcomp" -> "曝光补偿"
    "shutspeedvalue" -> "快门速度"
    "isospeedvalue" -> "ISO 感光度"
    "wbvalue" -> "白平衡"
    "noisereduction" -> "降噪"
    "lowvibtime" -> "防抖延时"
    "bulbtimelimit" -> "B 门时限"
    "artfilter" -> "艺术滤镜"
    "digitaltelecon" -> "数码远摄增距"
    "colortone" -> "色彩风格"
    "exposemovie" -> "视频曝光"
    "colorphase" -> "色彩相位"
    "touchactiveframe" -> "触摸对焦框"
    "silentnoisereduction" -> "静音降噪"
    "silenttime" -> "静音时间"
    else -> name
}
