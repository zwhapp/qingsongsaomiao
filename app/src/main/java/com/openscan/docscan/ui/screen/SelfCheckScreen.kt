package com.openscan.docscan.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openscan.docscan.core.SelfCheck
import kotlinx.coroutines.launch

/**
 * 环境自检页。
 *
 * 存在的意义：当 App 能装但功能不生效、又无法用 adb 抓日志时，
 * 让用户直接在手机上看到"到底哪一层坏了、报的什么错"。
 *
 * 界面刻意做得朴素——这是个诊断工具，不是给人欣赏的。
 */
@Composable
fun SelfCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var report by remember { mutableStateOf<SelfCheck.Report?>(null) }
    var running by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    suspend fun doRun() {
        running = true
        report = SelfCheck.run(context)
        running = false
    }

    LaunchedEffect(Unit, report == null) {
        if (report == null) doRun()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("环境自检") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val r = report
                    if (r != null) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(r.asPlainText()))
                            copied = true
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制报告")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            if (running && report == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在检测…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            val r = report
            if (r != null) {
                // 总览
                Surface(
                    color = if (r.allPassed) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = if (r.allPassed) "全部通过" else "存在问题",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (r.allPassed) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (r.allPassed) {
                                "图像处理链路正常。若仍觉得效果不明显，" +
                                    "请换一张有明确阴影/倾斜边界的照片再试。"
                            } else {
                                "下面标红项即为故障点，请把本页内容反馈给开发者。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.allPassed) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 逐项
                r.items.forEach { item ->
                    CheckRow(item)
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(14.dp))

                // 设备信息：排查 ABI 不匹配时关键
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Text(
                            "设备信息",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        InfoLine("设备", r.deviceModel)
                        InfoLine("系统", r.androidVersion)
                        InfoLine("CPU 架构", r.abi)
                        InfoLine("App 版本", r.appVersion)
                    }
                }

                if (copied) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "报告已复制到剪贴板",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { scope.launch { report = null; doRun() } },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("重新检测")
                }
            }

            Spacer(Modifier.height(24.dp))
            Box(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun CheckRow(item: SelfCheck.Item) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (item.passed) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                tint = if (item.passed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.passed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** 转成便于粘贴反馈的纯文本。 */
private fun SelfCheck.Report.asPlainText(): String = buildString {
    appendLine("轻松扫描 环境自检")
    appendLine("版本: $appVersion")
    appendLine("设备: $deviceModel")
    appendLine("系统: $androidVersion")
    appendLine("CPU 架构: $abi")
    appendLine("结果: ${if (allPassed) "全部通过" else "存在问题"}")
    appendLine("---")
    items.forEach {
        appendLine("${if (it.passed) "[通过]" else "[失败]"} ${it.name}")
        appendLine("        ${it.detail}")
    }
}
