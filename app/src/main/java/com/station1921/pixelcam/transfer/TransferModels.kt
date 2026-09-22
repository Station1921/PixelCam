package com.station1921.pixelcam.transfer

import android.graphics.Bitmap
import java.io.File

/** 所有图片扩展名（含 RAW）。视频见 [VIDEO_EXT]。 */
private val IMAGE_EXT = setOf(
    "jpg", "jpeg", "jpe", "png", "webp", "heic", "heif",
    "dng", "tif", "tiff",
    // 各家 RAW
    "arw", "cr2", "cr3", "nef", "nrw", "orf", "rw2", "pef",
    "srw", "raf", "raw", "3fr", "erf", "mef", "mrw", "x3f"
)

/** 仅 RAW 扩展名（用于区分 RAW 与普通图片） */
private val RAW_EXT = setOf(
    "dng", "tif", "tiff",
    "arw", "cr2", "cr3", "nef", "nrw", "orf", "rw2", "pef",
    "srw", "raf", "raw", "3fr", "erf", "mef", "mrw", "x3f"
)

/** 相机视频常见扩展名 */
private val VIDEO_EXT = setOf(
    "mp4", "m4v", "mov", "avi", "mkv", "mpg", "mpeg",
    "wmv", "flv", "3gp", "webm", "mts", "m2ts"
)

fun isImageName(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    return dot > 0 && dot < name.length - 1 &&
        IMAGE_EXT.contains(name.substring(dot + 1).lowercase())
}

fun isVideoName(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    return dot > 0 && dot < name.length - 1 &&
        VIDEO_EXT.contains(name.substring(dot + 1).lowercase())
}

/** 图片或视频都算「媒体」：传输/接收阶段一并拉取（之前只拉图片，导致相机视频收不到） */
fun isMediaName(name: String): Boolean = isImageName(name) || isVideoName(name)

/** 媒体大类：照片用 JPEG / RAW / 其它图片 区分，视频单独一类 */
enum class MediaKind { JPEG, RAW, IMAGE_OTHER, VIDEO }

fun mediaKindOf(name: String): MediaKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        isVideoName(name) -> MediaKind.VIDEO
        RAW_EXT.contains(ext) -> MediaKind.RAW
        listOf("jpg", "jpeg", "jpe").contains(ext) -> MediaKind.JPEG
        else -> MediaKind.IMAGE_OTHER
    }
}

/** 角标用的简短格式名：JPG / RAW / PNG / HEIC / MP4 / MOV … */
fun formatLabelOf(name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return when {
        isVideoName(name) -> if (ext.isNotEmpty()) ext else "视频"
        RAW_EXT.contains(ext.lowercase()) -> "RAW"
        else -> if (ext.isNotEmpty()) ext else "图片"
    }
}

/** 远端（相机 / 存储卡 / 服务器）上的一张照片。 */
data class RemotePhoto(
    /** 源内唯一的句柄，通常形如 "storageId:objectHandle" 或远端路径 */
    val id: String,
    val name: String,
    val size: Long,
    /** 拍摄/修改时间，毫秒；未知为 0 */
    val modified: Long = 0L
)

/**
 * 照片来源的统一抽象。
 *
 * 目前实现了四种：USB 直连（PTP/MTP）、相机 WiFi（FTP）、
 * WiFi SD 卡（FlashAir / ez Share），以及本机相册与直拍（在 UI 层处理）。
 *
 * 缩略图按需加载而不是随列表一起返回，避免一次拉几百张就把内存吃满。
 */
interface PhotoSource {
    val id: String
    val label: String

    suspend fun list(): List<RemotePhoto>

    /**
     * 快速探测：只扫最可能有新照片的位置，供「监视」轮询使用。
     *
     * 返回 null 表示该源不支持快速路径，调用方会自动回退到全量 [list]。
     * 实现需自行维护 download/thumbnail/preview 所需的句柄映射。
     */
    suspend fun listRecent(): List<RemotePhoto>? = null

    /**
     * 中等尺寸预览（长边 [edge]），监视页放大查看用。
     * 返回 null 表示拿不到预览（例如 RAW 无法解码），UI 会给出提示。
     */
    suspend fun preview(photo: RemotePhoto, edge: Int = 1280): Bitmap? = null

    suspend fun thumbnail(photo: RemotePhoto): Bitmap?
    suspend fun download(photo: RemotePhoto, dest: File, onProgress: (Int) -> Unit)
    fun close()

    /**
     * 远程触发相机快门（即拍即传）。
     * 默认不支持（返回 false）：Android 公开 MTP API 没有 capture 操作，
     * 多数无线 SD 卡也只是文件服务、无法控制快门。
     * 各来源可按自身协议重写（如索尼 Camera Remote API 的 actTakePicture）。
     */
    fun triggerCapture(): Boolean = false
}

/** 常见的相机 WiFi 默认地址，用于一键探测 */
val COMMON_CAMERA_HOSTS = listOf(
    // 奥林巴斯 / OM System：相机自身即 DHCP 服务器，HTTP 服务固定在 192.168.0.10（不是网关）
    "192.168.0.10",
    "192.168.1.1",
    "192.168.0.1",
    "192.168.1.2",
    "192.168.122.1",   // 索尼
    "192.168.54.1",    // 部分佳能
    "192.168.4.1",     // ez Share / 多数 WiFi SD 卡
    "flashair"
)
