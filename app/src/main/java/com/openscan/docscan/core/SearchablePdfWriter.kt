package com.openscan.docscan.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

/**
 * 可搜索 PDF 生成器（体积优化版）。
 *
 * ## 为什么不用 Android 的 PdfDocument
 *
 * `android.graphics.pdf.PdfDocument` 把 `drawText` 渲染成矢量路径，
 * 产出的 PDF 里**没有任何文字对象**——搜不到、选不中、复制不了。
 * 要做到真正可搜索，必须直接写 PDF 的文本操作符（`BT` / `Tj` / `ET`）
 * 并保留 `ToUnicode` 映射。所以这里手写 PDF 结构。
 *
 * ## 体积优化要点（对标 WPS）
 *
 * 1. **不嵌入整份字体**。文字层用渲染模式 3（不可见），字形永不绘制，
 *    因此不需要字形轮廓；只保留 `ToUnicode` CMap（~1KB）即可保证
 *    复制/搜索拿到正确中文（Acrobat / PDF.js / Chrome 均按 ToUnicode 抽取）。
 *    这把过去整份嵌入 NotoSansCJK（10~25MB）的固定开销砍到可忽略。
 * 2. **图像降采样**：[PdfExportConfig.imageMaxLongEdge] 默认 2200px（≈A4 200DPI），
 *    避免把 4000~6000px 原图原样塞进 PDF。
 * 3. **灰度**：文档本质是灰度/黑白，默认转 `/DeviceGray` 单通道
 *    （OpenCV 编码真·单通道 JPEG），比 RGB 少 2/3 数据。
 * 4. **JPEG 质量 72**：文档扫描足够清晰，远小于默认的 88。
 * 5. **黑白页单色通道**：`enhanceMode == BLACK_WHITE` 的页走 1-bit +
 *    `/FlateDecode`（`/DeviceGray /BitsPerComponent 1`），比 JPEG 再小一个数量级。
 *
 * ## 结构
 *
 * ```
 * %PDF-1.4
 *   1 0 obj  Catalog
 *   2 0 obj  Pages
 *   3 0 obj  Font（Type0 / Identity-H，非嵌入，仅 ToUnicode）
 *   4 0 obj  FontDescriptor（无 FontFile）
 *   5 0 obj  ToUnicode CMap
 *   ...      每页：Page + Contents(XObject: 图像 + 文字层)
 *   xref
 *   trailer
 * ```
 *
 * 文字定位用 OCR 给出的边界框，按图像坐标 → PDF 坐标换算。
 */
class SearchablePdfWriter {

    /** PDF 导出配置：默认即对标 WPS 的紧凑体积。 */
    data class PdfExportConfig(
        /** 嵌入图像长边上限（像素）。0 表示不限制。默认 2200 ≈ A4 200DPI。 */
        val imageMaxLongEdge: Int = 2200,
        /** 是否默认转灰度。彩色照片页可用 [Page.forceColor] 覆盖。 */
        val grayscale: Boolean = true,
        /** JPEG 质量 0~100。文档扫描 70~75 已足够清晰。 */
        val jpegQuality: Int = 72,
        /** 是否对黑白增强页启用 1-bit 单色通道（FlateDecode）。 */
        val monochromeEnabled: Boolean = true
    )

    /** 一页的输入。 */
    data class Page(
        val bitmap: Bitmap,
        /** OCR 结果；为空表示这页没有文字层 */
        val ocr: OcrEngine.PageResult? = null,
        /**
         * 可选的单色位图（8-bit，墨=0/背景=255）。非 null 且
         * [PdfExportConfig.monochromeEnabled] 为真时，优先用 1-bit 通道，
         * 体积远小于 JPEG。
         */
        val monoBitmap: Bitmap? = null,
        /** 强制保留彩色（如原图/照片页），覆盖全局灰度设置。 */
        val forceColor: Boolean = false
    )

    /**
     * 写出 PDF。
     *
     * @param output     目标文件
     * @param pages      页面列表
     * @param config     体积/质量配置
     * @param onProgress 进度回调 0f..1f
     */
    fun write(
        output: File,
        pages: List<Page>,
        config: PdfExportConfig = PdfExportConfig(),
        onProgress: (Float) -> Unit = {}
    ) {
        require(pages.isNotEmpty()) { "至少要有一页" }

        val needFont = pages.any { it.ocr != null && it.ocr.lines.isNotEmpty() }

        val objects = PdfObjectTable()
        val pageObjectIds = ArrayList<Int>()

        // ---- 固定资源对象 ----
        val catalogId = objects.reserve()
        val pagesId = objects.reserve()

        var fontId = -1
        var fontDescriptorId = -1
        var toUnicodeId = -1

        if (needFont) {
            fontId = objects.reserve()
            fontDescriptorId = objects.reserve()
            toUnicodeId = objects.reserve()
        }

        // ---- 逐页 ----
        pages.forEachIndexed { index, page ->
            val imageId = objects.reserve()
            val contentId = objects.reserve()
            val pageId = objects.reserve()
            pageObjectIds.add(pageId)

            // 图像 XObject：黑白单色 > 灰度/彩色 JPEG
            val useMono = config.monochromeEnabled && page.monoBitmap != null
            val spec: ImageSpec = if (useMono) {
                val mono = page.monoBitmap!!
                val bytes = encodeMonochrome(mono)
                ImageSpec(
                    data = bytes,
                    cs = "/DeviceGray",
                    bits = 1,
                    flt = "/FlateDecode",
                    width = mono.width,
                    height = mono.height
                )
            } else {
                val prepared = downsampleIfNeeded(page.bitmap, config.imageMaxLongEdge)
                val effectiveGray = config.grayscale && !page.forceColor
                val (bytes, cs) = encodeImage(prepared, effectiveGray, config.jpegQuality)
                if (prepared !== page.bitmap) prepared.recycle()
                ImageSpec(
                    data = bytes,
                    cs = cs,
                    bits = 8,
                    flt = "/DCTDecode",
                    width = prepared.width,
                    height = prepared.height
                )
            }
            val imgBytes = spec.data
            val colorSpace = spec.cs
            val bpc = spec.bits
            val filter = spec.flt
            val w = spec.width
            val h = spec.height

            val imgDict = buildString {
                append("<< /Type /XObject /Subtype /Image ")
                append("/Width $w /Height $h ")
                append("/ColorSpace $colorSpace /BitsPerComponent $bpc ")
                append("/Filter $filter /Length ${imgBytes.size} >>")
            }
            objects.set(
                imageId,
                imgDict.toByteArray(StandardCharsets.US_ASCII),
                stream = imgBytes
            )

            // 页面内容：先铺图像，再叠文字层（用实际嵌入图尺寸，避免错位）
            val content = buildPageContent(page, needFont, w, h)
            val contentBytes = content.toByteArray(StandardCharsets.US_ASCII)
            objects.set(
                contentId,
                "<< /Length ${contentBytes.size} >>".toByteArray(StandardCharsets.US_ASCII),
                stream = contentBytes
            )

            val fontRes = if (needFont) " /Font << /F1 $fontId 0 R >>" else ""
            objects.set(
                pageId,
                ("<< /Type /Page /Parent $pagesId 0 R " +
                        "/MediaBox [0 0 $w $h] " +
                        "/Resources << /XObject << /Im0 $imageId 0 R >>$fontRes >> " +
                        "/Contents $contentId 0 R >>").toByteArray(StandardCharsets.US_ASCII)
            )

            onProgress((index + 1).toFloat() / (pages.size + 1))
        }

        // ---- 字体对象（非嵌入，仅 ToUnicode）----
        if (needFont) {
            objects.set(
                fontId,
                ("<< /Type /Font /Subtype /Type0 /BaseFont /NotoSansCJK " +
                        "/Encoding /Identity-H /DescendantFonts [$fontDescriptorId 0 R] " +
                        "/ToUnicode $toUnicodeId 0 R >>")
                    .toByteArray(StandardCharsets.US_ASCII)
            )

            // 无 FontFile：文字层为渲染模式 3 不可见，不需要字形轮廓；
            // 抽取由 ToUnicode 驱动，与是否嵌入字体无关。
            objects.set(
                fontDescriptorId,
                ("<< /Type /FontDescriptor /FontName /NotoSansCJK /Flags 4 " +
                        "/FontBBox [-1000 -500 3000 1500] /ItalicAngle 0 /Ascent 1160 " +
                        "/Descent -320 /CapHeight 1160 /StemV 80 >>")
                    .toByteArray(StandardCharsets.US_ASCII)
            )

            val cMap = buildIdentityToUnicodeCMap()
            objects.set(
                toUnicodeId,
                "<< /Length ${cMap.size} >>".toByteArray(StandardCharsets.US_ASCII),
                stream = cMap
            )
        }

        // ---- Catalog / Pages ----
        objects.set(
            catalogId,
            "<< /Type /Catalog /Pages $pagesId 0 R >>".toByteArray(StandardCharsets.US_ASCII)
        )

        val kids = pageObjectIds.joinToString(" ") { "$it 0 R" }
        objects.set(
            pagesId,
            "<< /Type /Pages /Kids [$kids] /Count ${pageObjectIds.size} >>"
                .toByteArray(StandardCharsets.US_ASCII)
        )

        // ---- 序列化 ----
        FileOutputStream(output).use { fos ->
            serialize(fos, objects, catalogId)
        }

        onProgress(1f)
    }

    /** 图像规格的临时载体（data class 以便后续扩展/校验）。 */
    private data class ImageSpec(
        val data: ByteArray,
        val cs: String,
        val bits: Int,
        val flt: String,
        val width: Int,
        val height: Int
    )

    // ---------------------------------------------------------------- ToUnicode

    /**
     * 构建 ToUnicode CMap。
     *
     * 作用：告诉 PDF 阅读器"字形索引 X 对应哪个 Unicode 字符"，
     * 是**复制/搜索能拿到正确文字**的关键。没有它，Ctrl+F 搜到的是乱码。
     *
     * Identity-H 编码下字形索引 = Unicode 码位，所以映射是恒等的。
     * 用 bfrange 按区间批量声明，比逐字符 bfchar 紧凑得多。
     */
    private fun buildIdentityToUnicodeCMap(): ByteArray {
        // 分段给出常用区间，避免生成十几万行映射
        val ranges = listOf(
            0x0020 to 0x007E,   // Basic Latin
            0x00A0 to 0x00FF,   // Latin-1 补充
            0x2000 to 0x206F,   // 常用标点
            0x3000 to 0x303F,   // 中日韩符号标点
            0x3040 to 0x30FF,   // 平假名 / 片假名
            0x4E00 to 0x9FFF,   // CJK 统一表意文字（常用汉字）
            0xAC00 to 0xD7AF,   // 韩文音节
            0xF900 to 0xFAFF,   // CJK 兼容表意文字
            0xFF00 to 0xFFEF    // 全角形式
        )

        val sb = StringBuilder()
        sb.append("/CIDInit /ProcSet findresource begin\n")
        sb.append("12 dict begin\n")
        sb.append("begincmap\n")
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        sb.append("/CMapName /Adobe-Identity-UCS def\n")
        sb.append("/CMapType 2 def\n")
        sb.append("1 begincodespacerange\n")
        sb.append("<0000> <FFFF>\n")
        sb.append("endcodespacerange\n")

        sb.append("${ranges.size} beginbfrange\n")
        ranges.forEach { (start, end) ->
            sb.append("<").append(hex4(start)).append("> ")
            sb.append("<").append(hex4(end)).append("> ")
            sb.append("<").append(hex4(start)).append(">\n")
        }
        sb.append("endbfrange\n")

        sb.append("endcmap\n")
        sb.append("CMapName currentdict /CMap defineresource pop\n")
        sb.append("end\nend\n")

        return sb.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    private fun hex4(v: Int): String = String.format("%04X", v and 0xFFFF)

    // ---------------------------------------------------------------- 内容流

    /**
     * 构建单页内容流。
     *
     * 图像用 `cm` 变换矩阵铺满页面（PDF 坐标系原点在左下角，而图像
     * 采样原点在左上角，所以要翻转 Y 轴）。
     * 文字层用渲染模式 3 设为不可见。
     *
     * @param imgW/imgH 实际嵌入图像的像素尺寸（单色/降采样后可能与原图不同，
     *                  必须用它来铺图，文字层坐标亦基于此空间）
     */
    private fun buildPageContent(page: Page, hasFont: Boolean, imgW: Int, imgH: Int): String {
        val sb = StringBuilder()
        val w = imgW.toFloat()
        val h = imgH.toFloat()

        // ---- 1. 图像：铺满整页 ----
        sb.append("q\n")
        sb.append("$w 0 0 $h 0 0 cm\n")
        sb.append("/Im0 Do\n")
        sb.append("Q\n")

        // ---- 2. 文字层 ----
        val ocr = page.ocr
        if (!hasFont || ocr == null || ocr.lines.isEmpty()) return sb.toString()

        sb.append("BT\n")
        // 渲染模式 3 = 不可见；但内容仍参与搜索与选择
        sb.append("3 Tr\n")

        ocr.lines.forEach { line ->
            val box = line.boundingBox
            if (box.width() <= 0 || box.height() <= 0) return@forEach

            // 字号按行高估算。PDF 中 1 单位 = 1/72 英寸，这里 1 单位 = 1 像素，
            // 于是字号在数值上就等于像素行高。
            val fontSize = box.height().toFloat().coerceAtLeast(1f)

            // PDF 原点在左下角，图像原点在左上角，Y 轴需要翻转。
            // 文字基线落在行框底边，所以用 bottom 而不是 top。
            val x = box.left.toFloat()
            val y = h - box.bottom.toFloat()

            // 水平缩放：让文字层宽度贴合 OCR 框，这样 PDF 阅读器里
            // 划选高亮能大致覆盖原图文字。不影响可搜索性。
            val naturalWidth = estimateTextWidth(line.text, fontSize)
            val hScale = if (naturalWidth > 0f) {
                (box.width().toFloat() / naturalWidth).coerceIn(0.2f, 5f)
            } else {
                1f
            }

            // 把定位与缩放合并成一次 Tm，避免第二条 Tm 覆盖掉坐标。
            // 字体矩阵 [a b c d e f]：a=横向缩放，d=字号，e/f=平移。
            sb.append(fmt(fontSize * hScale)).append(" 0 0 ")
            sb.append(fmt(fontSize)).append(' ')
            sb.append(fmt(x)).append(' ').append(fmt(y)).append(" Tm\n")

            // Identity-H：每个字符写成 4 位十六进制 UTF-16BE 码位
            sb.append('<')
            line.text.forEach { ch ->
                val code = ch.code
                sb.append(String.format("%04X", code and 0xFFFF))
            }
            sb.append("> Tj\n")
        }

        sb.append("ET\n")
        return sb.toString()
    }

    /**
     * 估算文本在给定字号下的自然宽度。
     *
     * 中日韩字符按全角（1em）估，拉丁字符按 0.5em 估。
     * 这只是为了让文字层框选范围大致贴合，不影响可搜索性。
     */
    private fun estimateTextWidth(text: String, fontSize: Float): Float {
        var units = 0f
        text.forEach { ch ->
            units += if (isWide(ch)) 1.0f else 0.52f
        }
        return units * fontSize
    }

    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return code in 0x1100..0x115F ||   // 韩文字母
                code in 0x2E80..0xA4CF ||   // 中日韩部首、假名、汉字
                code in 0xAC00..0xD7A3 ||   // 韩文音节
                code in 0xF900..0xFAFF ||   // 兼容汉字
                code in 0xFE30..0xFE6F ||   // 中日韩兼容形式
                code in 0xFF00..0xFF60 ||   // 全角 ASCII
                code in 0xFFE0..0xFFE6
    }

    private fun fmt(v: Float): String {
        // 保留两位小数，去掉多余的尾零，控制 PDF 体积
        val s = String.format("%.2f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    // ---------------------------------------------------------------- 图像编码

    /** 长边超出上限则等比缩小（避免把超大原图塞进 PDF，也防 OOM）。 */
    private fun downsampleIfNeeded(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        if (maxLongEdge <= 0) return bitmap
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxLongEdge) return bitmap
        val scale = maxLongEdge.toFloat() / longEdge
        val w = maxOf(1, (bitmap.width * scale).toInt())
        val h = maxOf(1, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /**
     * 编码图像为 JPEG 字节，返回 (字节, 颜色空间)。
     *
     * - 灰度：OpenCV 转 CV_8UC1 后编码**单通道 JPEG** → `/DeviceGray`（真 8-bit 灰度）。
     * - 彩色：RGB JPEG → `/DeviceRGB`。
     * 用 OpenCV 编码是为了拿到"真正的单通道灰度 JPEG"（Android Bitmap 无法原生输出单通道 JPEG）。
     * 若 OpenCV 不可用，退化为 `Bitmap.compress`（RGB）。
     */
    private fun encodeImage(
        bitmap: Bitmap,
        grayscale: Boolean,
        quality: Int
    ): Pair<ByteArray, String> {
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality)
            val buf = MatOfByte()
            val cs = if (grayscale) {
                val gray = Mat()
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgcodecs.imencode(".jpg", gray, buf, params)
                gray.release()
                "/DeviceGray"
            } else {
                val bgr = Mat()
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                Imgcodecs.imencode(".jpg", bgr, buf, params)
                bgr.release()
                "/DeviceRGB"
            }
            rgba.release()
            buf.toArray() to cs
        } catch (e: Exception) {
            Log.w(TAG, "OpenCV 编码失败，回退 Bitmap.compress: ${e.message}")
            val rgb = if (grayscale) toGrayBitmap(bitmap) else bitmap
            val bos = ByteArrayOutputStream()
            rgb.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            if (rgb !== bitmap) rgb.recycle()
            bos.toByteArray() to "/DeviceRGB"
        }
    }

    /** 用 ColorMatrix 把位图去饱和成灰度（仅作 OpenCV 不可用时的回退）。 */
    private fun toGrayBitmap(src: Bitmap): Bitmap {
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * 把二值位图打包成 1-bit 行（MSB 在前，每行按字节补齐）再用 zlib 压缩。
     *
     * 约定：暗像素（墨）= 0 → 黑；亮像素（背景）= 1 → 白。
     * 配合 `/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode`，
     * 渲染器把 0 当黑、1 当白，符合扫描件直觉。
     */
    private fun encodeMonochrome(mono: Bitmap): ByteArray {
        val w = mono.width
        val h = mono.height
        val pixels = IntArray(w * h)
        mono.getPixels(pixels, 0, w, 0, 0, w, h)

        val rowBytes = (w + 7) / 8
        val data = ByteArray(rowBytes * h)
        for (y in 0 until h) {
            val rowBase = y * rowBytes
            val pixBase = y * w
            for (x in 0 until w) {
                val px = pixels[pixBase + x]
                val r = px shr 16 and 0xFF
                val g = px shr 8 and 0xFF
                val b = px and 0xFF
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                if (lum >= 128) { // 亮 → 白 → bit=1
                    val byteIndex = rowBase + (x shr 3)
                    data[byteIndex] = (data[byteIndex].toInt() or (0x80 ushr (x and 7))).toByte()
                }
                // 暗 → 黑 → bit=0（data 已初始化为 0）
            }
        }

        val def = Deflater(Deflater.BEST_COMPRESSION)
        def.setInput(data)
        def.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!def.finished()) {
            val n = def.deflate(buf)
            out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 序列化

    /** 惰性分配的对象表，支持先占位后填充，方便处理交叉引用。 */
    private class PdfObjectTable {
        private val entries = HashMap<Int, Pair<ByteArray, ByteArray?>>()
        private var next = 1

        fun reserve(): Int = next++

        fun set(id: Int, header: ByteArray, stream: ByteArray? = null) {
            entries[id] = header to stream
        }

        fun ordered(): List<Triple<Int, ByteArray, ByteArray?>> =
            entries.entries.sortedBy { it.key }.map {
                Triple(it.key, it.value.first, it.value.second)
            }
    }

    private fun serialize(fos: FileOutputStream, objects: PdfObjectTable, catalogId: Int) {
        val out = fos
        val offsets = HashMap<Int, Long>()
        var position = 0L

        fun write(bytes: ByteArray) {
            out.write(bytes)
            position += bytes.size
        }

        fun writeAscii(s: String) = write(s.toByteArray(StandardCharsets.US_ASCII))

        writeAscii("%PDF-1.4\n")
        // 二进制标记，提示消费方这是二进制文件
        write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        objects.ordered().forEach { (id, header, stream) ->
            offsets[id] = position
            writeAscii("$id 0 obj\n")
            write(header)
            if (stream != null) {
                writeAscii("\nstream\n")
                write(stream)
                writeAscii("\nendstream")
            }
            writeAscii("\nendobj\n")
        }

        // 交叉引用表
        val xrefPos = position
        val maxId = objects.ordered().maxOfOrNull { it.first } ?: 1
        writeAscii("xref\n")
        writeAscii("0 ${maxId + 1}\n")
        writeAscii("0000000000 65535 f \n")
        for (id in 1..maxId) {
            val off = offsets[id] ?: 0L
            writeAscii(String.format("%010d 00000 n \n", off))
        }

        writeAscii("trailer\n")
        writeAscii("<< /Size ${maxId + 1} /Root $catalogId 0 R >>\n")
        writeAscii("startxref\n")
        writeAscii("$xrefPos\n")
        writeAscii("%%EOF\n")

        out.flush()
    }

    companion object {
        private const val TAG = "SearchablePdfWriter"
    }
}
