package com.openscan.docscan.core

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 文档几何处理：边缘检测、四边形拟合、透视矫正。
 *
 * 全程本地计算，不依赖网络与云端服务。
 */
object DocumentGeometry {

    /** 四边形四个角，顺序固定为：左上、右上、右下、左下。 */
    data class Quad(val tl: Point, val tr: Point, val br: Point, val bl: Point) {
        fun toArray(): Array<Point> = arrayOf(tl, tr, br, bl)

        fun toRectF(scale: Float = 1f): RectF {
            val left = min(min(tl.x, bl.x), min(tr.x, br.x)).toFloat() * scale
            val top = min(min(tl.y, tr.y), min(bl.y, br.y)).toFloat() * scale
            val right = max(max(tl.x, bl.x), max(tr.x, br.x)).toFloat() * scale
            val bottom = max(max(tl.y, tr.y), max(bl.y, br.y)).toFloat() * scale
            return RectF(left, top, right, bottom)
        }

        companion object {
            fun fromRectF(r: RectF): Quad = Quad(
                Point(r.left.toDouble(), r.top.toDouble()),
                Point(r.right.toDouble(), r.top.toDouble()),
                Point(r.right.toDouble(), r.bottom.toDouble()),
                Point(r.left.toDouble(), r.bottom.toDouble())
            )

            /** 覆盖整幅图像的全幅四边形，作为检测失败时的兜底。 */
            fun full(width: Int, height: Int, inset: Double = 0.0): Quad {
                val dx = width * inset
                val dy = height * inset
                return Quad(
                    Point(dx, dy),
                    Point(width - 1 - dx, dy),
                    Point(width - 1 - dx, height - 1 - dy),
                    Point(dx, height - 1 - dy)
                )
            }
        }
    }

    /**
     * 在图像中寻找最可能的文档四边形。
     *
     * 流程：降采样 → 灰度 → 高斯模糊 → Canny 边缘 → 形态学闭运算连接断边
     *      → 轮廓提取 → 多边形逼近 → 按面积/凸性/矩形度打分取最优。
     *
     * 真实场景（书桌、桌布、杂物背景）远比合成图复杂：单一参数组合
     * 经常检不出。这里做**分级重试**——先从严，逐轮放宽，第一轮命中就用。
     *
     * @return 检测到的四边形（已映射回原图坐标系）；置信度不足时返回 null。
     */
    fun detectDocumentQuad(src: Bitmap): Quad? {
        // 从严到宽依次尝试。第一轮的参数能滤掉大部分误判；
        // 真实照片背景杂乱时，后几轮放宽 convex/矩形度要求换召回率。
        val stages = listOf(
            StageParams(cannyLow = 60.0, cannyHigh = 180.0, minScore = 0.28, requireConvex = true),
            StageParams(cannyLow = 40.0, cannyHigh = 120.0, minScore = 0.20, requireConvex = true),
            StageParams(cannyLow = 30.0, cannyHigh = 90.0, minScore = 0.14, requireConvex = false)
        )
        for (stage in stages) {
            val q = detectOnce(src, stage)
            if (q != null) return q
        }
        return null
    }

    /** 单轮检测参数。 */
    private data class StageParams(
        val cannyLow: Double,
        val cannyHigh: Double,
        val minScore: Double,
        val requireConvex: Boolean
    )

    private fun detectOnce(src: Bitmap, p: StageParams): Quad? {
        val work = downscaleForDetection(src)
        val scaleX = src.width.toDouble() / work.width
        val scaleY = src.height.toDouble() / work.height

        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val closed = Mat()
        var kernel: Mat? = null

        try {
            Utils.bitmapToMat(work, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            // 轻度模糊抑制纸张纹理噪点
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            Imgproc.Canny(blurred, edges, p.cannyLow, p.cannyHigh, 3, true)

            // 闭运算把断开的纸张边缘连成完整轮廓
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.dilate(closed, closed, kernel, Point(-1.0, -1.0), 1)

            val contours = ArrayList<MatOfPoint>()
            // findContours 会改写输入，所以传副本，副本用完立即释放
            val edgesForContours = closed.clone()
            try {
                Imgproc.findContours(
                    edgesForContours, contours, Mat(),
                    Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
                )
            } finally {
                edgesForContours.release()
            }

            val imageArea = (work.width * work.height).toDouble()
            var best: Quad? = null
            var bestScore = 0.0

            try {
                for (contour in contours) {
                    val area = Imgproc.contourArea(contour)
                    // 文档应占据画面相当比例，过滤零星噪点
                    if (area < imageArea * MIN_AREA_RATIO) continue

                    val c2f = MatOfPoint2f(*contour.toArray())
                    val approx = MatOfPoint2f()
                    val convexCheck = MatOfPoint()
                    try {
                        val peri = Imgproc.arcLength(c2f, true)
                        Imgproc.approxPolyDP(c2f, approx, APPROX_EPSILON * peri, true)

                        val pts = approx.toArray()
                        if (pts.size == 4) {
                            if (!p.requireConvex || Imgproc.isContourConvex(convexCheck.also { it.fromArray(*pts) })) {
                                val ordered = orderCorners(pts)
                                val score = scoreQuad(ordered, imageArea, area)
                                if (score > bestScore) {
                                    bestScore = score
                                    best = ordered
                                }
                            }
                        } else if (pts.size in 5..12) {
                            // 翻开的书、有弧度的纸张边，逼近结果常不是 4 点而是 5~12 点。
                            // 用最小外接矩形兜底 —— 对"接近矩形"的轮廓这已足够好。
                            // 注意 RotatedRect 没有 toArray()，取角点用 points(Point[])。
                            val rect = Imgproc.minAreaRect(c2f)
                            val box = arrayOfNulls<Point>(4)
                            rect.points(box)
                            val quadPts = box.requireNoNulls()
                            if (!p.requireConvex || Imgproc.isContourConvex(convexCheck.also { it.fromArray(*quadPts) })) {
                                val ordered = orderCorners(quadPts)
                                val score = scoreQuad(ordered, imageArea, area) * 0.9
                                if (score > bestScore) {
                                    bestScore = score
                                    best = ordered
                                }
                            }
                        }
                    } finally {
                        approx.release()
                        c2f.release()
                        convexCheck.release()
                    }
                }
            } finally {
                // 未被采用的轮廓必须逐个释放，否则 native 内存持续增长
                contours.forEach { it.release() }
            }

            if (best == null || bestScore < p.minScore) return null

            // 映射回原图坐标
            return Quad(
                Point(best.tl.x * scaleX, best.tl.y * scaleY),
                Point(best.tr.x * scaleX, best.tr.y * scaleY),
                Point(best.br.x * scaleX, best.br.y * scaleY),
                Point(best.bl.x * scaleX, best.bl.y * scaleY)
            )
        } finally {
            rgba.release(); gray.release(); blurred.release()
            edges.release(); closed.release(); kernel?.release()
        }
    }

    /**
     * 按输入四点做透视变换，把倾斜拍摄的纸张拉正为规整矩形。
     *
     * 输出尺寸由四边形边长推算，保持原始长宽比，并限制最大边以免内存爆掉。
     */
    fun warpToRect(src: Bitmap, quad: Quad, maxEdge: Int = DEFAULT_MAX_EDGE): Bitmap {
        val srcMat = Mat()
        val dstMat = Mat()
        val srcPts = MatOfPoint2f(*quad.toArray())
        val dstPts = MatOfPoint2f()

        try {
            Utils.bitmapToMat(src, srcMat)

            val (outW, outH) = targetSize(quad, maxEdge)

            dstPts.fromArray(
                Point(0.0, 0.0),
                Point(outW - 1.0, 0.0),
                Point(outW - 1.0, outH - 1.0),
                Point(0.0, outH - 1.0)
            )

            val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            // 注意：OpenCV 的 Size 构造函数签名是 Size(double, double)，
            // 而 outW/outH 是 Int（targetSize 里做了 .toInt()）。
            // Kotlin 不会隐式把 Int 提升成 Double，必须显式 toDouble()，
            // 否则报 "Argument type mismatch: actual type is 'kotlin.Int', but 'kotlin.Double' was expected"。
            Imgproc.warpPerspective(
                srcMat, dstMat, transform, Size(outW.toDouble(), outH.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(255.0, 255.0, 255.0)
            )
            transform.release()

            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, out)
            return out
        } finally {
            srcMat.release(); dstMat.release()
            srcPts.release(); dstPts.release()
        }
    }

    /** 根据四边形边长推算矫正后的输出尺寸。 */
    fun targetSize(quad: Quad, maxEdge: Int = DEFAULT_MAX_EDGE): Pair<Int, Int> {
        val widthTop = dist(quad.tl, quad.tr)
        val widthBottom = dist(quad.bl, quad.br)
        val heightLeft = dist(quad.tl, quad.bl)
        val heightRight = dist(quad.tr, quad.br)

        var outW = max(widthTop, widthBottom)
        var outH = max(heightLeft, heightRight)

        val longest = max(outW, outH)
        if (longest > maxEdge) {
            val k = maxEdge / longest
            outW *= k
            outH *= k
        }
        return Pair(max(outW.toInt(), 1), max(outH.toInt(), 1))
    }

    /**
     * 把任意四点归位为 左上/右上/右下/左下 顺序。
     *
     * 思路：
     *   · x+y 最小 → 左上；x+y 最大 → 右下（对角关系对任何凸四边形都成立）
     *   · 剩下两点用 y-x 区分：该值最小的偏上 → 右上；最大的偏下 → 左下
     *
     * 退化保护：若两次排序取到同一个点（四点共线或严重重合），
     * 说明这个四边形不可用，返回按角度排序的结果兜底。
     */
    fun orderCorners(pts: Array<Point>): Quad {
        val bySum = pts.sortedBy { it.x + it.y }
        val tl = bySum.first()
        val br = bySum.last()

        val remaining = pts.filter { it !== tl && it !== br }
        if (remaining.size != 2) {
            // 退化：按围绕质心的极角排序，保证顺序稳定
            return orderByAngle(pts)
        }

        val byDiff = remaining.sortedBy { it.y - it.x }
        val tr = byDiff.first()
        val bl = byDiff.last()

        // 若剩下两点无法区分上下（极窄或极扁的四边形），退回角度排序
        if (tr === bl) return orderByAngle(pts)

        return Quad(tl, tr, br, bl)
    }

    /** 按围绕质心的极角排序，作为退化情况的稳定兜底。 */
    private fun orderByAngle(pts: Array<Point>): Quad {
        val cx = pts.map { it.x }.average()
        val cy = pts.map { it.y }.average()
        // 屏幕坐标系 y 向下，用 -atan2 得到视觉上的顺时针顺序
        val sorted = pts.sortedBy { -kotlin.math.atan2(it.y - cy, it.x - cx) }
        // 旋转到以左上角开头
        val startIdx = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
        val rotated = List(sorted.size) { sorted[(startIdx + it) % sorted.size] }
        return Quad(rotated[0], rotated[1], rotated[2], rotated[3])
    }

    /**
     * 估计图像中文档的倾斜角（单位：度，正值 = 文字行向右下方倾斜）。
     *
     * ★ v1.5.3 起用"墨迹投影法"替换霍夫直线法。霍夫法的失效案例（真机 v1.5.2）：
     *   拍电脑屏幕上的斜报告，彩色原图上直线检测结果为 0°，输出整页斜 15°；
     *   同一内容在 PC 上却稳定检出 -15° —— 屏幕拍摄的摩尔纹/干扰线让真机上的
     *   霍夫投票偏离了文字方向。投影法只看「亮纸内部的深色笔画」这一最本质的
     *   信号，摩尔纹、JPEG 块效应、背景纹理都进不了墨迹掩膜。
     *
     * 方法：
     *   1. 纸面亮度 = 灰度 85 分位（与 ScanEnhancer 同口径）；
     *   2. 纸面区域 = 最大亮区连通域 → 闭运算填掉文字孔洞 → 腐蚀内缩排除纸边；
     *      ★ 必须先闭运算再腐蚀：直接腐蚀亮像素掩膜会把文字像素排除在
     *      区域外，墨迹掩膜恒空（离线第一版踩过的坑）；
     *   3. 墨迹 = 纸面区域内灰度 < 0.6×纸面 的像素（即文字笔画）；
     *   4. 对墨迹掩膜做 ±45° 旋转搜索，取「行投影高频残差方差」最大的角度。
     *      打分用高频残差而非 var/mean：文字行对齐时行投影呈尖锐周期峰，
     *      而 var/mean 在 ±45° 端有单调几何偏差（墨迹向对角带集中产生假峰，
     *      离线实测把合成斜 15° 的样本判成 +46°）。
     *
     * 只在长边 1000px 的小图上估计、400px 的掩膜上旋转搜索（约 56 次仿射），
     * 全程 ~100ms。
     *
     * 离线验证（tools/train/diag_skew_ink.py）：5 张真实基准图与霍夫旧版
     * 差 ≤2.1°；真机失败案例的黑白输出内容检出 -18.5°；合成旋转 -15°
     * 回归全部命中（±1.5°）。
     *
     * @return 倾斜角；画面里没有可辨认的文本文档时返回 0.0（不旋转）。
     */
    fun estimateSkewAngle(src: Bitmap): Double {
        val work = downscaleForSkew(src)
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(work, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return estimateSkewFromGray(gray)
        } finally {
            rgba.release(); gray.release()
        }
    }

    /**
     * 墨迹投影法核心：输入**灰度图**，返回估计倾斜角（角度约定同上）。
     *
     * 纯 OpenCV、无 Android 依赖 —— tools/jvmcheck 在桌面 JVM 上以与
     * 本文件完全一致的函数文本真实运行本函数（tools/jvmcheck/sync_skew.py 校验）。
     * 背景：v1.5.3 的 reduce 调用把输出深度传到了 rtype 参数位，Python 端
     * 验证了算法逻辑但 Kotlin 代码从未真实执行，真机上 reduce 断言失败、
     * 自动端正被整段跳过 —— Kotlin 端 OpenCV 签名错误只有真实执行才能
     * 暴露，桌面 JVM 冒烟自此成为打包前置项。
     */
    fun estimateSkewFromGray(gray: Mat): Double {
        val ink = Mat()
        try {
            if (!textInkMask(gray, ink)) return 0.0

            // 400px 掩膜上做旋转搜索（分数对分辨率不敏感，纯性能考量）
            val small = Mat()
            try {
                val longest = max(ink.cols(), ink.rows())
                if (longest > 400) {
                    val k = 400.0 / longest
                    Imgproc.resize(
                        ink, small, Size(ink.cols() * k, ink.rows() * k),
                        0.0, 0.0, Imgproc.INTER_NEAREST
                    )
                } else {
                    ink.copyTo(small)
                }

                // 粗扫 ±45° 步长 3°，再在峰值 ±3° 内步长 0.25° 细化
                var bestAngle = 0.0
                var bestScore = -1.0
                var angle = -45.0
                while (angle <= 45.0) {
                    val s = projectionScore(small, angle)
                    if (s > bestScore) { bestScore = s; bestAngle = angle }
                    angle += 3.0
                }
                var fineAngle = bestAngle
                var fineScore = bestScore
                angle = bestAngle - 3.0
                while (angle <= bestAngle + 3.0) {
                    val s = projectionScore(small, angle)
                    if (s > fineScore) { fineScore = s; fineAngle = angle }
                    angle += 0.25
                }
                return fineAngle
            } finally {
                small.release()
            }
        } finally {
            ink.release()
        }
    }

    /**
     * 从灰度图构建「纸面内墨迹」掩膜（墨=255）。
     *
     * [estimateSkewFromGray] 与 [estimateOrientationFromGray] 共用的前置：
     *   1. 纸面亮度 = 灰度 85 分位（与 ScanEnhancer 同口径）；
     *   2. 纸面区域 = 最大亮区连通域 → 闭运算填掉文字孔洞 → 腐蚀内缩排除纸边；
     *      ★ 必须先闭运算再腐蚀：直接腐蚀亮像素掩膜会把文字像素排除在
     *      区域外，墨迹掩膜恒空（离线第一版踩过的坑）；
     *   3. 墨迹 = 纸面区域内灰度 < 0.6×纸面 的像素（即文字笔画）。
     *
     * @return false 表示画面里没有可辨认的文本文档（此时 [inkOut] 内容未定义）
     */
    private fun textInkMask(gray: Mat, inkOut: Mat): Boolean {
        val paper = percentileLevel(gray, 0.85)
        if (paper < 40.0) return false   // 几乎全黑，没有文档

        // 纸面区域：最大亮区 → 闭运算填文字孔洞 → 腐蚀内缩排除纸边过渡带
        val bright = Mat()
        Imgproc.threshold(gray, bright, paper * 0.75, 255.0, Imgproc.THRESH_BINARY)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        var page: Mat? = null
        var interior: Mat? = null
        val inkInv = Mat()
        try {
            val n = Imgproc.connectedComponentsWithStats(bright, labels, stats, centroids)
            if (n <= 1) return false
            var bestLabel = 1
            var bestArea = 0.0
            for (i in 1 until n) {
                // stats 列：0=LEFT 1=TOP 2=WIDTH 3=HEIGHT 4=AREA
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (area > bestArea) { bestArea = area; bestLabel = i }
            }
            page = Mat()
            Core.compare(labels, Scalar(bestLabel.toDouble()), page, Core.CMP_EQ)

            val closeK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
            val erodeK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            Imgproc.morphologyEx(page, page, Imgproc.MORPH_CLOSE, closeK)
            interior = Mat()
            Imgproc.erode(page, interior, erodeK)
            closeK.release(); erodeK.release()

            // 墨迹：纸内深色笔画 = (gray < 0.6*paper) ∩ interior（min 即按位与）
            Imgproc.threshold(gray, inkInv, paper * 0.60, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.min(inkInv, interior, inkOut)

            val inkCount = Core.countNonZero(inkOut)
            if (inkCount < 0.002 * gray.total()) return false   // 墨迹太少，不是文本文档
            return true
        } finally {
            bright.release(); labels.release(); stats.release(); centroids.release()
            page?.release(); interior?.release()
            inkInv.release()
        }
    }

    /**
     * 检测文档是否需要转 90°（页面横躺：原图竖直正向拍摄，矫正后却输出为
     * 横放的 landscape）。返回需要的四分之一转数：0 = 不转，90 = 转 90°。
     *
     * ★ 为什么 ±45° 的倾斜估计抓不到它：文字行竖直时（页面横躺），
     * 墨迹投影的最佳角度落在 ±45° 搜索窗之外，倾斜估计只会给出窗内的
     * 假峰或 0°。方向问题必须用 0°/90° 双假设分别打分：
     *   · score0  = 0° 中心 ±3° 细扫的最大投影分数
     *   · score90 = 90° 中心 ±3° 细扫的最大投影分数
     * 文字行对齐的方向残差方差大（尖锐周期峰），垂直方向是平滑单峰，
     * 因此 score90/score0 > [ORI_TURN_RATIO] 即判定页面横躺。
     *
     * 打分函数与倾斜估计共用 [projectionScore]（高频残差方差），掩膜构建
     * 共用 [textInkMask]。±90 两个方向投影分数相同（一个正立一个倒立），
     * 这里恒返回 +90，正倒由管线里的一次离线 OCR 裁决（见 ScanPipeline）。
     *
     * 离线验证（tools/train/diag_orientation.py，5 案例）：
     *   横躺书页 1.63 / 人为转 90° 5.39 → 判转；正常图最高仅 0.93 → 不转。
     */
    fun estimateOrientation(src: Bitmap): Int {
        val work = downscaleForSkew(src)
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(work, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return estimateOrientationFromGray(gray)
        } finally {
            rgba.release(); gray.release()
        }
    }

    /** [estimateOrientation] 的纯 OpenCV 核心（tools/jvmcheck 同步校验）。 */
    fun estimateOrientationFromGray(gray: Mat): Int {
        val ink = Mat()
        val small = Mat()
        try {
            if (!textInkMask(gray, ink)) return 0

            val longest = max(ink.cols(), ink.rows())
            if (longest > 400) {
                val k = 400.0 / longest
                Imgproc.resize(
                    ink, small, Size(ink.cols() * k, ink.rows() * k),
                    0.0, 0.0, Imgproc.INTER_NEAREST
                )
            } else {
                ink.copyTo(small)
            }

            // 0° 假设：中心 ±3° 细扫（页面正立时文字行的真实角度就在这附近）
            var score0 = 0.0
            var angle = -3.0
            while (angle <= 3.0) {
                val s = projectionScore(small, angle)
                if (s > score0) score0 = s
                angle += 0.25
            }
            // 90° 假设：87~93° 细扫（页面横躺时文字行竖直，投影峰在 90° 附近）
            var score90 = 0.0
            angle = 87.0
            while (angle <= 93.0) {
                val s = projectionScore(small, angle)
                if (s > score90) score90 = s
                angle += 0.25
            }
            // 恒返回 +90：±90 投影分数相同，正倒由管线里的 OCR 裁决
            return if (score90 > ORI_TURN_RATIO * score0) 90 else 0
        } finally {
            ink.release(); small.release()
        }
    }

    /** 灰度直方图 [q] 分位（0~1）。O(256)，比排序快得多。 */
    private fun percentileLevel(gray: Mat, q: Double): Double {
        val hist = Mat()
        try {
            Imgproc.calcHist(
                listOf(gray), org.opencv.core.MatOfInt(0), Mat(), hist,
                org.opencv.core.MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f)
            )
            val target = gray.total() * q
            var acc = 0.0
            for (i in 0 until 256) {
                acc += hist.get(i, 0)[0]
                if (acc >= target) return i.toDouble()
            }
            return 255.0
        } finally {
            hist.release()
        }
    }

    /**
     * 墨迹掩膜旋转 [angleDeg] 度后按行求和，取「减去平滑基线后的残差方差」。
     * 文字行水平时行投影有尖锐周期峰（残差大）；任何几何性假峰（墨迹向
     * 对角带集中）都是平滑单峰（残差小）—— 用这一步滤掉 ±45° 端的退化。
     */
    private fun projectionScore(ink: Mat, angleDeg: Double): Double {
        val w = ink.cols()
        val h = ink.rows()
        val rad = Math.toRadians(angleDeg)
        val c = abs(kotlin.math.cos(rad))
        val s = abs(kotlin.math.sin(rad))
        val nw = (c * w + s * h).toInt() + 2
        val nh = (s * w + c * h).toInt() + 2
        val m = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), angleDeg, 1.0)
        val rot = Mat()
        val proj = Mat()
        val smooth = Mat()
        val resid = Mat()
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        try {
            m.put(0, 2, m.get(0, 2)[0] + nw / 2.0 - w / 2.0)
            m.put(1, 2, m.get(1, 2)[0] + nh / 2.0 - h / 2.0)
            Imgproc.warpAffine(
                ink, rot, m, Size(nw.toDouble(), nh.toDouble()),
                Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0)
            )
            // dim=1：每行求和 → 单列向量。
            // ★ 签名是 reduce(src, dst, dim, rtype, dtype)：第 4 参必须是 REDUCE_*，
            //   第 5 参才是输出深度。v1.5.3 曾把 CV_64F 传到 rtype 位（op=6 非法），
            //   真机上 reduce 直接断言失败，整个端正功能被跳过 —— 因为 Python 端
            //   (numpy) 验证了逻辑、Kotlin 代码却从未真跑过一次。此核心现已拆为
            //   estimateSkewFromGray（纯 OpenCV），由 tools/jvmcheck 在桌面 JVM
            //   用同一份函数文本真实运行后再打包。
            Core.reduce(rot, proj, 1, Core.REDUCE_SUM, org.opencv.core.CvType.CV_64F)
            val smoothRows = max(7, nh / 30)
            Imgproc.boxFilter(
                proj, smooth, org.opencv.core.CvType.CV_64F,
                Size(1.0, smoothRows.toDouble()), Point(-1.0, -1.0), true, Core.BORDER_REPLICATE
            )
            Core.subtract(proj, smooth, resid)
            Core.meanStdDev(resid, mean, std)
            val sd = std.get(0, 0)[0]
            return sd * sd
        } finally {
            m.release(); rot.release(); proj.release()
            smooth.release(); resid.release(); mean.release(); std.release()
        }
    }

    /**
     * 把图像旋转 [angleDeg] 度转正（配合 [estimateSkewAngle] 使用）。
     *
     * 角度符号约定：estimateSkewAngle 返回正值表示文字行向右下倾斜
     * （屏幕坐标系 y 向下，atan2 为正）。要把它转平需要把右侧抬起来，
     * 即视觉上的逆时针 —— Android Matrix.postRotate 正角度是顺时针，
     * 所以这里取负。
     *
     * 用 createBitmap(matrix) 自动扩张画布，旋转不会裁掉四角。
     */
    fun rotateToLevel(src: Bitmap, angleDeg: Double): Bitmap {
        if (abs(angleDeg) < 0.05) return src
        val matrix = Matrix().apply { postRotate(-angleDeg.toFloat()) }
        // 旋转出界的四角必须填白。此前用 Bitmap.createBitmap(..., matrix)，
        // 出界区是透明像素，转 BGR 后就是黑色——端正旋转在纸面四角留下
        // 黑色楔形块，黑白二值化后原样保留（真机对比 WPS 输出观感差距的
        // 来源之一）。白角在 Sauvola 判决下就是纸面，自然消隐。
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(src, matrix, null)
        return out
    }

    /**
     * 裁掉矫正输出四周的"纸外暗带"，返回裁剪后的位图（无需裁剪时原样返回）。
     *
     * 问题来源：quad 比实际纸张略大（文本种子框偏大、ML 分割边缘松）时，
     * 纸外桌面进入矫正输出；加上端正/转正旋转的黑角（已改填白）。这些暗区
     * 在黑白二值化后变成黑斑、黑块、脏边——对比 WPS 的输出（只含纸面、
     * 背景纯白），观感差距主要在这。必须在**增强之前**裁：此时是彩色图，
     * 纸面亮、桌面暗，最可分；一旦二值化，桌面和墨迹都是黑的，无从区分。
     *
     * 算法（Mat 域核心在 [trimDarkEdgesRect]，jvmcheck 与桌面 OpenCV 同步验证）：
     * 对每一边从外向内按 2% 带宽扫描行/列平均亮度，显著暗于纸心（差 ≥45
     * 且绝对值 <105，桌面特征）才内缩；每边最多内缩 6%，总剩余 <60% 放弃。
     * 阈值刻意保守——误裁纸面内容比留一圈黑边糟糕得多。
     */
    fun trimDarkEdges(src: Bitmap): Bitmap {
        val m = Mat()
        val g = Mat()
        try {
            Utils.bitmapToMat(src, m)
            Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY)
            val r = trimDarkEdgesRect(g) ?: return src
            return Bitmap.createBitmap(
                src, r[0], r[1], r[2] - r[0], r[3] - r[1]
            )
        } finally {
            m.release(); g.release()
        }
    }

    // ---- 内部工具 ----

    private fun downscaleForSkew(src: Bitmap): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= SKEW_MAX_EDGE) return src
        val k = SKEW_MAX_EDGE.toFloat() / longest
        val w = max((src.width * k).toInt(), 1)
        val h = max((src.height * k).toInt(), 1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun downscaleForDetection(src: Bitmap): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= DETECT_MAX_EDGE) return src
        val k = DETECT_MAX_EDGE.toFloat() / longest
        val w = max((src.width * k).toInt(), 1)
        val h = max((src.height * k).toInt(), 1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun dist(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    /** 打分：越接近整幅画面、越接近矩形、面积越大，得分越高。 */
    private fun scoreQuad(quad: Quad, imageArea: Double, contourArea: Double): Double {
        val wTop = dist(quad.tl, quad.tr)
        val wBottom = dist(quad.bl, quad.br)
        val hLeft = dist(quad.tl, quad.bl)
        val hRight = dist(quad.tr, quad.br)
        if (wTop < 1.0 || wBottom < 1.0 || hLeft < 1.0 || hRight < 1.0) return 0.0

        // 对边长度一致性
        val wRatio = min(wTop, wBottom) / max(wTop, wBottom)
        val hRatio = min(hLeft, hRight) / max(hLeft, hRight)
        val squareness = wRatio * hRatio

        val areaRatio = contourArea / imageArea
        return squareness * 0.6 + areaRatio * 0.4
    }

    /**
     * 按目标长边等比放大/缩小，使用 Lanczos4 插值。
     *
     * 用于"分辨率档位"里非整数倍的目标（例如 4000 → 6000 是 1.5 倍，
     * 超分模型只吃 2x/4x，这时用插值）。Lanczos4 在文字边缘的锐度
     * 明显优于默认的双线性，是纯 CPU 方案里性价比最高的。
     */
    fun resizeLanczos(src: Bitmap, targetLongEdge: Int): Bitmap {
        val curLong = maxOf(src.width, src.height)
        if (targetLongEdge <= 0 || curLong <= 0) return src
        val f = targetLongEdge.toDouble() / curLong
        val outW = (src.width * f).toInt().coerceAtLeast(1)
        val outH = (src.height * f).toInt().coerceAtLeast(1)
        if (outW == src.width && outH == src.height) return src

        val srcMat = Mat()
        val dstMat = Mat()
        try {
            Utils.bitmapToMat(src, srcMat)
            Imgproc.resize(
                srcMat, dstMat, Size(outW.toDouble(), outH.toDouble()),
                0.0, 0.0, Imgproc.INTER_LANCZOS4
            )
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, out)
            return out
        } finally {
            srcMat.release(); dstMat.release()
        }
    }

    /**
     * [trimDarkEdges] 的 Mat 域核心：在灰度图上计算四周暗带的裁剪矩形。
     *
     * @return `[x0, y0, x1, y1]`（裁剪后区域，右/下开区间）；无需裁剪时返回 null。
     * 该函数与 jvmcheck/InkSkewCore.kt 同名函数**逐字一致**（sync_skew.py 校验），
     * 桌面 JVM 冒烟用例在 Main.kt「trimDarkEdgesRect 合成回归」一节。
     */
    fun trimDarkEdgesRect(gray: Mat): IntArray? {
        val w = gray.cols()
        val h = gray.rows()
        if (w < 40 || h < 40) return null
        val band = maxOf(4, minOf(w, h) / TRIM_BAND_DIV)
        val maxInset = minOf(w, h) * TRIM_MAX_INSET_NUM / 100
        val segs = maxInset / band
        if (segs <= 0) return null

        // 纸面参考亮度：中央 50%×50% 区域均值
        val center = Mat(gray, Rect(w / 4, h / 4, w / 2, h / 2))
        val cMean = Core.mean(center).`val`[0]
        center.release()

        var x0 = 0; var y0 = 0; var x1 = w; var y1 = h
        // top：从当前上边缘向下逐带扫描
        while (y0 + band <= maxInset) {
            val r = Mat(gray, Rect(0, y0, w, band))
            val m = Core.mean(r).`val`[0]
            r.release()
            if (m < TRIM_ABS_MAX && m < cMean - TRIM_REL_DIFF) y0 += band else break
        }
        // bottom
        while (h - y1 + band <= maxInset) {
            val r = Mat(gray, Rect(0, y1 - band, w, band))
            val m = Core.mean(r).`val`[0]
            r.release()
            if (m < TRIM_ABS_MAX && m < cMean - TRIM_REL_DIFF) y1 -= band else break
        }
        // left / right：行范围用已裁剪的 y0..y1，避免已被裁掉的暗区拉低均值
        while (x0 + band <= maxInset) {
            val r = Mat(gray, Rect(x0, y0, band, y1 - y0))
            val m = Core.mean(r).`val`[0]
            r.release()
            if (m < TRIM_ABS_MAX && m < cMean - TRIM_REL_DIFF) x0 += band else break
        }
        while (w - x1 + band <= maxInset) {
            val r = Mat(gray, Rect(x1 - band, y0, band, y1 - y0))
            val m = Core.mean(r).`val`[0]
            r.release()
            if (m < TRIM_ABS_MAX && m < cMean - TRIM_REL_DIFF) x1 -= band else break
        }

        if (x0 == 0 && y0 == 0 && x1 == w && y1 == h) return null
        // 剩余面积过小说明判据失真（几乎全是暗区），宁可保留也不裁
        if (x1 - x0 < w * TRIM_KEEP_NUM / 100 || y1 - y0 < h * TRIM_KEEP_NUM / 100) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    private const val APPROX_EPSILON = 0.02

    // ---- trimDarkEdgesRect 阈值（与 jvmcheck 同步）----
    /** 暗带绝对上限：灰度均值低于它才可能是桌面（纸面边缘阴影通常亮于此）。 */
    private const val TRIM_ABS_MAX = 105.0
    /** 暗带相对判据：比纸心均值暗至少这么多。 */
    private const val TRIM_REL_DIFF = 45.0
    /** 扫描带宽 = 短边的 1/TRIM_BAND_DIV（约 2%）。 */
    private const val TRIM_BAND_DIV = 50
    /** 每边最大内缩比例（%）。 */
    private const val TRIM_MAX_INSET_NUM = 6
    /** 裁剪后剩余边长低于原长的该百分比（%）即放弃裁剪。 */
    private const val TRIM_KEEP_NUM = 60
    private const val MIN_AREA_RATIO = 0.12
    private const val DETECT_MAX_EDGE = 1080

    // 4000：矫正输出默认上限。3000 时手机拍的书页（通常 4000 宽的传感器输出）
    // 被二次压到 3000，文字细节肉眼可见地变肉（真机反馈"分辨率偏低"）。
    // 4000×3000 ARGB_8888 约 48MB，主流设备可承受。
    // 需要更高时由 ScanPipeline 传入更大的 maxEdge（见分辨率档位）。
    const val DEFAULT_MAX_EDGE = 4000
    private const val SKEW_MAX_EDGE = 1000

    /** 90° 方向判定阈值：score90 / score0 超过它才转正。 */
    private const val ORI_TURN_RATIO = 1.3
}
