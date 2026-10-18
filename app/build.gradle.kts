plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// 读取 release 签名凭据
//
// keystore.properties 由 gen-keystore.ps1 -Password '...' 生成，内容：
//     storeFile=docscan-release.jks
//     storePassword=...
//     keyAlias=docscan
//     keyPassword=...
//
// 该文件已被 .gitignore 排除（里面有明文口令），绝不能提交。
//
// 【关键】文件不存在时必须能正常构建，不能让整个工程打不开。
// 所以这里只读取、不抛异常；下面只在文件存在时才创建 signingConfig。
// 也就是说：没生成密钥库时，assembleDebug 照常能跑，
// 只是 assembleRelease 会产出 unsigned 包（这和加这段代码之前的行为一致）。
// ---------------------------------------------------------------------------
// 必须显式 import java.util.Properties，不能写全限定名 java.util.Properties ——
// 在 Gradle Kotlin DSL 里 `java` 已被 Android 插件解析成 JavaPluginExtension，
// 会被当成扩展访问而非包名，实测报 "Unresolved reference: util"。
import java.util.Properties

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasSigningConfig = keystorePropsFile.exists() &&
        keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.openscan.docscan"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openscan.docscan"
        minSdk = 26
        targetSdk = 35
        versionCode = 43
        versionName = "1.5.16-selfuse"

        vectorDrawables { useSupportLibrary = true }

        // 只保留 arm 架构。OpenCV 官方 AAR 含四套原生库，全打包会让 APK 多出约 90MB：
        //   arm64-v8a 19MB + armeabi-v7a 14MB + x86 38MB + x86_64 52MB
        // 真机几乎都是 arm，保留前两套即可覆盖 99% 以上设备。
        // 需要在 x86 模拟器上运行时，把下面三行注释掉。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 只打包中文和英文资源，裁掉第三方依赖带进来的其它语言。
        //
        // 注意属性名和位置：AGP 8.7.3 里这个属性叫 resourceConfigurations，
        // 定义在 BaseFlavor 上，所以必须写在 defaultConfig {} 里。
        // 不要写成 androidResources { localeFilters += ... } —— 那是较新 AGP 才有的
        // 写法，在 8.7.3 上会直接报 "Unresolved reference: localeFilters"。
        // 已用 javap 反编译 gradle-api-8.7.3.jar 核对：
        //   BaseFlavor.getResourceConfigurations() -> Set<String>
        //   AndroidResources 接口中不存在 localeFilters
        resourceConfigurations += listOf("zh", "en")
    }

    // 只有在 keystore.properties 存在时才注册这个 signingConfig。
    // 无条件注册会在文件缺失时把 storeFile 设成 null，导致 sync 阶段就报错。
    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // 没有 keystore.properties 时这里不设 signingConfig，
            // AGP 会退回到输出 unsigned 包（文件名含 unsigned）。
            // 生成了密钥库之后，同一句 assembleRelease 直接出签名包。
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // -----------------------------------------------------------------------
    // 产物文件名带版本号与构建类型。
    //
    // 默认产物叫 app-release.apk —— 每次都覆盖，分不清手上是哪个版本。
    // 之前真机排查时正是因为这一点，反复怀疑"是不是装的旧包"却无法证实。
    // 现在产出形如：
    //     QingSongScan-v1.4.1-11-release.apk （v1.4.1，versionCode 11，合规版签名包）
    //     QingSongScan-v1.5.1-selfuse-12-release.apk （自用版，内含 GPL 模型）
    //     QingSongScan-v1.4.1-11-debug.apk   （调试包）
    //
    // 只用 ASCII 与连字符，不用括号 —— 文件名会出现在 shell 命令里，
    // 括号在 PowerShell / bash 下都要转义，徒增踩坑。
    // 版本号直接取自 defaultConfig，改一处即可。
    // -----------------------------------------------------------------------
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val vName = variant.versionName ?: "unknown"
            val vCode = variant.versionCode
            output.outputFileName = "QingSongScan-v${vName}-${vCode}-${variant.buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"

        // Material 3 的 TopAppBar / Scaffold(contentWindowInsets) 等仍标着
        // @ExperimentalMaterial3Api，不显式 opt-in 就会报：
        //   "This material API is experimental and is likely to change or to be
        //    removed in the future."
        //
        // 这里用编译参数统一开启，而不是在每个 composable 上加 @OptIn ——
        // 本工程有十来处调用点，逐个标注又啰嗦又容易漏。
        // 升级 Compose 后如果这些 API 转正，这一行可以直接删掉。
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
        jniLibs { useLegacyPackaging = false }
    }

    androidResources {
        // TFLite 模型不要压缩，避免运行时解压开销
        noCompress += listOf("tflite")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // 官方 OpenCV，Apache-2.0。内置各架构原生库，体积由上面的 abiFilters 控制。
    implementation(libs.opencv)

    // 离线中文 OCR。bundled 版把模型打包进 APK，不依赖 Google Play 服务，
    // 无网络也能用（这一点对国内设备很关键）。
    implementation(libs.mlkit.text.recognition.chinese)

    // 离线超分辨率推理
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)

    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.ui.tooling)
}
