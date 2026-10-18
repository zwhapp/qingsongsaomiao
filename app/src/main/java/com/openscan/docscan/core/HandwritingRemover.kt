package com.openscan.docscan.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import kotlin.math.exp

/**
 * 手写笔迹分割（「去手写」模式的 ML 层）。
 *
 * 模型：handwriting_seg（自研）
 *   · 轻量 U-Net（单卷积/层，约 0.26M 参数），动态范围量化 TFLite，~0.3MB
 *   · 输入 [1,256,256,3] float32（RGB，0..1）
 *   · 输出 [1,256,256,1] float32（logits，sigmoid 后为手写概率）
 *   · 训练数据 100% 程序生成（tools/train/gen_handwriting.py，系统字体渲染印刷页
 *     + 程序合成手写笔迹），无第三方素材，**两个构建变体均可随包分发**
 *
 * 与颜色法（ScanEnhancer.eraseColoredInk）的分工：
 *   · 颜色法管「彩色笔迹 + 荧光笔」——靠色度/饱和度，无需模型；
 *   · ML 管「黑色/蓝黑钢笔/铅笔」手写 —— 与印刷同色，只能靠学到的几何特征；
 *   · 两者在 REMOVE_INK 分支做并集擦除。模型缺失/推理失败时优雅降级：
 *     返回 null，颜色法照常工作（即 v1.5.13 的行为）。
 *
 * 训练域匹配：模型在 256x256 上推理，app 端页面长边 3000~4000px，
 * 下采样 12~16x —— 与训练数据的渲染域（1536~2560 宽渲染后缩到 256）一致。
 *
 * 线程安全：单 Interpreter，[segment] 加锁串行（与 DocSegmenter 同策略）。
 */
object HandwritingRemover {

    @Volatile
    private var appContext: Context? = null

    private var interpreter: Interpreter? = null

    /** assets 中不存在模型文件时为 true（打包失误的暴露口）。 */
    var modelMissing: Boolean = false
        private set

    /** 由 ScanPipeline 持有 Context 的一方在创建时注入。重复调用无副作用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun obtainInterpreter(): Interpreter? {
        interpreter?.let { return it }
        val ctx = appContext ?: return null
        return runCatching {
            val mapped = loadModel(ctx)
            val opts = Interpreter.Options().apply {
                numThreads = 4
                // ★ 禁用 XNNPACK：本图含动态尺寸中间张量，XNNPACK delegate
                //   在 x86_64 模拟器上 SIGSEGV（libtensorflowlite_jni.so，
                //   实测模拟器崩溃）；普通 CPU 内核对 0.24M 参数小模型足够快。
                setUseXNNPACK(false)
            }
            Interpreter(mapped, opts)
        }.onSuccess {
            interpreter = it
        }.onFailure { e ->
            if (e is java.io.FileNotFoundException) modelMissing = true
            Log.w(TAG, "手写分割模型加载失败，降级为纯颜色法", e)
        }.getOrNull()
    }

    private fun loadModel(ctx: Context): MappedByteBuffer {
        // 与 DocSegmenter.loadModel 同策略：优先解压 mmap，失败回退流式拷贝。
        val tmp = File(ctx.cacheDir, MODEL_ASSET)
        return runCatching {
            ctx.assets.openFd(MODEL_ASSET).use { fd ->
                fd.createInputStream().use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    tmp.inputStream().channel.map(
                        java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, tmp.length()
                    )
                }
            }
        }.getOrElse {
            ctx.assets.open(MODEL_ASSET).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            java.io.RandomAccessFile(tmp, "r").use { raf ->
                raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }
        }
    }

    /**
     * 对一张（背景已归一化的）BGR 页面做手写分割。
     *
     * @return CV_8U 掩码（255 = 手写笔迹），尺寸与输入一致；模型不可用或
     *         推理异常时返回 null（调用方降级为纯颜色法）。失败**不缓存**，
     *         下次调用重新尝试（与 DocSegmenter 同策略）。
     */
    @Synchronized
    fun segment(bgr: Mat): Mat? {
        val eng = obtainInterpreter() ?: return null
        return runCatching {
            // 1) 缩到模型输入尺寸（AREA 下采样与训练域的下采样统计一致）
            val small = Mat()
            val k = INPUT_SIZE.toDouble() / maxOf(bgr.cols(), bgr.rows())
            Imgproc.resize(bgr, small,
                Size((bgr.cols() * k).coerceAtLeast(1.0), (bgr.rows() * k).coerceAtLeast(1.0)),
                0.0, 0.0, Imgproc.INTER_AREA)
            val sq = Mat()
            val sh = minOf(INPUT_SIZE, small.rows())
            val sw = minOf(INPUT_SIZE, small.cols())
            Imgproc.resize(small, sq, Size(sw.toDouble(), sh.toDouble()))
            small.release()

            // 2) BGR → RGB float32 [0,1] 缓冲
            val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val nPx = sq.rows() * sq.cols()
            val buf = ByteArray(nPx * 3)
            sq.get(0, 0, buf)
            sq.release()
            for (i in 0 until nPx) {
                val b = buf[i * 3].toInt() and 0xFF
                val g = buf[i * 3 + 1].toInt() and 0xFF
                val r = buf[i * 3 + 2].toInt() and 0xFF
                input.putFloat(r / 255.0f); input.putFloat(g / 255.0f); input.putFloat(b / 255.0f)
            }
            input.rewind()

            // 3) 推理（输出四维结构，与 DocSegmenter 同款写法）
            val output = Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(1) } } }
            eng.run(input, output)

            // 4) logits → 0/255 掩码（256 空间）
            val maskSmall = Mat(INPUT_SIZE, INPUT_SIZE, CvType.CV_8U)
            val px = ByteArray(INPUT_SIZE * INPUT_SIZE)
            for (y in 0 until sh) {
                for (x in 0 until sw) {
                    val lg = output[0][y][x][0]
                    px[y * INPUT_SIZE + x] =
                        if (1.0f / (1.0f + exp(-lg)) > 0.5f) 0xFF.toByte() else 0
                }
            }
            maskSmall.put(0, 0, px)

            // 5) 放大回输入尺寸（线性插值后重二值化，边缘平滑一次）
            val mask = Mat()
            Imgproc.resize(maskSmall, mask, Size(bgr.cols().toDouble(), bgr.rows().toDouble()),
                0.0, 0.0, Imgproc.INTER_LINEAR)
            maskSmall.release()
            Imgproc.threshold(mask, mask, 127.0, 255.0, Imgproc.THRESH_BINARY)
            mask
        }.onFailure { e ->
            Log.w(TAG, "手写分割推理失败，本次降级为纯颜色法", e)
        }.getOrNull()
    }

    fun release() {
        runCatching { interpreter?.close() }
        interpreter = null
    }

    private const val TAG = "HandwritingRemover"
    private const val MODEL_ASSET = "handwriting_seg.tflite"
    private const val INPUT_SIZE = 256
}
