package com.openscan.docscan.ui.screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import com.openscan.docscan.core.ScanPipeline
import com.openscan.docscan.ui.Routes
import com.openscan.docscan.ui.theme.DocScanTheme

/**
 * 处理「用本应用打开图片」的入口。
 *
 * 从系统分享/打开进来时，把 URI 转成位图放进裁剪页的交接位
 * （[reviewSource] / [reviewQuad]），然后直接跳到裁剪界面，
 * 省掉首页这一步。
 *
 * 这里同时开一个新的扫描会话，保证从外部进来的图片是"新文档的第一页"，
 * 不会和上一次没保存完的草稿混在一起。
 */
class ImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingUri = extractUri()

        setContent {
            DocScanTheme {
                val navController = rememberNavController()
                val pipeline = remember { ScanPipeline(this) }

                LaunchedEffect(incomingUri) {
                    if (incomingUri != null) {
                        runCatching { pipeline.loadBitmap(incomingUri) }
                            .onSuccess { bmp ->
                                ScanSession.start()
                                reviewSource = bmp
                                reviewQuad = null
                            }
                        navController.navigate(Routes.REVIEW) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    } else {
                        finish()
                    }
                }

                com.openscan.docscan.ui.AppNavHost(
                    navController = navController,
                    hasCameraPermission = { false },
                    requestCameraPermission = { it() },
                    onPermissionDenied = {},
                    permissionDeniedHint = false,
                    dismissPermissionHint = {}
                )
            }
        }
    }

    private fun extractUri(): android.net.Uri? {
        return when (intent?.action) {
            android.content.Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri
            android.content.Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }
}
