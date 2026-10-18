package com.openscan.docscan.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.io.File
import java.io.FileOutputStream
import kotlin.math.exp
import kotlin.math.max

/**
 * 基于 ML 分割模型的文档区域检测。
 *
 * 模型：FairScan segmentation model v1.2.0
 *   · DeepLabV3Plus + MobileNetV2 编码器，量化 TFLite，4.9MB
 *   · 输入 [1,256,256,3] float32（RGB，0..1）
 *   · 输出 [1,256,256,1] float32（logits，sigmoid 后为前景概率）
 *   · 来源 https://github.com/pynicolas/fairscan-segmentation-model （GPLv3）
 *
 * ★ 本构建是**自用版**：该 GPL-3.0 模型已放回 assets 并随包打包。
 *   自用不构成 GPL-3.0 第 6 条意义上的"传播（conveying）"，因此不触发
 *   copyleft 义务 —— 但**请勿把本构建对外分发**：模型权重来自 GPL-3.0 仓库，
 *   其训练数据为 CC BY-NC-SA 4.0（禁止商用），对外分发无法合规。
 *   需要对外分发的用合规版（v1.4.0，模型不打包，走文本种子 + 传统边缘），
 *   详见 COMPLIANCE-AUDIT.md。
 *
 *   上游版权与许可是"保留"而非"恢复"：GPL-3.0 全文已随包于
 *   assets/THIRD-PARTY-NOTICES.md，本注释也保留了原始来源与授权说明。
 *   一旦要对外分发，把 assets/doc_segmentation.tflite 删掉即回到合规版，
 *   本文件与 ScanPipeline 均无需改动（模型缺失时 obtainInterpreter 会失败并
 *   记录 lastError，ScanPipeline 据此跳到文本种子检测）。
 *
 * 为什么需要它：传统边缘检测（[DocumentGeometry.detectDocumentQuad]）依赖
 * "文档边界是清晰的直线边"这一假设。摊开的书、花纹桌布上的纸张、卷曲页，
 * 这些场景里边界要么是弧线、要么被内部文字边缘淹没 —— 之前 6 条传统算法
 * 路线全部实测失败。分割模型按"学到的文档外观"逐像素分类，不依赖边缘，
 * 恰好补上这个盲区。
 *
 * 离线验证（用真实失败照片跑 Python 推理）：花纹桌布上的摊开书、
 * 白纸页，两者都干净切出完整文档区域，边界贴合。
 *
 * 后处理：概率图 → 二值化 → 最大连通域 → 轮廓 → 四边形拟合。
 * 这一步复用 OpenCV（工程已有），不引入新依赖。
 *
 * 线程安全：单实例单 Interpreter，[detectQuad] 加锁串行。
 * 调用方持有实例并在退出时 [release]。
 */
class DocSegmenter(private val appContext: Context) {

    private var interpreter: Interpreter? = null

    /** 上次加载失败的原因。null 表示尚未尝试过。 */
    var lastError: String? = null
        private set

    /**
     * assets 中不存在模型文件时为 true。
     *
     * 自用版模型已随包打包，正常情况下**恒为 false**；一旦为 true，
     * 说明模型没打进 APK（打包失误）或资源被剥离，自检页会直接暴露这一点。
     * 供调用方做一次性判断后跳过 ML 路径，避免每次检测都白读一遍 assets。
     */
    var modelMissing: Boolean = false
        private set

    /**
     * 懒加载：模型缺失（理论上不可能，随 APK 打包）或加载失败时返回 null。
     *
     * 失败**不缓存**：`interpreter` 保持 null，下次调用会重新尝试。
     * 之前用 `runCatching{}.getOrElse{ null }.also{ interpreter = it }` 把
     * null 也存了进去，一旦首次失败就再也不会重试。
     */
    private fun obtainInterpreter(): Interpreter? {
        interpreter?.let { return it }
        return runCatching {
            val mapped = loadModel()
            val opts = Interpreter.Options().apply { numThreads = 4 }
            Interpreter(mapped, opts)
        }.onSuccess {
            lastError = null
            interpreter = it
        }.onFailure { e ->
            // 模型文件不存在（自用版理论上不该发生）与加载失败要区分开：
            // 前者是永久状态，调用方应记住并跳过；后者（如内存不足）下次可能成功。
            if (e is java.io.FileNotFoundException) modelMissing = true
            lastError = e.describe()
            Log.w(TAG, "分割模型加载失败，ML 检测不可用", e)
        }.getOrNull()
    }

    private fun loadModel(): MappedByteBuffer {
        // 优先直接 mmap assets（APK 内置）；openFd 要求条目未压缩，
        // build.gradle.kts 里已配 noCompress += "tflite"。
        // 若设备/打包异常导致 openFd 失败，回退到"解压到 cacheDir 再 mmap"。
        return runCatching {
            appContext.assets.openFd(MODEL_ASSET).use { fd ->
                fd.createInputStream().use { input ->
                    val tmp = File(appContext.cacheDir, MODEL_ASSET)
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    tmp.inputStream().channel.map(
                        java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, tmp.length()
                    )
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "assets.openFd 失败（${e.describe()}），回退到流式解压", e)
            val tmp = File(appContext.cacheDir, MODEL_ASSET)
            appContext.assets.open(MODEL_ASSET).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            java.io.RandomAccessFile(tmp, "r").use { raf ->
                raf.channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length()
                )
            }
        }
    }

    /**
     * 在照片中检测文档区域，返回四边形（原图坐标系）。
     *
     * @return 检测失败（模型不可用 / 画面中没有文档 / 结果不可信）时返回 null，
     *         调用方应回退到传统边缘检测。
     */
    @Synchronized
    fun detectQuad(src: Bitmap): DocumentGeometry.Quad? {
        val eng = obtainInterpreter() ?: run {
            lastError = lastError ?: "模型未加载"
            return null
        }
        // 每次推理前清掉上一轮的过程信息，只保留本轮结果
        lastError = null
        val rgba = Mat()
        val prob = Mat()
        val binary = Mat()
        var kernel: Mat? = null
        try {
            // 1) 缩到模型输入尺寸（推理在 256x256 上做，坐标最后映射回原图）
            val work = if (maxOf(src.width, src.height) > INFER_MAX_EDGE) {
                val k = INFER_MAX_EDGE.toFloat() / maxOf(src.width, src.height)
                Bitmap.createScaledBitmap(
                    src,
                    max((src.width * k).toInt(), 1),
                    max((src.height * k).toInt(), 1),
                    true
                )
            } else src
            val scaleX = src.width.toDouble() / work.width
            val scaleY = src.height.toDouble() / work.height

            // 2) 位图 → float32 RGB [0,1]
            Utils.bitmapToMat(work, rgba)   // RGBA
            val input = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            val resized = Bitmap.createScaledBitmap(work, INPUT_SIZE, INPUT_SIZE, true)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            if (resized !== work) resized.recycle()
            for (p in pixels) {
                // ARGB int → RGB float /255
                val r = (p shr 16 and 0xFF) / 255.0f
                val g = (p shr 8 and 0xFF) / 255.0f
                val b = (p and 0xFF) / 255.0f
                input.putFloat(r); input.putFloat(g); input.putFloat(b)
            }
            input.rewind()

            // 3) 推理
            // ★ 输出必须是四维 [1,256,256,1] 的 Java 对应结构 —— float[1][256][256][1]。
            //   之前写成 Array(1){Array(256){FloatArray(1)}}（三维 [1,256,1]，少一层），
            //   eng.run 会抛 IllegalArgumentException（输出形状不匹配），
            //   被 ScanPipeline 的 runCatching 静默吞掉后回退边缘检测，
            //   表现就是"ML 分割在真机上永远不生效"。
            val output = Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(1) } } }
            eng.run(input, output)

            // 4) logits → 概率 → 256x256 灰度 Mat → 放大回工作尺寸
            val probSmall = Mat(INPUT_SIZE, INPUT_SIZE, org.opencv.core.CvType.CV_32F)
            val flat = FloatArray(INPUT_SIZE * INPUT_SIZE)
            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    val lg = output[0][y][x][0]
                    flat[y * INPUT_SIZE + x] = 1.0f / (1.0f + exp(-lg))
                }
            }
            probSmall.put(0, 0, flat)
            Imgproc.resize(probSmall, prob, Size(work.width.toDouble(), work.height.toDouble()))
            probSmall.release()

            // 5) 二值化 + 形态学整形（去孤立点、填小洞）
            // ★ Imgproc.threshold 不改变位深：prob 是 CV_32F，threshold 输出也是
            //   CV_32F（值 0.0/255.0）。而 findContours 只接受 CV_8U，直接传会抛
            //   "Modes other than RETR_FLOODFILL ... support only CV_8UC1"——
            //   正是真机自检页抓到的那个错误。必须先 convertTo 转 8 位。
            //   （离线 Python 验证时写了 astype(np.uint8)，Kotlin 移植时漏掉。）
            val binary32 = Mat()
            Imgproc.threshold(prob, binary32, 0.5, 255.0, Imgproc.THRESH_BINARY)
            binary32.convertTo(binary, org.opencv.core.CvType.CV_8U)
            binary32.release()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

            // 6) 最大连通域 → 轮廓 → 四边形
            val contours = ArrayList<MatOfPoint>()
            val binaryForContours = binary.clone()
            try {
                Imgproc.findContours(
                    binaryForContours, contours, Mat(),
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
                )
            } finally {
                binaryForContours.release()
            }
            if (contours.isEmpty()) {
                lastError = "掩码无轮廓（模型未识别出文档）"
                return null
            }
            val imageArea = work.width.toDouble() * work.height
            // 面积最大的轮廓；太小（画面里没有文档）则放弃
            val best = contours.maxByOrNull { Imgproc.contourArea(it) }
            val bestArea = best?.let { Imgproc.contourArea(it) } ?: 0.0
            val ratio = if (imageArea > 0) bestArea / imageArea else 0.0
            val quad = best?.let { maskToQuad(it, imageArea) }
            contours.forEach { it.release() }
            if (quad == null) {
                lastError = "轮廓面积占比 %.1f%% 超出可信范围（15%%~97%%）".format(ratio * 100)
                return null
            }

            // 7) 映射回原图坐标系
            return DocumentGeometry.Quad(
                org.opencv.core.Point(quad.tl.x * scaleX, quad.tl.y * scaleY),
                org.opencv.core.Point(quad.tr.x * scaleX, quad.tr.y * scaleY),
                org.opencv.core.Point(quad.br.x * scaleX, quad.br.y * scaleY),
                org.opencv.core.Point(quad.bl.x * scaleX, quad.bl.y * scaleY)
            )
        } finally {
            rgba.release(); prob.release(); binary.release(); kernel?.release()
        }
    }

    /** 从掩码轮廓拟合四边形：优先 4 点逼近，退化时用最小外接矩形。 */
    private fun maskToQuad(contour: MatOfPoint, imageArea: Double): DocumentGeometry.Quad? {
        val area = Imgproc.contourArea(contour)
        // 文档至少占画面 15%，超过 97% 说明模型输出基本是全幅，没有信息量
        if (area < imageArea * 0.15 || area > imageArea * 0.97) return null

        val c2f = MatOfPoint2f(*contour.toArray())
        val approx = MatOfPoint2f()
        try {
            val peri = Imgproc.arcLength(c2f, true)
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            val pts = approx.toArray()
            return when {
                pts.size == 4 -> DocumentGeometry.orderCorners(pts)
                pts.size in 5..12 -> {
                    val rect = Imgproc.minAreaRect(c2f)
                    val box = arrayOfNulls<org.opencv.core.Point>(4)
                    rect.points(box)
                    DocumentGeometry.orderCorners(box.requireNoNulls())
                }
                else -> null
            }
        } finally {
            approx.release()
            c2f.release()
        }
    }

    fun release() {
        runCatching { interpreter?.close() }
        interpreter = null
    }

    companion object {
        private const val TAG = "DocSegmenter"
        private const val MODEL_ASSET = "doc_segmentation.tflite"
        private const val INPUT_SIZE = 256

        /** 大于该边长的输入先缩，节省预处理与后处理开销（模型本身只吃 256）。 */
        private const val INFER_MAX_EDGE = 2000
    }
}
