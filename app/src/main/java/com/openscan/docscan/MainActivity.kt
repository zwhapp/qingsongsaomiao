package com.openscan.docscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.openscan.docscan.ui.AppNavHost
import com.openscan.docscan.ui.theme.DocScanTheme

class MainActivity : ComponentActivity() {

    private var cameraGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (granted) onCameraReady?.invoke()
        onCameraReady = null
    }

    /** 相机权限通过后要执行的动作（点"拍照扫描"时被赋值）。 */
    private var onCameraReady: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            DocScanTheme {
                val navController = rememberNavController()
                var permissionDeniedHint by remember { mutableStateOf(false) }

                AppNavHost(
                    navController = navController,
                    hasCameraPermission = { cameraGranted },
                    requestCameraPermission = { onGranted ->
                        if (cameraGranted) {
                            onGranted()
                        } else {
                            onCameraReady = onGranted
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onPermissionDenied = { permissionDeniedHint = true },
                    permissionDeniedHint = permissionDeniedHint,
                    dismissPermissionHint = { permissionDeniedHint = false }
                )
            }
        }
    }
}
