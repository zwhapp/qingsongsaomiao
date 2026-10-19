package inkcheck

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * ★★★ 本文件中的 estimateSkewFromGray / textInkMask / estimateOrientationFromGray /
 * projectionScore / percentileLevel / binarizeBlackWhite / sauvolaThreshold /
 * removeSmallComponents / estimateStrokeWidth / flattenResidual /
 * estimateBackgroundAt / percentile
 * 必须与 app 端（DocumentGeometry.kt / ScanEnhancer.kt）中的同名函数
 * **逐字一致**（sync_skew.py 在每次运行前校验，不一致即失败）。
 *
 * 修改算法时：先改 app 端源文件，再把同名函数原样复制过来。
 * 这里存在的唯一意义：让 Kotlin 端的 OpenCV 调用在桌面 JVM 上被真实执行 ——
 * v1.5.3 的教训：Python 复刻验证了算法逻辑，但 Kotlin 端的 API 签名错误
 * （Core.reduce 把 dtype 传到 rtype 位）只有真实执行才能暴露。
 */
object InkSkew {

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
     * 文档二值化：**迟滞 Sauvola**（双阈值 + 连通传递）。
     * 纯 OpenCV、无 Android 依赖 —— 由 tools/jvmcheck 桌面 JVM 真实执行（sync 校验）。
     *
     * 背景（v1.5.5，用户反馈"黑白+超清下笔画间断缺失"，屏幕拍摄的细体字最严重）：
     * 旧方案 = 单阈值 Sauvola(k=0.28) + medianBlur(3)，有两个结构性伤害：
     *   · **medianBlur(3) 对 1~2px 细笔画是灾难**——3×3 中值里细线像素是少数派，
     *     会被当作孤立噪声抹掉或打断。当年注释只防了形态学开运算，其实 median 同样伤细线；
     *   · 单一 k 值两难：k 大背景净但笔画弱段丢失（断），k 小背景脏。
     *
     * 迟滞二值化（类比 Canny 的双阈值）同时解决两者：
     *   strong = g < T(k=0.34)   —— 确定是墨（背景噪点几乎进不来）
     *   weak   = g < T(k=0.18)   —— 可能是墨（淡笔画、笔画弱段都抓到）
     *   输出   = weak 中与 strong 连通的像素（传递闭包）
     * 断掉的笔画只要有任意一段足够实，就会通过连通性把整段弱墨迹救回来；
     * 而孤立的背景噪点因为没有种子，仍然进不了结果。
     *
     * 传递闭包用「迭代膨胀 ∩ weak」实现：3×3 每轮扩 1px，countNonZero 不再
     * 增长即收敛（= 8 连通闭包，与 connectedComponents 语义一致，但全程
     * native 向量化，无逐像素循环）。
     *
     * 去噪用 **CC 面积过滤**（<4px 的黑连通域删掉）替代 median3：
     * 同样能清掉孤立噪点，但绝不伤及细笔画整线。
     *
     * 离线 A/B（tools/train/diag_bin_hyst.py，四案例）：
     *   黑连通域个数（同等场景下越少 = 笔画越连贯）
     *     屏幕报告(主案例)  839 → 791，字明显更实
     *     book2 书页      2620 → 1228（-53%）
     *     text_photo 小字  1881 → 1604，脚注小字从断裂虚线变完整清晰
     *     photo           2098 → 1679，无糊块、无大面积涂黑
     * 放大对比图：tools/train/_bin_v2/cmp_*.jpg
     *
     * @param gray 输入灰度图（8U）
     * @param out  输出二值图（8U，纸面 255 / 墨迹 0），可与 gray 同一对象
     */
    fun binarizeBlackWhite(gray: Mat, out: Mat) {
        val g32 = Mat()
        gray.convertTo(g32, org.opencv.core.CvType.CV_32F)

        val mean = Mat()
        val sq = Mat()
        val meanSq = Mat()
        val std = Mat()
        val tStrong = Mat()
        val tWeak = Mat()
        val strong = Mat()
        val weak = Mat()
        try {
            // 窗口 = 长边/65（4000px 页面 → 61px ≈ 1~2 个字），夹取 15~201。
            val longest = maxOf(gray.cols(), gray.rows())
            var win = (longest / 65).coerceIn(15, 201)
            if (win % 2 == 0) win += 1
            val kSize = Size(win.toDouble(), win.toDouble())

            Imgproc.boxFilter(g32, mean, org.opencv.core.CvType.CV_32F, kSize)
            Core.multiply(g32, g32, sq)
            Imgproc.boxFilter(sq, meanSq, org.opencv.core.CvType.CV_32F, kSize)

            // std = sqrt(max(meanSq - mean², 0))
            Core.multiply(mean, mean, sq)
            Core.subtract(meanSq, sq, std)
            Core.max(std, Scalar(0.0), std)
            val sqrtStd = Mat()
            Core.sqrt(std, sqrtStd)
            sqrtStd.copyTo(std)
            sqrtStd.release()

            // T(k) = mean*(1-k) + mean*std*k/R
            sauvolaThreshold(mean, std, tStrong, BIN_K_STRONG)
            sauvolaThreshold(mean, std, tWeak, BIN_K_WEAK)

            // strong / weak：g < T 即墨（compare/CMP_LT 输出 0/255）
            Core.compare(g32, tStrong, strong, Core.CMP_LT)
            Core.compare(g32, tWeak, weak, Core.CMP_LT)
        } finally {
            g32.release(); mean.release(); sq.release()
            meanSq.release(); std.release(); tStrong.release(); tWeak.release()
        }

        // 传递闭包：seed 从 strong 出发，迭代 dilate 后与 weak 求交。
        val seed = Mat()
        val grown = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            strong.copyTo(seed)
            var prevCount = -1
            // 上限 64 轮：断裂 1~3px 时通常 <10 轮收敛；countNonZero 停滞即提前退出
            for (i in 0 until 64) {
                Imgproc.dilate(seed, grown, kernel)
                Core.min(grown, weak, grown)   // 0/255 掩膜的 min 即按位与
                val cnt = Core.countNonZero(grown)
                grown.copyTo(seed)
                if (cnt == prevCount) break    // 收敛 = 闭包完成
                prevCount = cnt
            }
            // seed 是「墨=255」（compare/CMP_LT 的自然输出），反转为约定极性：
            // 纸=255 / 墨=0。★ v1.5.5 首版漏了这一步 —— 输出黑纸白字，
            // 且 CC 去噪在错误极性下完全失效。由 jvmcheck 的 Python 对拍用例
            // 抓住（黑占比 84.5% vs 基线 15.5%）。
            Core.bitwise_not(seed, out)
        } finally {
            seed.release(); grown.release(); kernel.release()
            strong.release(); weak.release()
        }

        // CC 面积去噪替代 median3：清孤立噪点（墨掩膜里的小前景块），不伤细笔画。
        // 顺带估算平均笔画宽度，供摩尔纹滤波确定核上限（v1.5.8）。
        var strokeW = 10.0
        val inkMask = Mat()
        try {
            Core.bitwise_not(out, inkMask)   // 墨=255
            removeSmallComponents(inkMask, BIN_MIN_AREA)
            Core.bitwise_not(inkMask, out)
            strokeW = estimateStrokeWidth(inkMask)
        } finally {
            inkMask.release()
        }

        // 开-闭滤波去摩尔纹（v1.5.6，用户反馈"屏幕拍摄黑白输出波纹状细线"）：
        // 屏摄的低幅摩尔纹在**亮度上与文字不可分**（实测波纹区局部 std≈66 vs
        // 文字区 65，diag_moire_sep.py），调阈值/预中值都无效 —— 只能按形态
        // 结构区分：波纹是 1~3px 的暗细线，文字笔画粗得多。close 删除宽度
        // 小于核的暗细线，open 恢复笔画间的白间隙，核随分辨率缩放。
        //
        // ★ v1.5.8（用户反馈"黑白处理后笔画断续严重"，影印小字文档）：
        // close 对"宽度 < 核"的暗线一视同仁 —— 细笔画也是暗细线！分辨率
        // 核（4000px → 4）对大字文档无伤，但对笔画只有 2~3px 的小字页会把
        // 笔画整条删断。因此核上限改为 min(分辨率核, 笔画宽度-1)：
        // 笔画越细核越小，细到 <4px（估宽 ≤3）直接跳过滤波 —— 宁可保留
        // 摩尔纹也不吃笔画。粗笔画页（屏摄报告等）核不变，去纹能力不受影响。
        val resK = (maxOf(out.cols(), out.rows()) / 1000).coerceIn(3, 6)
        val moireK = minOf(resK, (strokeW - 1.0).toInt()).coerceIn(1, 6)
        if (moireK >= 3) {
            val moireKern = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, Size(moireK.toDouble(), moireK.toDouble())
            )
            try {
                Imgproc.morphologyEx(out, out, Imgproc.MORPH_OPEN, moireKern)
                Imgproc.morphologyEx(out, out, Imgproc.MORPH_CLOSE, moireKern)
            } finally {
                moireKern.release()
            }
        }

        // 贴边黑带清理（v1.5.8，影印书页真机反馈输出顶部有实心黑带）：
        // 矫正框比纸边大一圈时，框外的深色桌布/垫布二值化后成为贴边实心黑带。
        // 三条件同时满足才清理 —— 正文笔画（块黑密度 P95 仅 0.21）和页内插图
        // 都不可能同时「贴边 + 横跨 ≥95% 画幅 + 面积 ≥0.5%」：
        //   1. 黑色连通域贴画幅边缘；
        //   2. 横带（宽 ≥95%W 且厚 ≥1%H）或竖带（高 ≥95%H 且宽 ≥1%W）；
        //   3. 面积 ≥ 0.5% 画幅。
        removeBorderBands(out)

        // 墨内微隙桥接（v1.5.9）：墨极性 3×3 close 只填墨内部的 1~2px 空隙，
        // 对细笔画的断裂是"缝合"而不是"删除"（纸极性 close 会删细线，极性
        // 不能反）。随后重做 CC 去噪清掉桥接引入的噪点粘连。
        // 离线标定（diag_printpage.py）：normal/sharp/screen/text_photo 四档
        // 黑占比变化 ≤0.1%，CC 一致下降 1~5%，无糊化副作用。
        val bridgeInk = Mat()
        try {
            Core.bitwise_not(out, bridgeInk)
            val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            try {
                Imgproc.morphologyEx(bridgeInk, bridgeInk, Imgproc.MORPH_CLOSE, k3)
            } finally {
                k3.release()
            }
            removeSmallComponents(bridgeInk, BIN_MIN_AREA)
            Core.bitwise_not(bridgeInk, out)
        } finally {
            bridgeInk.release()
        }
    }

    /**
     * 清理 [binimg]（纸=255/墨=0）中贴画幅边缘的横/竖实心黑带 → 强制白。
     * 判据见 [binarizeBlackWhite] 尾部注释。标定（diag_printpage.py）：
     * 影印书页清除顶部背景带 1 条；text_photo 的照片边缘背景带同步被清
     * （15.80%→12.86%，方向正确）；screen 报告零变化；正文零误伤。
     */
    private fun removeBorderBands(binimg: Mat) {
        val w = binimg.cols()
        val h = binimg.rows()
        val ink = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            Core.bitwise_not(binimg, ink)   // 墨=255
            val n = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids)
            val remove = BooleanArray(n)
            var any = false
            for (i in 1 until n) {
                // stats 列：0=LEFT 1=TOP 2=WIDTH 3=HEIGHT 4=AREA
                val x = stats.get(i, 0)[0]
                val y = stats.get(i, 1)[0]
                val cw = stats.get(i, 2)[0]
                val ch = stats.get(i, 3)[0]
                val area = stats.get(i, 4)[0]
                val touches = x == 0.0 || y == 0.0 || x + cw >= w || y + ch >= h
                if (!touches || area < w * h * 0.005) continue
                val horizontal = cw >= w * 0.95 && ch >= h * 0.005
                val vertical = ch >= h * 0.95 && cw >= w * 0.005
                if (horizontal || vertical) {
                    remove[i] = true
                    any = true
                }
            }
            if (!any) return
            val labRow = IntArray(w)
            val row = ByteArray(w)
            for (y in 0 until h) {
                labels.get(y, 0, labRow)
                ink.get(y, 0, row)
                var changed = false
                for (x in 0 until w) {
                    val l = labRow[x]
                    if (l != 0 && remove[l]) {
                        row[x] = 0        // 删除 = 墨清零（黑带变白）
                        changed = true
                    }
                }
                if (changed) ink.put(y, 0, row)
            }
            Core.bitwise_not(ink, binimg)
        } finally {
            ink.release(); labels.release(); stats.release(); centroids.release()
        }
    }

    /**
     * 估算墨掩膜的平均笔画宽度（像素）。
     *
     * 距离变换：每个墨像素到最近背景的距离，宽度 w 的笔画内部距离呈
     * 三角分布（0 ~ w/2，均值 ≈ w/4），故估宽 = 掩膜内距离均值 × 4。
     * 在 ≤800px 的缩略图上算，开销可忽略；估宽偏保守（略微低估），
     * 对"宁可少去纹、不可删笔画"的目标正好合适。
     */
    private fun estimateStrokeWidth(inkMask: Mat): Double {
        val small = Mat()
        try {
            val longest = maxOf(inkMask.cols(), inkMask.rows())
            val f = if (longest > 800) 800.0 / longest else 1.0
            if (f < 1.0) {
                Imgproc.resize(
                    inkMask, small, Size(inkMask.cols() * f, inkMask.rows() * f),
                    0.0, 0.0, Imgproc.INTER_AREA
                )
                Imgproc.threshold(small, small, 127.0, 255.0, Imgproc.THRESH_BINARY)
            } else {
                inkMask.copyTo(small)
            }
            if (Core.countNonZero(small) == 0) return 10.0
            val dist = Mat()
            try {
                Imgproc.distanceTransform(small, dist, Imgproc.DIST_L2, 3)
                val mean = Core.mean(dist, small).`val`[0]
                return mean * 4.0
            } finally {
                dist.release()
            }
        } finally {
            small.release()
        }
    }

    /** T = mean*(1-k) + mean*std*k/R，写入 [out]。 */
    private fun sauvolaThreshold(mean: Mat, std: Mat, out: Mat, k: Double) {
        val t2 = Mat()
        try {
            Core.multiply(mean, std, t2, k / BIN_R)
            Core.multiply(mean, Scalar(1.0 - k), out)
            Core.add(out, t2, out)
        } finally {
            t2.release()
        }
    }

    /**
     * 删除前景掩膜（前景 255 / 背景 0）中面积小于 [minArea] 的连通域。
     * 按行整行读写像素（native 行拷贝 + JVM 循环），4000×3000 约 100~200ms。
     */
    private fun removeSmallComponents(mask: Mat, minArea: Int) {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            if (n <= 1) return
            // stats 列：0=LEFT 1=TOP 2=WIDTH 3=HEIGHT 4=AREA
            val small = BooleanArray(n)
            var any = false
            for (i in 1 until n) {
                if (stats.get(i, Imgproc.CC_STAT_AREA)[0] < minArea) {
                    small[i] = true
                    any = true
                }
            }
            if (!any) return
            val w = mask.cols()
            val labRow = IntArray(w)
            val row = ByteArray(w)
            for (y in 0 until mask.rows()) {
                labels.get(y, 0, labRow)
                mask.get(y, 0, row)
                var changed = false
                for (x in 0 until w) {
                    val l = labRow[x]
                    if (l != 0 && small[l]) {
                        row[x] = 0        // 删除 = 前景清零
                        changed = true
                    }
                }
                if (changed) mask.put(y, 0, row)
            }
        } finally {
            labels.release(); stats.release(); centroids.release()
        }
    }

    /**
     * 去手写：把画面里的彩色笔迹 / 荧光笔（[Mode.REMOVE_INK]）擦成纸白，
     * 同时尽量保留印刷彩色大色块（红色印章、蓝色 Logo 等）。
     *
     * 判定（颜色法，OpenCV 基础算子，由 tools/jvmcheck 桌面 JVM 真跑校验）：
     *   1. 色度 chroma = max(r,g,b) - min(r,g,b)：彩色像素色度高，纸/黑字≈0；
     *   2. 排除"带色偏的暗印刷字"：要求最大通道 > [INK_VALUE_THRESH]；
     *   3. 连通域区分：用"面积 ≥ 阈值 且 实心度 fill ≥ 阈 且 长宽比 aspect ≤ 阈"
     *      三条件同时成立才保留为印刷大色块（印章/Logo）；手写笔迹、荧光笔条
     *      因不够"紧凑+够大"被擦除（见 [filterColoredComponents]）。
     *
     * 已知局限：叠在黑字上的荧光笔会连同下面黑字一起被擦掉（半透明荧光层
     * 无法只剥离而保留其下黑字），v1 先如此，后续可换透明层检测。
     *
     * @param bgr 已做背景归一 + 二级平坦化的 BGR 图（in-place 修改）
     */
    fun eraseColoredInk(bgr: Mat) {
        val channels = ArrayList<Mat>(3)
        Core.split(bgr, channels)
        val r = channels[2]
        val g = channels[1]
        val b = channels[0]
        val maxc = Mat()
        val minc = Mat()
        val chroma = Mat()
        val sat = Mat()
        val sat8 = Mat()
        val colored = Mat()
        val satOk = Mat()
        val bright = Mat()
        val filled = Mat()
        try {
            // chroma = max - min
            Core.max(r, g, maxc)
            Core.max(maxc, b, maxc)
            Core.min(r, g, minc)
            Core.min(minc, b, minc)
            Core.subtract(maxc, minc, chroma)

            // sat = chroma / max（0~1）：归一化是逐通道除法，chroma 与 max 同倍
            // 放大、比值近似不变 —— 用它挡住"暖黄纸"与"暗部放大"两类误报
            val maxcF = Mat()
            val chromaF = Mat()
            try {
                maxc.convertTo(maxcF, org.opencv.core.CvType.CV_32F)
                Core.max(maxcF, Scalar(1.0), maxcF)
                chroma.convertTo(chromaF, org.opencv.core.CvType.CV_32F)
                Core.divide(chromaF, maxcF, sat)
            } finally {
                maxcF.release(); chromaF.release()
            }
            Core.multiply(sat, Scalar(255.0), sat)
            sat.convertTo(sat8, org.opencv.core.CvType.CV_8U)

            // 彩色 = 色度够高 且 饱和度够高 且 不是太暗（避免误删带色偏的印刷黑字）
            Imgproc.threshold(chroma, colored, INK_CHROMA_THRESH, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(sat8, satOk, INK_SAT_THRESH * 255.0, 255.0, Imgproc.THRESH_BINARY)
            Core.min(colored, satOk, colored)   // 0/255 掩膜的 min = 按位与
            Imgproc.threshold(maxc, bright, INK_VALUE_THRESH, 255.0, Imgproc.THRESH_BINARY)
            Core.min(colored, bright, colored)

            // 连通域过滤：把要保留的大色块从掩膜清掉，只留手写/荧光笔待擦
            filterColoredComponents(colored, maxOf(bgr.cols(), bgr.rows()))

            // ★ 擦除用"形态学闭运算填充"而不是纯白：直接置 255 会抬高局部均值
            //   → Sauvola 阈值随之上移 → 反而把更多像素判成墨（实测 book2 黑占比
            //   7.05%→9.40%，jvmcheck 无损性对拍抓住）。闭运算（先膨胀后腐蚀）
            //   用邻域最大值填掉比核窄的暗笔迹 —— 填进去的就是周围纸色；且对
            //   均匀纸面是**恒等变换**（邻域同值，取 max 再取 min 仍是原值），
            //   所以纸面误判不会造成任何扰动。
            //   （曾试"大核高斯模糊当填充源"：核一大就把整片结构抹平，
            //   text_photo 反而被推高到 0.693%；inpaint 在 OpenCV Java 里属于
            //   photo 模块而非 imgproc，不可用，故用形态学闭运算。）
            var k = (maxOf(bgr.cols(), bgr.rows()) / INK_CLOSE_DIV).coerceIn(3, 41)
            if (k % 2 == 0) k += 1
            val closeKern = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())
            )
            try {
                Imgproc.morphologyEx(bgr, filled, Imgproc.MORPH_CLOSE, closeKern)
            } finally {
                closeKern.release()
            }
            filled.copyTo(bgr, colored)
        } finally {
            maxc.release(); minc.release(); chroma.release(); sat.release()
            sat8.release(); colored.release(); satOk.release()
            bright.release(); filled.release()
            channels.forEach { it.release() }
        }
    }

    /**
     * 去手写 ML 层：把 [mask]（255=手写笔迹）标记的区域用纸色填掉。
     *
     * 填充方式与 [eraseColoredInk] 相同 —— 形态学闭运算结果填回（邻域最大值
     * = 周围纸色，对均匀纸面恒等），保证对未误判区域零扰动。
     *
     * 掩码膨胀：ML 掩码在 256x256 上推理再放大回原尺寸，边界有约
     * (长边/256) 量化误差，且下采样可能吃掉笔画边缘一两个像素，故按
     * [INK_MASK_DIL_DIV] 比例膨胀一圈，宁可多擦一点纸也不能留半个笔画
     * （残留半截笔画在二值化后比整条更扎眼）。
     *
     * @param bgr   已做背景归一 + 二级平坦化的 BGR 图（in-place 修改）
     * @param mask  与 bgr 同尺寸的 CV_8U 掩码（255=手写，来自 HandwritingRemover）
     */
    fun eraseMaskedRegion(bgr: Mat, mask: Mat) {
        val dil = Mat()
        val filled = Mat()
        var dilKern: Mat? = null
        var closeKern: Mat? = null
        try {
            val longEdge = maxOf(bgr.cols(), bgr.rows())
            var dk = (longEdge / INK_MASK_DIL_DIV).coerceIn(3, 41)
            if (dk % 2 == 0) dk += 1
            dilKern = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, Size(dk.toDouble(), dk.toDouble())
            )
            Imgproc.dilate(mask, dil, dilKern)

            var k = (longEdge / INK_CLOSE_DIV).coerceIn(3, 41)
            if (k % 2 == 0) k += 1
            closeKern = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())
            )
            Imgproc.morphologyEx(bgr, filled, Imgproc.MORPH_CLOSE, closeKern)
            filled.copyTo(bgr, dil)
        } finally {
            dil.release(); filled.release()
            dilKern?.release(); closeKern?.release()
        }
    }

    /**
     * 在彩色掩膜 [mask]（255=彩色，0=非彩色）上做连通域分析，把"应保留的
     * 印刷色块"从掩膜里清掉（置 0），只留下要擦除的手写/荧光笔。
     *
     * 保留判据（见 [eraseColoredInk]）：三项须**同时**满足
     *   · 面积 ≥ [INK_KEEP_AREA_BASE]（随分辨率按 (longEdge/4000)² 缩放，挡掉小笔点）；
     *   · 实心度 fill ≥ [INK_KEEP_FILL]（挡掉稀疏笔划）；
     *   · 长宽比 aspect ≤ [INK_KEEP_ASPECT]（挡掉细长/荧光笔条）。
     * 细长荧光笔条、稀疏笔划、短粗笔点因不满足"紧凑+够大"而被擦除；只有
     * 紧凑实心且够大的印刷块（印章/Logo）才保留。
     */
    private fun filterColoredComponents(mask: Mat, longEdge: Int) {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            if (n <= 1) return
            val w = mask.cols()
            val h = mask.rows()
            // 面积阈值随分辨率缩放：在 4000px 长边基准上取 INK_KEEP_AREA_BASE，
            // 其它分辨率按 (longEdge/4000)² 缩放（面积 ∝ 像素²）。
            val f = longEdge / 4000.0
            val minArea = (f * f * INK_KEEP_AREA_BASE).toInt().coerceAtLeast(1)
            // 先一次性收集要保留的标签，再单遍扫描清掩膜（避免逐组件全图扫描）
            val keep = BooleanArray(n)
            var anyKeep = false
            for (i in 1 until n) {
                // stats 列：0=LEFT 1=TOP 2=WIDTH 3=HEIGHT 4=AREA
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                val cw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0]
                val ch = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]
                val boxArea = cw * ch
                val fill = if (boxArea > 0) area / boxArea else 0.0
                val aspect = if (cw > 0 && ch > 0) maxOf(cw, ch) / minOf(cw, ch) else 0.0
                val preserve = area >= minArea && fill >= INK_KEEP_FILL && aspect <= INK_KEEP_ASPECT
                if (preserve) { keep[i] = true; anyKeep = true }
            }
            if (!anyKeep) return
            val labRow = IntArray(w)
            val mrow = ByteArray(w)
            for (y in 0 until h) {
                labels.get(y, 0, labRow)
                mask.get(y, 0, mrow)
                var changed = false
                for (x in 0 until w) {
                    val l = labRow[x]
                    if (l != 0 && keep[l]) { mrow[x] = 0; changed = true }
                }
                if (changed) mask.put(y, 0, mrow)
            }
        } finally {
            labels.release(); stats.release(); centroids.release()
        }
    }

    /** 迟滞强阈值：确定是墨。k 大 → 阈值低 → 只有足够深的笔画才算种子。 */
    private const val BIN_K_STRONG = 0.34

    /** 迟滞弱阈值：可能是墨。k 小 → 阈值高 → 淡笔画、笔画弱段都抓到。 */
    private const val BIN_K_WEAK = 0.18

    /** Sauvola 标准差动态范围归一值（8bit 灰度理论最大标准差的一半）。 */
    private const val BIN_R = 128.0

    /** CC 去噪面积下限：孤立噪点通常 1~2px，4px 起删绝不伤笔画。 */
    private const val BIN_MIN_AREA = 4

    /**
     * 去手写：色度下限，chroma = max(r,g,b) - min(r,g,b)。
     * 标定（tools/train/diag_ink_chroma.py）：真实纸面归一化后 P95 色度仅
     * 31~57，而彩色笔迹 ≥80（圆珠笔最暗的"黑蓝"也有 80）—— 取 75 落在两者
     * 之间的空隙里。★ 首版取 28 误把暖黄纸判成彩色，实测 book2 黑占比
     * 被推高 2.35%，由 jvmcheck 无损性对拍抓住。
     */
    private const val INK_CHROMA_THRESH = 75.0

    /**
     * 去手写：饱和度下限，sat = chroma / max（0~1）。
     * 归一化是逐通道除法，chroma 与 max 同倍放大、比值近似不变，故 sat 能
     * 挡住"暖黄纸"（sat≈0.16）与"暗部放大"这两类误报；彩色笔迹 sat ≥0.41
     * （最淡的粉色荧光笔），故取 0.38 保留全部常见笔迹。
     */
    private const val INK_SAT_THRESH = 0.38

    /** 去手写：最大通道亮度阈值，排除带色偏但很暗的印刷黑字（避免误删）。 */
    private const val INK_VALUE_THRESH = 45.0

    /**
     * 去手写：擦除填充的形态学核除数，闭运算核 = 长边 / 该值（夹取 3~41，取奇）。
     * 核要大于笔画宽度才能把整条笔迹填平；笔画宽度随分辨率等比变化，
     * 故取长边的固定比例即可自适应（4000px 页面 → 22，预览 2000px → 11）。
     */
    private const val INK_CLOSE_DIV = 180

    /** 去手写：ML 掩码膨胀比例，膨胀核 = 长边 / 该值（夹取 3~41）。
     *  256 空间掩码放大回原尺寸有约长边/256 的量化误差，按此比例多膨胀
     *  一圈（3300px 页面 ≈ 17px）盖住笔画边缘，宁多擦纸不留半截笔画。 */
    private const val INK_MASK_DIL_DIV = 200

    /** 去手写：长边 4000px 基准下的"保留大色块"面积下限（像素）。 */
    private const val INK_KEEP_AREA_BASE = 1600

    /** 去手写：实心度阈值，fill=面积/外接框面积，高于即视为实心印刷块。 */
    private const val INK_KEEP_FILL = 0.5

    /** 去手写：长宽比阈值，太扁（荧光笔条/短粗笔点）视为手写予以擦除。 */
    private const val INK_KEEP_ASPECT = 3.0

    // ==== 以下为 ScanEnhancer.kt 同步段（flattenResidual 及其依赖） ====

    /**
     * 背景/低频场估计的通用实现（[estimateBackground] 与 [flattenResidual] 共用）。
     *
     * @param edge      估计用的工作分辨率上限（长边）。背景是低频信号，降采样估计足够。
     * @param kDiv      形态学核 = 工作分辨率长边 / kDiv。
     * @param kMax      核上限，防止超大页面上核失控。
     * @param sigmaDiv  高斯 sigma = 核 / sigmaDiv。
     */
    private fun estimateBackgroundAt(
        src: Mat,
        out: Mat,
        edge: Int,
        kDiv: Int,
        kMax: Int,
        sigmaDiv: Double
    ) {
        // ★ 在低分辨率上估计背景场。
        //   背景（光照）是低频信号，用 1/4 尺寸估计完全够，之后再放大回来
        //   —— 放大本身的插值就是一次平滑，正好替代原来昂贵的大 sigma 高斯。
        //   若直接在 4000px 上用 333px 形态学核 + sigma 166 高斯：
        //     · 形态学核 O(k²) 极慢；
        //     · GaussianBlur(ksize=0, sigma=166) 会自动生成约 997px 的核，
        //       单页要十几秒，完全不可用。
        //   降到 1000px 上估计后，核只有 ~83，整体快几十倍且更平滑。
        val longest = maxOf(src.cols(), src.rows())
        val small = Mat()
        val scaled = longest > edge
        try {
            if (scaled) {
                val f = edge.toDouble() / longest
                Imgproc.resize(src, small, Size(), f, f, Imgproc.INTER_AREA)
            } else {
                src.copyTo(small)
            }

            val sl = maxOf(small.cols(), small.rows())
            var k = (sl / kDiv).coerceIn(31, kMax)
            if (k % 2 == 0) k += 1

            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())
            )
            try {
                if (small.channels() == 3) {
                    val channels = ArrayList<Mat>(3)
                    Core.split(small, channels)
                    // 注意：这里绝对不能在循环内 release。
                    // Core.split 把三个通道分别写进 channels 里的 Mat，
                    // 循环里对每个 Mat 做完形态学闭运算与模糊后如果立刻 release()，
                    // 那么下面的 Core.merge 读到的就是已经释放的 native 缓冲 ——
                    // 结果要么抛异常，要么 merge 出一张损坏的背景图。
                    // 而异常会被 ScanPipeline 的 runCatching 吞掉并回退原图，
                    // 表现出来就是"去阴影没有任何效果"。
                    // 正确做法是在 merge 之后统一释放。
                    for (ch in channels) {
                        Imgproc.morphologyEx(ch, ch, Imgproc.MORPH_CLOSE, kernel)
                        // sigma 随核缩放但封顶：block 已经在低分辨率上，
                        // 不需要原来 k/2 那么大的 sigma
                        Imgproc.GaussianBlur(ch, ch, Size(0.0, 0.0), (k / sigmaDiv).coerceAtLeast(3.0))
                    }
                    Core.merge(channels, small)
                    channels.forEach { it.release() }
                } else {
                    Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
                    Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), (k / sigmaDiv).coerceAtLeast(3.0))
                }
            } finally {
                kernel.release()
            }

            // 放大回原尺寸（插值即平滑）
            if (scaled) {
                Imgproc.resize(
                    small, out, Size(src.cols().toDouble(), src.rows().toDouble()),
                    0.0, 0.0, Imgproc.INTER_LINEAR
                )
            } else {
                small.copyTo(out)
            }

            // 防止背景为 0 导致后面除法出现 Inf/NaN
            Core.max(out, Scalar(1.0, 1.0, 1.0), out)
        } finally {
            small.release()
        }
    }

    /**
     * 二级平坦化：把"文字密集处纸面偏深"的残余低频不均压平。
     *
     * ★ v1.5.7：gain = P85(field)/field（自适应纸面基准），上限 2.2。
     */
    private fun flattenResidual(bgr: Mat) {
        val f32 = Mat()
        val field = Mat()
        val gain = Mat()
        val fieldGray = Mat()
        val field8 = Mat()
        val small = Mat()
        val out = Mat()
        try {
            bgr.convertTo(f32, org.opencv.core.CvType.CV_32F)
            estimateBackgroundAt(
                f32, field, RESIDUAL_EDGE, RESIDUAL_K_DIV, RESIDUAL_K_MAX, RESIDUAL_SIGMA_DIV
            )

            // 纸面代表亮度 = 背景场的 85 分位（缩略图上统计，开销可忽略）
            Imgproc.cvtColor(field, fieldGray, Imgproc.COLOR_BGR2GRAY)
            fieldGray.convertTo(field8, org.opencv.core.CvType.CV_8U)
            Imgproc.resize(field8, small, Size(160.0, 160.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val p = percentile(small, RESIDUAL_PAPER_PERCENTILE)
            if (p <= 1.0) return

            // gain = P85 / 局部场，逐点算；保证纸面最亮的部分（≈P85）不动，
            // 只有被文字/阴影拉低的地方被提起来
            Core.divide(p, field, gain)
            Core.max(gain, Scalar(1.0, 1.0, 1.0), gain)
            Core.min(gain, Scalar(RESIDUAL_MAX_GAIN, RESIDUAL_MAX_GAIN, RESIDUAL_MAX_GAIN), gain)

            Core.multiply(f32, gain, out)
            out.convertTo(bgr, org.opencv.core.CvType.CV_8U)
        } finally {
            f32.release(); field.release(); gain.release()
            fieldGray.release(); field8.release(); small.release(); out.release()
        }
    }

    /**
     * 求 8 位单通道图的 p 分位灰度。
     *
     * 自己数直方图，不用 Imgproc.calcHist —— 后者的 Java 签名要求
     * MatOfInt / MatOfFloat 包装类，为一次 256 桶统计绕这一圈不值得，
     * 而且直方图本身就是 O(n + 256)，比排序快。
     * 输入是 160×160 的缩略图（25600 像素），开销可以忽略。
     */
    private fun percentile(gray8: Mat, p: Double): Double {
        val n = gray8.rows() * gray8.cols()
        if (n <= 0) return 0.0
        val buf = ByteArray(n)
        gray8.get(0, 0, buf)

        val hist = IntArray(256)
        for (b in buf) hist[b.toInt() and 0xFF]++

        val target = n * (p / 100.0)
        var acc = 0.0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= target) return i.toDouble()
        }
        return 255.0
    }

    /** 背景估计的工作分辨率上限（长边）。 */
    private const val BG_ESTIMATE_EDGE = 1000

    /** 第一级背景核除数：核 = 长边 / 12。 */
    private const val BG_K_DIV = 12

    /** 第一级背景核上限。 */
    private const val BG_K_MAX = 151

    /** 第一级背景高斯 sigma 除数。 */
    private const val BG_SIGMA_DIV = 6.0

    /** 二级平坦化的工作分辨率上限（长边）。比第一级更低：要处理的是更粗的低频。 */
    private const val RESIDUAL_EDGE = 600

    /** 二级核除数：600/8 = 75，等效到 4000px 页面约 500px 核。 */
    private const val RESIDUAL_K_DIV = 8

    /** 二级核上限（工作分辨率上）。 */
    private const val RESIDUAL_K_MAX = 151

    /** 二级高斯 sigma 除数。 */
    private const val RESIDUAL_SIGMA_DIV = 8.0

    /**
     * 二级平坦化的最大增益。只提亮不压暗，故下限恒为 1.0。
     * v1.5.7 分子改为 P85 后暗角提升幅度变大，上限从 1.5 放宽到 2.2；
     * v1.5.8 真机反馈影印书页（边缘阴影深）仍偏灰，进一步放宽到 2.6。
     */
    private const val RESIDUAL_MAX_GAIN = 2.6

    /** 二级平坦化取"纸面代表亮度"的分位（与 liftPaperWhite 同口径）。 */
    private const val RESIDUAL_PAPER_PERCENTILE = 85.0

    /** 90° 方向判定阈值：score90 / score0 超过它才转正。 */
    private const val ORI_TURN_RATIO = 1.3

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

    /**
     * 冒烟专用：模拟 ScanEnhancer.enhance 的真实前置链 ——
     * 第一级背景归一（src/bg×255）+ 二级平坦化。flattenResidual 的设计
     * 输入是"已做过第一级归一"的图，直接喂原图不可比。
     */
    fun normalizeForSmoke(src: Mat, out: Mat) {
        val bg = Mat()
        try {
            estimateBackgroundAt(src, bg, BG_ESTIMATE_EDGE, BG_K_DIV, BG_K_MAX, BG_SIGMA_DIV)
            Core.divide(src, bg, out, 255.0)
        } finally {
            bg.release()
        }
        flattenResidual(out)
    }
}
