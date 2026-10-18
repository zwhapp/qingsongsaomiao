package com.openscan.docscan.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openscan.docscan.core.OpenCvLoader
import com.openscan.docscan.core.ScanEnhancer
import com.openscan.docscan.core.ScanPipeline
import kotlinx.coroutines.launch

/**
 * 增强效果选项。
 *
 * 分辨率已经从"效果"里独立出去（见 [ScanPipeline.Resolution]）——
 * 之前把"高清增强 2x / 超清 4x"和模式混在一排按钮里，用户既分不清
 * 是换了处理方式还是只换了尺寸，也没法组合（比如"黑白 + 极清"）。
 * 现在两组正交：先选效果，再选输出分辨率，任意组合。
 */
private data class EffectOption(
    val label: String,
    val mode: ScanEnhancer.Mode
)

private val EFFECT_OPTIONS = listOf(
    EffectOption("去阴影", ScanEnhancer.Mode.DE_SHADOW),
    EffectOption("黑白文档", ScanEnhancer.Mode.BLACK_WHITE),
    EffectOption("去手写", ScanEnhancer.Mode.REMOVE_INK),
    EffectOption("灰度", ScanEnhancer.Mode.GRAYSCALE),
    EffectOption("原图", ScanEnhancer.Mode.ORIGINAL)
)

/**
 * 裁剪页 → 增强页之间的单页交接。
 *
 * 用两个独立字段而不是 `ScanSession.capturedFile`，是因为裁剪页已经把
 * 图片解码成 Bitmap 并让用户调过角点了，重新读盘会丢掉这些工作。
 * 位图所有权在进入增强页时转移，增强完成后由调用方回收。
 */
@Volatile
var reviewSource: Bitmap? = null

@Volatile
var reviewQuad: com.openscan.docscan.core.DocumentGeometry.Quad? = null

/**
 * 增强界面：实时预览 + 效果切换 + 加入文档。
 *
 * 预览使用降采样后的图以保证流畅；确认时才用全分辨率重新跑一遍管线。
 *
 * 处理结果**不直接落盘**，而是追加到 [ScanSession]，由页面管理页
 * 统一决定何时保存成多页文档。
 */
@Composable
fun EnhanceScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pipeline = remember { ScanPipeline(context) }

    var selected by remember { mutableStateOf(EFFECT_OPTIONS.first()) }
    var resolution by remember { mutableStateOf(ScanPipeline.Resolution.DEFAULT) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var originalPreview by remember { mutableStateOf<Bitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var stageText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // 待处理的源图：来自裁剪页刚刚确认的那一页
    val source = reviewSource
    val quad = reviewQuad

    // 初次进入或换效果时重算预览。
    // 注意不要把 resolution 放进 key：预览固定跑 PREVIEW_MAX_EDGE，
    // 换分辨率并不改变预览画面，放进 key 只会让用户白等一次重算。
    LaunchedEffect(selected, source) {
        val bmp = source ?: return@LaunchedEffect
        if (preview == null || originalPreview == null) {
            originalPreview = bmp
        }
        processing = true
        errorText = null
        progress = 0f
        stageText = "准备中…"

        val ready = OpenCvLoader.ensureLoaded()
        if (!ready) {
            processing = false
            errorText = "图像处理引擎初始化失败，请重启应用"
            return@LaunchedEffect
        }

        runCatching {
            pipeline.process(
                source = bmp,
                options = ScanPipeline.Options(
                    quad = quad,
                    enhance = selected.mode,
                    // 预览固定用较低分辨率：手机上屏幕也就 1000+ px 宽，
                    // 预览跑 3000/6000 只会拖慢切换速度、看不出差别。
                    // 真正落到文档里的是确认时按用户所选档位重跑的结果。
                    targetLongEdge = PREVIEW_MAX_EDGE
                )
            ) { stage, p ->
                progress = p
                stageText = when (stage) {
                    ScanPipeline.Stage.WARP -> "正在矫正文档…"
                    ScanPipeline.Stage.ENHANCE -> "正在增强…"
                    ScanPipeline.Stage.UPSCALE -> "正在高清放大…"
                    ScanPipeline.Stage.DONE -> "完成"
                }
            }
        }.onSuccess { result ->
            preview = result.bitmap
            processing = false
            // 某一步降级了就把原因显示出来，不要再静默成"看起来没效果"
            errorText = result.warnings.joinToString("；").ifBlank { null }
        }.onFailure { e ->
            errorText = "处理失败：${e.message ?: e::class.java.simpleName}"
            processing = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("增强效果") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                        .padding(vertical = 10.dp)
                ) {
                    // 效果选择条：5 个选项一行放不下，保留横向滑动；
                    // 但被截断的一侧自动渐隐，明示"右边/左边还有"。
                    val effectScroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // DstIn 混合需要离屏合成，否则渐变会把整层画花
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val fadePx = 16.dp.toPx()
                                // 滑到最左后左侧渐隐消失，反之亦然（draw 阶段读
                                // ScrollState 会自动触发重绘）
                                if (effectScroll.value > 0) {
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            0f to Color.Transparent, 1f to Color.Black,
                                            endX = fadePx
                                        ),
                                        blendMode = BlendMode.DstIn
                                    )
                                }
                                if (effectScroll.value < effectScroll.maxValue) {
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            0f to Color.Black, 1f to Color.Transparent,
                                            startX = size.width - fadePx
                                        ),
                                        blendMode = BlendMode.DstIn
                                    )
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
                                .horizontalScroll(effectScroll),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            EFFECT_OPTIONS.forEach { option ->
                                EffectChip(
                                    label = option.label,
                                    selected = option == selected,
                                    enabled = !processing && !saving,
                                    onClick = { selected = option }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 分辨率选择条：4 个档位均分宽度，任何屏幕都完整可见，无需滚动
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "分辨率",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ScanPipeline.Resolution.all.forEach { r ->
                                ResolutionChip(
                                    label = r.label,
                                    hint = r.hint,
                                    selected = r == resolution,
                                    enabled = !processing && !saving,
                                    onClick = { resolution = r },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "输出长边约 ${resolution.targetLongEdge}px",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = !processing && !saving && preview != null,
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                val bmp = source ?: return@Button
                                saving = true
                                errorText = null
                                scope.launch {
                                    runCatching {
                                        // 全分辨率重跑一次管线，把结果追加进会话
                                        pipeline.process(
                                            source = bmp,
                                            options = ScanPipeline.Options(
                                                quad = quad,
                                                enhance = selected.mode,
                                                targetLongEdge = resolution.targetLongEdge
                                            )
                                        ) { stage, p ->
                                            progress = p
                                            stageText = when (stage) {
                                                ScanPipeline.Stage.WARP -> "正在矫正文档…"
                                                ScanPipeline.Stage.ENHANCE -> "正在增强…"
                                                ScanPipeline.Stage.UPSCALE -> "正在高清放大…"
                                                ScanPipeline.Stage.DONE -> "收尾…"
                                            }
                                        }
                                    }.onSuccess { outcome ->
                                        val result = outcome.bitmap
                                        // 源图已经用不上了，先回收再交接结果
                                        bmp.recycle()
                                        reviewSource = null
                                        reviewQuad = null
                                        ScanSession.addPage(
                                            result, quad, resolution.targetLongEdge
                                        )

                                        // 预览用的位图和结果不是同一个对象，一起释放
                                        preview?.takeIf { it !== result }?.recycle()
                                        preview = null
                                        originalPreview = null
                                        saving = false

                                        // 降级原因如实回传，但不要拦住保存流程
                                        errorText = outcome.warnings.joinToString("；").ifBlank { null }
                                        onAdded()
                                    }.onFailure { e ->
                                        saving = false
                                        errorText = "处理失败：${e.message ?: e::class.java.simpleName}"
                                    }
                                }
                            }
                        ) {
                            if (saving) {
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
                            Text("加入文档")
                        }
                    }
                }
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                val shown = if (showOriginal) originalPreview else preview
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "增强结果预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        showOriginal = true
                                        tryAwaitRelease()
                                        showOriginal = false
                                    }
                                )
                            }
                    )
                }

                if (processing || saving) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stageText.ifBlank { "处理中…" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.width(160.dp)
                            )
                        }
                    }
                }

                if (!processing && preview != null) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = if (showOriginal) "原图" else "长按对比原图",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            errorText?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun EffectChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(11.dp),
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
            .then(
                if (selected) Modifier else Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(11.dp)
                )
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

/**
 * 分辨率档位小胶囊：主标题 + 一行说明（"推荐"/"体积小"…）。
 *
 * 说明文字是必要的 —— 光看"标准/高清/超清/极清"用户无法判断该选哪个，
 * 尤其"极清"要多花几秒且文件大一倍，得让人知道代价。
 */
@Composable
private fun ResolutionChip(
    label: String,
    hint: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(11.dp),
        modifier = modifier
            .clickable(enabled = enabled) { onClick() }
            .then(
                if (selected) Modifier else Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(11.dp)
                )
            )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** 预览用的长边上限。手机屏幕看不出更高分辨率的差别，但会明显变慢。 */
private const val PREVIEW_MAX_EDGE = 2000

