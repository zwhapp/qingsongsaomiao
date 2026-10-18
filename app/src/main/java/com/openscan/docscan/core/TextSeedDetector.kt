package com.openscan.docscan.core

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * 文本种子 + 纸面亮度区域生长的文档区域检测（纯 OpenCV，无模型文件、无许可负担）。
 *
 * 两阶段：
 *   1) **文本种子** —— blackhat 提取比局部背景暗的细结构（=文字笔画）→
 *      高斯模糊得到"文字密度场" → 再阈值 + 形态学整形 → 连通域过滤，
 *      得到"文字聚集在哪"的掩码；
 *   2) **纸面生长** —— 在 15% 分辨率上做局部最大值（dilate）近似纸面光照场，
 *      取"亮度 ≥ 92% 局部纸面"的像素做连通域，选与文本种子重叠最大的那块，
 *      即为整张纸的轮廓 → 四边形拟合。
 *
 * 定位：检测链的**第二级**（第一级是 ML 分割，第三级是传统边缘检测）。
 *   · 自用版内置了 ML 模型，本检测器是 ML 未检出时的主力备份；
 *   · 合规版（v1.4.0，不打包模型）里它是第一级。
 * 为什么需要它：传统边缘检测依赖"文档边界是清晰直线"这一假设，对书页、
 * 花纹背景实测 2/5 直接失败。本检测器对单页场景实测与 ML 基准平均 IoU **0.786**、
 * 5/5 从不失败，耗时 ~30ms（grabCut 同等精度要 2.8s，快 75 倍）。
 *
 * 已知局限：摊开的书会把左右两页一并圈入（ML 模型按"单页文档"学习只圈一页），
 * 这正是 ML 优先的原因之一。用户可在裁剪界面拖角点修正，不影响可用性。
 * 另一局限：纯图片/无文字页面没有文本种子 → 返回 null → 回退传统边缘检测。
 *
 * 离线验证与参数依据：tools/train/compare3way.py（GPL / 重训 / 文本种子 三方对比，
 * 同时验证了"ML 优先"的排序）与 tools/alt_compare.py（grow_quad，ratio=0.92、
 * est=0.15，ratio 参数网格扫描 0.80~0.96 中 0.92 在精度与边界贴合上最优）。
 *
 * 线程安全：所有 Mat 均为方法局部变量，无可共享状态。
 */
class TextSeedDetector {

    var lastError: String? = null
        private set

    /**
     * 检测文档区域，返回四边形（原图坐标系）。
     *
     * @return 检测失败（画面中没有文字 / 没有可信纸面亮区）时返回 null，
     *         调用方应继续尝试下一级检测路径。
     */
    fun detectQuad(src: Bitmap): DocumentGeometry.Quad? {
        lastError = null
        // 参数都是按 ~1000px 页面调的，超长边先缩
        val scaled = maxOf(src.width, src.height) > WORK_MAX_EDGE
        val k = if (scaled) WORK_MAX_EDGE.toFloat() / maxOf(src.width, src.height) else 1f
        val work = if (scaled) {
            Bitmap.createScaledBitmap(
                src,
                max((src.width * k).toInt(), 1),
                max((src.height * k).toInt(), 1),
                true
            )
        } else src
        val scaleX = src.width.toDouble() / work.width
        val scaleY = src.height.toDouble() / work.height

        val gray = Mat()
        val bh = Mat()
        val strokes = Mat()
        val density = Mat()
        val blocks = Mat()
        val textMask = Mat(work.height, work.width, CvType.CV_8U)
        textMask.setTo(Scalar(0.0))
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        var kernel: org.opencv.core.Mat? = null
        try {
            val rgba = Mat()
            Utils.bitmapToMat(work, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()

            // ---------- 阶段 1：文本种子 ----------
            // blackhat：提取"比局部背景暗的小结构"，即文字笔画。
            // 15×15 核 ≈ 笔画尺度的两倍，只保留细暗结构、忽略大片阴影。
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            Imgproc.morphologyEx(gray, bh, Imgproc.MORPH_BLACKHAT, kernel)
            kernel.release()
            Imgproc.threshold(bh, strokes, STROKE_THR, 255.0, Imgproc.THRESH_BINARY)

            // 文字密度场：笔画模糊成块，再阈值得到"文字聚集区"
            Imgproc.GaussianBlur(strokes, density, Size(61.0, 61.0), 0.0)
            Imgproc.threshold(density, blocks, DENSITY_THR, 255.0, Imgproc.THRESH_BINARY)
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(31.0, 31.0))
            Imgproc.morphologyEx(blocks, blocks, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(blocks, blocks, Imgproc.MORPH_OPEN, kernel)
            kernel.release()

            val w = work.width
            val h = work.height
            val imageArea = w.toDouble() * h

            val nBlocks = Imgproc.connectedComponentsWithStats(blocks, labels, stats, centroids, 8)
            val statBuf = IntArray(5)
            val blockLabels = ArrayList<Int>()
            for (i in 1 until nBlocks) {
                stats.get(i, 0, statBuf)
                if (statBuf[4] >= imageArea * MIN_BLOCK_AREA &&
                    statBuf[2] >= 10 && statBuf[3] >= 6
                ) blockLabels.add(i)
            }
            if (blockLabels.isEmpty()) {
                lastError = "未检测到文字块（纯图片页请手动框选）"
                return null
            }

            // 合成文本掩码：所有合格块按标签逐个 compare 再 or（块数少，开销可忽略）
            val eq = Mat()
            for (lab in blockLabels) {
                Core.compare(labels, Scalar(lab.toDouble()), eq, Core.CMP_EQ)
                Core.bitwise_or(textMask, eq, textMask)
            }
            eq.release()

            // ---------- 阶段 2：纸面亮度区域生长 ----------
            // 光照场是低频信号：在 15% 分辨率上取局部最大值（dilate 9×9 ≈ 原图 60px）
            // 再放大回来，等效"每个位置周围最亮处的亮度"，即局部纸面。
            val estW = max((w * BG_EST_SCALE).toInt(), 8)
            val estH = max((h * BG_EST_SCALE).toInt(), 8)
            val small = Mat()
            Imgproc.resize(gray, small, Size(estW.toDouble(), estH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val paperSmall = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            Imgproc.dilate(small, paperSmall, kernel)
            kernel.release()
            small.release()
            Imgproc.GaussianBlur(paperSmall, paperSmall, Size(0.0, 0.0), 3.0)
            val paper = Mat()
            Imgproc.resize(paperSmall, paper, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            paperSmall.release()

            // pm = (gray >= paper × 0.92)。阈值取局部纸面的 92%：
            // 过低（0.8）会把纸面阴影边缘外的桌面也吞进来，过高（0.96）会把
            // 纸边卷曲的微暗区切掉。0.92 在参数网格扫描中精度最优。
            val g32 = Mat()
            gray.convertTo(g32, CvType.CV_32F)
            val thr = Mat()
            paper.convertTo(thr, CvType.CV_32F)
            paper.release()
            Core.multiply(thr, Scalar(PAPER_RATIO), thr)
            val paperMask = Mat()
            Core.compare(g32, thr, paperMask, Core.CMP_GE)
            g32.release()
            thr.release()

            val nRegions = Imgproc.connectedComponentsWithStats(paperMask, labels, stats, centroids, 8)
            paperMask.release()

            // 与文本种子重叠最大的亮连通域 = 纸面。
            // 一次遍历统计重叠（O(总像素)），避免逐连通域全图扫描的 O(块数×总像素)。
            val labelsArr = IntArray(w * h)
            labels.get(0, 0, labelsArr)
            val maskArr = ByteArray(w * h)
            textMask.get(0, 0, maskArr)
            val overlap = IntArray(nRegions)
            for (i in labelsArr.indices) {
                if (maskArr[i].toInt() != 0) overlap[labelsArr[i]]++
            }
            var best = -1
            var bestOv = 0
            for (i in 1 until nRegions) {
                stats.get(i, 0, statBuf)
                if (statBuf[4] < imageArea * MIN_REGION_AREA) continue
                if (overlap[i] > bestOv) {
                    bestOv = overlap[i]
                    best = i
                }
            }
            if (best < 0) {
                lastError = "纸面亮区与文字块无重叠"
                return null
            }

            val fg = Mat()
            Core.compare(labels, Scalar(best.toDouble()), fg, Core.CMP_EQ)
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
            Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val contours = ArrayList<MatOfPoint>()
            val fgClone = fg.clone()
            try {
                Imgproc.findContours(fgClone, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            } finally {
                fgClone.release()
            }
            if (contours.isEmpty()) {
                lastError = "纸面区域无轮廓"
                return null
            }
            val bestC = contours.maxByOrNull { Imgproc.contourArea(it) }
            val areaRatio = (bestC?.let { Imgproc.contourArea(it) } ?: 0.0) / imageArea
            if (bestC == null || areaRatio < MIN_QUAD_AREA || areaRatio > MAX_QUAD_AREA) {
                contours.forEach { it.release() }
                lastError = "纸面区域面积占比 %.1f%% 超出可信范围（10%%~97%%）".format(areaRatio * 100)
                return null
            }

            // 轮廓 → 四边形：4 点直接用；5~12 点退化用最小外接矩形
            val c2f = MatOfPoint2f(*bestC.toArray())
            val approx = MatOfPoint2f()
            try {
                val peri = Imgproc.arcLength(c2f, true)
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
                val pts = approx.toArray()
                val quad = when {
                    pts.size == 4 -> DocumentGeometry.orderCorners(pts)
                    pts.size in 5..12 -> {
                        val rect = Imgproc.minAreaRect(c2f)
                        val box = arrayOfNulls<org.opencv.core.Point>(4)
                        rect.points(box)
                        DocumentGeometry.orderCorners(box.requireNoNulls())
                    }
                    else -> null
                }
                if (quad == null) {
                    lastError = "纸面轮廓无法拟合四边形（顶点数=%d）".format(pts.size)
                    return null
                }
                return DocumentGeometry.Quad(
                    org.opencv.core.Point(quad.tl.x * scaleX, quad.tl.y * scaleY),
                    org.opencv.core.Point(quad.tr.x * scaleX, quad.tr.y * scaleY),
                    org.opencv.core.Point(quad.br.x * scaleX, quad.br.y * scaleY),
                    org.opencv.core.Point(quad.bl.x * scaleX, quad.bl.y * scaleY)
                )
            } finally {
                c2f.release()
                approx.release()
                contours.forEach { it.release() }
            }
        } finally {
            gray.release(); bh.release(); strokes.release(); density.release()
            blocks.release(); textMask.release(); labels.release()
            stats.release(); centroids.release(); kernel?.release()
        }
    }

    companion object {
        private const val WORK_MAX_EDGE = 1200

        /** 笔画提取阈值：blackhat 响应低于此值的视为背景。 */
        private const val STROKE_THR = 20.0

        /** 文字密度阈值：模糊后密度低于此值的不算文字块。 */
        private const val DENSITY_THR = 12.0

        /** 光照场估计分辨率（相对长图短边比例）。 */
        private const val BG_EST_SCALE = 0.15

        /** 纸面判定阈值：亮度 ≥ 局部纸面 × 此值才算纸面像素。 */
        private const val PAPER_RATIO = 0.92

        /** 文本块最小面积占比。 */
        private const val MIN_BLOCK_AREA = 0.004

        /** 纸面亮连通域最小面积占比。 */
        private const val MIN_REGION_AREA = 0.05

        /** 四边形可信面积区间。 */
        private const val MIN_QUAD_AREA = 0.10
        private const val MAX_QUAD_AREA = 0.97
    }
}
