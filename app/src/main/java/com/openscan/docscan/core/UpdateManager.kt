package com.openscan.docscan.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
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

    /**
     * 下载镜像（按优先级依次尝试，最后直连兜底）。
     * 国内直连 github.com 的 release 附件（objects.githubusercontent.com）经常超时或被墙，
     * 这些公共代理把原始下载链接整段拼在自己的域名后面，能显著提速。
     * 顺序可按实测速度调整；任意一个失效会自动跳到下一个，全部失败才报错。
     */
    private val DOWNLOAD_PROXIES = listOf(
        "https://ghproxy.net/",
        "https://mirror.ghproxy.com/",
        "https://ghproxy.com/",
        "https://github.moeyy.xyz/"
    )

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

    /**
     * 流式下载 APK 到 cache/update/，onProgress 回调 0..1。
     * 依次尝试各下载镜像、最后直连兜底：任一个成功即返回，全部失败抛错。
     * onSource 在切换下载源时回调（用于 UI 提示正在用哪个镜像）。
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        expectedSize: Long = 0L,
        onProgress: (Float) -> Unit,
        onSource: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val dest = File(dir, "update.apk")
        val candidates = DOWNLOAD_PROXIES.map { it + url } + url
        var lastErr: Throwable? = null
        for (candidate in candidates) {
            try {
                onSource("正在通过 ${proxyLabel(candidate)} 下载…")
                return@withContext downloadFrom(candidate, dest, expectedSize, onProgress)
            } catch (e: Throwable) {
                lastErr = e // 该镜像失败，尝试下一个
            }
        }
        throw IOException(
            "所有下载源（含 ${DOWNLOAD_PROXIES.size} 个镜像与直连）均失败：" +
                (lastErr?.message ?: "未知错误")
        )
    }

    /** 单次从给定地址下载；非 200、空文件或大小明显不符（下到错误页）都会抛错触发换源。 */
    private fun downloadFrom(
        url: String,
        dest: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit
    ): File {
        val conn = openDownload(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else expectedSize
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
            // 大小明显对不上说明下到了错误页（如镜像返回 HTML 报错而非 APK）
            val got = dest.length()
            if (got <= 0) throw IOException("下载到空文件")
            if (expectedSize > 0 && kotlin.math.abs(got - expectedSize) > expectedSize * 0.02) {
                dest.delete()
                throw IOException("文件大小不符（期望 $expectedSize，实际 $got）")
            }
            return dest
        } finally {
            conn.disconnect()
        }
    }

    /** 下载用连接：比 API 用更长的读超时，避免 70MB 大文件在慢镜像上被中间超时截断。 */
    private fun openDownload(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "QingSongScan-UpdateCheck")
        return conn
    }

    /** 把候选地址还原成可读的源名，用于 UI 提示。 */
    private fun proxyLabel(u: String): String {
        for (p in DOWNLOAD_PROXIES) {
            if (u.startsWith(p)) return p.removeSuffix("/")
        }
        return "GitHub 直连"
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
