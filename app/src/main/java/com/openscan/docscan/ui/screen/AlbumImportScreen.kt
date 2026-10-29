package com.openscan.docscan.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.openscan.docscan.data.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import android.content.ContentUris

/**
 * 相册批量导入：自定义图库多选，严格按"用户点选顺序"记录并导入，
 * 绝不按拍摄时间 / 文件名等规则自动重排。
 *
 * ## 为什么不用系统相册选择器（PickMultipleVisualMedia）
 * 系统选择器返回的列表顺序**不保证**等于用户点选顺序，且通常按时间排序，
 * 这与"严格按添加顺序"的需求直接冲突。自建图库让"第几张被点"成为
 * 唯一排序依据。
 *
 * ## 顺序如何贯穿全流程
 * - 点选顺序 → 入队顺序（[ScanSession.pendingFiles] 是 FIFO 队列）
 * - 页面管理页按队列顺序识别 → 保存时 `pageIndex` = 顺序下标
 * - 全程不做任何按时间 / 文件名的排序
 *
 * 浏览网格按时间倒序只是为了方便用户翻找，与"导入顺序"完全解耦。
 */
@Composable
fun AlbumImportScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DocumentStore(context) }

    var hasPermission by remember { mutableStateOf(checkImagePermission(context)) }
    // null = 尚未加载；emptyList = 已加载但相册为空
    var images by remember { mutableStateOf<List<MediaImage>?>(null) }
    // 有序选择集：value = 点选次序（1 起）。导入顺序即按 value 升序。
    val selection = remember { mutableStateMapOf<Long, Int>() }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showBackConfirm by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        hasPermission = granted
        if (granted) {
            scope.launch { images = loadImages(context) }
        }
    }

    LaunchedEffect(Unit) {
        if (hasPermission && images == null) {
            images = loadImages(context)
        }
    }

    fun requestPermission() {
        val perms = imagePermissionList()
        if (perms.isEmpty()) {
            hasPermission = checkImagePermission(context)
            if (hasPermission) scope.launch { images = loadImages(context) }
        } else {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    // 导入顺序 = 点选次序（value 升序），绝不按时间/文件名
    fun orderedIds(): List<Long> =
        selection.entries.sortedBy { it.value }.map { it.key }

    fun confirmAdd() {
        if (busy) return
        val ids = orderedIds()
        if (ids.isEmpty()) return
        scope.launch {
            busy = true
            progress = 0f
            ScanSession.start() // 新会话：清掉上一轮残留草稿（与拍照导入入口一致）
            var failed = 0
            withContext(Dispatchers.IO) {
                val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                ids.forEachIndexed { i, id ->
                    val src = ContentUris.withAppendedId(base, id)
                    val dst = store.newImportFile()
                    // 逐字节复制（保留 EXIF），再由管线 loadBitmap(file) 应用方向，
                    // 与相机拍摄入队走同一路径。
                    val ok = runCatching {
                        context.contentResolver.openInputStream(src)?.use { input ->
                            FileOutputStream(dst).use { out -> input.copyTo(out) }
                        } ?: throw IllegalStateException("无法打开图片")
                    }.isSuccess
                    if (ok) {
                        ScanSession.enqueuePending(dst)
                    } else {
                        failed++
                        runCatching { dst.delete() }
                    }
                    withContext(Dispatchers.Main) { progress = (i + 1f) / ids.size }
                }
            }
            if (failed > 0) {
                Toast.makeText(context, "$failed 张无法读取，已跳过", Toast.LENGTH_SHORT).show()
            }
            onAdded()
        }
    }

    fun onLeave() {
        if (selection.isNotEmpty()) showBackConfirm = true else onBack()
    }
    BackHandler(enabled = true) { onLeave() }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("从相册导入") },
                navigationIcon = {
                    IconButton(onClick = { onLeave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        TextButton(onClick = { selection.clear() }) { Text("清空") }
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
                    if (busy) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        val n = orderedIds().size
                        Text(
                            text = "正在添加第 ${(progress * n).toInt().coerceIn(1, n.coerceAtLeast(1))}/$n 张…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = if (selection.isEmpty()) "点选照片，顺序即导入顺序"
                            else "已选 ${selection.size} 张，按所选顺序导入",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = selection.isNotEmpty() && !busy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { confirmAdd() }
                    ) {
                        if (busy) {
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
                        Text(if (selection.isEmpty()) "添加" else "添加 ${selection.size} 张")
                    }
                }
            }
        }
    ) { inner ->
        val list = images
        when {
            !hasPermission -> PermissionMissing(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                onGrant = { requestPermission() },
                onOpenSettings = { openAppSettings(context) }
            )
            list == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            list.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentAlignment = Alignment.Center
            ) {
                Text("相册中没有图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(list, key = { it.id }) { img ->
                    val order = selection[img.id]
                    val selected = order != null
                    AlbumCell(
                        uri = img.uri,
                        selected = selected,
                        order = order,
                        onClick = {
                            if (selected) selection.remove(img.id)
                            else selection[img.id] = selection.size + 1
                        }
                    )
                }
            }
        }
    }

    if (showBackConfirm) {
        AlertDialog(
            onDismissRequest = { showBackConfirm = false },
            title = { Text("放弃已选照片") },
            text = { Text("已选的 ${selection.size} 张尚未导入，返回后将丢失选择。确定返回吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showBackConfirm = false
                    selection.clear()
                    onBack()
                }) { Text("返回", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showBackConfirm = false }) { Text("继续选择") } }
        )
    }

    errorText?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { errorText = null }) { Text("知道了") } }
        )
    }
}

@Composable
private fun AlbumCell(
    uri: Uri,
    selected: Boolean,
    order: Int?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(6.dp)
            )
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (selected) 1f else 0.85f)
        )
        if (selected) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                modifier = Modifier.fillMaxSize()
            ) {}
            if (order != null) {
                // 序号角标：直观显示"第几张被点选"，强化顺序感知
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = order.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionMissing(
    modifier: Modifier = Modifier,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "需要读取相册权限",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "导入照片需要访问你的相册。我们只会读取你选择的图片，不会上传任何数据。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant, shape = RoundedCornerShape(12.dp)) {
            Text("授予权限")
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onOpenSettings) { Text("去系统设置开启") }
    }
}

// ---------------- 数据 / 工具 ----------------

/**
 * 一张相册图片的轻量描述。
 * [dateAdded] 仅用于浏览网格的默认排序（时间倒序），**绝不**用于导入顺序。
 */
private data class MediaImage(
    val id: Long,
    val displayName: String,
    val uri: Uri,
    val dateAdded: Long
)

/** 查询相册图片（ID + 名称 + 内容 URI），按时间倒序便于浏览。 */
private suspend fun loadImages(context: Context): List<MediaImage> =
    withContext(Dispatchers.IO) {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val out = ArrayList<MediaImage>()
        context.contentResolver.query(
            base, projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "image_$id"
                val date = cursor.getLong(dateCol)
                out.add(MediaImage(id, name, ContentUris.withAppendedId(base, id), date))
            }
        }
        out
    }

/** 需要请求的图片读取权限（按系统版本区分）。 */
private fun imagePermissionList(): List<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        listOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun checkImagePermission(context: Context): Boolean {
    return imagePermissionList().any {
        ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        context.startActivity(intent)
    }
}
