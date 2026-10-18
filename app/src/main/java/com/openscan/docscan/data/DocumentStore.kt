package com.openscan.docscan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.openscan.docscan.core.OcrEngine
import com.openscan.docscan.core.SearchablePdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文档存储：图片落盘、导出 PDF / 图片、清理缓存。
 *
 * 全部写入应用私有目录，导出才落到公共 Pictures 目录，不需要存储权限
 * （Android 10+ 使用 app-specific 目录）。
 */
class DocumentStore(private val context: Context) {

    /** 页面图片存放目录（私有）。 */
    private val pagesDir: File
        get() = File(context.filesDir, "documents").apply { mkdirs() }

    /** 相机拍摄的临时文件目录。 */
    val captureCacheDir: File
        get() = File(context.cacheDir, "captures").apply { mkdirs() }

    /** 导出目录。 */
    private val exportDir: File
        get() = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
            "exports"
        ).apply { mkdirs() }

    fun pageFile(fileName: String): File = File(pagesDir, fileName)

    fun newCaptureFile(): File =
        File(captureCacheDir, "cap_${System.currentTimeMillis()}.jpg")

    /**
     * 保存一页到私有目录。
     */
    suspend fun savePage(bitmap: Bitmap, docId: Long, pageIndex: Int): File =
        withContext(Dispatchers.IO) {
            val fileName = "doc${docId}_p${pageIndex}_${System.currentTimeMillis()}.jpg"
            val file = pageFile(fileName)
            FileOutputStream(file).use { fos ->
                // 质量 95：之前 92 在深色笔画边缘有可见振铃，扫描件文本页尤其明显
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            file
        }

    /**
     * 读取页面位图。
     */
    suspend fun loadPage(fileName: String, maxEdge: Int = 0): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = pageFile(fileName)
            if (!file.exists()) return@withContext null
            if (maxEdge <= 0) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxEdge) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        }

    /**
     * 删除磁盘上的页面文件。
     */
    suspend fun deletePageFiles(files: List<String>) = withContext(Dispatchers.IO) {
        files.forEach { name ->
            runCatching { pageFile(name).takeIf { it.exists() }?.delete() }
        }
    }

    /**
     * 多页合成 **可搜索** PDF。
     *
     * ## 为什么不用 PdfDocument
     *
     * `android.graphics.pdf.PdfDocument` 的 `drawText` 会把文字转成矢量路径，
     * 产出的 PDF 无法搜索、无法选中、无法复制。所以这里改用
     * [SearchablePdfWriter] 手写 PDF 结构，嵌入字体并写入真正的文本对象。
     *
     * ## 流程
     *
     * 1. 逐页读图
     * 2. 若 `withOcr` 为 true，对每页跑离线中文 OCR
     * 3. 把「图像 + 不可见文字层」一起写进 PDF
     *
     * OCR 失败不会中断导出——那一页退化为纯图片页，其余页照常带文字层。
     *
     * @param withOcr 是否生成文字层。关掉时导出速度更快、体积更小，但不可搜索。
     */
    suspend fun exportPdf(
        title: String,
        pages: List<PageEntity>,
        withOcr: Boolean = true,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty()) { "没有可导出的页面" }

        val ocr = if (withOcr) OcrEngine() else null
        try {
            val writerPages = ArrayList<SearchablePdfWriter.Page>(pages.size)

            pages.forEachIndexed { index, page ->
                val bitmap = loadPage(page.fileName)
                    ?: throw IllegalStateException("页面文件缺失：${page.fileName}")

                // OCR 占总进度 80%，写 PDF 占 20%，让进度条更贴合实际耗时
                val ocrResult = ocr?.let { engine ->
                    engine.recognizeOrEmpty(bitmap).also { result ->
                        onProgress(0.8f * (index + 1) / pages.size)
                    }
                }

                writerPages.add(SearchablePdfWriter.Page(bitmap = bitmap, ocr = ocrResult))
            }

            val out = newExportFile(title, "pdf")
            SearchablePdfWriter().write(out, writerPages) { p ->
                onProgress(0.8f + 0.2f * p)
            }

            // 位图已经写进 PDF，及时释放——多页高清图很占内存
            writerPages.forEach { runCatching { it.bitmap.recycle() } }

            onProgress(1f)
            out
        } finally {
            ocr?.close()
        }
    }

    /**
     * 导出全部页面的识别文本为 .txt。
     *
     * @return 文本文件；所有页都没有识别到文字时返回 null
     */
    suspend fun exportText(
        title: String,
        pages: List<PageEntity>,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val ocr = OcrEngine()
        try {
            val sb = StringBuilder()
            var any = false

            pages.forEachIndexed { index, page ->
                val bitmap = loadPage(page.fileName, maxEdge = 2400) ?: return@forEachIndexed
                val result = ocr.recognizeOrEmpty(bitmap)
                bitmap.recycle()

                if (pages.size > 1) {
                    if (index > 0) sb.append("\n\n")
                    sb.append("===== 第 ${index + 1} 页 =====\n")
                }
                if (result.fullText.isNotBlank()) {
                    sb.append(result.fullText).append('\n')
                    any = true
                }
                onProgress((index + 1).toFloat() / pages.size)
            }

            if (!any) return@withContext null

            val out = newExportFile(title, "txt")
            out.writeText(sb.toString().trimEnd() + "\n", Charsets.UTF_8)
            out
        } finally {
            ocr.close()
        }
    }

    /** 生成一个带时间戳、已清洗文件名的导出文件路径。 */
    private fun newExportFile(title: String, extension: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "扫描文档" }
        return File(exportDir, "${safeTitle}_$stamp.$extension")
    }

    /**
     * 导出文件统一出口：发布到公共媒体库（下载/Pictures，文件管理器可见）。
     * 发布失败时保留私有目录文件并返回其绝对路径。
     *
     * @return 给用户看的「文件在哪」描述
     */
    fun describeExportLocation(context: Context, file: File): String {
        val publicPath = PublicExporter.publish(context, file)
        return publicPath ?: file.absolutePath
    }

    /**
     * 导出全部页面为 JPEG，返回导出目录。
     */
    suspend fun exportImages(
        title: String,
        pages: List<PageEntity>,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "扫描文档" }
        val dir = File(exportDir, "${safeTitle}_$stamp").apply { mkdirs() }

        pages.forEachIndexed { index, page ->
            val bitmap = loadPage(page.fileName) ?: return@forEachIndexed
            val out = File(dir, "page_${(index + 1).toString().padStart(3, '0')}.jpg")
            FileOutputStream(out).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            bitmap.recycle()
            onProgress((index + 1).toFloat() / pages.size.coerceAtLeast(1))
        }
        dir
    }

    /**
     * 识别全部页面的文字（不落盘），用于详情页展示。
     *
     * @param maxPageEdge 读图时限制长边，避免大图 OOM
     */
    suspend fun recognizeAll(
        pages: List<PageEntity>,
        maxPageEdge: Int = 2400,
        onProgress: (Float) -> Unit = {}
    ): List<Pair<Int, OcrEngine.PageResult>> = withContext(Dispatchers.IO) {
        val ocr = OcrEngine()
        try {
            pages.mapIndexedNotNull { index, page ->
                val bitmap = loadPage(page.fileName, maxEdge = maxPageEdge)
                    ?: return@mapIndexedNotNull null
                val result = ocr.recognizeOrEmpty(bitmap)
                bitmap.recycle()
                onProgress((index + 1).toFloat() / pages.size.coerceAtLeast(1))
                (index + 1) to result
            }
        } finally {
            ocr.close()
        }
    }

    /** 清理相机临时文件。 */
    suspend fun clearCaptureCache() = withContext(Dispatchers.IO) {
        captureCacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun exportDirectory(): File = exportDir
}
