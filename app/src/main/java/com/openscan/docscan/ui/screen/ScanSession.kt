package com.openscan.docscan.ui.screen

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openscan.docscan.core.DocumentGeometry

/**
 * 多页扫描会话。
 *
 * ## 为什么要单独做一个会话对象
 *
 * 多页扫描不是"拍一张存一张"，而是一个**跨页面的临时草稿**：
 *   拍照 → 裁剪 → 增强 → 「继续拍下一页」回到拍照 → …
 *   直到用户点「完成」才一次性落盘成一份多页文档。
 *
 * 这几步分散在四个 Composable 里，靠 NavHost 的 back stack 传递状态。
 * 用单例对象持有草稿，天然避开两个坑：
 *   - 大 Bitmap 塞进 Bundle 触发 TransactionTooLargeException
 *   - 进程被回收后状态丢失（这里只存在于内存，属于可接受取舍）
 *
 * ## 生命周期
 *
 * [start] 开新会话 → [addPage] 逐页追加 → [clear] 保存完成后清理。
 * [capturedFile] 是"拍完但还没走裁剪流程"的临时交接位，
 * 由相机页写入、裁剪页消费，消费后立即置空，避免重复处理。
 */
object ScanSession {

    /** 一页草稿：位图 + 用户确认的裁剪四边形 + 实际使用的分辨率档位。 */
    data class Draft(
        val bitmap: Bitmap,
        val quad: DocumentGeometry.Quad?,
        /** 输出长边（px）。用于文档列表展示"这一页是多少分辨率"。 */
        val targetLongEdge: Int = 0
    )

    /** 已确认的页面草稿，按扫描顺序。 */
    private val _pages = mutableStateListOf<Draft>()

    /**
     * 连拍待处理队列：已拍下、尚未做识别/矫正/增强的原图文件。
     *
     * ★ 连拍统一识别（v1.5.20）：拍照只入队、不即时识别。用户拍完全部
     * 页面后点「完成拍摄」，在页面管理页一次性批量跑管线——既避免每拍
     * 一张就卡几秒，也让用户先核对页序再花时间处理。
     * 文件由相机页写入、批量处理取走，处理成功即从队列移除并删除原图。
     */
    private val _pendingFiles = mutableStateListOf<java.io.File>()
    val pendingFiles: List<java.io.File> get() = _pendingFiles
    val pendingCount: Int get() = _pendingFiles.size

    /** 会话是否处于活跃状态（用于首页文案与返回键行为）。 */
    var active by mutableStateOf(false)
        private set

    val pages: List<Draft> get() = _pages

    val pageCount: Int get() = _pages.size

    val isEmpty: Boolean get() = _pages.isEmpty()

    /** 相机刚拍下、尚未裁剪的文件。裁剪页取走即清空。 */
    @Volatile
    var capturedFile: java.io.File? = null

    /** 开启新会话，清掉上一次的残留。 */
    fun start() {
        releaseAll()
        active = true
    }

    /** 追加一个已确认的页面。 */
    fun addPage(
        bitmap: Bitmap,
        quad: DocumentGeometry.Quad?,
        targetLongEdge: Int = 0
    ) {
        _pages.add(Draft(bitmap = bitmap, quad = quad, targetLongEdge = targetLongEdge))
    }

    /** 删除指定页并回收它的位图。 */
    fun removeAt(index: Int) {
        val removed = _pages.removeAt(index)
        runCatching { removed.bitmap.recycle() }
    }

    /** 把某页上移 / 下移，返回是否真的移动了。 */
    fun move(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from !in _pages.indices || to !in _pages.indices) return false
        _pages.add(to, _pages.removeAt(from))
        return true
    }

    /** 连拍原图入队（只排队，不识别）。 */
    fun enqueuePending(file: java.io.File) {
        _pendingFiles.add(file)
    }

    /** 从待处理队列移除一张原图（用户删页时调用，同时删除文件）。 */
    fun removePendingAt(index: Int) {
        if (index !in _pendingFiles.indices) return
        runCatching { _pendingFiles.removeAt(index).delete() }
    }

    /** 取出并清空整个待处理队列（批量处理开始时调用）。 */
    fun takeAllPending(): List<java.io.File> {
        val all = _pendingFiles.toList()
        _pendingFiles.clear()
        return all
    }

    fun clear() {
        releaseAll()
        _pendingFiles.forEach { runCatching { it.delete() } }
        _pendingFiles.clear()
        capturedFile = null
        active = false
    }

    private fun releaseAll() {
        _pages.forEach { runCatching { it.bitmap.recycle() } }
        _pages.clear()
    }
}
