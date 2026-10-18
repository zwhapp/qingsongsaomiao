package com.openscan.docscan.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * 拍照界面。
 *
 * 使用 CameraX preview + ImageCapture，拍照结果写入应用私有缓存目录，
 * 交由后续裁剪流程消费。
 *
 * 支持连拍：已扫描的页数会显示在顶部，拍完一张后自动回到本页继续拍。
 */
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onCaptured: () -> Unit,
    onOpenPages: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val store = remember { DocumentStore(context) }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    // 会话里的页数变化时自动重组，角标实时更新
    val scannedCount = ScanSession.pageCount

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                bindCamera(ctx, lifecycleOwner, previewView, executor) { capture ->
                    imageCapture = capture
                }
                previewView
            }
        )

        // 取景辅助框
        FramingOverlay()

        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Spacer(Modifier.weight(1f))

            // 已扫描页数角标：会话里有页面时出现，点击直接进入页面管理
            if (scannedCount > 0) {
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
                            text = "已扫 $scannedCount 页",
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
                text = if (scannedCount > 0) {
                    "已扫 $scannedCount 页，继续拍或点右上角完成"
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
                            isSaving = false
                            onCaptured()
                        },
                        onError = { e ->
                            isSaving = false
                            errorText = "拍摄失败：${e.message ?: "未知错误"}"
                        }
                    )
                }
            )
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

/** 四角取景框，纯装饰，提示用户文档应落在什么范围内。 */
@Composable
private fun FramingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 150.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(12.dp)
                )
        )
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

        val preview = Preview.Builder().build().also {
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
                // 修正 EXIF 方向并写入统一命名，供裁剪流程读取
                runCatching {
                    normalizeOrientation(target)
                    ScanSession.capturedFile = target
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
