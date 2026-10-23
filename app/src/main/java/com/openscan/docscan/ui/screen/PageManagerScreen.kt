package com.openscan.docscan.ui.screen

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openscan.docscan.DocScanApplication
import com.openscan.docscan.core.ScanEnhancer
import com.openscan.docscan.core.ScanPipeline
import com.openscan.docscan.data.DocumentStore
import com.openscan.docscan.data.DocumentEntity
import com.openscan.docscan.data.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页面管理页：连拍队列确认 + 批量识别 + 排序删除 + 一键保存。
 *
 * ★ v1.5.20 连拍统一识别：相机拍的原图先进「待处理队列」（只存文件、
 *   零计算），本页点「开始识别」后才逐张跑完整管线（检测→矫正→增强）。
 *   已处理页仍保留原有的排序/删除/保存能力。
 */
@Composable
fun PageManagerScreen(
    onBack: () -> Unit,
    onAddMore: () -> Unit,
    onSaved: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as DocScanApplication
    val store = remember { DocumentStore(context) }
    val pipeline = remember { ScanPipeline(context) }

    var saving by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var stageText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // 处理模式：默认黑白（与增强页默认一致，v1.5.20 起默认黑白）
    var mode by remember { mutableStateOf(ScanEnhancer.Mode.BLACK_WHITE) }

    // 队列与已处理页都是会话里的可观察列表，增删后自动重组
    val pending = ScanSession.pendingFiles
    val pages = ScanSession.pages

    DisposableEffect(Unit) {
        onDispose { runCatching { pipeline.release() } }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (pending.isNotEmpty()) "${pending.size} 页待识别"
                        else "已扫描 ${pages.size} 页"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving && !processing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDiscardDialog = true },
                        enabled = !saving && !processing
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "放弃本次扫描")
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
                    if (saving || processing) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stageText.ifBlank { if (processing) "正在识别…" else "正在保存…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            enabled = !saving && !processing,
                            onClick = onAddMore,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("继续拍")
                        }

                        Button(
                            enabled = !saving && !processing &&
                                (pages.isNotEmpty() || pending.isNotEmpty()),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                // 有待处理队列时，这个按钮=批量识别；没有时=保存
                                if (pending.isNotEmpty()) {
                                    processing = true
                                    progress = 0f
                                    stageText = "准备识别…"
                                    scope.launch {
                                        val files = ScanSession.takeAllPending()
                                        files.forEachIndexed { i, f ->
                                            stageText = "正在识别第 ${i + 1}/${files.size} 页…"
                                            progress = (i.toFloat() / files.size).coerceIn(0f, 0.99f)
                                            val ok = runCatching {
                                                withContext(Dispatchers.Default) {
                                                    val bmp = pipeline.loadBitmap(f)
                                                    val result = pipeline.process(
                                                        source = bmp,
                                                        options = ScanPipeline.Options(
                                                            quad = null,
                                                            enhance = mode,
                                                            targetLongEdge = ScanPipeline.Resolution.ULTRA.targetLongEdge
                                                        )
                                                    )
                                                    // process 可能返回源图本身（未矫正且原图模式），
                                                    // 只有不同对象时才代回收，避免毁掉会话里的位图
                                                    if (result.bitmap !== bmp) bmp.recycle()
                                                    ScanSession.addPage(
                                                        result.bitmap,
                                                        result.appliedQuad,
                                                        ScanPipeline.Resolution.ULTRA.targetLongEdge
                                                    )
                                                }
                                            }.isSuccess
                                            if (ok) runCatching { f.delete() }
                                            else ScanSession.enqueuePending(f) // 失败回队，重试或删除
                                        }
                                        processing = false
                                        progress = 1f
                                        stageText = ""
                                    }
                                } else {
                                    saving = true
                                    progress = 0f
                                    stageText = "正在保存…"
                                    scope.launch {
                                        runCatching {
                                            saveSession(
                                                app = app,
                                                store = store,
                                                effectMode = mode.name,
                                                onProgress = { p, s ->
                                                    progress = p
                                                    stageText = s
                                                }
                                            )
                                        }.onSuccess { docId ->
                                            saving = false
                                            ScanSession.clear()
                                            onSaved(docId)
                                        }.onFailure { e ->
                                            saving = false
                                            message = "保存失败：${e.message}"
                                        }
                                    }
                                }
                            }
                        ) {
                            if (saving || processing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (pending.isNotEmpty()) "开始识别（${pending.size}）"
                                else "完成保存"
                            )
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
            // ---- 待处理队列（连拍原图） ----
            if (pending.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            text = "待识别队列（拍完统一处理，节约时间）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        // 处理模式选择（识别前可改）
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ScanEnhancer.Mode.BLACK_WHITE to "黑白",
                                ScanEnhancer.Mode.DE_SHADOW to "去阴影",
                                ScanEnhancer.Mode.REMOVE_INK to "去手写",
                                ScanEnhancer.Mode.GRAYSCALE to "灰度",
                                ScanEnhancer.Mode.ORIGINAL to "原图",
                            ).forEach { (m, label) ->
                                FilterChip(
                                    selected = mode == m,
                                    onClick = { mode = m },
                                    enabled = !processing && !saving,
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
                itemsIndexed(pending, key = { i, f -> "p$i-${f.name}" }) { index, file ->
                    PendingPageCard(
                        file = file,
                        index = index,
                        enabled = !processing && !saving,
                        onDelete = { ScanSession.removePendingAt(index) }
                    )
                }
            }

            // ---- 已处理页 ----
            itemsIndexed(pages, key = { index, _ -> "d$index" }) { index, draft ->
                PageDraftCard(
                    index = index,
                    bitmap = draft.bitmap,
                    total = pages.size,
                    enabled = !saving && !processing,
                    onMoveUp = { ScanSession.move(index, index - 1) },
                    onMoveDown = { ScanSession.move(index, index + 1) },
                    onDelete = { ScanSession.removeAt(index) }
                )
            }

            if (pages.isEmpty() && pending.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 70.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "还没有页面",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "点下面的「继续拍」开始扫描",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃本次扫描") },
            text = {
                Text(
                    "已拍摄的 ${pending.size} 页待处理 + ${pages.size} 页已处理" +
                        "将被丢弃，且不可恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    ScanSession.clear()
                    onSaved(-1L)
                }) {
                    Text("放弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("取消") }
            }
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") }
            }
        )
    }
}

/** 待处理队列里的一页：文件缩略图 + 页码 + 删除。 */
@Composable
private fun PendingPageCard(
    file: File,
    index: Int,
    enabled: Boolean,
    onDelete: () -> Unit
) {
    // 缩略图只解码到 400px 长边，列表滚动不卡；退出页面时回收
    var thumb by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(file.path) {
        thumb = runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val long = maxOf(opts.outWidth, opts.outHeight)
            var sample = 1
            while (long / sample > 400) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
        onDispose { runCatching { thumb?.recycle() }; thumb = null }
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
                    .height(150.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "第 ${index + 1} 页待识别",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "缩略图不可用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "第 ${index + 1} 页（待识别）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除本页",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun PageDraftCard(
    index: Int,
    bitmap: Bitmap,
    total: Int,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
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
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "第 ${index + 1} 页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f))
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "第 ${index + 1} 页",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${bitmap.width}×${bitmap.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onMoveUp, enabled = enabled && index > 0) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "上移")
                }
                IconButton(onClick = onMoveDown, enabled = enabled && index < total - 1) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "下移")
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除本页",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 把会话里的页面草稿落盘成一份多页文档。
 *
 * 注意：这里**不重复跑增强管线**——草稿里的位图已经是用户在增强页
 * 确认过的结果，再跑一遍既慢又会二次损失画质。
 *
 * @return 新建文档的 id
 */
private suspend fun saveSession(
    app: DocScanApplication,
    store: DocumentStore,
    effectMode: String,
    onProgress: (Float, String) -> Unit
): Long = withContext(Dispatchers.IO) {
    val drafts = ScanSession.pages.toList()
    require(drafts.isNotEmpty()) { "没有可保存的页面" }

    val dao = app.database.scanDao()
    val now = System.currentTimeMillis()

    onProgress(0f, "正在创建文档…")

    val title = "扫描_" + SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date(now))
    val docId = dao.insertDocument(
        DocumentEntity(title = title, createdAt = now, updatedAt = now)
    )

    val entities = ArrayList<PageEntity>(drafts.size)
    drafts.forEachIndexed { index, draft ->
        onProgress(0.05f + 0.9f * index / drafts.size, "正在保存第 ${index + 1}/${drafts.size} 页…")
        val file = store.savePage(draft.bitmap, docId, index)
        entities.add(
            PageEntity(
                documentId = docId,
                fileName = file.name,
                pageIndex = index,
                width = draft.bitmap.width,
                height = draft.bitmap.height,
                enhanceMode = effectMode,
                upscaleScale = 1
            )
        )
    }

    dao.insertPages(entities)
    onProgress(1f, "完成")
    docId
}
