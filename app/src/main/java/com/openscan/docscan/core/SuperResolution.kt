package com.openscan.docscan.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 离线超分辨率放大。
 *
 * 双通路设计：
 *   · [Path.NEURAL]  —— 检测到 assets 中存在超分模型时启用，逐块推理，细节生成更好；
 *   · [Path.CLASSIC] —— 无模型时的后备方案，Lanczos 重采样 + 反锐化掩膜。
 *
 * 两条路径都完全在本机执行，不产生任何网络请求。
 *
 * ## 关于模型
 * 工程默认不内置模型文件（体积与授权原因）。把兼容的 TFLite 超分模型
 * 放到 `app/src/main/assets/` 下，命名见 [MODEL_CANDIDATES] 即可自动启用。
 * 模型要求：单输入单输出，NHWC float32，输入为低分辨率图块，
 * 输出为对应的高分辨率图块（缩放系数见 [SCALE_FACTOR]）。
 */
class SuperResolution(context: Context) {

    enum class Path { NEURAL, CLASSIC }

    private val appContext = context.applicationContext
    private var interpreter: Interpreter? = null
    private var inputSize: Int = 0
    private var patchSize: Int = DEFAULT_PATCH

    /** 当前实际生效的处理路径。 */
    var activePath: Path = Path.CLASSIC
        private set

    init {
        interpreter = tryLoadModel()
        if (interpreter != null) {
            activePath = Path.NEURAL
            val shape = interpreter!!.getInputTensor(0).shape() // [1, H, W, 3]
            inputSize = shape[1]
            patchSize = inputSize
        } else {
            Log.i(TAG, "未找到超分模型，回退到经典重采样路径（Lanczos + USM）")
        }
    }

    /**
     * 放大图像。
     *
     * @param src   输入位图
     * @param scale 放大倍数，支持 2 或 4
     * @param onProgress 进度回调，取值 0f..1f，在主线程调用方的协程中触发
     */
    fun upscale(src: Bitmap, scale: Int, onProgress: (Float) -> Unit = {}): Bitmap {
        require(scale == 2 || scale == 4) { "仅支持 2x / 4x 放大，收到 $scale" }

        return if (activePath == Path.NEURAL && interpreter != null) {
            runCatching { upscaleNeural(src, onProgress) }
                .getOrElse { e ->
                    Log.w(TAG, "神经超分失败，回退经典路径：${e.message}")
                    activePath = Path.CLASSIC
                    upscaleClassic(src, scale, onProgress)
                }
        } else {
            upscaleClassic(src, scale, onProgress)
        }
    }

    // ---------------- 神经路径 ----------------

    private fun upscaleNeural(src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        val itp = interpreter ?: throw IllegalStateException("interpreter 未初始化")
        val srcMat = Mat()
        val rgb = Mat()
        val outRgba = Mat()
        try {
            Utils.bitmapToMat(src, srcMat)
            Imgproc.cvtColor(srcMat, rgb, Imgproc.COLOR_RGBA2RGB)

            val scale = SCALE_FACTOR
            val outW = srcMat.cols() * scale
            val outH = srcMat.rows() * scale
            val outRgb = Mat(outH, outW, rgb.type())
            try {
                val tilesX = (rgb.cols() + patchSize - 1) / patchSize
                val tilesY = (rgb.rows() + patchSize - 1) / patchSize
                val totalTiles = (tilesX * tilesY).coerceAtLeast(1)
                var doneTiles = 0

                val inputBuffer = ByteBuffer
                    .allocateDirect(1 * patchSize * patchSize * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                val outputBuffer = ByteBuffer
                    .allocateDirect(1 * patchSize * scale * patchSize * scale * 3 * 4)
                    .order(ByteOrder.nativeOrder())

                for (ty in 0 until tilesY) {
                    for (tx in 0 until tilesX) {
                        val x0 = tx * patchSize
                        val y0 = ty * patchSize

                        // 边缘块用白色 padding 补齐，保证每块都是固定尺寸
                        val patch = Mat(
                            patchSize, patchSize, rgb.type(),
                            org.opencv.core.Scalar(255.0, 255.0, 255.0)
                        )
                        val availW = minOf(patchSize, rgb.cols() - x0)
                        val availH = minOf(patchSize, rgb.rows() - y0)
                        val sub = rgb.submat(y0, y0 + availH, x0, x0 + availW)
                        try {
                            sub.copyTo(patch.submat(0, availH, 0, availW))
                        } finally {
                            sub.release()
                        }

                        fillInputBuffer(patch, inputBuffer)
                        outputBuffer.rewind()
                        itp.run(inputBuffer, outputBuffer)

                        val outPatch = bufferToMat(outputBuffer, patchSize * scale)
                        try {
                            // 只把有效区域（非 padding 部分）拷回输出，避免边缘白条
                            val validW = minOf(availW * scale, outW - x0 * scale)
                            val validH = minOf(availH * scale, outH - y0 * scale)
                            if (validW > 0 && validH > 0) {
                                val srcRegion = outPatch.submat(0, validH, 0, validW)
                                val dstRegion = outRgb.submat(
                                    y0 * scale, y0 * scale + validH,
                                    x0 * scale, x0 * scale + validW
                                )
                                try {
                                    srcRegion.copyTo(dstRegion)
                                } finally {
                                    srcRegion.release()
                                    dstRegion.release()
                                }
                            }
                        } finally {
                            outPatch.release()
                            patch.release()
                        }

                        doneTiles++
                        onProgress(doneTiles.toFloat() / totalTiles)
                    }
                }

                // 神经输出偶有色偏，做一次温和的锐化收尾
                applyUsm(outRgb, 0.35)

                Imgproc.cvtColor(outRgb, outRgba, Imgproc.COLOR_RGB2RGBA)
                val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(outRgba, out)
                return out
            } finally {
                outRgb.release()
            }
        } finally {
            srcMat.release(); rgb.release(); outRgba.release()
        }
    }

    private fun fillInputBuffer(patch: Mat, buffer: ByteBuffer) {
        buffer.rewind()
        val data = ByteArray(patch.total().toInt() * patch.channels())
        patch.get(0, 0, data)
        for (v in data) {
            buffer.putFloat((v.toInt() and 0xFF) / 255f)
        }
    }

    private fun bufferToMat(buffer: ByteBuffer, size: Int): Mat {
        buffer.rewind()
        val mat = Mat(size, size, org.opencv.core.CvType.CV_8UC3)
        val data = ByteArray(size * size * 3)
        for (i in data.indices) {
            val f = buffer.float
            data[i] = (f.coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        mat.put(0, 0, data)
        return mat
    }

    // ---------------- 经典路径 ----------------

    /**
     * Lanczos 重采样 + 多轮反锐化。
     *
     * 纯双三次插值放大后画面会发糊；Lanczos 保留更多高频，
     * 再叠加一次有限幅度的 USM，能在不产生明显光晕的前提下提升文字锐度。
     */
    private fun upscaleClassic(src: Bitmap, scale: Int, onProgress: (Float) -> Unit): Bitmap {
        onProgress(0.15f)

        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)

        // 先做一轮 2x，大倍数时迭代替代以获得更好的中间结果
        var current = srcMat
        var factor = 1
        try {
            while (factor < scale) {
                val step = minOf(2, scale / factor)
                val next = Mat()
                Imgproc.resize(
                    current, next,
                    Size(current.cols().toDouble() * step, current.rows().toDouble() * step),
                    0.0, 0.0, Imgproc.INTER_LANCZOS4
                )
                if (current !== srcMat) current.release()
                current = next
                factor *= step
                onProgress(0.15f + 0.6f * (factor.toFloat() / scale))
            }

            // 放大后再锐化，避免把插值噪声一起放大
            sharpenForText(current, scale)

            // current 本身就是 RGBA（且是连续的），直接交给 matToBitmap 即可。
            //
            // 这里原本写了一句 Imgproc.cvtColor(current, outRgba, Imgproc.COLOR_RGBA2RGBA)，
            // 但 OpenCV 并没有 COLOR_RGBA2RGBA 这个常量（已用 javap 核对 4.10.0 的
            // Imgproc：只有 RGBA2BGR / RGBA2BGRA / RGBA2GRAY / RGBA2RGB 等，无 RGBA2RGBA）。
            // 而且"RGBA 转 RGBA"本身就是无操作，去掉更干净，
            // 也省掉一次全图拷贝和一块 Mat 的释放责任。
            val out = Bitmap.createBitmap(current.cols(), current.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(current, out)

            onProgress(1f)
            return out
        } finally {
            if (current !== srcMat) current.release()
            srcMat.release()
        }
    }

    /** 针对文字的行锐化：轻微 USM + 局部对比度提升，幅度随放大倍数收敛。 */
    private fun sharpenForText(mat: Mat, scale: Int) {
        val amount = if (scale >= 4) 0.45 else 0.6
        applyUsm(mat, amount)

        // Y 通道做一次轻微对比度拉伸，让字迹从背景里"浮"出来
        val channels = ArrayList<Mat>(4)
        Core.split(mat, channels)
        try {
            for (i in 0 until minOf(3, channels.size)) {
                // dst = src * 1.08 - 10。
                // ★ 之前写的是 Core.addWeighted(channels[i], 1.08, Mat(), 0.0, -10.0, channels[i])：
                //   把空 Mat(0x0) 当第二个操作数，OpenCV 直接抛
                //   (-209) Sizes of input arguments do not match ... in function 'arithm_op'。
                //   触发路径：选"超清/极清"档点"加入文档"→ upscale → sharpenForText，
                //   100% 必现（预览不放大所以预览没事）。真机录屏定位于 v1.5.1。
                //   convertTo 就是 src*alpha + beta 的饱和转换，语义相同、少一个参数、更快。
                channels[i].convertTo(channels[i], channels[i].type(), 1.08, -10.0)
            }
            Core.merge(channels, mat)
        } finally {
            channels.forEach { it.release() }
        }
    }

    private fun applyUsm(mat: Mat, amount: Double) {
        val blurred = Mat()
        Imgproc.GaussianBlur(mat, blurred, Size(0.0, 0.0), 1.0)
        Core.addWeighted(mat, 1.0 + amount, blurred, -amount, 0.0, mat)
        blurred.release()
    }

    // ---------------- 模型加载 ----------------

    private fun tryLoadModel(): Interpreter? {
        for (name in MODEL_CANDIDATES) {
            val ok = runCatching {
                appContext.assets.openFd(name).use { fd ->
                    FileInputStream(fd.fileDescriptor).use { fis ->
                        val mapped = fis.channel.map(
                            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
                        )
                        val opts = Interpreter.Options().apply {
                            setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                        }
                        Interpreter(mapped, opts)
                    }
                }
            }.getOrNull()
            if (ok != null) {
                Log.i(TAG, "已加载超分模型：$name")
                return ok
            }
        }
        return null
    }

    /** 释放原生资源，Activity 销毁时调用。 */
    fun close() {
        interpreter?.close()
        interpreter = null
        activePath = Path.CLASSIC
    }

    companion object {
        private const val TAG = "SuperResolution"

        /** 放大倍数由模型决定，默认 2x。 */
        const val SCALE_FACTOR = 2

        /** 无模型时单个推理块边长。 */
        private const val DEFAULT_PATCH = 128

        /**
         * 按优先级查找的模型文件名。
         * 把任意一个放进 app/src/main/assets/ 即自动启用神经超分。
         */
        val MODEL_CANDIDATES = listOf(
            "sr_2x.tflite",
            "super_resolution.tflite",
            "edsr_2x.tflite",
            "lapsrn_2x.tflite"
        )
    }
}
