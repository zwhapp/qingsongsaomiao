package com.openscan.docscan.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 内置自检。
 *
 * 起因：用户报告"能装但功能不生效（圆点拖不动、检测不到边缘、去阴影无效果）"，
 * 而电脑无法连手机、抓不到 logcat。原来的实现把所有异常都 runCatching 吞掉，
 * 界面上看不出任何异常，只能靠猜。
 *
 * 这个自检把每一层能力拆开单独验证，失败时给出**具体到某一个 OpenCV 函数**的原因，
 * 让用户直接把屏幕上的文字反馈出来，无需 adb。
 *
 * 覆盖三层：
 *   1. 加载层   —— libopencv_java4.so 能否被 System.loadLibrary 装进来
 *   2. 桥接层   —— 一个最小 Mat 运算能否跑通（验证 JNI 方法表是否完整）
 *   3. 业务层   —— 去阴影 / 边缘检测各自能否在真实尺寸图像上产出结果
 */
object SelfCheck {

    private const val TAG = "SelfCheck"

    data class Item(
        val name: String,
        val passed: Boolean,
        val detail: String
    )

    data class Report(
        val items: List<Item>,
        val abi: String,
        val androidVersion: String,
        val deviceModel: String,
        /** 应用版本名。用于确认用户装的是哪个构建 —— 排查"改了但没生效"的第一步。 */
        val appVersion: String
    ) {
        val allPassed: Boolean get() = items.all { it.passed }
    }

    suspend fun run(context: Context): Report = withContext(Dispatchers.Default) {
        val items = mutableListOf<Item>()

        // --- 1. 加载层 ---
        val loaded = OpenCvLoader.ensureLoaded()
        items += Item(
            name = "OpenCV 原生库加载",
            passed = loaded,
            detail = if (loaded) {
                "成功（${OpenCVLoader::class.java.`package`?.name}）"
            } else {
                "失败：libopencv_java4.so 未能加载。" +
                    "若安装包只含 arm 架构而设备是 x86，就会出现这种情况。"
            }
        )

        // 后续检查都依赖 opencv_java4，没加载就没必要继续
        if (!loaded) {
            items += Item("后续检查", false, "已跳过：原生库未加载")
            return@withContext buildReport(items, context)
        }

        // --- 2. 桥接层：最小 JNI 往返 ---
        runCatching {
            val a = Mat.ones(Size(8.0, 8.0), CvType.CV_8UC3)
            val b = Mat()
            Imgproc.cvtColor(a, b, Imgproc.COLOR_BGR2GRAY)
            val ok = b.rows() == 8 && b.cols() == 8
            a.release(); b.release()
            check(ok) { "返回尺寸不符" }
        }.onSuccess {
            items += Item("JNI 桥接", true, "Mat/cvtColor 往返正常")
        }.onFailure { e ->
            items += Item("JNI 桥接", false, "失败：${e.describe()}")
        }

        // --- 3. 去阴影核心：形态学 + 背景归一化 ---
        runCatching {
            val test = makeTestImage(320, 240)
            val out = ScanEnhancer.enhance(test, ScanEnhancer.Mode.DE_SHADOW)
            // 判据：输出不应与输入像素级完全相同（完全相同说明这一步实际没做事）
            val changed = diffRatio(test, out)
            test.recycle(); out.recycle()
            check(changed > 0.01) {
                "输出与原图差异仅 ${(changed * 100).format1()}%，几乎没生效"
            }
            "输出与原图差异 ${(changed * 100).format1()}%，效果已产生"
        }.onSuccess { d ->
            items += Item("去阴影算法", true, d)
        }.onFailure { e ->
            items += Item("去阴影算法", false, "失败：${e.describe()}")
        }

        // --- 4. 边缘检测 ---
        runCatching {
            val test = makeTestDocument(480, 640)
            val quad = DocumentGeometry.detectDocumentQuad(test)
            test.recycle()
            if (quad == null) {
                "未检出四边形（合成图上应当能检出，说明检测链路有问题）"
            } else {
                "检出四边形 左上(${quad.tl.x.format0()}, ${quad.tl.y.format0()}) " +
                    "右下(${quad.br.x.format0()}, ${quad.br.y.format0()})"
            }
        }.onSuccess { d ->
            // 合成图检不出虽然不算崩溃，但要如实标出来
            val detected = d.startsWith("检出")
            items += Item("边缘检测", detected, d)
        }.onFailure { e ->
            items += Item("边缘检测", false, "失败：${e.describe()}")
        }

        // --- 5. 透视矫正 ---
        runCatching {
            val test = makeTestDocument(480, 640)
            val quad = DocumentGeometry.Quad.full(test.width, test.height)
            val warped = DocumentGeometry.warpToRect(test, quad)
            val ok = warped.width > 0 && warped.height > 0
            test.recycle(); warped.recycle()
            check(ok) { "输出尺寸为 0" }
            "输出 ${warped.width}×${warped.height}"
        }.onSuccess { d ->
            items += Item("透视矫正", true, d)
        }.onFailure { e ->
            items += Item("透视矫正", false, "失败：${e.describe()}")
        }

        // --- 6. ML 分割模型（自用版内置，检测链第一级）---
        // 目的是确认"模型确实进了包、能加载、能跑推理"，而不是验证它在合成图上的精度。
        //
        // 判定必须分构建变体，否则合规版（本就不该有模型）会被误报成失败：
        //   · 自用版（versionName 含 selfuse）—— 期望模型在包内。缺失即打包失误：
        //     那会悄悄退化成文本种子检测，用户只觉得"自动检测没以前准"。
        //   · 合规版（对外分发构建）—— 期望模型不在包内。意外存在反而是合规问题
        //     （GPL-3.0 模型随包分发），必须暴露而不是放过。
        runCatching {
            val expectModel = packageVersion(context).contains("selfuse", ignoreCase = true)
            val seg = DocSegmenter(context)
            try {
                val test = makeTestDocument(640, 480)
                val quad = try {
                    seg.detectQuad(test)
                } finally {
                    test.recycle()
                }
                val (ok, msg) = when {
                    !expectModel && seg.modelMissing ->
                        true to "正常：合规构建不含分割模型，检测链从文本种子起步（预期行为）"
                    !expectModel ->
                        false to "异常：合规构建不应包含 GPL-3.0 分割模型，请检查 assets 打包内容"
                    seg.modelMissing ->
                        false to "失败：自用版应内置模型但 assets 中缺失（打包失误），已降级到文本种子检测"
                    seg.lastError != null ->
                        false to "失败：模型加载失败 —— ${seg.lastError}"
                    quad != null ->
                        true to "正常：模型已加载，合成图检出四边形"
                    else ->
                        true to "正常：模型已加载（合成图未检出，不影响判定）"
                }
                items += Item("ML 分割模型", ok, msg)
            } finally {
                seg.release()
            }
        }.onFailure { e ->
            items += Item("ML 分割模型", false, "失败：${e.describe()}")
        }

        // --- 7. 文本种子检测（TextSeedDetector，检测链第二级）---
        // 验证完整链路：笔画提取 → 文字密度场 → 纸面亮度生长 → 四边形拟合。
        // 合成图是"暗底 + 亮纸 + 若干黑色文字条"，检测器应圈出整张纸。
        // 它在自用版里是 ML 未检出时的备份，在合规版里是第一级，所以无论
        // 模型是否打包都必须可用 —— 这一项失败说明降级路径也断了。
        runCatching {
            val detector = TextSeedDetector()
            val test = makeTestDocument(640, 480)
            val quad = try {
                detector.detectQuad(test)
            } finally {
                test.recycle()
            }
            if (quad == null) {
                "未检出：${detector.lastError ?: "未知原因"}"
            } else {
                val wq = quad.br.x - quad.tl.x
                val hq = quad.bl.y - quad.tl.y
                val cover = (wq * hq) / (640.0 * 480.0)
                // 合成图的"纸面"占 70%×76% ≈ 53%，亮度生长会基本贴合纸面边界。
                // 区间外说明被暗底整体吞掉或只圈到文字条。
                if (cover < 0.35 || cover > 0.90) {
                    "检出但占比异常（%.0f%%，合成图预期 35~90%%）".format(cover * 100)
                } else {
                    "正常：检出四边形，覆盖 %.0f%%".format(cover * 100)
                }
            }
        }.onSuccess { d ->
            items += Item("文本种子检测", d.startsWith("正常"), d)
        }.onFailure { e ->
            items += Item("文本种子检测", false, "失败：${e.describe()}")
        }

        buildReport(items, context)
    }

    private fun buildReport(items: List<Item>, context: Context) = Report(
        items = items,
        abi = Build.SUPPORTED_ABIS.joinToString(", "),
        androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        appVersion = packageVersion(context)
    )

    /** 取本应用 versionName，取不到时返回 "?"。见第 6 项：它同时用于识别构建变体。 */
    private fun packageVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
    /** 合成一张带亮暗渐变的图，用于验证增强算法确实改变了像素。 */
    private fun makeTestImage(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 左上暗、右下亮，模拟阴影
                val v = 60 + 180 * ((x + y).toFloat() / (w + h))
                val c = v.toInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 合成一张"浅色纸 + 深色内部矩形"，模拟文档，用于验证边缘检测。 */
    private fun makeTestDocument(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inside = x > w * 0.15 && x < w * 0.85 && y > h * 0.12 && y < h * 0.88
                val c = if (inside) 245 else 40
                px[y * w + x] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
            }
        }
        // 文字条：模拟若干行文字。文本种子检测器（blackhat→密度场）依赖
        // "纸上有笔画"这一特征定位纸面，纯白矩形检不出来，必须有暗色细条。
        val rowH = maxOf((h * 0.06).toInt(), 8)
        val lineH = maxOf((rowH * 0.45).toInt(), 3)
        val x0 = (w * 0.22).toInt()
        val x1 = (w * 0.78).toInt()
        var y0 = (h * 0.18).toInt()
        while (y0 < h * 0.80) {
            for (yy in y0 until minOf(y0 + lineH, h)) {
                for (xx in x0 until x1) {
                    px[yy * w + xx] = (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 30
                }
            }
            y0 += rowH
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 两张同尺寸位图的像素差异比例（0~1）。 */
    private fun diffRatio(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        val n = a.width * a.height
        val pa = IntArray(n)
        val pb = IntArray(n)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var differing = 0
        for (i in 0 until n) {
            val da = pa[i] and 0xFF
            val db = pb[i] and 0xFF
            if (kotlin.math.abs(da - db) > 8) differing++
        }
        return differing.toDouble() / n
    }

    private fun Double.format1() = String.format("%.1f", this)
    private fun Double.format0() = String.format("%.0f", this)
}
