package com.station1921.pixelcam.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.station1921.pixelcam.transfer.isImageName
import com.station1921.pixelcam.transfer.isMediaName
import com.station1921.pixelcam.transfer.isVideoName
import com.station1921.pixelcam.transfer.mediaKindOf
import com.station1921.pixelcam.transfer.formatLabelOf
import com.station1921.pixelcam.transfer.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一张已经进入本应用、可以拿去编辑的照片 */
data class LocalPhoto(
    val file: File,
    val name: String,
    /** 是否被「保存调整」原地烘焙过（人工精修） */
    val edited: Boolean = false,
    /** 是否经过 AI 处理（AI 自动美化副本 / 修图页 AI 优化后保存） */
    val ai: Boolean = false,
    /** 媒体大类（JPEG / RAW / 其它图片 / 视频），用于相册格式角标与筛选 */
    val kind: MediaKind = MediaKind.JPEG,
    /** 角标用的简短格式名（JPG / RAW / MP4 …） */
    val formatLabel: String = "图片"
) {
    val modified: Long get() = file.lastModified()
}

object PhotoStore {

    private const val FLAG_PREFS = "pixelcam_photo_flags"
    private const val KEY_EDITED = "edited_paths"
    private const val KEY_AI = "ai_paths"

    /**
     * 按文件重建 [LocalPhoto]（读取 edited/ai 标记）。
     * 浏览页删除某张、或从修图页返回后，用来就地刷新单条记录的角标。
     */
    fun localPhotoOf(context: Context, file: File): LocalPhoto {
        val base = file.nameWithoutExtension
        return LocalPhoto(
            file = file,
            name = file.name,
            edited = isEdited(context, file),
            ai = isAi(context, file) || base.endsWith("_美化") || base.endsWith("_AI"),
            kind = mediaKindOf(file.name),
            formatLabel = formatLabelOf(file.name)
        )
    }

    /** 从相机导入的照片落在这里 */
    fun importedDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Imported").apply {
            mkdirs()
        }

    /**
     * 该文件是否曾被「保存调整」原地覆盖（内容已烘焙最终成图）。
     * 被标记的文件再次打开修图时以"零美化"为起点，避免二次叠默认观感。
     */
    fun isEdited(context: Context, file: File): Boolean {
        val sp = context.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        return sp.getStringSet(KEY_EDITED, emptySet())!!.contains(file.absolutePath)
    }

    /** 标记某文件已烘焙成图（保存调整后调用） */
    fun markEdited(context: Context, file: File) {
        val sp = context.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        val set = (sp.getStringSet(KEY_EDITED, emptySet()) ?: emptySet()).toMutableSet()
        set.add(file.absolutePath)
        sp.edit().putStringSet(KEY_EDITED, set).apply()
    }

    /** 该文件是否经过 AI 处理（首页显示「AI美化」角标依据之一） */
    fun isAi(context: Context, file: File): Boolean {
        val sp = context.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        return sp.getStringSet(KEY_AI, emptySet())!!.contains(file.absolutePath) ||
            file.nameWithoutExtension.endsWith("_美化") ||
            file.nameWithoutExtension.endsWith("_AI")
    }

    /** 标记某文件经过 AI 处理（AI 自动美化副本 / AI 优化后保存） */
    fun markAi(context: Context, file: File) {
        val sp = context.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        val set = (sp.getStringSet(KEY_AI, emptySet()) ?: emptySet()).toMutableSet()
        set.add(file.absolutePath)
        sp.edit().putStringSet(KEY_AI, set).apply()
    }

    /** 文件被删除时顺带清理标记，避免脏数据堆积 */
    private fun clearFlags(context: Context, file: File) {
        val sp = context.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        val edited = (sp.getStringSet(KEY_EDITED, emptySet()) ?: emptySet()).toMutableSet()
        val ai = (sp.getStringSet(KEY_AI, emptySet()) ?: emptySet()).toMutableSet()
        if (edited.remove(file.absolutePath) || ai.remove(file.absolutePath)) {
            sp.edit()
                .putStringSet(KEY_EDITED, edited)
                .putStringSet(KEY_AI, ai)
                .apply()
        }
    }

    /** 图传过程的临时缓存（缩略图复用、断点续传） */
    fun remoteCacheDir(context: Context): File =
        File(context.cacheDir, "remote").apply { mkdirs() }

    suspend fun listImported(context: Context): List<LocalPhoto> = withContext(Dispatchers.IO) {
        val sp = context.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        val editedSet = sp.getStringSet(KEY_EDITED, emptySet()) ?: emptySet()
        val aiSet = sp.getStringSet(KEY_AI, emptySet()) ?: emptySet()
        importedDir(context)
            .listFiles { f -> f.isFile && isMediaName(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                val base = f.nameWithoutExtension
                LocalPhoto(
                    file = f,
                    name = f.name,
                    edited = f.absolutePath in editedSet,
                    ai = f.absolutePath in aiSet || base.endsWith("_美化") || base.endsWith("_AI"),
                    kind = mediaKindOf(f.name),
                    formatLabel = formatLabelOf(f.name)
                )
            }
            ?: emptyList()
    }

    /**
     * 删除一张已导入的照片。
     * 文件位于应用专属外部目录 (Android/data/…/Pictures/Imported)，
     * 删除无需任何存储权限；只删应用自己的副本，不影响系统相册。
     */
    suspend fun delete(context: Context, photo: LocalPhoto): Boolean =
        withContext(Dispatchers.IO) {
            val ok = photo.file.delete()
            if (ok) clearFlags(context, photo.file)
            ok
        }

    /** 生成一个不会覆盖已有文件的导入目标路径 */
    fun importTarget(context: Context, name: String): File {
        val dir = importedDir(context)
        val base = name.substringBeforeLast('.').ifBlank { "IMG" }
        val ext = name.substringAfterLast('.', "jpg")
        var candidate = File(dir, "$base.$ext")
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$n.$ext")
            n++
        }
        return candidate
    }

    fun timestampName(prefix: String = "PixelCam"): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_$stamp.jpg"
    }

    /** 导出到系统相册 Pictures/PixelCam */
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        name: String,
        quality: Int
    ): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PixelCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    return@withContext null
                }
            } ?: return@withContext null
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return@withContext null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }

    /**
     * 把应用内已有的图片文件原样拷贝进系统相册（不重新编码、无画质损失）。
     * 重名时由 MediaStore 自动追加序号。
     */
    suspend fun saveFileToGallery(context: Context, file: File, name: String): Uri? =
        withContext(Dispatchers.IO) {
            val mime = when (file.extension.lowercase(Locale.US)) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "heic", "heif" -> "image/heic"
                "mp4", "m4v", "mov" -> "video/mp4"
                "avi" -> "video/x-msvideo"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "3gp" -> "video/3gpp"
                else -> if (isVideoName(file.name)) "video/*" else "image/jpeg"
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PixelCam")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return@withContext null
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                return@withContext null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }
}
