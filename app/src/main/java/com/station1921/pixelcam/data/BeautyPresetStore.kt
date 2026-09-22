package com.station1921.pixelcam.data

import android.content.Context
import com.station1921.pixelcam.beauty.BeautyParams
import com.station1921.pixelcam.beauty.BeautyPreset
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户自定义修图预设的持久化层。
 *
 * 预设只保存「美化」部分（磨皮/美型/调色/滤镜/风光），不保存构图（旋转/镜像/裁剪）——构图是
 * 针对某一张图的，套用到别的图时没有意义。保存时 geo 强制为 IDENTITY；套用时由上层
 * [com.station1921.pixelcam.ui.EditorViewModel.applyPreset] 保留目标图当前构图。
 *
 * 存储：SharedPreferences + org.json（内置，无需额外依赖）。
 */
object BeautyPresetStore {

    private const val FILE = "custom_beauty_presets"
    private const val KEY = "presets"

    /** 读取全部自定义预设（按保存顺序） */
    fun loadAll(context: Context): List<BeautyPreset> {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<BeautyPreset>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list += BeautyPreset(obj.getString("name"), fromJson(obj.getJSONObject("params")))
            }
            list
        }.getOrDefault(emptyList())
    }

    /** 按名称取某个预设的参数（不存在返回 null） */
    fun byName(context: Context, name: String): BeautyParams? =
        loadAll(context).firstOrNull { it.name == name }?.params

    /** 新增或覆盖同名预设，返回最新列表 */
    fun upsert(context: Context, preset: BeautyPreset): List<BeautyPreset> {
        val list = loadAll(context).toMutableList()
        val i = list.indexOfFirst { it.name == preset.name }
        if (i >= 0) list[i] = preset else list += preset
        save(context, list)
        return list
    }

    /** 删除指定名称的预设，返回最新列表 */
    fun delete(context: Context, name: String): List<BeautyPreset> {
        val list = loadAll(context).filter { it.name != name }
        save(context, list)
        return list
    }

    private fun save(context: Context, list: List<BeautyPreset>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("params", toJson(p.params))
                }
            )
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    private fun toJson(p: BeautyParams): JSONObject = JSONObject().apply {
        put("smooth", p.smooth)
        put("whiten", p.whiten)
        put("blemish", p.blemish)
        put("ruddy", p.ruddy)
        put("faceSlim", p.faceSlim)
        put("vFace", p.vFace)
        put("bigEye", p.bigEye)
        put("noseSlim", p.noseSlim)
        put("brightness", p.brightness)
        put("contrast", p.contrast)
        put("saturation", p.saturation)
        put("temperature", p.temperature)
        put("highlights", p.highlights)
        put("shadows", p.shadows)
        put("sharpen", p.sharpen)
        put("vignette", p.vignette)
        put("filterId", p.filterId)
        put("lowLight", p.lowLight)
        put("dehaze", p.dehaze)
        put("clarity", p.clarity)
    }

    private fun fromJson(o: JSONObject): BeautyParams = BeautyParams(
        smooth = o.optDouble("smooth", 0.0).toFloat(),
        whiten = o.optDouble("whiten", 0.0).toFloat(),
        blemish = o.optDouble("blemish", 0.0).toFloat(),
        ruddy = o.optDouble("ruddy", 0.0).toFloat(),
        faceSlim = o.optDouble("faceSlim", 0.0).toFloat(),
        vFace = o.optDouble("vFace", 0.0).toFloat(),
        bigEye = o.optDouble("bigEye", 0.0).toFloat(),
        noseSlim = o.optDouble("noseSlim", 0.0).toFloat(),
        brightness = o.optDouble("brightness", 0.0).toFloat(),
        contrast = o.optDouble("contrast", 0.0).toFloat(),
        saturation = o.optDouble("saturation", 0.0).toFloat(),
        temperature = o.optDouble("temperature", 0.0).toFloat(),
        highlights = o.optDouble("highlights", 0.0).toFloat(),
        shadows = o.optDouble("shadows", 0.0).toFloat(),
        sharpen = o.optDouble("sharpen", 0.0).toFloat(),
        vignette = o.optDouble("vignette", 0.0).toFloat(),
        filterId = o.optInt("filterId", 0),
        lowLight = o.optDouble("lowLight", 0.0).toFloat(),
        dehaze = o.optDouble("dehaze", 0.0).toFloat(),
        clarity = o.optDouble("clarity", 0.0).toFloat()
    )
}
