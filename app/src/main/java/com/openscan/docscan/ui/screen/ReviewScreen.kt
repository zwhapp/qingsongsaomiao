package com.openscan.docscan.ui.screen

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openscan.docscan.core.DocumentGeometry
import com.openscan.docscan.core.OpenCvLoader
import com.openscan.docscan.core.ScanPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import kotlin.math.hypot

/**
 * 裁剪 / 矫正界面。
 *
 * 用户可拖动四个角点手动修正边缘；点"自动检测"让算法重新找一遍。
 * 确认后把这一页追加进 [ScanSession]，回到拍摄流程。
 *
 * 同一个界面同时承担两个入口：
 *   - 拍照后进来（source 来自 [ScanSession.capturedFile]）
 *   - 从首页「导入图片」进来（source 来自相册选择器）
 */
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pipeline = remember { ScanPipeline(context) }

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    // 图像坐标系下的四边形
    var quad by remember { mutableStateOf<DocumentGeometry.Quad?>(null) }
    var isDetecting by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 相册选择器
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            sourceUri = uri
            scope.launch {
                errorText = null
                val bmp = runCatching { pipeline.loadBitmap(uri) }.getOrNull()
                if (bmp == null) {
                    errorText = "无法读取所选图片"
                } else {
                    sourceBitmap = bmp
                    quad = DocumentGeometry.Quad.full(bmp.width, bmp.height, 0.02)
                    autoDetect(pipeline, bmp, { q -> quad = q }, { msg -> errorText = msg.ifBlank { null } })
                }
            }
        }
    }

    // 从相机拍摄结果载入
    LaunchedEffect(Unit) {
        val captured = ScanSession.capturedFile
        ScanSession.capturedFile = null
        if (captured != null) {
            val bmp = runCatching { pipeline.loadBitmap(captured) }.getOrNull()
            if (bmp != null) {
                sourceBitmap = bmp
                quad = DocumentGeometry.Quad.full(bmp.width, bmp.height, 0.02)
                autoDetect(pipeline, bmp, { q -> quad = q }, { msg -> errorText = msg.ifBlank { null } })
            } else {
                errorText = "无法读取拍摄结果"
            }
        } else if (sourceBitmap == null && sourceUri == null) {
            // 没有拍到也没有选到，直接拉起相册
            pickLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    // 本页退出时释放仍持有的位图。
    // 若已经交接给增强页（点了"下一步"），sourceBitmap 会被置空，
    // 此处不会误回收正在被下游使用的位图。
    DisposableEffect(Unit) {
        onDispose {
            sourceBitmap?.takeIf { it !== reviewSource }?.recycle()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("调整边缘") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val bmp = sourceBitmap ?: return@TextButton
                            scope.launch {
                                isDetecting = true
                                autoDetect(pipeline, bmp, { q -> quad = q }, { msg -> errorText = msg.ifBlank { null } })
                                isDetecting = false
                            }
                        }
                    ) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("自动检测")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val bmp = sourceBitmap ?: return@TextButton
                            quad = DocumentGeometry.Quad.full(bmp.width, bmp.height, 0.02)
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重置")
                    }

                    Spacer(Modifier.weight(1f))

                    Button(
                        // 自动检测进行中禁用：此刻 quad 还是"全图默认框"，
                        // 带着它进增强页 = 整页不矫正（真机案例：斜 15° 的文档原样输出）。
                        // 检测只要一两秒，等一下就能拿到框；失败了也可以手动拖角点。
                        enabled = !isDetecting,
                        onClick = {
                            val bmp = sourceBitmap
                            val q = quad
                            if (bmp == null || q == null) {
                                errorText = "请先拍照或选择一张图片"
                            } else {
                                // 交接给增强页：位图与四边形在这里转手，
                                // 所有权从本页移出，避免被 onDispose 提前回收。
                                reviewSource = bmp
                                reviewQuad = q
                                sourceBitmap = null
                                onNext()
                            }
                        },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Text("下一步")
                    }
                }
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            Text(
                text = "拖动四个圆点，让边框贴合纸张边缘",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val bmp = sourceBitmap
                if (bmp == null) {
                    CircularProgressIndicator()
                } else {
                    CropEditor(
                        bitmap = bmp,
                        quad = quad,
                        onQuadChange = { quad = it },
                        onCanvasSized = { canvasSize = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isDetecting) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在检测边缘…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            errorText?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/**
 * 图像 + 四边形拖拽编辑。
 *
 * 位图按 fitCenter 显示，绘制与手势都在显示坐标系里做，
 * 提交时才按显示/原图比例换算回原图坐标。
 */
@Composable
private fun CropEditor(
    bitmap: Bitmap,
    quad: DocumentGeometry.Quad?,
    onQuadChange: (DocumentGeometry.Quad) -> Unit,
    onCanvasSized: (IntSize) -> Unit,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingIndex by remember { mutableStateOf(-1) }

    // ---------------------------------------------------------------------
    // 关键：pointerInput 的 block 只在 key 变化时重新执行，
    // 它对外部变量的捕获是"执行那一刻"的快照。
    //
    // 之前把 quad 直接写在 lambda 里读，而 key 只有 (bitmap, boxSize)：
    // 自动检测完成后 quad 变了，手势 block 却还拿着旧四边形，
    // 于是 onDragStart 里算出的最近角点全是旧坐标 —— 表现为"圆点拖不动"。
    //
    // 用 rememberUpdatedState 持有最新引用，再把它读进手势 block，
    // 这样不必把 quad 放进 key（放进去会导致每次拖动都重建手势识别器，
    // 中途重建会丢掉正在进行的这次拖拽，手感会断）。
    // ---------------------------------------------------------------------
    val currentQuad by rememberUpdatedState(quad)
    val currentBoxSize by rememberUpdatedState(boxSize)

    // 密度换算：dp → px。手势与绘制都在像素域工作，常量必须先转换。
    //
    // ★ 关键：`toPx()` 在 Compose 里的身份是**成员扩展函数**
    //   （interface Density { fun Dp.toPx(): Float }），不是顶层函数。
    //   我用 javap 逐个查过 1.7.5 的全部 aar，确认整个 Compose 里
    //   只有 Density.class / Density$DefaultImpls.class 声明了它，
    //   DpKt（顶层函数类）里根本没有 —— 所以
    //     · `import androidx.compose.ui.unit.toPx` → 报 Unresolved reference
    //     · `density.toPx(dpValue)`            → 也报 Unresolved reference
    //   两种写法都不成立。唯一可行的是把 Dp 放进 Density 接收者作用域内调用。
    //   这里用 with(density) {} —— density 本身就是 Density 实例，
    //   于是 `dpValue.toPx()` 里的隐式接收者正好命中它。
    val density = LocalDensity.current
    val grabRadiusPx = remember(density) { with(density) { HANDLE_GRAB_RADIUS_DP.toPx() } }
    val handleRadiusPx = remember(density) { with(density) { HANDLE_RADIUS_DP.toPx() } }

    Box(
        modifier = modifier
            .onSizeChanged {
                boxSize = it
                onCanvasSized(it)
            }
            .pointerInput(bitmap) {
                detectDragGestures(
                    onDragStart = { start ->
                        val size = currentBoxSize
                        val q = currentQuad ?: return@detectDragGestures
                        if (size.width == 0 || size.height == 0) return@detectDragGestures
                        val scale = minOf(
                            size.width.toFloat() / bitmap.width,
                            size.height.toFloat() / bitmap.height
                        )
                        if (scale <= 0f) return@detectDragGestures
                        val left = (size.width - bitmap.width * scale) / 2f
                        val top = (size.height - bitmap.height * scale) / 2f

                        var nearest = -1
                        var minDist = Float.MAX_VALUE
                        q.toArray().forEachIndexed { index, p ->
                            val dx = (p.x * scale + left).toFloat() - start.x
                            val dy = (p.y * scale + top).toFloat() - start.y
                            val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            if (d < minDist) {
                                minDist = d
                                nearest = index
                            }
                        }
                        // 宽容抓取：112dp 内按下即抓住最近的角点。
                        // 用户反馈"要按得很准才能拖动"——旧实现半径只有
                        // 46dp（再早是 46 裸像素 ≈ 14dp），手指必须精确
                        // 按在圆点上。现在放大到 112dp，大致方向对就能抓。
                        // 抓住后手柄按拖动增量移动（不跳到手指位置），
                        // 保留用户已调好的精度。
                        draggingIndex = if (minDist < grabRadiusPx) nearest else -1
                    },
                    onDrag = { change, dragAmount ->
                        val idx = draggingIndex
                        val q = currentQuad
                        val size = currentBoxSize
                        if (idx < 0 || q == null) return@detectDragGestures
                        if (size.width == 0 || size.height == 0) return@detectDragGestures

                        val scale = minOf(
                            size.width.toFloat() / bitmap.width,
                            size.height.toFloat() / bitmap.height
                        )
                        if (scale <= 0f) return@detectDragGestures

                        change.consume()
                        val pts = q.toArray().toMutableList()
                        val p = pts[idx]
                        val nx = (p.x + dragAmount.x / scale)
                            .coerceIn(0.0, (bitmap.width - 1).toDouble())
                        val ny = (p.y + dragAmount.y / scale)
                            .coerceIn(0.0, (bitmap.height - 1).toDouble())
                        pts[idx] = Point(nx, ny)

                        onQuadChange(DocumentGeometry.Quad(pts[0], pts[1], pts[2], pts[3]))
                    },
                    onDragEnd = { draggingIndex = -1 },
                    onDragCancel = { draggingIndex = -1 }
                )
            }
    ) {
        AsyncImage(
            model = bitmap,
            contentDescription = "待矫正的文档",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        val q = quad
        if (q != null && boxSize.width > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                val left = (size.width - bitmap.width * scale) / 2f
                val top = (size.height - bitmap.height * scale) / 2f

                fun toCanvas(p: Point) = Offset(
                    (p.x * scale + left).toFloat(),
                    (p.y * scale + top).toFloat()
                )

                val c0 = toCanvas(q.tl)
                val c1 = toCanvas(q.tr)
                val c2 = toCanvas(q.br)
                val c3 = toCanvas(q.bl)

                // 四边形填充（半透明）+ 描边
                val path = Path().apply {
                    moveTo(c0.x, c0.y)
                    lineTo(c1.x, c1.y)
                    lineTo(c2.x, c2.y)
                    lineTo(c3.x, c3.y)
                    close()
                }
                drawPath(path, color = Color(0x332FA37A), style = Fill)
                drawPath(path, color = Color(0xFF2FA37A), style = Stroke(width = 3f))

                // 四角手柄。正在拖动的那个画一圈高亮外环，
                // 让"我已经抓住了"这件事肉眼可见 —— 抓取半径放大到
                // 112dp 后，用户按下时手指往往不在圆点上，
                // 没有这个反馈就不知道自己有没有抓到。
                listOf(c0, c1, c2, c3).forEachIndexed { i, c ->
                    if (i == draggingIndex) {
                        drawCircle(Color(0x55FFFFFF), radius = handleRadiusPx + 12f, center = c)
                    }
                    drawCircle(Color.White, radius = handleRadiusPx + 3f, center = c)
                    drawCircle(Color(0xFF1B6B4A), radius = handleRadiusPx, center = c)
                }
            }
        }
    }
}

/**
 * 边缘检测的异步封装。
 *
 * 检测失败时回退到内缩全幅四边形，并把失败原因通过 [onFailure] 回传，
 * 而不是像以前那样静默 return —— 否则用户点了"自动检测"什么都没发生，
 * 完全看不出是引擎没就绪还是画面里找不到文档边缘。
 */
private suspend fun autoDetect(
    pipeline: ScanPipeline,
    bitmap: Bitmap,
    onResult: (DocumentGeometry.Quad) -> Unit,
    onFailure: (String) -> Unit = {}
) {
    if (!OpenCvLoader.ensureLoaded()) {
        onFailure("图像处理引擎未就绪，无法自动检测（可手动拖动圆点）")
        return
    }

    // 检测过程说明直接展示给用户：成功时显示哪一级命中，
    // 失败时精确到"ML 分割异常 / ML 未检出 / 边缘检测异常 / 边缘检测未检出"，
    // 把这段文字反馈出来即可定位失败环节，无需 adb。
    val outcome = pipeline.detectQuad(bitmap)
    if (outcome.quad == null) {
        onFailure("未识别到文档边缘，已回退到整幅画面。过程：${outcome.detail}")
    } else {
        onFailure("检测方式：${outcome.detail}")
    }
    onResult(
        outcome.quad ?: DocumentGeometry.Quad.full(bitmap.width, bitmap.height, 0.02)
    )
}

/**
 * 圆点半径与触摸判定半径，单位 dp。
 *
 * 这里必须经过 LocalDensity 换算成像素，不能直接写裸数字 ——
 * 之前写 15f / 46f（裸 px），在 3 倍屏（如三星 S9180，密度约 3.3）上
 * 实际只有约 4.5dp / 14dp：圆点小到看不清，触摸区直径不到 3 毫米，
 * 正常手指几乎不可能按中 —— 表现为"圆点拖不动"。
 *
 * 触摸区按 Material 触控目标建议取 44dp 以上，圆点视觉 10dp。
 */
private val HANDLE_RADIUS_DP = 12.dp
private val HANDLE_GRAB_RADIUS_DP = 112.dp
