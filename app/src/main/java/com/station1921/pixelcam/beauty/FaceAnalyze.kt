package com.station1921.pixelcam.beauty

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.station1921.pixelcam.PixelCamApp
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import java.io.File
import kotlin.math.max

/** 归一化前的像素矩形框。 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val cx: Float get() = (left + right) * 0.5f
    val cy: Float get() = (top + bottom) * 0.5f
    fun scaled(k: Float) = Box(left * k, top * k, right * k, bottom * k)
}

data class FaceInfo(
    val face: Box,
    val leftEye: Box?,
    val rightEye: Box?,
    val nose: Box?,
    val mouth: Box?
) {
    fun scaled(k: Float) = FaceInfo(
        face.scaled(k),
        leftEye?.scaled(k),
        rightEye?.scaled(k),
        nose?.scaled(k),
        mouth?.scaled(k)
    )
}

/**
 * 人脸检测：三级策略，全部在本机完成（不联网、不上传、不计费）。
 *
 * 1. ML Kit（优先）：定位精度高，能给出眼睛/鼻子/嘴等轮廓。个别机型、
 *    低分辨率或人脸过小时可能整批漏检/抛错（此前错误被静默吞掉 → 永远"未检测到人脸"）。
 * 2. OpenCV YuNet（兜底主力，face_detection_yunet_2023mar.onnx 仅 232KB）：
 *    小 CNN 检测器，对小脸、歪头、刘海遮挡的召回率远高于 Haar 级联，
 *    自带双眼瞳孔/鼻尖/嘴角 5 个关键点。样张（全身人像小脸）实测一次命中。
 * 3. OpenCV Haar 级联（最后防线）：纯本地、零模型依赖，只要求"正面清晰人脸"。
 *
 * 探测图统一把长边归一到 [DETECT_EDGE]（小图放大、大图缩小）——
 * 小图不放大时 ML Kit/Haar 对几十像素的小脸极易漏检。
 *
 * 检测结果会被上层缓存复用，拖滑块时不会反复检测。
 */
object FaceAnalyze {

    private const val TAG = "FaceAnalyze"

    /** 探测图长边。ML Kit 小脸要 ≥100px，小图必须放大后再检测 */
    private const val DETECT_EDGE = 1280

    private val options: FaceDetectorOptions by lazy {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()
    }

    /**
     * 检测 [src] 中的人脸。
     * 长边统一归一到 [DETECT_EDGE]（小图放大、大图缩小）再检测，
     * 坐标随后按比例还原，这样在 4 千万像素的 RAW 转 JPG 上也能快速出结果。
     */
    fun detect(src: Bitmap): List<FaceInfo> {
        val longEdge = max(src.width, src.height)
        val scale = DETECT_EDGE.toFloat() / longEdge

        val probe = if (scale != 1f) {
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, h, true)
        } else src

        return try {
            var found = runCatching { detectOn(probe) }.getOrElse { e ->
                Log.w(TAG, "ML Kit 人脸检测不可用：${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            if (found.isEmpty()) {
                found = runCatching { detectYunet(probe) }.getOrElse { e ->
                    Log.w(TAG, "YuNet 兜底检测失败：${e.message}")
                    emptyList()
                }
            }
            if (found.isEmpty()) {
                found = runCatching { detectHaarFallback(probe) }.getOrElse { e ->
                    Log.w(TAG, "Haar 兜底检测失败：${e.message}")
                    emptyList()
                }
            }
            if (found.isEmpty() || scale == 1f) found
            else found.map { it.scaled(1f / scale) }
        } finally {
            if (probe !== src) probe.recycle()
        }
    }

    // ——————————————————— ML Kit ———————————————————

    private fun detectOn(bitmap: Bitmap): List<FaceInfo> {
        val detector = FaceDetection.getClient(options)
        return try {
            val task = detector.process(InputImage.fromBitmap(bitmap, 0))
            @Suppress("BlockingMethodInNonBlockingContext")
            val faces: List<Face> = Tasks.await(task)
            faces.mapNotNull { it.toInfo() }
        } finally {
            runCatching { detector.close() }
        }
    }

    private fun Face.toInfo(): FaceInfo? {
        val oval = getContour(FaceContour.FACE)?.points
        val faceBox = when {
            !oval.isNullOrEmpty() -> bounding(oval)
            else -> Box(
                boundingBox.left.toFloat(),
                boundingBox.top.toFloat(),
                boundingBox.right.toFloat(),
                boundingBox.bottom.toFloat()
            )
        }
        if (faceBox.width <= 1f || faceBox.height <= 1f) return null

        val upperLip = getContour(FaceContour.UPPER_LIP_TOP)?.points.orEmpty()
        val lowerLip = getContour(FaceContour.LOWER_LIP_BOTTOM)?.points.orEmpty()

        return FaceInfo(
            face = faceBox,
            leftEye = contourBox(FaceContour.LEFT_EYE),
            rightEye = contourBox(FaceContour.RIGHT_EYE),
            nose = contourBox(FaceContour.NOSE_BOTTOM),
            mouth = if (upperLip.size + lowerLip.size >= 4) bounding(upperLip + lowerLip) else null
        )
    }

    private fun Face.contourBox(type: Int): Box? =
        getContour(type)?.points?.takeIf { it.size >= 3 }?.let(::bounding)

    private fun bounding(points: List<android.graphics.PointF>): Box {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < l) l = p.x
            if (p.y < t) t = p.y
            if (p.x > r) r = p.x
            if (p.y > b) b = p.y
        }
        return Box(l, t, r, b)
    }

    // ——————————————————— OpenCV YuNet 兜底（主力） ———————————————————

    /**
     * YuNet（FaceDetectorYN）：小 CNN，输出 15 列/脸
     * [x, y, w, h, 右眼x, 右眼y, 左眼x, 左眼y, 鼻尖x, 鼻尖y, 右嘴角x, 右嘴角y, 左嘴角x, 左嘴角y, score]
     */
    private fun detectYunet(bitmap: Bitmap): List<FaceInfo> {
        if (!OpenCv.ensureLoaded()) return emptyList()
        val modelPath = assetToFile("face_detection_yunet_2023mar.onnx", "yunet_2023mar.onnx")
            ?.absolutePath ?: return emptyList()

        val bgr = Mat()
        Utils.bitmapToMat(bitmap, bgr)
        Imgproc.cvtColor(bgr, bgr, Imgproc.COLOR_RGBA2BGR)
        val faces = Mat()
        return try {
            val detector = FaceDetectorYN.create(
                modelPath, "",
                Size(bgr.cols().toDouble(), bgr.rows().toDouble()),
                0.6f, 0.3f, 5000
            )
            val n = detector.detect(bgr, faces)
            if (n <= 0) emptyList()
            else (0 until faces.rows()).mapNotNull { row ->
                val v = FloatArray(15)
                faces.get(row, 0, v)
                if (v[14] < 0.6f) return@mapNotNull null
                yunetToInfo(v)
            }
        } finally {
            faces.release()
            bgr.release()
        }
    }

    /** 由 YuNet 输出构建 FaceInfo：关键点按人脸尺寸扩成区域框 */
    private fun yunetToInfo(v: FloatArray): FaceInfo {
        val f = Box(v[0], v[1], v[0] + v[2], v[1] + v[3])
        val fw = f.width
        val fh = f.height
        val eyeW = fw * 0.28f
        val eyeH = fh * 0.16f
        val noseW = fw * 0.18f
        val noseH = fh * 0.16f
        val rightMouth = Pair(v[10], v[11])
        val leftMouth = Pair(v[12], v[13])
        val mouthW = kotlin.math.abs(leftMouth.first - rightMouth.first)
            .coerceAtLeast(fw * 0.20f) + fw * 0.10f
        val mouthH = fh * 0.16f
        val mcx = (rightMouth.first + leftMouth.first) / 2f
        val mcy = (rightMouth.second + leftMouth.second) / 2f
        return FaceInfo(
            face = f,
            leftEye = around(v[4], v[5], eyeW, eyeH),
            rightEye = around(v[6], v[7], eyeW, eyeH),
            nose = around(v[8], v[9], noseW, noseH),
            mouth = around(mcx, mcy, mouthW, mouthH)
        )
    }

    // ——————————————————— OpenCV Haar 兜底（最后防线） ———————————————————

    private fun detectHaarFallback(bitmap: Bitmap): List<FaceInfo> {
        if (!OpenCv.ensureLoaded()) return emptyList()
        val cascadePath = cascadeFile()?.absolutePath ?: return emptyList()
        val classifier = org.opencv.objdetect.CascadeClassifier(cascadePath)
        if (classifier.empty()) return emptyList()

        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val rects = MatOfRect()
        try {
            // 40px 起步：低于 40px 的脸在 1280 探测图上已没有美化意义
            classifier.detectMultiScale(gray, rects, 1.08, 6, 0, Size(40.0, 40.0), Size())
            val arr = rects.toArray()
            return arr.map { withLandmarks(it) }
        } finally {
            rects.release()
            gray.release()
            rgba.release()
        }
    }

    /** Haar 只给脸框，按人脸的通用比例估算眼/鼻/嘴区域，供美肤/美型使用 */
    private fun withLandmarks(r: Rect): FaceInfo {
        val f = Box(
            r.x.toFloat(),
            r.y.toFloat(),
            (r.x + r.width).toFloat(),
            (r.y + r.height).toFloat()
        )
        val fw = f.width
        val fh = f.height
        val eyeY = f.top + fh * 0.40f
        val eyeW = fw * 0.20f
        val eyeH = fh * 0.10f
        val noseW = fw * 0.16f
        val noseH = fh * 0.16f
        val mouthW = fw * 0.34f
        val mouthH = fh * 0.13f
        return FaceInfo(
            face = f,
            leftEye = around(f.left + fw * 0.34f, eyeY, eyeW, eyeH),
            rightEye = around(f.left + fw * 0.66f, eyeY, eyeW, eyeH),
            nose = around(f.cx, f.top + fh * 0.60f, noseW, noseH),
            mouth = around(f.cx, f.top + fh * 0.78f, mouthW, mouthH)
        )
    }

    private fun around(cx: Float, cy: Float, w: Float, h: Float): Box =
        Box(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

    /** 把 assets 里的模型文件解到 cache（首次），OpenCV 原生层需要真实文件路径 */
    private fun assetToFile(assetName: String, targetName: String): File? {
        if (!PixelCamApp.hasInstance()) return null
        val app = PixelCamApp.instance
        val target = File(app.cacheDir, targetName)
        if (!target.exists()) {
            runCatching {
                app.assets.open(assetName).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
        return if (target.exists()) target else null
    }

    private fun cascadeFile(): File? =
        assetToFile("haarcascade_frontalface_alt2.xml", "haar_frontalface_alt2.xml")
}
