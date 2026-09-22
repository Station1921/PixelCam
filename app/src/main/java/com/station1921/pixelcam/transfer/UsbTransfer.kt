package com.station1921.pixelcam.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** PTP/MTP 中表示"全部"的哨兵值 0xFFFFFFFF */
private const val MTP_ALL = -1

object UsbCameras {

    /**
     * 列出可能支持 PTP/MTP 的 USB 设备。
     *
     * 优先按静态图像设备类（USB class 6）筛选；如果一个都筛不出来，
     * 就把所有 USB 设备都列给用户手动试连——有些相机会把自己报成厂商自定义类。
     */
    fun find(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()
        val all = manager.deviceList.values.toList()
        return all.filter { it.hasStillImageInterface() }.ifEmpty { all }
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return manager.hasPermission(device)
    }

    /**
     * 打开设备、建立 PTP/MTP 会话。
     * 调用前必须先通过 UsbManager.requestPermission 拿到授权，否则返回 null。
     */
    suspend fun open(context: Context, device: UsbDevice): UsbMtpSource? =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return@withContext null
            if (!manager.hasPermission(device)) return@withContext null

            val connection = manager.openDevice(device) ?: return@withContext null
            val mtp = MtpDevice(device)
            if (!mtp.open(connection)) {
                connection.close()
                return@withContext null
            }
            UsbMtpSource(device, mtp, connection, context.cacheDir)
        }

    private fun UsbDevice.hasStillImageInterface(): Boolean {
        if (deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return true
        for (i in 0 until interfaceCount) {
            if (getInterface(i).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return true
        }
        return false
    }
}

/**
 * 通过 Android 内置的 [MtpDevice] 访问相机。
 * 这是系统公开 API（API 12+），协议由框架实现，无需自己撸 PTP。
 */
class UsbMtpSource(
    private val device: UsbDevice,
    private val mtp: MtpDevice,
    private val connection: UsbDeviceConnection,
    private val cacheDir: File
) : PhotoSource {

    private val handleOf = HashMap<String, Int>()

    override val id: String get() = "usb:${device.deviceId}"

    override val label: String
        get() = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ")
            .ifBlank { "USB 相机 (${device.deviceId})" }

    override suspend fun list(): List<RemotePhoto> = withContext(Dispatchers.IO) {
        handleOf.clear()
        val out = ArrayList<RemotePhoto>()
        for (sid in mtp.storageIds ?: intArrayOf()) {
            val roots = runCatching { mtp.getObjectHandles(sid, MTP_ALL, MTP_ALL) }.getOrNull()
                ?: continue
            walk(sid, roots, out)
        }
        out.sortByDescending { it.modified }
        out
    }

    /** 递归遍历：目录继续下钻，文件按扩展名过滤 */
    private fun walk(storageId: Int, handles: IntArray, out: MutableList<RemotePhoto>) {
        for (h in handles) {
            val info = runCatching { mtp.getObjectInfo(h) }.getOrNull() ?: continue
            if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                val handles = runCatching { mtp.getObjectHandles(storageId, MTP_ALL, h) }.getOrNull()
                if (handles != null && handles.size > 0) walk(storageId, handles, out)
                continue
            }
            val name = info.name
            if (!isMediaName(name)) continue

            val key = "$storageId:$h"
            handleOf[key] = h
            out += RemotePhoto(
                id = key,
                name = name,
                size = info.compressedSizeLong,
                modified = info.dateModified * 1000L
            )
        }
    }

    /**
     * 监视专用快速路径：只扫 DCIM 里修改时间最新的 1~2 个目录。
     *
     * 全量 [list] 会对整张卡逐级 getObjectInfo，卡里有几千个对象时一次要好几秒，
     * 扛不住 3 秒一轮的轮询。相机新拍的照片必然落在当期目录，
     * 所以这里只下钻最新目录，把单次扫描的 getObjectInfo 调用从上千次压到几十次。
     */
    override suspend fun listRecent(): List<RemotePhoto>? = withContext(Dispatchers.IO) {
        val out = ArrayList<RemotePhoto>()
        var scanned = false
        for (sid in mtp.storageIds ?: intArrayOf()) {
            val roots = runCatching { mtp.getObjectHandles(sid, MTP_ALL, MTP_ALL) }.getOrNull()
                ?: continue
            for (dir in newestDirs(sid, roots)) {
                val handles = runCatching { mtp.getObjectHandles(sid, MTP_ALL, dir) }.getOrNull()
                    ?: continue
                scanned = true
                walk(sid, handles, out)
            }
        }
        if (scanned) out.sortedByDescending { it.modified } else null
    }

    /** 找到 DCIM 目录，再取其中日期最新的 [n] 个子目录；没有 DCIM 就返回空（交给全量扫描） */
    private fun newestDirs(storageId: Int, roots: IntArray, n: Int = 2): List<Int> {
        var dcim: Int? = null
        for (h in roots) {
            val info = runCatching { mtp.getObjectInfo(h) }.getOrNull() ?: continue
            if (info.format == MtpConstants.FORMAT_ASSOCIATION &&
                info.name.equals("DCIM", ignoreCase = true)
            ) {
                dcim = h
                break
            }
        }
        val root = dcim ?: return emptyList()
        val subs = runCatching { mtp.getObjectHandles(storageId, MTP_ALL, root) }.getOrNull()
            ?: return listOf(root)

        val dirs = ArrayList<Pair<Int, Long>>()
        for (h in subs) {
            val info = runCatching { mtp.getObjectInfo(h) }.getOrNull() ?: continue
            if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                dirs += h to info.dateModified * 1000L
            }
        }
        if (dirs.isEmpty()) return listOf(root)
        return dirs.sortedByDescending { it.second }.take(n).map { it.first }
    }

    /**
     * 监视页大图：整文件落到 cache 再按屏幕尺寸解码。
     * RAW（arw/cr3 等）BitmapFactory 解不了，会返回 null，UI 提示可直接导入。
     */
    override suspend fun preview(photo: RemotePhoto, edge: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val handle = handleOf[photo.id] ?: return@withContext null
            val f = File(cacheDir, "mtp_preview_$handle")
            if (!f.exists() || (photo.size > 0 && f.length() != photo.size)) {
                f.delete()
                if (!mtp.importFile(handle, f.absolutePath)) return@withContext null
            }
            decodeThumb(f, edge)
        }

    override suspend fun thumbnail(photo: RemotePhoto): Bitmap? = withContext(Dispatchers.IO) {
        val handle = handleOf[photo.id] ?: return@withContext null
        val bytes = runCatching { mtp.getThumbnail(handle) }.getOrNull()
            ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override suspend fun download(
        photo: RemotePhoto,
        dest: File,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val handle = handleOf[photo.id] ?: error("对象句柄已失效：${photo.name}")
        dest.parentFile?.mkdirs()
        if (!mtp.importFile(handle, dest.absolutePath)) {
            error("导入失败：${photo.name}")
        }
        onProgress(100)
    }

    override fun close() {
        runCatching { mtp.close() }
        runCatching { connection.close() }
        handleOf.clear()
    }
}
