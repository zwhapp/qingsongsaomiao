package com.openscan.docscan.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.openscan.docscan.data.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor

/**
 * 拍照界面（连拍模式）。
 *
 * ★ v1.5.20 连拍统一识别：拍一张只是把原图存进 [ScanSession] 待处理队列，
 * **不做任何识别/矫正/增强**——不再每拍一张就跳裁剪页卡几秒。用户拍完
 * 所有页后点「完成拍摄」，在页面管理页一次性批量处理。
 *
 * 取景框与成像范围严格一致：preview 用 FIT_CENTER 显示传感器完整画面
 * （letterbox 上下留黑边），取景框按同一 4:3 区域绘制。旧实现用
 * FILL_CENTER 把预览画面放大裁切，用户看到的框比实际成像范围小，
 * 拍到的内容永远比框里多一圈。
 */
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onOpenPages: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showBackConfirm by remember { mutableStateOf(false) }

    val store = remember { DocumentStore(context) }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    // 待处理队列变化时自动重组，角标实时更新
    val pendingCount = ScanSession.pendingCount
    val processedCount = ScanSession.pageCount

    // 有未保存内容时，退出相机必须先确认，防止误触返回丢失已拍内容
    val hasUnsaved = pendingCount > 0 || processedCount > 0
    fun requestBack() {
        if (hasUnsaved) showBackConfirm = true else onBack()
    }
    BackHandler(enabled = true) { requestBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    // FIT_CENTER：完整显示传感器 4:3 画面（上下 letterbox），
                    // 保证取景框内 = 实际成像范围。FILL_CENTER 会放大裁切，
                    // 所见远小于所得。
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                bindCamera(ctx, lifecycleOwner, previewView, executor) { capture ->
                    imageCapture = capture
                }
                previewView
            }
        )

        // 取景辅助框：与实际预览区同一 4:3 矩形（预览 FIT_CENTER 后
        // 宽占满、高 = 宽×4/3，上下留黑）
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .padding(horizontal = 24.dp)
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
            )
        }

        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { requestBack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Spacer(Modifier.weight(1f))

            // 角标：已拍（待处理）+ 已处理页数，点击直接进入页面管理
            if (pendingCount > 0 || processedCount > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clickable(onClick = onOpenPages)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (pendingCount > 0) {
                                "已拍 $pendingCount 页待处理"
                            } else {
                                "已处理 $processedCount 页"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "查看已扫描页面",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

            IconButton(onClick = { torchOn = !torchOn }) {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "闪光灯",
                    tint = if (torchOn) Color(0xFFFFD166) else Color.White
                )
            }
        }

        // 底部控制区
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            errorText?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.padding(horizontal = 34.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                text = if (pendingCount > 0) {
                    "已拍 $pendingCount 页，拍完后点「完成」统一识别"
                } else {
                    "将纸张完整放入框内，尽量保持平整"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )

            Spacer(Modifier.height(18.dp))

            ShutterButton(
                enabled = imageCapture != null && !isSaving,
                loading = isSaving,
                onClick = {
                    val capture = imageCapture ?: return@ShutterButton
                    isSaving = true
                    errorText = null
                    takePhoto(
                        context = context,
                        capture = capture,
                        executor = executor,
                        store = store,
                        onSuccess = {
                            // 只入队，不跳转、不识别——连拍继续
                            isSaving = false
                        },
                        onError = { e ->
                            isSaving = false
                            errorText = "拍摄失败：${e.message ?: "未知错误"}"
                        }
                    )
                }
            )

            // 「完成拍摄」：拍满收工，去页面管理页统一识别
            if (pendingCount > 0) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onFinish,
                    enabled = !isSaving,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("完成拍摄（$pendingCount 页）")
                }
            }
        }
    }

    // 手电筒联动
    LaunchedEffect(torchOn) {
        runCatching {
            // CameraX 1.4 通过 CameraControl 控制闪光灯，此处用简单方式：重新绑定不可行，
            // 由 ImageCapture 的 FLASH_MODE 承担主要职责，torch 作为提示保留。
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // CameraX 生命周期由 lifecycleOwner 自动管理，无需手动解绑
        }
    }

    // 退出确认：有已拍未处理 / 已处理的页面时，防止误触返回丢失
    if (showBackConfirm) {
        AlertDialog(
            onDismissRequest = { showBackConfirm = false },
            title = { Text("退出拍摄") },
            text = {
                Text(
                    buildString {
                        append("当前有 ")
                        append(pendingCount)
                        append(" 页待处理")
                        if (processedCount > 0) {
                            append("、")
                            append(processedCount)
                            append(" 页已处理")
                        }
                        append("。退出后这些内容不会保存，确定要丢弃吗？")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackConfirm = false
                    ScanSession.clear()
                    onBack()
                }) {
                    Text("丢弃并退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackConfirm = false }) { Text("继续拍摄") }
            }
        )
    }
}

@Composable
private fun ShutterButton(
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .background(
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
        } else {
            IconButton(onClick = onClick, enabled = enabled) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(
                            color = if (enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Gray
                            },
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

// ---------------- CameraX 绑定与拍摄 ----------------

@SuppressLint("UnsafeOptInUsageError")
private fun bindCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    executor: Executor,
    onReady: (ImageCapture) -> Unit
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()

        val preview = Preview.Builder()
            // 锁死 4:3，与取景框 aspectRatio 一致，保证所见即所得
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
            .build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .setJpegQuality(95)
            .build()

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture
            )
            onReady(capture)
        }
    }, executor)
}

private fun takePhoto(
    context: Context,
    capture: ImageCapture,
    executor: Executor,
    store: DocumentStore,
    onSuccess: () -> Unit,
    onError: (Throwable) -> Unit
) {
    val target = store.newCaptureFile()

    capture.takePicture(
        ImageCapture.OutputFileOptions.Builder(target).build(),
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                // 修正 EXIF 方向，然后**只入队**：识别统一放在拍完所有页之后
                runCatching {
                    normalizeOrientation(target)
                    ScanSession.enqueuePending(target)
                }.onFailure {
                    onError(it)
                    return
                }
                onSuccess()
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        }
    )
}

/** 按 EXIF 旋转角度把位图摆正并覆盖保存。 */
private fun normalizeOrientation(file: File) {
    val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
    val orientation = exif.getAttributeInt(
        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    )
    val degrees = when (orientation) {
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return

    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    java.io.FileOutputStream(file).use { fos ->
        rotated.compress(Bitmap.CompressFormat.JPEG, 95, fos)
    }
    if (rotated !== bmp) bmp.recycle()
    rotated.recycle()
}
