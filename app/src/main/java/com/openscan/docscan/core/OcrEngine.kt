package com.openscan.docscan.core

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 离线中文 OCR 引擎。
 *
 * 基于 ML Kit 的 bundled 中文模型——模型随 APK 打包，**不依赖 Google Play
 * 服务，也不需要网络**。这一点对国内设备尤其重要：unbundled 版本在无 GMS
 * 的设备上会直接不可用。
 *
 * 生命周期：整个实例可复用，内部持有单个识别器。用完调用 [close]。
 */
class OcrEngine {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /** 一页的识别结果。 */
    data class PageResult(
        /** 完整文本，按行拼接，可直接复制 */
        val fullText: String,
        /** 逐行结果，含边界框，用于定位 */
        val lines: List<TextLine>,
        /** 页面上所有文本块的整体包围盒，可用于判断"这页有没有字" */
        val boundingBox: Rect?
    ) {
        val isEmpty: Boolean get() = fullText.isBlank()
        val lineCount: Int get() = lines.size
        val charCount: Int get() = fullText.length
    }

    /** 一行文本及其在图像中的位置（像素坐标，左上角为原点）。 */
    data class TextLine(
        val text: String,
        val boundingBox: Rect,
        /** 该行所属块的序号，同一段落的行通常相同 */
        val blockIndex: Int
    )

    /**
     * 识别一页。
     *
     * 全程离线。识别在 ML Kit 内部线程池执行，本函数以挂起方式等待结果，
     * 不会阻塞调用方线程。
     *
     * 输入会先经过 [prepareForOcr]：小字号图先放大再识别（ML Kit 对
     * 字符高度敏感，书页整页拍下来单字往往只有 20 像素上下，
     * 实测错误多为形近字混淆，如 遇→過 / 心→八 / 循环→大常环）。
     * 识别出的边界框会按比例缩回原图坐标 —— 可搜索 PDF 的文字层
     * 依赖"框坐标 = 页面位图坐标"这一约定。
     *
     * @param bitmap 已增强（去阴影/矫正）后的图像，OCR 建立在它之上效果最好
     */
    suspend fun recognize(bitmap: Bitmap): PageResult {
        val (input, scaleBack) = prepareForOcr(bitmap)
        val image = InputImage.fromBitmap(input, 0)

        val text = suspendCancellableCoroutine<Text> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
            cont.invokeOnCancellation { /* ML Kit 不支持取消，让回调自行结束 */ }
        }

        return convert(text, scaleBack)
    }

    /**
     * OCR 输入预处理：
     * 1. 二值图先接笔画 —— 黑白模式的局部自适应阈值会把细笔画断成小段
     *    （尤其纸张发灰处），ML Kit 对断裂笔画极易认错（真机实测错字率
     *    明显高于彩色/灰度模式）。检测到二值图时做一轮 2×2 闭运算把
     *    断点连回，再轻模糊抗锯齿。
     * 2. 长边不足 [OCR_MIN_LONG_EDGE] 时等比放大。
     *
     * 返回缩放系数的倒数（scaleBack），调用方用它把识别框映射回原图。
     * 放大上限 2.5 倍 —— 再大只是浪费时间，准确率不再提升。
     */
    private fun prepareForOcr(bitmap: Bitmap): Pair<Bitmap, Float> {
        var work = bitmap
        if (isBinary(work)) {
            val repaired = repairBinaryForOcr(work)
            if (repaired != null) {
                if (repaired !== work) work = repaired
            }
        }
        val longEdge = maxOf(work.width, work.height)
        if (longEdge >= OCR_MIN_LONG_EDGE) return work to 1f
        val factor = (OCR_MIN_LONG_EDGE.toFloat() / longEdge).coerceAtMost(2.5f)
        val scaled = Bitmap.createScaledBitmap(
            work,
            (work.width * factor).toInt().coerceAtLeast(1),
            (work.height * factor).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== work && work !== bitmap) work.recycle()
        return scaled to 1f / factor
    }

    /** 粗判图像是否二值：采样 2000 个像素，落在两端 16 级灰度内的占比 > 95%。 */
    private fun isBinary(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w == 0 || h == 0) return false
        val step = maxOf(1, (w * h) / 2000)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var checked = 0
        var twoTone = 0
        var i = 0
        while (i < px.size) {
            val r = (px[i] shr 16) and 0xFF
            val g = (px[i] shr 8) and 0xFF
            val b = px[i] and 0xFF
            val gray = (r * 299 + g * 587 + b * 114) / 1000
            checked++
            if (gray <= 16 || gray >= 240) twoTone++
            i += step
        }
        return checked > 0 && twoTone * 100 / checked > 95
    }

    /**
     * 二值图 OCR 前修复：形态学闭运算连接断笔画 + 轻高斯模糊抗锯齿。
     * 走 OpenCV；OpenCV 未就绪或处理失败时返回 null（保持原图）。
     */
    private fun repairBinaryForOcr(src: Bitmap): Bitmap? {
        return runCatching {
            val mat = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(src, mat)
            try {
                val gray = org.opencv.core.Mat()
                org.opencv.imgproc.Imgproc.cvtColor(
                    mat, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY
                )
                // 反相后前景（笔画）为白，闭运算连接水平/竖直断点
                org.opencv.core.Core.bitwise_not(gray, gray)
                val kernel = org.opencv.imgproc.Imgproc.getStructuringElement(
                    org.opencv.imgproc.Imgproc.MORPH_RECT,
                    org.opencv.core.Size(2.0, 2.0)
                )
                org.opencv.imgproc.Imgproc.morphologyEx(
                    gray, gray,
                    org.opencv.imgproc.Imgproc.MORPH_CLOSE, kernel
                )
                org.opencv.imgproc.Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(0.0, 0.0), 0.8)
                org.opencv.core.Core.bitwise_not(gray, gray)
                kernel.release()

                org.opencv.imgproc.Imgproc.cvtColor(
                    gray, mat, org.opencv.imgproc.Imgproc.COLOR_GRAY2RGBA
                )
                gray.release()
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                org.opencv.android.Utils.matToBitmap(mat, out)
                out
            } finally {
                mat.release()
            }
        }.getOrNull()
    }

    /**
     * 容错版本：识别失败时返回空结果而不是抛异常。
     *
     * 用在批量导出流程里——不能因为某一页识别异常就让整个 PDF 导出失败。
     */
    suspend fun recognizeOrEmpty(bitmap: Bitmap): PageResult {
        return runCatching { recognize(bitmap) }
            .getOrElse { e ->
                Log.w(TAG, "OCR 识别失败，该页按无文字处理：${e.message}")
                PageResult(fullText = "", lines = emptyList(), boundingBox = null)
            }
    }

    private fun convert(result: Text, scaleBack: Float = 1f): PageResult {
        val lines = ArrayList<TextLine>()
        var union: Rect? = null

        result.textBlocks.forEachIndexed { blockIdx, block ->
            block.lines.forEach { line ->
                val lineText = line.text.trim()
                if (lineText.isEmpty()) return@forEach

                // 极少数情况下 boundingBox 为 null，跳过该行
                val rawBox = line.boundingBox ?: return@forEach

                // 若识别前放大过输入，这里把框缩回原图坐标
                val box = if (scaleBack == 1f) rawBox else Rect(
                    (rawBox.left * scaleBack).toInt(),
                    (rawBox.top * scaleBack).toInt(),
                    (rawBox.right * scaleBack).toInt(),
                    (rawBox.bottom * scaleBack).toInt()
                )

                lines.add(TextLine(text = lineText, boundingBox = box, blockIndex = blockIdx))

                val current = union
                if (current == null) {
                    union = Rect(box)
                } else {
                    // 累加所有行的包围盒
                    current.union(box)
                    union = current
                }
            }
        }

        // 用换行拼接，保留原有段落结构。块之间额外加空行，便于阅读。
        val fullText = buildString {
            var lastBlock = -1
            lines.forEach { line ->
                if (lastBlock != -1 && line.blockIndex != lastBlock) append('\n')
                append(line.text)
                append('\n')
                lastBlock = line.blockIndex
            }
        }.trimEnd()

        return PageResult(
            fullText = fullText,
            lines = lines,
            boundingBox = union
        )
    }

    /** 释放识别器。Activity/Service 结束时调用。 */
    fun close() {
        runCatching { recognizer.close() }
    }

    companion object {
        private const val TAG = "OcrEngine"

        /** OCR 输入的最小长边，低于此值先放大再识别。 */
        private const val OCR_MIN_LONG_EDGE = 2400
    }
}
