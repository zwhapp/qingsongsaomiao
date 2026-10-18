# 本地 Android Studio 环境安装指南

本机（Windows 11）Android Studio 环境已装好，工程**已实际编译通过**。
本文记录实际安装结果、踩过的坑、以及复现方式。

---

## 一、实际安装结果

| 内容 | 实际路径 | 占用 |
|---|---|---|
| Android Studio | `C:\Program Files\Android\Android Studio` | 3.4 GB |
| 自带 JBR（JDK 25） | `C:\Program Files\Android\Android Studio\jbr` | 含在上面 |
| **构建专用 JDK 17** | `D:\Android\JDK17` | 300 MB |
| Android SDK | `D:\Android\Sdk` | 2.6 GB |

版本：Android Studio **2026.2**（build `262.9437.185.2621.16467767`）
SDK：`platform-tools 37.0.1` / `platforms;android-35` / `build-tools;35.0.0`
/ `ndk;28.2.13676358` / `cmake;3.31.6`

### 为什么 Android Studio 在 C 盘而不是 D 盘

原计划装 D 盘，但 NSIS 安装器的 `/D=` 参数**没有生效**，实际装到了 C 盘。
（安装器返回退出码 1223 即 `ERROR_CANCELLED`，但文件其实写成功了 ——
**不能凭退出码判断安装成败**。）

你后来确认保留现状，只把 SDK 放 D 盘。

---

## 二、⚠️ 关键坑：JDK 版本冲突

**这是本次安装最容易卡住的地方，务必先理解。**

- Android Studio 2026.2 自带的 JBR 是 **JDK 25**
- 本工程用 Gradle 8.9 + AGP 8.7.3，**只支持 JDK 17~22**

直接用 IDE 自带 JDK 构建会报：

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 69        # 69 = Java 25
```

### 为什么容易误判

`gradle --version` 在这个组合下**能正常打印版本号**，
因为它不编译构建脚本。只有真正执行任务才崩。**别用 `--version` 判断兼容性。**

Gradle 官方兼容矩阵关键行：

| Java | 运行 Gradle 需 |
|---|---|
| 22 | 8.8+ |
| 23 | 8.10+ |
| 24 | 8.14+ |
| **25** | **9.1.0+** |

### 已采用的解法

在 `gradle.properties` 里显式指定构建 JDK：

```properties
org.gradle.java.home=D:\\Android\\JDK17
```

实测：即使外层 `JAVA_HOME` 指向 JBR 25，Gradle 也会按这行强制用 JDK 17，
日志显示 `Daemon JVM: 'D:\Android\JDK17' (from org.gradle.java.home)`。

> 另一种思路是把工程升到 AGP 8.13 + Gradle 8.13 以适配 JDK 25，
> 但那要改工程配置，风险更大，所以选了"额外装 JDK 17"这条路，**工程代码零改动**。

---

## 三、如何构建

### 方式 A：命令行（已验证可用）

```powershell
cd C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro
$env:JAVA_HOME = "D:\Android\JDK17"
.\gradlew assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（约 80 MB）

装到手机：

```powershell
D:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

### 方式 B：Android Studio

`File → Open` 选 `DocScanPro` 目录，等 Gradle Sync 完成，点绿色三角。

> IDE 里 Gradle 会自动读 `gradle.properties` 的 `org.gradle.java.home`，
> 所以不需要手动改 IDE 的 JDK 设置。若 IDE 仍报 JDK 错误，
> 去 `Settings → Build → Build Tools → Gradle → Gradle JDK` 选 `D:\Android\JDK17`。

---

## 四、网络：Gradle 官方源不可用

`services.gradle.org` 在本机实测不稳定：
- `java.net.SocketTimeoutException: Connect timed out`
- `schannel: server closed abruptly`

已把 wrapper 指向腾讯云镜像，**实测 21.7 MB/s**：

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
networkTimeout=120000
```

其余依赖走 Google Maven / Maven Central，实测可达。

---

## 五、验证环境是否正常

```powershell
# 构建 JDK
& "D:\Android\JDK17\bin\java.exe" -version

# adb 能看到手机吗（手机需插上并授权）
& "D:\Android\Sdk\platform-tools\adb.exe" devices

# SDK 装了哪些组件
& "D:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed

# 检查最终 APK 的权限（确认没有 INTERNET）
& "D:\Android\Sdk\build-tools\35.0.0\aapt2.exe" dump badging app\build\outputs\apk\debug\app-debug.apk | Select-String "uses-permission"
```

`adb devices` 显示 `unauthorized` 时，去手机屏幕上点"允许 USB 调试"。

---

## 六、如果哪天要重装

两个脚本仍在工程根目录，但**需按实际情况调整参数**：

```powershell
# 1. 装 IDE（注意 /D 参数在本机不生效，实际会装到 C 盘）
powershell -ExecutionPolicy Bypass -File .\install-studio.ps1

# 2. 装 SDK 组件
powershell -ExecutionPolicy Bypass -File .\setup-sdk.ps1
```

`setup-sdk.ps1` 默认参数已更新为实际的 C 盘 IDE 路径。
JDK 17 需单独准备（脚本没包含这一步），来源：

```
https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/
  OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip
```

解压后放到 `D:\Android\JDK17`。

---

## 七、其他注意事项

**别用 `build-apk.bat` 了。** 那个脚本是上一轮为"完全没有 Android Studio"时写的，
它会自己再下一套独立环境到 `%USERPROFILE%\.docscan-buildenv`。
现在已有正规环境，直接用 `gradlew` 更省事，也避免两套环境互相干扰。

**Gradle 缓存仍在 C 盘**（`C:\Users\zwhco\.gradle`，约 2-3 GB）。
要挪需设 `GRADLE_USER_HOME` 环境变量。C 盘空间够，没动。

**APK 80 MB 偏大**，主因是三块原生库 × 两个架构：
`libopencv_java4.so`(20.9MB) + `libmlkit_google_ocr_pipeline.so`(11MB)
+ `libtensorflowlite_jni.so`(3.4MB)。若只保留 `arm64-v8a` 可降到约 55 MB
（改 `app/build.gradle.kts` 的 `abiFilters`）。

