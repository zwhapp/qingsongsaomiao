package com.openscan.docscan.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.OpenCVLoader

/**
 * OpenCV 初始化封装。
 *
 * 采用静态初始化 + 互斥锁，保证并发调用时只初始化一次；
 * 首次使用图像处理前必须确保 [ensureLoaded] 返回 true。
 */
object OpenCvLoader {

    private const val TAG = "OpenCvLoader"
    private val mutex = Mutex()

    /**
     * 初始化结果。
     *
     * ★ 只缓存**成功**，不缓存失败。
     *   之前写成 `loaded: Boolean?`，失败时也会把 false 永远钉在缓存里：
     *   一旦首次 initLocal 因瞬时原因失败（例如自检在 Application.onCreate
     *   里抢先跑、此时 System.loadLibrary 尚未就绪），此后所有调用都会
     *   直接返回 false，整条图像链路（含 ML 分割）永久不可用 ——
     *   表现恰好就是"自动检测点了没反应"，且重启也没用（进程内缓存）。
     *   现在失败返回 null，允许下次重试。
     */
    @Volatile
    private var loaded: Boolean? = null

    /**
     * 同步确保 OpenCV 已就绪。可在任意线程调用，内部加锁。
     * 失败不会被缓存，下次调用会重试。
     */
    suspend fun ensureLoaded(): Boolean {
        if (loaded == true) return true
        return mutex.withLock {
            if (loaded == true) return@withLock true
            val ok = runCatching { OpenCVLoader.initLocal() }
                .getOrElse { e ->
                    Log.e(TAG, "OpenCV 初始化异常：${e.describe()}")
                    false
                }
            // 只有成功才缓存；失败留 null，下次重新尝试
            if (ok) loaded = true
            ok
        }
    }

    /**
     * 启动时后台预热，避免用户第一次点扫描时卡顿。
     */
    fun warmUpAsync(context: Context, onResult: (Boolean) -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            val ok = ensureLoaded()
            onResult(ok)
        }
    }

    val isReady: Boolean get() = loaded == true
}
