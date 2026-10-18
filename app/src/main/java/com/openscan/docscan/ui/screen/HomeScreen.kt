package com.openscan.docscan.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.openscan.docscan.R
import com.openscan.docscan.core.UpdateManager
import com.openscan.docscan.ui.theme.AppCornerRadius
import kotlinx.coroutines.launch
import java.io.File

/**
 * 首页：三个入口 + 离线声明。
 *
 * 刻意做得非常简洁——扫描工具的使用路径越短越好。
 */
@Composable
fun HomeScreen(
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenSelfCheck: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenSponsor: () -> Unit,
    permissionDeniedHint: Boolean,
    dismissPermissionHint: () -> Unit
) {
    // 赞助入口只在分发版显示。自用版（versionName 含 selfuse）是开发者自己的
    // 设备，给自己开"打赏自己"的入口没有意义；分发版（无模型、可外发）才需要。
    // 判定方式与 SelfCheck 第 6 项一致：versionName 含 "selfuse"。
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName.orEmpty()
        }.getOrDefault("")
    }
    val isSelfuse = remember { versionName.contains("selfuse") }
    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(36.dp))

            // 标题区。注意这是全工程唯一走 strings.xml 的文案：
            // 应用名是品牌标识，必须和清单 label（@string/app_name）保持同一真源，
            // 否则桌面上显示的名字和首页标题会不一致。改名时只需动 strings.xml。
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "去手写 · 去阴影 · 高清放大 · 文档矫正",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            OfflineBadge()

            Spacer(Modifier.height(30.dp))

            // 主操作：拍照
            PrimaryActionCard(
                icon = Icons.Filled.PhotoCamera,
                title = "拍照扫描",
                subtitle = "自动识别纸张边缘并矫正",
                highlight = true,
                onClick = onCapture
            )

            Spacer(Modifier.height(14.dp))

            PrimaryActionCard(
                icon = Icons.Filled.PhotoLibrary,
                title = "从相册导入",
                subtitle = "处理已有的照片或截图",
                highlight = false,
                onClick = onImport
            )

            Spacer(Modifier.height(14.dp))

            PrimaryActionCard(
                icon = Icons.Outlined.FolderOpen,
                title = "我的文档",
                subtitle = "查看、导出、分享扫描结果",
                highlight = false,
                onClick = onOpenDocuments
            )

            Spacer(Modifier.height(14.dp))

            // 合规入口：Apache-2.0 第 4 条要求分发时提供许可证副本。
            // 打包阶段剔除了依赖的 META-INF/LICENSE，此处提供等价的应用内声明页。
            LowKeyEntry(
                icon = Icons.Outlined.Info,
                text = "开源许可说明",
                onClick = onOpenLicenses
            )

            // 诊断入口，同样做成不显眼的样式——它是排查工具，不该抢主流程的注意力，
            // 但在功能异常时又必须能被找到。
            LowKeyEntry(
                icon = Icons.Outlined.BugReport,
                text = "环境自检（功能异常时点这里）",
                onClick = onOpenSelfCheck
            )

            // 赞助入口（仅分发版显示）：完全自愿的打赏，样式与上面两个低调入口一致，
            // 不做卡片——它是感谢性质的功能，不该比"我的文档"更显眼。
            if (!isSelfuse) {
                LowKeyEntry(
                    icon = Icons.Outlined.FavoriteBorder,
                    text = "赞助开发者（完全自愿）",
                    onClick = onOpenSponsor
                )
            }

            // 版本号 + 检查更新（仅分发版）：GitHub Releases 是唯一的分发渠道，
            // 自用版开发者手动 adb 安装，无需在线更新。
            if (!isSelfuse) {
                UpdateEntry(currentVersion = versionName)
            }

            Spacer(Modifier.weight(1f))

            AnimatedVisibility(visible = permissionDeniedHint) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(AppCornerRadius),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dismissPermissionHint() }
                ) {
                    Text(
                        text = "相机权限被拒绝。请到系统设置中手动开启，或改用相册导入。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 低调的次级入口（关于 / 自检）。
 *
 * 刻意不做成卡片：这些是低频入口，抢了主流程的注意力反而降低可用性，
 * 但合规与他人排查时又必须找得到、点得动。
 */
@Composable
private fun LowKeyEntry(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(AppCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 版本号标注 + 检查更新（仅分发版）。
 *
 * 流程：点入口 → 查 GitHub Releases latest → 有新版弹窗 → 下载到 cache →
 * 拉起系统安装器。全程只有"读版本号"和"下 APK"两个网络动作。
 */
@Composable
private fun UpdateEntry(currentVersion: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    // 上一次下载完成的包：安装授权被拒后回来，点入口直接重装，不用重新下载
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    fun check() {
        // 上个版本的缓存包没必要留着
        runCatching { File(context.cacheDir, "update/update.apk").delete() }
        checking = true
        scope.launch {
            when (val r = UpdateManager.checkLatest(currentVersion)) {
                is UpdateManager.CheckResult.Available -> pending = r.info
                is UpdateManager.CheckResult.UpToDate ->
                    Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                is UpdateManager.CheckResult.Failed ->
                    Toast.makeText(
                        context,
                        "检查失败：${r.reason}（GitHub 可能暂时不可达，请稍后再试）",
                        Toast.LENGTH_LONG
                    ).show()
            }
            checking = false
        }
    }

    fun download(info: UpdateManager.UpdateInfo) {
        downloading = true
        progress = 0f
        scope.launch {
            runCatching {
                UpdateManager.downloadApk(context, info.apkUrl) { p -> progress = p }
            }.onSuccess { f ->
                downloading = false
                pending = null
                downloadedFile = f
                if (!UpdateManager.installApk(context, f)) {
                    Toast.makeText(
                        context,
                        "请先在设置中允许本应用\"安装未知应用\"，然后回来再点一次\"检查更新\"",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { e ->
                downloading = false
                Toast.makeText(
                    context,
                    "下载失败：${e.message ?: e::class.java.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LowKeyEntry(
        icon = Icons.Outlined.CloudDownload,
        text = if (checking) {
            "正在检查更新…"
        } else {
            "当前版本 v$currentVersion · 检查更新"
        },
        onClick = {
            val f = downloadedFile
            when {
                f != null -> UpdateManager.installApk(context, f)
                !checking && !downloading -> check()
            }
        }
    )

    val info = pending
    if (info != null || downloading) {
        AlertDialog(
            onDismissRequest = { if (!downloading) pending = null },
            title = {
                Text(
                    if (downloading) "正在下载更新"
                    else "发现新版本 v${info?.version.orEmpty()}"
                )
            },
            text = {
                Column {
                    if (downloading) {
                        Text("已完成 ${(progress * 100).toInt()}%")
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("当前版本 v$currentVersion")
                        info?.sizeBytes?.takeIf { it > 0 }?.let { size ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "安装包约 %.1f MB".format(size / 1048576.0),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        info?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 8
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "下载完成后自动拉起系统安装。检查更新只读取 GitHub 公开信息，不上传任何数据。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (!downloading) {
                    TextButton(onClick = { info?.let { download(it) } }) { Text("下载并安装") }
                }
            },
            dismissButton = {
                if (!downloading) {
                    TextButton(onClick = { pending = null }) { Text("以后再说") }
                }
            }
        )
    }
}

@Composable
private fun OfflineBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "100% 离线处理 · 不联网 · 无会员",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun PrimaryActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlight: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(AppCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = if (highlight) 22.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(if (highlight) 46.dp else 40.dp)
                    .background(
                        color = if (highlight) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlight) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(if (highlight) 24.dp else 21.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (highlight) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (highlight) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
