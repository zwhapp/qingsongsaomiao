package com.openscan.docscan.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 把导出文件发布到**公共媒体库**（相册/文件管理器可见）。
 *
 * ## 为什么要做这一步
 *
 * 之前导出直接写 `getExternalFilesDir(Pictures)/exports`，那是 app-specific
 * 目录：在 Android 11+ 的文件管理器里藏在 Android/data 深处，用户
 * "导出成功但找不到文件"（真机反馈原话）。
 *
 * ## 怎么做
 *
 * Android 10+（API 29+）用 MediaStore 插入，相对路径 Pictures/轻松扫描，
 * **不需要任何存储权限**；导出后文件管理器和相册直接可见。
 * Android 9 及以下 MediaStore 插入需要写权限，继续用 app-specific 目录
 * （老设备用户一般会用文件管理器找 Android/data）。
 */
object PublicExporter {

    private const val TAG = "PublicExporter"

    /** 公共可见的顶级目录名。与展示名保持一致，用户导出后在相册里一眼能认出来。 */
    const val PUBLIC_DIR = "轻松扫描"

    /**
     * 把一个本地文件发布到公共媒体库。
     *
     * @return 发布后的显示路径（如 Pictures/轻松扫描/xx.pdf），失败返回 null
     *         （失败时本地文件仍在原处，调用方应提示原路径）
     */
    fun publish(context: Context, file: File): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val isPdf = file.extension.equals("pdf", ignoreCase = true)
            val isTxt = file.extension.equals("txt", ignoreCase = true)
            // PDF/文本放 Download（文件管理器最直观），图片放 Pictures。
            // 注意 getContentUri 挂在 Images.Media / Downloads 上：
            //   · MediaStore.Images 本身没有该方法（是 MediaStore.Images.Media 才有，
            //     已用 javap 核对 android-35/android.jar）
            //   · MediaStore.Downloads 是 final 类，方法直接在其上
            val collection = if (isPdf || isTxt) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val relPath = if (isPdf || isTxt) {
                "Download/$PUBLIC_DIR"
            } else {
                "${Environment.DIRECTORY_PICTURES}/$PUBLIC_DIR"
            }
            val mime = when {
                isPdf -> "application/pdf"
                isTxt -> "text/plain"
                else -> "image/jpeg"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = context.contentResolver.insert(collection, values)
                ?: return@runCatching null as String?

            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching null as String?

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

            // 发布成功后删掉私有目录里的临时文件，避免双份占空间
            runCatching { file.delete() }

            when {
                isPdf || isTxt -> "$relPath/${file.name}"
                else -> "$relPath/${file.name}"
            }
        }.getOrElse { e ->
            Log.w(TAG, "发布到公共媒体库失败，文件保留在私有目录", e)
            null
        }
    }
}
