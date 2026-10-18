package com.openscan.docscan.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 分发版的在线更新（GitHub Releases）。
 *
 * 设计约束：
 * - 只读公开 API（releases/latest），不上传任何东西——与"100% 离线处理"的
 *   隐私承诺不冲突，但 UI 上必须把"联网仅用于检查更新"说清楚。
 * - 零第三方依赖：HttpURLConnection + org.json（平台自带），不给 70MB 的包
 *   再添一个库。
 * - 国内直连 api.github.com 不一定可达（可能被网络拦截），所以超时必须短、
 *   失败必须可重试且文案如实，绝不伪装成"已是最新版"。
 */
object UpdateManager {

    private const val REPO = "zwhapp/qingsongsaomiao"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 检查更新拿到的信息。 */
    data class UpdateInfo(
        val version: String,      // 归一化后的版本号，如 "1.4.19"
        val apkUrl: String,       // release 资产里的 APK 直链
        val notes: String,        // release 说明（可能为空）
        val sizeBytes: Long
    )

    sealed class CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult()
        data object UpToDate : CheckResult()
        data class Failed(val reason: String) : CheckResult()
    }

    /**
     * 查询最新 release 并与当前版本比较。
     *
     * 仓库还没有任何 release 时（404）按"已是最新"处理——首次分发阶段
     * 这是正常状态，不该当成错误吓用户。
     */
    suspend fun checkLatest(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open(LATEST_API)
            try {
                val code = conn.responseCode
                if (code == 404) return@withContext CheckResult.UpToDate
                if (code != 200) {
                    return@withContext CheckResult.Failed("GitHub 返回 $code")
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.optString("tag_name").orEmpty()
                val remote = normalize(tag)
                if (remote.isEmpty()) return@withContext CheckResult.Failed("release 缺少 tag_name")

                var apkAsset: JSONObject? = null
                json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkAsset = a
                            break
                        }
                    }
                }
                if (apkAsset == null) {
                    return@withContext CheckResult.Failed("release 里没有 APK 附件")
                }

                val info = UpdateInfo(
                    version = remote,
                    apkUrl = apkAsset.optString("browser_download_url"),
                    notes = json.optString("body").orEmpty().trim(),
                    sizeBytes = apkAsset.optLong("size", 0L)
                )
                if (isNewer(remote, normalize(currentVersion))) {
                    CheckResult.Available(info)
                } else {
                    CheckResult.UpToDate
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e ->
            CheckResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /** 流式下载 APK 到 cache/update/，onProgress 回调 0..1。返回下载文件。 */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val dest = File(dir, "update.apk")
        val conn = open(url)
        try {
            val total = conn.contentLengthLong.let { if (it > 0) it else -1L }
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toDouble() / total).toFloat())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        dest
    }

    /**
     * 拉起系统安装器。返回 false 表示缺"安装未知应用"授权，此时已跳转
     * 授权页，用户回来再点一次安装即可。
     */
    fun installApk(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return false
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    // ---------- 内部工具 ----------

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "QingSongScan-UpdateCheck")
        return conn
    }

    /** "v1.4.19" / "1.4.19-selfuse" → "1.4.19"：去 v 前缀与数字段之后的尾巴。 */
    private fun normalize(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }
            .trimEnd('.')

    /** 逐数字段比较：1.4.19 > 1.4.9 > 1.4。段数不足按 0 补齐。 */
    fun isNewer(remote: String, current: String): Boolean {
        if (remote.isEmpty() || current.isEmpty()) return false
        val r = remote.split('.').map { it.toLongOrNull() ?: 0L }
        val c = current.split('.').map { it.toLongOrNull() ?: 0L }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val a = r.getOrElse(i) { 0L }
            val b = c.getOrElse(i) { 0L }
            if (a != b) return a > b
        }
        return false
    }
}
