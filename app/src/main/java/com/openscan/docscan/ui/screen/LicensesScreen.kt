package com.openscan.docscan.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 开源许可声明页。
 *
 * 存在的意义：`app/build.gradle.kts` 的 `packaging.resources.excludes` 会剔除
 * 依赖带进来的 `/META-INF/LICENSE*` 与 `NOTICE*`（避免打包时的重复文件冲突），
 * 但 **Apache-2.0 第 4 条 (a)(d) 明确要求分发时提供许可证副本并保留 NOTICE**。
 * 所以这里把 `assets/THIRD-PARTY-NOTICES.md` 全文呈现给用户，作为等价履行。
 *
 * 内容来源就是随包分发的同一个文件，不存在"文档写了但实际没提供"的缺口。
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var raw by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            raw = withContext(Dispatchers.IO) {
                context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            // 资源缺失不该让页面崩掉——把原因原样显示出来，便于排查
            failure = t.message ?: t.javaClass.simpleName
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("开源许可说明") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (raw != null) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(raw!!))
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "复制全文"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { inner ->
        val body = raw
        when {
            failure != null -> MessagePane(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(24.dp),
                text = "许可声明文件读取失败：$failure"
            )

            body == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }

            else -> LicenseBody(
                blocks = remember(body) { parseMarkdown(body) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
            )
        }
    }
}

@Composable
private fun MessagePane(modifier: Modifier, text: String) {
    Column(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun LicenseBody(blocks: List<MdBlock>, modifier: Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // 一句话说明"为什么有这个页面"，避免用户以为是设置项
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "本应用内嵌了多个第三方开源组件。根据各自的许可证要求，" +
                        "此处提供完整的署名与许可证副本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        blocks.forEach { block -> MdBlockView(block) }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun MdBlockView(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> {
            Spacer(Modifier.height(if (block.level == 1) 20.dp else 14.dp))
            Text(
                text = block.text,
                style = when (block.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (block.level == 1) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }
        }

        is MdBlock.Paragraph -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 19.sp,
            modifier = Modifier.padding(vertical = 3.dp)
        )

        is MdBlock.Bullet -> Row(Modifier.padding(vertical = 3.dp)) {
            Text(
                text = block.marker,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(20.dp)
            )
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 19.sp
            )
        }

        is MdBlock.Quote -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp,
                modifier = Modifier.padding(10.dp)
            )
        }

        // 代码块留给许可证全文：按行原样呈现，横向可滚（法律文本不能改格式）
        is MdBlock.Code -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text = block.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 表格转成纵向卡片：窄屏上比横向滚动的表格好读得多，信息不丢失
        is MdBlock.Table -> {
            Spacer(Modifier.height(4.dp))
            block.rows.forEach { row ->
                val head = row.firstOrNull().orEmpty()
                val rest = row.drop(1)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = head,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (rest.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = rest.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        MdBlock.Divider -> HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 10.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// 极简 Markdown 解析
//
// 不引入任何 Markdown 库：一是本文件只需覆盖 THIRD-PARTY-NOTICES.md 里用到的
// 语法子集（标题 / 段落 / 列表 / 引用 / 代码块 / 表格 / 分隔线），二是许可声明
// 属于合规材料，依赖越少越不容易出问题。
// ---------------------------------------------------------------------------

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

private fun parseMarkdown(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val code = StringBuilder()
    var inCode = false
    var table = ArrayList<List<String>>()

    fun flushTable() {
        if (table.isNotEmpty()) {
            out.add(MdBlock.Table(table.toList()))
            table = ArrayList()
        }
    }

    for (line in src.lines()) {
        // 代码块优先：里面的 | 和 # 都当普通文本
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out.add(MdBlock.Code(code.toString().trimEnd('\n')))
                code.setLength(0)
                inCode = false
            } else {
                flushTable()
                inCode = true
            }
            continue
        }
        if (inCode) {
            code.append(line).append('\n')
            continue
        }

        val t = line.trimEnd()
        val trimmed = t.trim()

        // 表格：以 | 开头且以 | 结尾；分隔行（|---|）丢弃
        if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
            val cells = trimmed.trim('|').split('|').map { it.trim() }
            if (cells.all { it.isNotEmpty() && it.all { c -> c == '-' || c == ':' } }) {
                continue
            }
            table.add(cells)
            continue
        }
        flushTable()

        when {
            trimmed.isEmpty() -> Unit

            trimmed.startsWith("#### ") -> out.add(MdBlock.Heading(3, trimmed.removePrefix("#### ").trim()))
            trimmed.startsWith("### ") -> out.add(MdBlock.Heading(3, trimmed.removePrefix("### ").trim()))
            trimmed.startsWith("## ") -> out.add(MdBlock.Heading(2, trimmed.removePrefix("## ").trim()))
            trimmed.startsWith("# ") -> out.add(MdBlock.Heading(1, trimmed.removePrefix("# ").trim()))

            trimmed == "---" || trimmed == "***" -> out.add(MdBlock.Divider)

            trimmed.startsWith("> ") -> out.add(MdBlock.Quote(trimmed.removePrefix("> ").trim()))

            trimmed.startsWith("- ") -> out.add(MdBlock.Bullet("•", trimmed.removePrefix("- ").trim()))
            trimmed.startsWith("* ") -> out.add(MdBlock.Bullet("•", trimmed.removePrefix("* ").trim()))

            // 有序列表：保留原编号，方便对照文档
            ORDERED_LIST.matches(trimmed) -> {
                val dot = trimmed.indexOf(". ")
                out.add(MdBlock.Bullet(trimmed.substring(0, dot + 1), trimmed.substring(dot + 2).trim()))
            }

            else -> out.add(MdBlock.Paragraph(trimmed))
        }
    }

    if (inCode && code.isNotEmpty()) out.add(MdBlock.Code(code.toString().trimEnd('\n')))
    flushTable()
    return out
}

private val ORDERED_LIST = Regex("^\\d{1,2}\\.\\s+.+")

/** 与 `app/src/main/assets/` 下的文件同名，两处必须保持一致。 */
private const val NOTICES_ASSET = "THIRD-PARTY-NOTICES.md"
