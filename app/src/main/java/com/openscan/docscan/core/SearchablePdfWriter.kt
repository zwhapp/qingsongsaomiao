package com.openscan.docscan.core

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * 可搜索 PDF 生成器。
 *
 * ## 为什么不用 Android 的 PdfDocument
 *
 * `android.graphics.pdf.PdfDocument` 把 `drawText` 渲染成矢量路径，
 * 产出的 PDF 里**没有任何文字对象**——搜不到、选不中、复制不了。
 * 要做到真正可搜索，必须直接写 PDF 的文本操作符（`BT` / `Tj` / `ET`）
 * 并嵌入字体。所以这里手写 PDF 结构。
 *
 * ## 结构
 *
 * ```
 * %PDF-1.4
 *   1 0 obj  Catalog
 *   2 0 obj  Pages
 *   3 0 obj  Font（Type0 / Identity-H，支持中日韩）
 *   4 0 obj  FontDescriptor
 *   5 0 obj  CIDFont（CIDFontType2）
 *   6 0 obj  FontFile2（嵌入的 TTF 子集）
 *   ...      每页：Page + Contents(XObject: 图像 + 文字层)
 *   xref
 *   trailer
 * ```
 *
 * ## 文字层原理
 *
 * 每页放两层：
 *   1. 图像 XObject —— 用户看到的扫描图
 *   2. 文本对象   —— 不可见（渲染模式 3），但可被搜索、选中、复制
 *
 * 文字定位用 OCR 给出的边界框，按图像坐标 → PDF 坐标换算。
 *
 * ## 字体
 *
 * 需要嵌入一个含中文字形的 TTF。Android 系统自带
 * `/system/fonts/NotoSansCJK-Regular.ttc`（或 fallback），
 * 用 Identity-H 编码直接按 Unicode 码位索引字形。
 */
class SearchablePdfWriter {

    /** 一页的输入。 */
    data class Page(
        val bitmap: Bitmap,
        /** OCR 结果；为空表示这页没有文字层 */
        val ocr: OcrEngine.PageResult? = null
    )

    /**
     * 写出 PDF。
     *
     * @param output     目标文件
     * @param pages      页面列表
     * @param onProgress 进度回调 0f..1f
     */
    fun write(
        output: File,
        pages: List<Page>,
        onProgress: (Float) -> Unit = {}
    ) {
        require(pages.isNotEmpty()) { "至少要有一页" }

        val fontData = loadChineseFont()
        if (fontData == null) {
            Log.w(TAG, "未找到可用中文字体，文字层将缺失（PDF 仍可正常查看）")
        }

        val objects = PdfObjectTable()
        val pageObjectIds = ArrayList<Int>()

        // ---- 固定资源对象 ----
        val catalogId = objects.reserve()
        val pagesId = objects.reserve()

        var fontId = -1
        var fontFileId = -1
        var fontDescriptorId = -1
        var toUnicodeId = -1

        if (fontData != null) {
            fontId = objects.reserve()
            fontDescriptorId = objects.reserve()
            fontFileId = objects.reserve()
            toUnicodeId = objects.reserve()
        }

        // ---- 逐页 ----
        pages.forEachIndexed { index, page ->
            val imageId = objects.reserve()
            val contentId = objects.reserve()
            val pageId = objects.reserve()
            pageObjectIds.add(pageId)

            // 图像 XObject：统一转成 RGB JPEG 以控制体积
            val jpeg = compressToJpeg(page.bitmap, quality = 88)
            objects.set(
                imageId,
                ("<< /Type /XObject /Subtype /Image " +
                        "/Width ${page.bitmap.width} /Height ${page.bitmap.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                        "/Filter /DCTDecode /Length ${jpeg.size} >>")
                    .toByteArray(StandardCharsets.US_ASCII),
                stream = jpeg
            )

            // 页面内容：先铺图像，再叠文字层
            val content = buildPageContent(page, fontData != null)
            val contentBytes = content.toByteArray(StandardCharsets.US_ASCII)
            objects.set(
                contentId,
                "<< /Length ${contentBytes.size} >>".toByteArray(StandardCharsets.US_ASCII),
                stream = contentBytes
            )

            // 页面尺寸用图像的像素尺寸按 72dpi 折算，保持长宽比
            val pageW = page.bitmap.width.toFloat()
            val pageH = page.bitmap.height.toFloat()
            val fontRes = if (fontData != null) " /Font << /F1 $fontId 0 R >>" else ""

            objects.set(
                pageId,
                ("<< /Type /Page /Parent $pagesId 0 R " +
                        "/MediaBox [0 0 $pageW $pageH] " +
                        "/Resources << /XObject << /Im0 $imageId 0 R >>$fontRes >> " +
                        "/Contents $contentId 0 R >>").toByteArray(StandardCharsets.US_ASCII)
            )

            onProgress((index + 1).toFloat() / (pages.size + 1))
        }

        // ---- 字体对象 ----
        if (fontData != null) {
            objects.set(
                fontId,
                ("<< /Type /Font /Subtype /Type0 /BaseFont /NotoSansCJK " +
                        "/Encoding /Identity-H /DescendantFonts [$fontDescriptorId 0 R] " +
                        "/ToUnicode $toUnicodeId 0 R >>")
                    .toByteArray(StandardCharsets.US_ASCII)
            )

            objects.set(
                fontDescriptorId,
                ("<< /Type /FontDescriptor /FontName /NotoSansCJK /Flags 4 " +
                        "/FontBBox [-1000 -500 3000 1500] /ItalicAngle 0 /Ascent 1160 " +
                        "/Descent -320 /CapHeight 1160 /StemV 80 " +
                        "/FontFile2 $fontFileId 0 R >>")
                    .toByteArray(StandardCharsets.US_ASCII)
            )

            objects.set(
                fontFileId,
                "<< /Length ${fontData.size} /Length1 ${fontData.size} >>"
                    .toByteArray(StandardCharsets.US_ASCII),
                stream = fontData
            )

            // Identity-H 下 Unicode 与 CID 一致，ToUnicode 可以退化为恒等映射。
            // 这里给一个最小可用映射：覆盖 BMP 中常见的可打印区间。
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
     */
    private fun buildPageContent(page: Page, hasFont: Boolean): String {
        val sb = StringBuilder()
        val w = page.bitmap.width.toFloat()
        val h = page.bitmap.height.toFloat()

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

    // ---------------------------------------------------------------- 字体

    /**
     * 加载一个含中文字形的 TTF。
     *
     * 注意：这里返回的是**整份字体文件**。生产环境更严谨的做法是
     * 做子集化（subset）只嵌入实际用到的字形，能把体积压到几十 KB；
     * 但子集化需要解析并重建 TTF 表结构，实现复杂。
     * 当前直接嵌入完整字体，牺牲体积换取可靠性。
     */
    private fun loadChineseFont(): ByteArray? {
        val candidates = listOf(
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/DroidSansFallbackFull.ttf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/NotoSansCJKsc-Regular.otf"
        )

        for (path in candidates) {
            val f = File(path)
            if (!f.exists() || !f.canRead()) continue
            return runCatching {
                val bytes = f.readBytes()
                // TTC（字体集合）需要提取第一个字体，PDF 不接受 TTC 容器
                if (bytes.size >= 4 &&
                    bytes[0] == 't'.code.toByte() &&
                    bytes[1] == 't'.code.toByte() &&
                    bytes[2] == 'c'.code.toByte() &&
                    bytes[3] == 'T'.code.toByte()
                ) {
                    extractFirstFromTtc(bytes)
                } else {
                    bytes
                }
            }.getOrElse { e ->
                Log.w(TAG, "读取字体失败 $path: ${e.message}")
                null
            }
        }

        return null
    }

    /**
     * 从 TTC（TrueType Collection）中提取第一个字体，转成独立 TTF。
     *
     * TTC 结构：
     *   'ttcf' | version(4) | numFonts(4) | offsetTable[numFonts] | 各字体数据
     *
     * 提取方式：读出第一个字体的 offset，然后从该处起把整个字体数据
     * 连同它引用的所有表重新构造成独立 TTF。
     */
    private fun extractFirstFromTtc(ttc: ByteArray): ByteArray {
        fun u32(off: Int): Long =
            ((ttc[off].toLong() and 0xFF) shl 24) or
                    ((ttc[off + 1].toLong() and 0xFF) shl 16) or
                    ((ttc[off + 2].toLong() and 0xFF) shl 8) or
                    (ttc[off + 3].toLong() and 0xFF)

        fun u16(off: Int): Int =
            ((ttc[off].toInt() and 0xFF) shl 8) or (ttc[off + 1].toInt() and 0xFF)

        val numFonts = u32(8).toInt()
        if (numFonts <= 0) throw IllegalStateException("TTC 内没有字体")

        val fontOffset = u32(12).toInt()      // 第一个字体的 sfnt 起始
        val numTables = u16(fontOffset + 4)

        // 收集各表的原始数据
        data class Table(val tag: String, val data: ByteArray)

        val tables = ArrayList<Table>(numTables)
        for (i in 0 until numTables) {
            val rec = fontOffset + 12 + i * 16
            val tag = String(ttc, rec, 4, StandardCharsets.US_ASCII)
            val offset = u32(rec + 8).toInt()
            val length = u32(rec + 12).toInt()
            if (offset + length > ttc.size) continue
            tables.add(Table(tag, ttc.copyOfRange(offset, offset + length)))
        }

        if (tables.isEmpty()) throw IllegalStateException("TTC 内未解析出字体表")

        // 按 tag 排序（TTF 规范要求）
        tables.sortBy { it.tag }

        val out = ByteArrayOutputStream()
        fun w32(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun w16(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        // sfnt 头：保留原版本的 scaler type
        out.write(ttc, fontOffset, 4)
        w16(numTables)

        // 计算 searchRange 等（可为 0，多数解析器接受）
        var entrySelector = 0
        var pow2 = 1
        while (pow2 * 2 <= numTables) { pow2 *= 2; entrySelector++ }
        w16(pow2 * 16)
        w16(entrySelector)
        w16(numTables * 16 - pow2 * 16)

        // 表目录 + 表数据（4 字节对齐）
        var dataOffset = 12 + numTables * 16
        val offsets = ArrayList<Pair<Table, Int>>()
        tables.forEach { t ->
            out.write(t.tag.toByteArray(StandardCharsets.US_ASCII))
            w32(0)                      // checksum 置 0，多数阅读器容忍
            w32(dataOffset)
            w32(t.data.size)
            offsets.add(t to dataOffset)
            dataOffset += t.data.size
            if (dataOffset % 4 != 0) dataOffset += 4 - (dataOffset % 4)
        }

        offsets.forEach { (t, _) ->
            out.write(t.data)
            while (out.size() % 4 != 0) out.write(0)
        }

        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 图像压缩

    private fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        // 图像 XObject 用 DeviceRGB，转成不带 alpha 的位图
        val rgb = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { dst ->
                val canvas = android.graphics.Canvas(dst)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        } else {
            bitmap
        }
        rgb.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (rgb !== bitmap) rgb.recycle()
        return bos.toByteArray()
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
