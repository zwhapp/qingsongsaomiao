package inkcheck

import nu.pattern.OpenCV
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * 桌面 JVM 冒烟入口：
 *   gradle run                      → 跑内置回归集（bug151 + 基准图 + 合成旋转）
 *   gradle run --args="path.jpg"    → 对单张图估计倾斜角
 */
fun main(args: Array<String>) {
    OpenCV.loadLocally()

    if (args.isNotEmpty()) {
        val gray = loadGray(args[0])
        println(String.format("%s  %+.2f°", args[0], InkSkew.estimateSkewFromGray(gray)))
        gray.release()
        return
    }

    val here = File(System.getProperty("user.dir"))          // tools/jvmcheck
    val proj = here.parentFile.parentFile                    // DocScanPro/
    val bug = File(proj, "_bug151")

    data class Case(val path: File, val label: String, val expect: Double?)

    val cases = listOf(
        Case(File(bug, "input.jpg"), "bug151原图(斜15°)", -15.50),
        Case(File(bug, "dev_result_crop.jpg"), "真机输出(斜18°)", -18.50),
        Case(File(proj, "tools/book2_raw.jpg"), "book2_raw.jpg", 1.25),
        Case(File(proj, "tools/photo_moved.jpg"), "photo_moved.jpg", -1.25),
        Case(File(proj, "tools/photo_full.jpg"), "photo_full.jpg", -1.25),
        Case(File(proj, "tools/text_photo_moved.jpg"), "text_photo_moved.jpg", -1.00),
        Case(File(proj, "tools/text_photo_full.jpg"), "text_photo_full.jpg", -1.00),
    )

    var fail = 0
    println("== 基准回归（期望 = Python diag_skew_ink.py 基线，容差 1.0°）==")
    for (c in cases) {
        if (!c.path.exists()) { println("  MISSING ${c.path}"); fail++; continue }
        val gray = loadGray(c.path.absolutePath)
        val a = try {
            InkSkew.estimateSkewFromGray(gray)
        } catch (e: Throwable) {
            println("  ✗ ${c.label}: EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++; gray.release(); continue
        }
        gray.release()
        val exp = c.expect
        val mark = if (exp == null) "?" else if (Math.abs(a - exp) <= 1.0) "✓" else "✗"
        if (mark == "✗") fail++
        println(String.format("  %s %-24s %+.2f°  (py %+.2f°)", mark, c.label, a, exp ?: Double.NaN))
    }

    println("== 合成旋转回归（水平基准图转 -15°，应检出 -15°±2°）==")
    for (n in listOf("photo_full.jpg", "text_photo_full.jpg", "book2_raw.jpg")) {
        val f = File(proj, "tools/$n")
        if (!f.exists()) { println("  MISSING $f"); fail++; continue }
        val gray = loadGray(f.absolutePath)
        // cv2/getRotationMatrix2D 正角 = 逆时针；+15° 旋转得到文字右端下落的
        // "斜 -15°" 图（与 diag_skew_ink.py 的合成方式一致）
        val rot = rotateWithExpand(gray, 15.0)
        val a = try {
            InkSkew.estimateSkewFromGray(rot)
        } catch (e: Throwable) {
            println("  ✗ $n: EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++; rot.release(); gray.release(); continue
        }
        rot.release(); gray.release()
        val mark = if (Math.abs(a - (-15.0)) <= 2.0) "✓" else "✗"
        if (mark == "✗") fail++
        println(String.format("  %s %-24s %+.2f°", mark, n, a))
    }

    println("== 90° 方向回归（0/90 四分之一转判定，5 案例离线基线）==")
    data class OriCase(val path: File, val label: String, val expect: Int, val rot90: Boolean = false)
    val oriCases = listOf(
        OriCase(File(proj, "tools/train/_shadow/bookpage_src.jpg"), "横躺书页", 90),
        OriCase(File(proj, "tools/text_photo_full.jpg"), "text_photo", 0),
        OriCase(File(proj, "tools/book2_raw.jpg"), "book2", 0),
        OriCase(File(proj, "tools/text_photo_full.jpg"), "text_photo人为转90°", 90, rot90 = true),
    )
    for (c in oriCases) {
        if (!c.path.exists()) { println("  MISSING ${c.path}"); fail++; continue }
        val gray = loadGray(c.path.absolutePath)
        val input = if (c.rot90) {
            val r = Mat()
            Core.rotate(gray, r, Core.ROTATE_90_CLOCKWISE)
            gray.release()
            r
        } else gray
        val r = try {
            InkSkew.estimateOrientationFromGray(input)
        } catch (e: Throwable) {
            println("  ✗ ${c.label}: EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++; input.release(); continue
        }
        input.release()
        val mark = if (r == c.expect) "✓" else "✗"
        if (mark == "✗") fail++
        println(String.format("  %s %-20s 判定 %d°  (期望 %d°)", mark, c.label, r, c.expect))
    }

    println("== trimDarkEdgesRect 合成回归（纸外暗带裁剪：该裁的裁、亮纸不裁、浅阴影不误裁）==")
    // 案例1：亮纸 220 + 四周各 40px 深色桌面(40)——每边至少内缩一个 band
    // 案例2：均匀亮纸——不裁（null）
    // 案例3：纸面边缘浅阴影（190 vs 纸心 220）——不到桌面判据，不误裁
    // 案例4：真实扫描图 text_photo_full——断言几乎不裁（已有输出不含暗边）
    run {
        val W = 800; val H = 1100
        fun makeEdge(ink: Double, edge: Double): Mat {
            val m = Mat(H, W, org.opencv.core.CvType.CV_8UC1)
            m.setTo(org.opencv.core.Scalar.all(ink))
            val inner = Mat(m, org.opencv.core.Rect(40, 40, W - 80, H - 80))
            inner.setTo(org.opencv.core.Scalar.all(edge))
            inner.release()
            return m
        }
        fun relRect(r: IntArray?, W: Int, H: Int): String =
            if (r == null) "null" else "x0=${r[0]} y0=${r[1]} x1=${r[2]} y1=${r[3]}"

        // 案例1：四边暗带。band = min(800,1100)/50 = 22px；暗带 40px 足够触发 1 段内缩
        val c1 = makeEdge(40.0, 220.0)
        val r1 = try { InkSkew.trimDarkEdgesRect(c1) } catch (e: Throwable) {
            println("  ✗ 暗带案例: EXCEPTION ${e.javaClass.simpleName}"); fail++; null
        }
        c1.release()
        val ok1 = r1 != null && r1[0] >= 22 && r1[1] >= 22 && r1[2] <= W - 22 && r1[3] <= H - 22
        if (!ok1) fail++
        println("  ${if (ok1) "✓" else "✗"} 暗带案例(四边40px深40)   ${relRect(r1, W, H)}")
        if (r1 != null && r1[0] == 0 && r1[1] == 0 && r1[2] == W && r1[3] == H) fail++

        // 案例2：均匀亮纸 → null
        val c2 = makeEdge(220.0, 220.0)
        val r2 = try { InkSkew.trimDarkEdgesRect(c2) } catch (e: Throwable) {
            println("  ✗ 均匀亮纸: EXCEPTION ${e.javaClass.simpleName}"); fail++; null
        }
        c2.release()
        val ok2 = r2 == null
        if (!ok2) fail++
        println("  ${if (ok2) "✓" else "✗"} 均匀亮纸(不应裁)          ${relRect(r2, W, H)}")

        // 案例3：边缘浅阴影 190（比纸心暗 30，不到 TRIM_REL_DIFF=45）→ 不裁
        val c3 = Mat(H, W, org.opencv.core.CvType.CV_8UC1)
        c3.setTo(org.opencv.core.Scalar.all(190.0))
        run {
            val inner = Mat(c3, org.opencv.core.Rect(60, 60, W - 120, H - 120))
            inner.setTo(org.opencv.core.Scalar.all(220.0))
            inner.release()
        }
        val r3 = try { InkSkew.trimDarkEdgesRect(c3) } catch (e: Throwable) {
            println("  ✗ 浅阴影: EXCEPTION ${e.javaClass.simpleName}"); fail++; null
        }
        c3.release()
        // 边缘均值 190 < 105? 否 → 不裁。允许返回 null
        val ok3 = r3 == null
        if (!ok3) fail++
        println("  ${if (ok3) "✓" else "✗"} 浅阴影190(不应裁)          ${relRect(r3, W, H)}")

        // 案例4：真实扫描图——允许裁掉真实存在的暗边（text_photo 右侧实测
        // 有 5% 左右背景带），但每边裁剪量必须 ≤ 上限 6%，验证保护逻辑生效
        val f4 = File(proj, "tools/text_photo_full.jpg")
        if (!f4.exists()) { println("  MISSING text_photo_full.jpg"); fail++ } else {
            val g4 = loadGray(f4.absolutePath)
            val gw = g4.cols(); val gh = g4.rows()
            val r4 = try { InkSkew.trimDarkEdgesRect(g4) } catch (e: Throwable) {
                println("  ✗ 真实图: EXCEPTION ${e.javaClass.simpleName}"); fail++; null
            }
            g4.release()
            val capW = gw * 6 / 100; val capH = gh * 6 / 100
            val ok4 = r4 == null ||
                (r4[0] in 0..capW && r4[1] in 0..capH &&
                    (gw - r4[2]) in 0..capW && (gh - r4[3]) in 0..capH)
            if (!ok4) fail++
            println("  ${if (ok4) "✓" else "✗"} 真实图text_photo(受6%上限保护) ${relRect(r4, gw, gh)}")
        }

        // 案例5：弧形顶边（暗色只占带内一部分，整带均值必然漏检）→ 应内裁。
        val c5 = Mat(H, W, org.opencv.core.CvType.CV_8UC1)
        c5.setTo(org.opencv.core.Scalar.all(220.0))
        for (x in 100 until 690 step 10) {
            val yA = (28.0 * kotlin.math.sin(Math.PI * (x - 100) / 600.0)).toInt()
            val yB = (28.0 * kotlin.math.sin(Math.PI * (x + 10 - 100) / 600.0)).toInt()
            Imgproc.line(c5, Point(x.toDouble(), yA.toDouble()),
                Point((x + 10).toDouble(), yB.toDouble()), org.opencv.core.Scalar.all(20.0), 8)
        }
        val r5 = try { InkSkew.trimDarkEdgesRect(c5) } catch (e: Throwable) {
            println("  ✗ 弧形顶边: EXCEPTION ${e.javaClass.simpleName}"); fail++; null
        }
        c5.release()
        val ok5 = r5 != null && r5[1] >= 20 && r5[1] <= 48
        if (!ok5) fail++
        println("  ${if (ok5) "✓" else "✗"} 弧形顶边(应裁20~48px)     ${relRect(r5, W, H)}")

        // 案例6：贴边的分散字块（每个连通域都很小）→ 不得拼成黑弧误裁。
        val c6 = Mat(H, W, org.opencv.core.CvType.CV_8UC1)
        c6.setTo(org.opencv.core.Scalar.all(220.0))
        for (x in 100 until 700 step 40) {
            Imgproc.rectangle(c6, Point(x.toDouble(), 0.0),
                Point((x + 12).toDouble(), 16.0), org.opencv.core.Scalar.all(20.0), -1)
        }
        val r6 = try { InkSkew.trimDarkEdgesRect(c6) } catch (e: Throwable) {
            println("  ✗ 贴边字块: EXCEPTION ${e.javaClass.simpleName}"); fail++; null
        }
        c6.release()
        val ok6 = r6 == null
        if (!ok6) fail++
        println("  ${if (ok6) "✓" else "✗"} 贴边分散字块(不应裁)      ${relRect(r6, W, H)}")

        // 案例7：贴边实心表格线（fill≈1）→ 弧形边分支不得误裁。
        val c7 = Mat(H, W, org.opencv.core.CvType.CV_8UC1)
        c7.setTo(org.opencv.core.Scalar.all(220.0))
        Imgproc.rectangle(c7, Point(100.0, 0.0), Point(700.0, 6.0),
            org.opencv.core.Scalar.all(20.0), -1)
        val r7 = try { InkSkew.trimDarkEdgesRect(c7) } catch (e: Throwable) {
            println("  ✗ 贴边表格线: EXCEPTION ${e.javaClass.simpleName}"); fail++; null
        }
        c7.release()
        val ok7 = r7 == null
        if (!ok7) fail++
        println("  ${if (ok7) "✓" else "✗"} 贴边实心表格线(不应裁)    ${relRect(r7, W, H)}")
    }

    println("== 二级平坦化冒烟（P85 自适应基准，彩色全链路真实执行）==")
    // 期望：执行无异常 + 纸面 P85 落在 [225, 255]（纯平坦不强制提白，
    // 白化由 liftPaperWhite 收口；正常曝光页纸面 P85 应在 240 上下）。
    for (n in listOf("text_photo_full.jpg", "book2_raw.jpg", "photo_full.jpg")) {
        val f = File(proj, "tools/$n")
        if (!f.exists()) { println("  MISSING $f"); fail++; continue }
        val bgr = Imgcodecs.imread(f.absolutePath)
        if (bgr.empty()) { println("  READ FAIL $f"); fail++; continue }
        val out = Mat()
        try {
            // 真实前置链：第一级背景归一 + 二级平坦化（单跑 flatten 不可比）
            InkSkew.normalizeForSmoke(bgr, out)
            if (bgr.depth() != org.opencv.core.CvType.CV_8U) {
                println("  ✗ $n: 输出深度非 8U"); fail++; continue
            }
            val g = Mat()
            Imgproc.cvtColor(out, g, Imgproc.COLOR_BGR2GRAY)
            val small = Mat()
            Imgproc.resize(g, small, org.opencv.core.Size(160.0, 160.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val p85 = paperPercentile(small, 85.0)
            small.release(); g.release()
            val ok = p85 in 225.0..255.0
            if (!ok) fail++
            println(String.format("  %s %-22s 纸面P85 %.0f", if (ok) "✓" else "✗", n, p85))
        } catch (e: Throwable) {
            println("  ✗ $n: EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++
        } finally {
            bgr.release(); out.release()
        }
    }

    println("== 黑白二值化对拍（输入 = Python 完整线上链路导出的灰度图）==")
    // 期望值来自 tools/train/diag_bin_hyst.py 方案 C（迟滞 34/18）在该灰度上的输出，
    // 并叠加 v1.5.8 贴边黑带清理后的新基线（diag_printpage.py 重标定：
    // text_photo 照片边缘背景带被清 15.80→12.86，CC 1455→1453；screen 无贴边带，不变）。
    // 输入必须用导出的 gray_*.png —— 对 raw 灰度直接二值化因缺背景归一不可比。
    for (c in listOf(
        Triple(File(proj, "tools/train/_bin_v2/gray_text_photo.png"), "text_photo", 12.86 to 1419),
        Triple(File(proj, "tools/train/_bin_v2/gray_screen.png"), "screen报告", 13.57 to 768),
    )) {
        val (f, label, expect) = c
        if (!f.exists()) { println("  MISSING $f"); fail++; continue }
        val gray = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (gray.empty()) { println("  READ FAIL $f"); fail++; continue }
        val bin = Mat()
        try {
            InkSkew.binarizeBlackWhite(gray, bin)
            val total = bin.total()
            val ink = total - Core.countNonZero(bin)
            val ratio = ink.toDouble() / total * 100.0
            val blackMask = Mat()
            Core.compare(bin, Scalar(0.0), blackMask, Core.CMP_EQ)
            val ncc = Imgproc.connectedComponents(blackMask, Mat()) - 1
            blackMask.release()
            val okRatio = Math.abs(ratio - expect.first) <= 4.0
            val okCC = Math.abs(ncc - expect.second) <= expect.second * 0.2
            val ok = okRatio && okCC
            if (!ok) fail++
            println(String.format(
                "  %s %-12s 黑占比 %.2f%% (py %.2f%%)  CC %d (py %d)",
                if (ok) "✓" else "✗", label, ratio, expect.first, ncc, expect.second
            ))
        } catch (e: Throwable) {
            println("  ✗ $label: EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++
        } finally {
            bin.release(); gray.release()
        }
    }

    println("== 黑白贴边弧线清理（黑弧应清、正文应留、实心表格线不误删）==")
    run {
        fun darkCount(m: Mat, x: Int, y: Int, rw: Int, rh: Int): Int {
            val r = Mat(m, org.opencv.core.Rect(x, y, rw, rh))
            val n = (r.total() - Core.countNonZero(r)).toInt()
            r.release()
            return n
        }
        val w = 800; val h = 1000
        val gray = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
        gray.setTo(org.opencv.core.Scalar.all(245.0))
        // 顶部弧形暗边 + 左侧斜细边（形态与真机 WPS 对拍样张一致）。
        for (x in 100 until 690 step 10) {
            val yA = (28.0 * kotlin.math.sin(Math.PI * (x - 100) / 600.0)).toInt()
            val yB = (28.0 * kotlin.math.sin(Math.PI * (x + 10 - 100) / 600.0)).toInt()
            Imgproc.line(gray, Point(x.toDouble(), yA.toDouble()),
                Point((x + 10).toDouble(), yB.toDouble()), org.opencv.core.Scalar.all(15.0), 8)
        }
        Imgproc.line(gray, Point(0.0, 300.0), Point(4.0, 520.0),
            org.opencv.core.Scalar.all(15.0), 5)
        // 页内正文笔画：清理贴边黑弧时必须原样保留。
        Imgproc.line(gray, Point(150.0, 400.0), Point(650.0, 400.0),
            org.opencv.core.Scalar.all(15.0), 4)
        val bin = Mat()
        try {
            InkSkew.binarizeBlackWhite(gray, bin)
            val topAfter = darkCount(bin, 0, 0, w, 60)
            val leftAfter = darkCount(bin, 0, 250, 20, 320)
            val bodyAfter = darkCount(bin, 140, 385, 520, 30)
            val ok = topAfter <= 20 && leftAfter <= 20 && bodyAfter >= 1500
            if (!ok) fail++
            println("  ${if (ok) "✓" else "✗"} 黑弧残留 顶=$topAfter 左=$leftAfter  正文黑像素=$bodyAfter")
        } catch (e: Throwable) {
            println("  ✗ 黑白弧线: EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++
        } finally {
            gray.release(); bin.release()
        }

        // 对照：贴边实心表格线 fill≈1，不属于弧形背景，必须保留。
        val table = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
        table.setTo(org.opencv.core.Scalar.all(245.0))
        Imgproc.rectangle(table, Point(100.0, 0.0), Point(700.0, 6.0),
            org.opencv.core.Scalar.all(15.0), -1)
        val tableBin = Mat()
        try {
            InkSkew.binarizeBlackWhite(table, tableBin)
            val tableAfter = darkCount(tableBin, 90, 0, 620, 20)
            val ok = tableAfter >= 3500
            if (!ok) fail++
            println("  ${if (ok) "✓" else "✗"} 贴边实心表格线保留黑像素=$tableAfter")
        } catch (e: Throwable) {
            println("  ✗ 表格线: EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++
        } finally {
            table.release(); tableBin.release()
        }
    }

    println("== 去手写冒烟（合成蓝笔迹+红印章：蓝色应被擦、红色应保留、黑字不动）==")
    run {
        // ★ 尺寸与笔画粗细必须"真实"：闭运算核 = 长边/180，只有核大于笔画
        //   宽度才能取到邻域纸色把笔迹填平。1600px 页面 → 核 9，真实笔迹约
        //   4px（A4 上 0.5mm 笔尖），故这里画 4px。早先用 400px 画布配 3px
        //   笔画（核只有 3）根本填不动，测试会假失败。
        val w = 1600; val h = 1200
        val img = Mat(h, w, org.opencv.core.CvType.CV_8UC3, Scalar(245.0, 245.0, 245.0))
        // 黑字笔画（不应被擦）
        Imgproc.line(img, Point(120.0, 120.0), Point(480.0, 120.0), Scalar(10.0, 10.0, 12.0), 4)
        // 蓝色手写笔迹（应被擦）
        Imgproc.line(img, Point(160.0, 320.0), Point(800.0, 360.0), Scalar(20.0, 40.0, 220.0), 4)
        Imgproc.line(img, Point(160.0, 480.0), Point(720.0, 520.0), Scalar(20.0, 40.0, 220.0), 4)
        // 红色印章块（应保留）
        Imgproc.rectangle(img, Point(1040.0, 720.0), Point(1320.0, 1000.0), Scalar(210.0, 0.0, 0.0), -1)
        // 判据用"暗像素数"而不是"彩色像素数"：擦除是局部均值填充，笔迹被
        // 抹平后色度虽降但可能仍有淡色残留，只有"变亮（不再是墨）"才是真擦掉。
        val blueBefore = countDarkInRect(img, 140, 290, 820, 550, 150.0)
        val redBefore = countDarkInRect(img, 1020, 700, 1340, 1020, 150.0)
        val blackBefore = countDarkInRect(img, 100, 100, 500, 140, 150.0)
        val work = img.clone()
        try {
            InkSkew.eraseColoredInk(work)
            val blueAfter = countDarkInRect(work, 140, 290, 820, 550, 150.0)
            val redAfter = countDarkInRect(work, 1020, 700, 1340, 1020, 150.0)
            val blackAfter = countDarkInRect(work, 100, 100, 500, 140, 150.0)
            // 蓝笔迹被擦（残留 <10%）；红印章几乎不动（>95%）；黑字完全不动
            val ok = blueAfter < blueBefore * 0.1 &&
                    redAfter > redBefore * 0.95 &&
                    blackAfter == blackBefore
            if (!ok) fail++
            println(String.format(
                "  %s 暗像素 蓝笔迹 %d→%d  红印章 %d→%d  黑字 %d→%d",
                if (ok) "✓" else "✗", blueBefore, blueAfter,
                redBefore, redAfter, blackBefore, blackAfter
            ))
        } catch (e: Throwable) {
            println("  ✗ EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++
        } finally {
            img.release(); work.release()
        }
    }

    println("== 去手写 ML 掩码擦除（eraseMaskedRegion：掩码区黑笔迹应被填平、掩码外印刷字不动）==")
    run {
        // 模拟 app 端 REMOVE_INK 的 ML 路径：HandwritingRemover 给出掩码后，
        // 擦除本体（膨胀 + 闭运算填充）是纯 OpenCV，必须在桌面 JVM 上验证。
        val w = 1600; val h = 1200
        val img = Mat(h, w, org.opencv.core.CvType.CV_8UC3, Scalar(245.0, 245.0, 245.0))
        // 黑色手写笔迹（掩码应覆盖并擦除）
        Imgproc.line(img, Point(200.0, 300.0), Point(900.0, 340.0), Scalar(15.0, 15.0, 18.0), 5)
        Imgproc.line(img, Point(200.0, 460.0), Point(820.0, 500.0), Scalar(15.0, 15.0, 18.0), 5)
        // 印刷文字笔画（不在掩码内，必须原样保留）
        Imgproc.line(img, Point(120.0, 120.0), Point(480.0, 120.0), Scalar(10.0, 10.0, 12.0), 4)
        val scribbleBefore = countDarkInRect(img, 180, 280, 920, 520, 150.0)
        val textBefore = countDarkInRect(img, 100, 100, 500, 140, 150.0)
        // 模拟 ML 掩码：只标手写区域（真实场景来自 256 掩码放大，这里有量化边）
        val mask = Mat.zeros(h, w, org.opencv.core.CvType.CV_8UC1)
        Imgproc.line(mask, Point(200.0, 300.0), Point(900.0, 340.0), Scalar(255.0, 255.0, 255.0), 9)
        Imgproc.line(mask, Point(200.0, 460.0), Point(820.0, 500.0), Scalar(255.0, 255.0, 255.0), 9)
        val work = img.clone()
        try {
            InkSkew.eraseMaskedRegion(work, mask)
            val scribbleAfter = countDarkInRect(work, 180, 280, 920, 520, 150.0)
            val textAfter = countDarkInRect(work, 100, 100, 500, 140, 150.0)
            val ok = scribbleAfter < scribbleBefore * 0.05 && textAfter == textBefore
            if (!ok) fail++
            println(String.format(
                "  %s 暗像素 手写掩码区 %d→%d  掩码外印刷字 %d→%d",
                if (ok) "✓" else "✗", scribbleBefore, scribbleAfter, textBefore, textAfter
            ))
        } catch (e: Throwable) {
            println("  ✗ EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++
        } finally {
            img.release(); work.release(); mask.release()
        }
    }

    println("== 去手写无损性·暖黄纸（无色文档走 REMOVE_INK 必须≈黑白链路）==")
    // 这条是"不影响目前的使用效果"的硬保证，也是抓出首版 bug 的那条用例：
    // 首版 chroma 阈值 28 把暖黄纸判成彩色并置白，局部均值被抬高 → Sauvola
    // 阈值上移 → book2 黑占比 7.05%→9.40%。这里把真实照片转成灰度再叠一层
    // 米黄纸色偏（B×0.88 / G×0.94，chroma≈29、sat≈0.12，正是真实纸面的量级），
    // 要求擦除前后黑占比几乎不变 —— 纸面绝不能被当成彩色笔迹处理掉。
    for (n in listOf("text_photo_full.jpg", "book2_raw.jpg", "photo_full.jpg")) {
        val f = File(proj, "tools/$n")
        if (!f.exists()) { println("  MISSING $f"); fail++; continue }
        val raw = Imgcodecs.imread(f.absolutePath)
        if (raw.empty()) { println("  READ FAIL $f"); fail++; continue }
        val bgr = Mat()
        try {
            val g = Mat()
            Imgproc.cvtColor(raw, g, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(g, bgr, Imgproc.COLOR_GRAY2BGR)   // 真正无色
            g.release()
            val ch = ArrayList<Mat>(3)
            Core.split(bgr, ch)
            try {
                Core.multiply(ch[0], Scalar(0.88), ch[0])       // 米黄纸：B 压暗
                Core.multiply(ch[1], Scalar(0.94), ch[1])       //          G 略压
                Core.merge(ch, bgr)
            } finally {
                ch.forEach { it.release() }
            }
            val a = Mat()
            val b = Mat()
            try {
                InkSkew.normalizeForSmoke(bgr, a)
                a.copyTo(b)
                InkSkew.eraseColoredInk(b)
                val ra = binBlackRatio(a)
                val rb = binBlackRatio(b)
                val diff = Math.abs(ra - rb)
                val ok = diff <= 0.10
                if (!ok) fail++
                println(String.format(
                    "  %s %-22s 黑占比 原链路 %.2f%%  去手写 %.2f%%  差 %.3f%%",
                    if (ok) "✓" else "✗", n, ra, rb, diff
                ))
            } finally {
                a.release(); b.release()
            }
        } finally {
            raw.release(); bgr.release()
        }
    }

    println("== 去手写无损性·真实照片（含彩色内容时允许合理变化，仅拦灾难性回归）==")
    // 这些照片本身可能含彩色插图/印章，擦掉它们本来就会改变结果，故只设宽松
    // 上限（首版 bug 的 2.35% 会被这里拦住），精确判据交给上面那条暖黄纸用例。
    for (n in listOf("text_photo_full.jpg", "book2_raw.jpg", "photo_full.jpg")) {
        val f = File(proj, "tools/$n")
        if (!f.exists()) { println("  MISSING $f"); fail++; continue }
        val bgr = Imgcodecs.imread(f.absolutePath)
        if (bgr.empty()) { println("  READ FAIL $f"); fail++; continue }
        val a = Mat()          // 原链路（等价"黑白文档"）
        val b = Mat()          // 插入去手写后的链路
        try {
            InkSkew.normalizeForSmoke(bgr, a)
            a.copyTo(b)
            InkSkew.eraseColoredInk(b)
            val ra = binBlackRatio(a)
            val rb = binBlackRatio(b)
            val diff = Math.abs(ra - rb)
            val ok = diff <= 1.50
            if (!ok) fail++
            println(String.format(
                "  %s %-22s 黑占比 原链路 %.2f%%  去手写 %.2f%%  差 %.3f%%",
                if (ok) "✓" else "✗", n, ra, rb, diff
            ))
        } catch (e: Throwable) {
            println("  ✗ $n: EXCEPTION ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            fail++
        } finally {
            bgr.release(); a.release(); b.release()
        }
    }

    println(if (fail == 0) "\nALL PASS" else "\nFAILED: $fail case(s)")
    kotlin.system.exitProcess(if (fail == 0) 0 else 1)
}

private fun loadGray(path: String): Mat {
    val bgr = Imgcodecs.imread(path)
    require(!bgr.empty()) { "cannot read $path" }
    val gray = Mat()
    Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
    bgr.release()
    return gray
}

/** 8 位单通道缩略图的 p 分位灰度（Main.kt 本地统计，不进 sync 范围）。 */
private fun paperPercentile(gray8: Mat, p: Double): Double {
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


/** 旋转并扩张画布，四角不裁。angleDeg 为 OpenCV 约定（正=逆时针）。 */
private fun rotateWithExpand(gray: Mat, angleDeg: Double): Mat {
    val w = gray.cols(); val h = gray.rows()
    val m = Imgproc.getRotationMatrix2D(
        org.opencv.core.Point(w / 2.0, h / 2.0), angleDeg, 1.0
    )
    val cos = Math.abs(m.get(0, 0)[0]); val sin = Math.abs(m.get(0, 1)[0])
    val nw = (h * sin + w * cos).toInt(); val nh = (h * cos + w * sin).toInt()
    m.put(0, 2, m.get(0, 2)[0] + nw / 2.0 - w / 2.0)
    m.put(1, 2, m.get(1, 2)[0] + nh / 2.0 - h / 2.0)
    val out = Mat()
    Imgproc.warpAffine(gray, out, m, org.opencv.core.Size(nw.toDouble(), nh.toDouble()))
    m.release()
    return out
}

/** 统计整张 BGR 图中"彩色"像素数（与 app 端同口径：chroma>28 且 max>45）。 */
private fun countColored(m: Mat): Int {
    val n = m.rows() * m.cols()
    val buf = ByteArray(n * 3)
    m.get(0, 0, buf)
    var cnt = 0
    for (i in 0 until n) {
        val r = buf[i * 3 + 2].toInt() and 0xFF
        val g = buf[i * 3 + 1].toInt() and 0xFF
        val b = buf[i * 3].toInt() and 0xFF
        val maxc = maxOf(r, g, b)
        val minc = minOf(r, g, b)
        if (maxc - minc > 28 && maxc > 45) cnt++
    }
    return cnt
}

/**
 * 统计矩形区域内"暗像素"（平均灰度 < thresh）数量。
 * 去手写是局部均值填充，笔迹被抹平后仍可能带淡色，故用"是否还暗"
 * 判断是否真被擦掉，比数彩色像素更能反映最终黑白输出。
 */
private fun countDarkInRect(m: Mat, x0: Int, y0: Int, x1: Int, y1: Int, thresh: Double): Int {
    val xa = maxOf(0, x0); val ya = maxOf(0, y0)
    val xb = minOf(m.cols() - 1, x1); val yb = minOf(m.rows() - 1, y1)
    var cnt = 0
    val px = ByteArray(3)
    for (y in ya..yb) {
        for (x in xa..xb) {
            m.get(y, x, px)
            val g = ((px[0].toInt() and 0xFF) +
                    (px[1].toInt() and 0xFF) +
                    (px[2].toInt() and 0xFF)) / 3.0
            if (g < thresh) cnt++
        }
    }
    return cnt
}

/** BGR 转灰后走 binarizeBlackWhite，返回黑占比（%）。 */
private fun binBlackRatio(bgr: Mat): Double {
    val gray = Mat()
    val bin = Mat()
    try {
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        InkSkew.binarizeBlackWhite(gray, bin)
        return (bin.total() - Core.countNonZero(bin)).toDouble() / bin.total() * 100.0
    } finally {
        gray.release(); bin.release()
    }
}

/** 统计矩形区域内"彩色"像素数（坐标含端点，自动夹到画幅内）。 */
private fun countColoredInRect(m: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Int {
    val xa = maxOf(0, x0); val ya = maxOf(0, y0)
    val xb = minOf(m.cols() - 1, x1); val yb = minOf(m.rows() - 1, y1)
    var cnt = 0
    val px = ByteArray(3)
    for (y in ya..yb) {
        for (x in xa..xb) {
            m.get(y, x, px)
            val r = px[2].toInt() and 0xFF
            val g = px[1].toInt() and 0xFF
            val b = px[0].toInt() and 0xFF
            val maxc = maxOf(r, g, b)
            val minc = minOf(r, g, b)
            if (maxc - minc > 28 && maxc > 45) cnt++
        }
    }
    return cnt
}
