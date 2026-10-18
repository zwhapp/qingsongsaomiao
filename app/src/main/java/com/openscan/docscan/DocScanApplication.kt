package com.openscan.docscan

import android.app.Application
import android.util.Log
import com.openscan.docscan.core.OpenCvLoader
import com.openscan.docscan.data.ScanDatabase

class DocScanApplication : Application() {

    val database: ScanDatabase by lazy { ScanDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()

        // OpenCV 延迟到实际处理图像时再初始化，冷启动更快
        OpenCvLoader.warmUpAsync(this) { ok ->
            Log.i(TAG, if (ok) "OpenCV 初始化完成（离线）" else "OpenCV 初始化失败，图像处理将不可用")
        }
    }

    companion object {
        private const val TAG = "DocScanApplication"
    }
}
