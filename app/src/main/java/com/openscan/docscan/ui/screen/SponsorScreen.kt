package com.openscan.docscan.ui.screen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openscan.docscan.ui.theme.AppCornerRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 赞助页：展示支付宝收款码，供用户**志愿**打赏开发者。
 *
 * 设计约束与取舍：
 *  · 本应用无后端、无商户资质，不接支付 SDK——个人收款码 + 用户扫码转账是
 *    唯一不需要任何资质/签约的志愿付费方式；
 *  · 个人收款码无法携带金额（那是「商家收款码 + 金额参数」的能力），所以
 *    ¥1/¥5/¥10 只是**建议金额**：选中后提示文案引导用户在支付宝里输入该数，
 *    并不 (也不可能) 直接发起定额转账；
 *  · 入口只在分发版（versionName 无 selfuse）显示，见 HomeScreen。
 */
@Composable
fun SponsorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var amount by remember { mutableStateOf<Int?>(null) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        qrBitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(QR_ASSET).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("赞助开发者") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            Text(
                text = "轻松扫描完全免费，也没有任何广告。" +
                    "如果它帮到了你，欢迎请开发者喝杯奶茶 ☕",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "完全自愿，金额随意——用得开心就是最好的支持。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- 建议金额 ----
            Text(
                text = "建议金额（支付宝里可自行修改）",
                style = MaterialTheme.typography.titleSmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(1, 5, 10).forEach { yuan ->
                    val selected = amount == yuan
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { amount = if (selected) null else yuan }
                    ) {
                        Text(
                            text = "¥$yuan",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }

            // ---- 收款码 ----
            Surface(
                shape = RoundedCornerShape(AppCornerRadius),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Surface(shape = RoundedCornerShape(12.dp)) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "支付宝收款码",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .aspectRatio(
                                        bmp.width.toFloat() / bmp.height.toFloat()
                                    )
                            )
                        }
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.73f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                Icons.Outlined.QrCode2, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (amount != null) {
                            "打开支付宝扫一扫，转账时输入 ¥$amount"
                        } else {
                            "打开支付宝扫一扫即可转账"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ---- 操作按钮 ----
            Button(
                onClick = {
                    scope.launch {
                        saveMessage = withContext(Dispatchers.IO) {
                            saveQrToGallery(context)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存收款码到相册")
            }

            OutlinedButton(
                onClick = { openAlipayScan(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开支付宝扫一扫")
            }

            saveMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = "保存后在支付宝「扫一扫」右上角选择相册中的二维码即可；" +
                    "手机装有支付宝时也可直接点上面按钮。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 收款码资源路径。 */
private const val QR_ASSET = "sponsor/alipay.jpg"

/**
 * 把 assets 里的收款码图存入公共相册（Pictures/轻松扫描/）。
 * 走 MediaStore 插入，Android 10+ 无需任何权限（思路同 PublicExporter）。
 */
private fun saveQrToGallery(context: Context): String {
    return runCatching {
        val bytes = context.assets.open(QR_ASSET).use { it.readBytes() }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "轻松扫描_赞助收款码.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/轻松扫描"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = context.contentResolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: return "保存失败：无法写入相册"
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        "已保存到相册 Pictures/轻松扫描/"
    }.getOrElse { "保存失败：${it.message ?: it.javaClass.simpleName}" }
}

/** 尝试拉起支付宝扫一扫；未安装支付宝时静默失败（页面文案已覆盖该场景）。 */
private fun openAlipayScan(context: Context) {
    runCatching {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("alipays://platformapi/startapp?appId=10000007")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
