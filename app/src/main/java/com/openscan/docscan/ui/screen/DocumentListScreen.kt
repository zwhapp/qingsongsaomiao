package com.openscan.docscan.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.openscan.docscan.DocScanApplication
import com.openscan.docscan.core.OcrEngine
import com.openscan.docscan.data.DocumentEntity
import com.openscan.docscan.data.DocumentStore
import com.openscan.docscan.data.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文档列表 / 详情。
 *
 * [detailMode] 为 true 时展示单份文档的所有页面并提供导出入口。
 */
@Composable
fun DocumentListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    initialDocId: Long? = null,
    detailMode: Boolean = false
) {
    if (detailMode && initialDocId != null) {
        DocumentDetailContent(onBack = onBack, docId = initialDocId)
    } else {
        DocumentGridContent(onBack = onBack, onOpen = onOpen)
    }
}

@Composable
private fun DocumentGridContent(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DocScanApplication
    val dao = remember { app.database.scanDao() }
    val store = remember { DocumentStore(context) }

    val documents by produceState(initialValue = emptyList<DocumentEntity>()) {
        dao.observeDocuments().collect { value = it }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("我的文档") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { inner ->
        if (documents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "还没有扫描记录",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "回到首页，点「拍照扫描」开始",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 14.dp, end = 14.dp,
                top = inner.calculateTopPadding() + 10.dp,
                bottom = 22.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(documents, key = { it.id }) { doc ->
                DocumentCard(
                    doc = doc,
                    store = store,
                    onClick = { onOpen(doc.id) }
                )
            }
        }
    }
}

@Composable
private fun DocumentCard(
    doc: DocumentEntity,
    store: DocumentStore,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as DocScanApplication
    val dao = remember { app.database.scanDao() }

    val thumb by produceState<Bitmap?>(initialValue = null, doc.id) {
        val pages = dao.getPages(doc.id)
        val first = pages.firstOrNull()
        value = if (first != null) store.loadPage(first.fileName, maxEdge = 420) else null
    }
    val pageCount by produceState(initialValue = 1, doc.id) {
        value = dao.getPages(doc.id).size
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.76f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                thumb?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = doc.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                Text(
                    text = doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${pageCount} 页 · " + SimpleDateFormat("MM-dd HH:mm", Locale.US)
                        .format(Date(doc.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DocumentDetailContent(onBack: () -> Unit, docId: Long) {
    val context = LocalContext.current
    val app = context.applicationContext as DocScanApplication
    val dao = remember { app.database.scanDao() }
    val store = remember { DocumentStore(context) }
    val scope = rememberCoroutineScope()

    var doc by remember { mutableStateOf<DocumentEntity?>(null) }
    var pages by remember { mutableStateOf<List<PageEntity>>(emptyList()) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // OCR：识别结果 + 是否生成带文字层的 PDF
    var ocrResult by remember { mutableStateOf<List<Pair<Int, OcrEngine.PageResult>>?>(null) }
    var ocrBusy by remember { mutableStateOf(false) }
    var searchablePdf by remember { mutableStateOf(true) }

    suspend fun reload() {
        doc = dao.getDocument(docId)
        pages = dao.getPages(docId)
    }

    LaunchedEffect(docId) { reload() }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = doc?.title ?: "文档",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (exporting || ocrBusy) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = statusText.ifBlank { "正在处理…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    val busy = exporting || ocrBusy

                    // 可搜索 PDF 开关：关掉能显著加快导出并减小体积
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "导出可搜索 PDF",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = if (searchablePdf) {
                                    "会逐页识别文字，可搜索、可复制，耗时较长"
                                } else {
                                    "仅嵌入图片，导出更快，但无法搜索文字"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = searchablePdf,
                            onCheckedChange = { searchablePdf = it },
                            enabled = !busy
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(
                            enabled = !busy && pages.isNotEmpty(),
                            onClick = {
                                exporting = true
                                progress = 0f
                                statusText = if (searchablePdf) "正在识别文字…" else "正在生成 PDF…"
                                scope.launch {
                                    runCatching {
                                        store.exportPdf(
                                            title = doc?.title ?: "扫描文档",
                                            pages = pages,
                                            withOcr = searchablePdf
                                        ) { p -> progress = p }
                                    }.onSuccess { file ->
                                        exporting = false
                                        statusText = ""
                                        // 发布到公共媒体库（下载/Pictures），文件管理器可见
                                        val where = store.describeExportLocation(context, file)
                                        message = "已导出，位置：\n$where"
                                        shareFile(context, file)
                                    }.onFailure { e ->
                                        exporting = false
                                        statusText = ""
                                        message = "导出失败：${e.message}"
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (exporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(6.dp))
                            } else {
                                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("PDF")
                        }

                        OutlinedButton(
                            enabled = !busy && pages.isNotEmpty(),
                            onClick = {
                                ocrBusy = true
                                progress = 0f
                                statusText = "正在识别文字…"
                                scope.launch {
                                    runCatching {
                                        store.recognizeAll(pages) { p -> progress = p }
                                    }.onSuccess { result ->
                                        ocrBusy = false
                                        statusText = ""
                                        ocrResult = result
                                    }.onFailure { e ->
                                        ocrBusy = false
                                        statusText = ""
                                        message = "识别失败：${e.message}"
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("识别文字")
                        }

                        OutlinedButton(
                            enabled = !busy && pages.isNotEmpty(),
                            onClick = {
                                exporting = true
                                progress = 0f
                                statusText = "正在导出图片…"
                                scope.launch {
                                    runCatching {
                                        store.exportImages(doc?.title ?: "扫描文档", pages) { p ->
                                            progress = p
                                        }
                                    }.onSuccess { dir ->
                                        exporting = false
                                        statusText = ""
                                        // 逐个发布到公共媒体库，返回第一个的位置
                                        var where: String? = null
                                        dir.listFiles()?.forEach { f ->
                                            val p = store.describeExportLocation(context, f)
                                            if (where == null) where = p
                                        }
                                        where?.let {
                                            message = "已导出 ${pages.size} 张，位置：\n$it"
                                        } ?: run {
                                            message = "已导出 ${pages.size} 张到：\n${dir.absolutePath}"
                                        }
                                    }.onFailure { e ->
                                        exporting = false
                                        statusText = ""
                                        message = "导出失败：${e.message}"
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("图片")
                        }
                    }
                }
            }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pages, key = { it.id }) { page ->
                PagePreviewCard(page = page, store = store)
            }

            if (pages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "这份文档还没有页面",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除文档") },
            text = { Text("确定删除「${doc?.title ?: ""}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        val d = doc ?: return@launch
                        val removed = dao.deleteDocumentWithPages(d)
                        store.deletePageFiles(removed.map { it.fileName })
                        onBack()
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("知道了") }
            }
        )
    }

    ocrResult?.let { result ->
        OcrResultDialog(
            results = result,
            title = doc?.title ?: "识别结果",
            onDismiss = { ocrResult = null }
        )
    }
}

/**
 * 识别结果面板。
 *
 * 三件事：整篇文本一键复制、按页查看、导出 .txt。
 * 文本用 `SelectionContainer` 包住，方便用户直接划选。
 */
@Composable
private fun OcrResultDialog(
    results: List<Pair<Int, OcrEngine.PageResult>>,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedPage by remember { mutableStateOf(0) }
    val clipboard = remember { ClipboardManagerCompat(context) }

    val fullText = remember(results) {
        buildString {
            results.forEachIndexed { index, (pageNo, page) ->
                if (page.fullText.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    if (results.size > 1) append("===== 第 $pageNo 页 =====\n")
                    append(page.fullText)
                }
            }
        }
    }

    val totalChars = results.sumOf { it.second.charCount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("识别结果")
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${results.size} 页 · 共 $totalChars 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                if (totalChars == 0) {
                    Text(
                        text = "这些页面里没有识别到文字。\n\n" +
                                "建议：先在增强页选「黑白文档」提高对比度，再回来重新识别。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    // 多页时给出页签
                    if (results.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 10.dp)
                        ) {
                            items(results.size) { index ->
                                val pageNo = results[index].first
                                val isSel = index == selectedPage
                                Surface(
                                    color = if (isSel) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = RoundedCornerShape(9.dp),
                                    modifier = Modifier.clickable { selectedPage = index }
                                ) {
                                    Text(
                                        text = "第 $pageNo 页",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSel) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    val current = results.getOrNull(selectedPage)?.second
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = current?.fullText.orEmpty().ifBlank { "（本页无文字）" },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDismiss() }
            ) { Text("关闭") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = totalChars > 0,
                    onClick = {
                        clipboard.copy(title, fullText)
                        onDismiss()
                    }
                ) { Text("复制全文") }

                TextButton(
                    enabled = totalChars > 0,
                    onClick = {
                        clipboard.copy(title, fullText)
                        // 借系统的文本分享面板，省掉自己写导出逻辑
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, title)
                            putExtra(Intent.EXTRA_TEXT, fullText)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "分享识别文字"))
                        }
                        onDismiss()
                    }
                ) { Text("分享") }
            }
        }
    )
}

/** 剪贴板封装，屏蔽掉 API 差异与 Toast。 */
private class ClipboardManagerCompat(private val context: android.content.Context) {
    fun copy(label: String, text: String) {
        runCatching {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            android.widget.Toast.makeText(context, "已复制全文", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun PagePreviewCard(page: PageEntity, store: DocumentStore) {
    val bitmap by produceState<Bitmap?>(initialValue = null, page.id) {
        value = store.loadPage(page.fileName, maxEdge = 1400)
    }

    Card(
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "第 ${page.pageIndex + 1} 页",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(it.width.toFloat() / it.height.toFloat())
                    )
                } ?: Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "第 ${page.pageIndex + 1} 页",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    // 直接显示像素尺寸 + 百万像素总量：用户选了哪档分辨率、
                    // 实际拿到了多少，一眼可核对（比"2x"这类相对倍数直观得多）
                    text = buildString {
                        append("${page.width}×${page.height}")
                        val mp = page.width.toLong() * page.height / 1_000_000.0
                        append(" · %.1fMP".format(mp))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 通过 FileProvider 分享导出文件，避免暴露 file:// 路径。 */
private fun shareFile(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享扫描文档"))
    }
}
