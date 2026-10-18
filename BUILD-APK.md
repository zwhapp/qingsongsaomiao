# DocScanPro 打包 APK 实操指南

> 本文件替代旧的 `build-apk.bat` / `build-apk.ps1`。  
> 那两个脚本已被废弃，**不要再执行**。下面所有步骤都用官方 `gradlew` 完成。



---

## 0. 先记住一个坑：`gradlew` 需要 `JAVA_HOME`

这是本轮实测发现的，也是最容易卡住的地方。

工程里 `gradle.properties` 写了：

```properties
org.gradle.java.home=D:\\Android\\JDK17
```

但**这行救不了命令行构建**。原因：

`gradlew.bat` 是个 Windows 批处理脚本，它在启动 JVM **之前**就要找到 java 可执行文件。  
找不到就直接退出，连 Gradle 都还没跑起来，自然读不到 `gradle.properties`。

实测报错原文：

```
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
Please set the JAVA_HOME variable in your environment to match the
location of your Java installation.
```

**结论（务必区分）**：

| 构建方式                    | 需要手动设 JAVA_HOME 吗 | 说明                                                 |
| ----------------------- | ----------------- | -------------------------------------------------- |
| 命令行 `gradlew.bat`       | **需要**            | wrapper 自己要先找到 JVM                                 |
| Android Studio 里点 Build | **不需要**           | IDE 用自带 JBR 启动 Gradle，之后 `org.gradle.java.home` 接手 |

---

## 1. 方式 A：命令行打包（推荐，最快）

### 1.1 debug APK（可直接装机，无需签名配置）

每次开新终端都要先设 `JAVA_HOME`：

```powershell
cd C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro
$env:JAVA_HOME = 'D:\Android\JDK17'
.\gradlew.bat assembleDebug
```

产物：

```
app\build\outputs\apk\debug\app-debug.apk
```

实测体积 **83.8 MB**。  
签名用的是 Android 自动生成的 debug 证书，能直接 `adb install`。

### 1.2 release APK（体积小，但需要签名）

```powershell
cd C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro
$env:JAVA_HOME = 'D:\Android\JDK17'
.\gradlew.bat assembleRelease
```

产物：

```
app\build\outputs\apk\release\app-release-unsigned.apk
```

实测体积 **70.8 MB**（比 debug 小 13 MB，R8 混淆 + 资源压缩的收益）。

注意文件名里的 **unsigned**：这个包**装不上手机**，必须先签名。

### 1.3 想省掉每次设 JAVA_HOME

在系统里永久设一次即可（只需一次）：

```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME', 'D:\Android\JDK17', 'User')
```

设完**关掉终端重开**才生效。之后直接 `.\gradlew.bat assembleDebug` 即可。

---

## 2. 生成签名密钥库（只做一次）

### 2.1 为什么必须有自己的密钥库

Android 要求每个 APK 都有签名，系统靠签名判断「这个更新包是不是来自同一个作者」。  
debug 包用的是 Android 自动生成的调试证书，**不能用于发布** —— 它不受你控制，  
而且同一台机器重装 SDK 后可能变化。

密钥库（`.jks` 文件）就是你的「发布身份」。它有两个不可逆的后果：

- **丢了** → 永远无法给已安装该应用的用户推送更新，只能换个包名重新发一个「新应用」
- **泄露** → 别人可以用你的身份发布恶意更新

所以密钥库必须你自己生成、自己保管。构建流程不代生成是**故意**的。

### 2.2 生成

```powershell
cd C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro
.\gen-keystore.ps1
```

会提示你输入两遍口令。**至少 12 位**，找个密码管理器存好。

生成后得到：

| 文件                    | 说明                          |
| --------------------- | --------------------------- |
| `docscan-release.jks` | 密钥库本体（实测 2746 字节）**— 立刻备份** |
| `keystore.properties` | 口令凭据，只在你用了非交互模式时才生成         |

已验证该密钥库的有效期到 **2054-03-01**，RSA 2048 位，SHA256withRSA 签名算法。  
Google Play 要求密钥有效期覆盖到 2033 年之后，10000 天完全够用。

### 2.3 前置条件：PowerShell 执行策略

**如果你看到这个报错，先看本节：**

```
.\gen-keystore.ps1 : 无法加载文件 ...\gen-keystore.ps1，因为在此系统上禁止运行脚本。
CategoryInfo   : SecurityError: (:) []，PSSecurityException
FullyQualifiedErrorId : UnauthorizedAccess
```

这不是脚本的问题，是 Windows 客户端版 PowerShell 的**默认安全策略**：  
所有作用域都是 `Undefined` 时，实际生效 `Restricted` —— 禁止运行任何 `.ps1` 文件。

查当前状态：

```powershell
Get-ExecutionPolicy -List
```

**永久解法**（只需一次，不需要管理员权限，只影响当前用户）：

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned -Force
```

`RemoteSigned` 的含义：本地自己写的脚本可直接跑，从网上下载的脚本仍需数字签名。  
这是微软对开发机的推荐设置，比 `Unrestricted` 安全。**本机已设置好此项。**

**临时解法**（不想改任何系统设置时）：

```powershell
powershell -ExecutionPolicy Bypass -File .\gen-keystore.ps1 -Password 'YourStrongPass123'
```

注意 `-ExecutionPolicy Bypass` 只对本次调用生效，且必须是 `powershell -File` 形式，  
不能直接写在 `.\gen-keystore.ps1` 前面。

> 顺带说明：`gradlew.bat` 是批处理文件，**不受执行策略限制**，  
> 所以打包命令从来不会碰到这个问题。

### 2.4 脚本的三种用法

```powershell
.\gen-keystore.ps1                                    # 交互式，提示输入口令
.\gen-keystore.ps1 -Password 'YourStrongPass123'      # 非交互，并顺带写 keystore.properties
.\gen-keystore.ps1 -ReadOnly                          # 只看密钥库在哪，不生成
```

非交互模式适合写进自动化脚本，或者在无法手动输入口令的场合使用。

### 2.5 安全提醒

`keystore.properties` 里是**明文口令**，`docscan-release.jks` 是**你的身份**。  
这两个文件都已经写进 `.gitignore`（`*.jks` 和 `keystore.properties`），  
**不要**把它们拷到共享盘、不要提交、不要发给任何人。

如果你要把工程开源，先确认 `git status` 里看不到这两个文件。

### 2.6 检查已有的密钥库

```powershell
& "D:\Android\JDK17\bin\keytool.exe" -list -v -keystore .\docscan-release.jks -alias docscan
```

会打印指纹、有效期、算法。**发布前记下 SHA-256 指纹**，以后核对签名是否一致就靠它。

> 脚本刻意写成纯 ASCII。Windows PowerShell 5.1 读无 BOM 的 UTF-8 文件会按 ANSI 解码，  
> 中文注释会变成乱码并引发假语法错误（前面已踩过）。改这个文件时请保持 ASCII。

---

## 3. 签出可安装的 release APK

### 方式 1：命令行注入签名参数

把 `YOUR_PASSWORD` 换成第 2 步设的口令，**整条命令一行写完**：

```powershell
.\gradlew.bat assembleRelease -Pandroid.injected.signing.store.file=D:\Android\keys\docscan-release.jks -Pandroid.injected.signing.store.password=YOUR_PASSWORD -Pandroid.injected.signing.key.alias=docscan -Pandroid.injected.signing.key.password=YOUR_PASSWORD
```

产物变成 `app-release.apk`（文件名不含 unsigned），可直接装机。

> **实际文件名另有约定**：`app/build.gradle.kts` 自定义了 `outputFileName`，真实产物形如
> `QingSongScan-v1.5.1-selfuse-12-release.apk`（`app/build/outputs/apk/release/` 下）。
> 带版本号是为了避免"手上这个包到底是哪一版"的反复怀疑。`app-release.apk` 只在
> 未启用该自定义时才会出现。
>
> **两个构建变体**：内置 GPL 分割模型的为自用版（versionName 含 `selfuse`，约 75.8 MB，不可外发）；
> 删掉 `app/src/main/assets/doc_segmentation.tflite` 后构建的是合规版（约 70.9 MB，可分发）。

**实测确认**：用上述参数构建后，`apksigner verify` 输出 `Verifies`，  
签名方案为 v2（APK Signature Scheme v2），签名者证书与你密钥库里的 DN 一致。  
这是完整链路的端到端验证。

### 方式 2：Android Studio 图形界面（更省心）

1. 打开工程（`File > Open` 选 `DocScanPro` 目录）
2. 菜单 `Build > Generate Signed App Bundle / APK`
3. 选 **APK**，选 release
4. 点 `Create new...` 新建或 `Choose existing...` 选已有的 `.jks`
5. 填 alias 和口令，`Finish`

---

## 4. 自动签名（已配置好）

签名配置**已经写进 `app/build.gradle.kts`**，你不需要改任何构建文件。  
你只需要生成一次密钥库，之后 `assembleRelease` 一条命令就出签名包。

### 4.1 怎么用

```powershell
# 第一步（只做一次）：用非交互模式生成密钥库，同时写出 keystore.properties
.\gen-keystore.ps1 -Password 'YourStrongPass123'

# 第二步：以后每次打包都只有这一条命令
$env:JAVA_HOME = 'D:\Android\JDK17'
.\gradlew.bat assembleRelease
```

产物直接是 `app-release.apk`（**不含 unsigned**），可以装机、可以上架。

### 4.2 它是怎么工作的

`gen-keystore.ps1 -Password '...'` 会生成两个文件：

| 文件                    | 作用                    |
| --------------------- | --------------------- |
| `docscan-release.jks` | 你的签名密钥                |
| `keystore.properties` | 让 Gradle 自动找到上面的密钥和口令 |

`app/build.gradle.kts` 在构建时会读 `keystore.properties`，  
若文件存在就自动启用 release 签名。

### 4.3 关键设计：没有凭据时不能把工程搞坏

这是这段配置最需要注意的地方。如果无条件引用 `keystore.properties`，  
那么**在你生成密钥库之前，整个工程连打开都会失败**（Gradle sync 阶段就报错）。

所以配置做了条件判断：**文件不存在时就不注册 signingConfig**，  
行为退回到「产出 unsigned 包」。实测两种场景都通过：

| 场景                       | 实测结果                                                                        |
| ------------------------ | --------------------------------------------------------------------------- |
| 没有 `keystore.properties` | ✅ `assembleRelease` 成功，产出 `app-release-unsigned.apk`                        |
| 有 `keystore.properties`  | ✅ `assembleRelease` 成功，产出 `app-release.apk`，`apksigner verify` 报 `Verifies` |
| `assembleDebug`          | ✅ 两种情况都不受影响                                                                 |

### 4.4 踩到的坑：`java.util.Properties` 不能写全限定名

在 Gradle Kotlin DSL 里，`java` 已经被 Android 插件解析成 `JavaPluginExtension` 扩展，  
不再当包名用。写成 `java.util.Properties()` 实测报：

```
Unresolved reference: util
Unresolved reference: load
```

正确做法是在文件顶部显式 import：

```kotlin
import java.util.Properties
```

## 5. 三种签名方式对比

现在你手上有三条路，按需选：

| 方式                 | 命令                                         | 适合              |
| ------------------ | ------------------------------------------ | --------------- |
| **自动签名**（推荐）       | `.\gradlew.bat assembleRelease`            | 日常打包，最省事        |
| 命令行注入参数            | 加四个 `-Pandroid.injected.signing.*` 参数      | 临时用别的密钥库，不改本机配置 |
| Android Studio GUI | `Build > Generate Signed App Bundle / APK` | 不想碰命令行          |

**代价**：口令以明文存在 `keystore.properties` 里。这是 Android 官方文档推荐的做法，  
前提是该文件确实被 `.gitignore` 排除（本工程已排除）且不放在共享目录。  
如果你更在意安全，删掉 `keystore.properties` 就会自动退回 unsigned 模式，  
改用第 3 节方式 1 每次手输参数。

---

## 6. 装到手机

手机开启「开发者选项 → USB 调试」，USB 连电脑，然后：

```powershell
& "D:\Android\Sdk\platform-tools\adb.exe" devices          # 确认设备已授权
& "D:\Android\Sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"
```

`-r` 表示覆盖安装。

release 包同理，把路径换成 `app\build\outputs\apk\release\app-release.apk`。

> 注意：debug 包的 applicationId 带 `.debug` 后缀（见 `app/build.gradle.kts` 的 `applicationIdSuffix`），  
> 所以 debug 和 release 可以**同时装在一台手机上**，互不覆盖。

---

## 7. 常用命令速查

```powershell
$env:JAVA_HOME = 'D:\Android\JDK17'      # 每个新终端都要先执行

.\gradlew.bat assembleDebug              # debug 包，可直接装
.\gradlew.bat assembleRelease            # release 包（未签名）
.\gradlew.bat clean                      # 清理构建产物
.\gradlew.bat clean assembleDebug        # 从零重建，用于验证可复现性
.\gradlew.bat lintRelease                # 单独跑 release lint
.\gradlew.bat --stop                     # 停掉 Gradle 守护进程
```

---

## 8. 构建环境（实测确认）

| 项                   | 实际值                                       |
| ------------------- | ----------------------------------------- |
| JDK（专供构建）           | `D:\Android\JDK17`（Temurin 17.0.20.1）     |
| Android SDK         | `D:\Android\Sdk`                          |
| Android Studio      | `C:\Program Files\Android\Android Studio` |
| Gradle              | 8.9（`gradle-wrapper.jar` 齐全，走腾讯镜像）        |
| AGP                 | 8.7.3                                     |
| compileSdk / minSdk | 35 / 26                                   |
| adb                 | 37.0.1                                    |

---

## 9. 四个已修掉的坑（供排查参考）

### 9.1 JDK 版本互斥

Gradle 8.9 + AGP 8.7.3 只支持 **JDK 17~22**。

Android Studio 2026.2 自带的 JBR 是 **JDK 25**，直接拿来构建会报：

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 69      (69 = Java 25)
```

所以工程用 `org.gradle.java.home` 把构建 JDK 钉死在 17，与 IDE 自带 JBR 解耦。

### 9.2 release lint 比 debug 严格

`assembleRelease` 会跑 `lintVital`，`assembleDebug` 不会。release 构建首次就因此失败：

```
res/xml/backup_rules.xml:5: Error: exports/ is not in an included path [FullBackupContent]
res/xml/backup_rules.xml:6: Error: Unexpected domain cache [FullBackupContent]
res/xml/data_extraction_rules.xml:6: Error: exports/ is not in an included path [FullBackupContent]
4 errors, 0 warnings
```

根因是两个域写错了：

- `exports/` 实际落在 `getExternalFilesDir()`（见 `data/DocumentStore.kt`），属于 **external** 域，  
  文件里写成了 `domain="file"`（那是内部私有目录）→ 改成 `domain="external"`，路径 `Pictures/exports/`
- `full-backup-content` **根本没有 cache 域**，`<exclude domain="cache">` 是非法写法。  
  cache 目录本来就不会被自动备份，直接删掉那行即可

按上述修正后 `assembleRelease` 通过。这也说明：**debug 能过不代表 release 能过**，  
发版前务必真跑一次 `assembleRelease`。

### 9.3 Gradle Kotlin DSL 里 `java` 不是包名

在 `app/build.gradle.kts` 里写 `java.util.Properties()` 会失败：

```
e: app/build.gradle.kts:25:26: Unresolved reference: util
e: app/build.gradle.kts:27:47: Unresolved reference: load
```

原因：Android 插件给 Project 挂了个名为 `java` 的扩展（`JavaPluginExtension`），  
Kotlin DSL 里 `java` 先被解析成这个扩展访问，而不是 `java` 包。  
于是 `java.util.Properties()` 被理解成「在扩展上找 `util` 属性」。

修法：文件顶部显式 `import java.util.Properties`，然后用 `Properties()` 不带包前缀。

同类风险：任何在 Gradle Kotlin DSL 里想用 `java.` 开头全限定名的地方都有可能踩到，  
遇到 `Unresolved reference: util` 先怀疑这个。

### 9.4 Compose 的 `toPx()` 是成员扩展函数，不能当普通函数用

这是本项目最费时间的一个坑，**连错三种写法都不行**，值得单独记下来。

想动态把一个 `Dp` 常量换成像素时：

```kotlin
// ❌ 写法 1：直接调
HANDLE_TOUCH_RADIUS_DP.toPx()
// e: Unresolved reference 'toPx'.

// ❌ 写法 2：补 import（连 import 本身都报错）
import androidx.compose.ui.unit.toPx
// e: ReviewScreen.kt:64:33 Unresolved reference 'toPx'.

// ❌ 写法 3：当成"首参收 Density"的普通方法
density.toPx(HANDLE_TOUCH_RADIUS_DP)
// e: Unresolved reference 'toPx'.

// ✅ 写法 4：放进 Density 接收者作用域
with(density) { HANDLE_TOUCH_RADIUS_DP.toPx() }   // 通过
```

**根因**：`toPx` 的真实形态是

```kotlin
interface Density {
    fun Dp.toPx(): Float      // ← 成员扩展函数，不是顶层函数
}
```


它有两个接收者：外部宿主 `Density`（决定能否被找到）+ 扩展接收者 `Dp`。
所以必须**先有一个 Density 实例作为作用域接收者**，才能对 `Dp` 调 `.toPx()`。

**字节码取证**（用 javap 查 `ui-unit-android-1.7.5.aar`）：

```
androidx/compose/ui/unit/Density.class
  public abstract float getDensity();
  public default float toPx-0680j_4(float);     // Dp → Float
  public default float toPx--R2X_6o(long);      // TextUnit → Float
```

并且 `Density.class` 的 `kotlin.Metadata` 注解 `d2` 字段里，
`toPx` 的声明处标着 `Landroidx/compose/ui/unit/Dp;` —— 这正是成员扩展的元数据形态。
全量搜索所有 `androidx.compose.ui.*` 的 aar，**`DpKt`（顶层函数类）里 `toPx` 出现次数为 0**，
确认它不是顶层函数。

**排查提示**：如果报错位置在 **import 行**（如 `:64:33 Unresolved reference 'toPx'`），
说明该符号在包层级不存在，可直接排除「import 写漏」的可能，往「它是成员扩展」方向查。

---

## 10. release 构建验收结果（实测）

| 检查项 | 结果 |
|---|---|
| `assembleRelease` | ✅ BUILD SUCCESSFUL in 1m 7s |
| R8 混淆 + 资源压缩 | ✅ 通过，无 keep 规则报错 |
| lintVitalRelease | ✅ 通过 |
| zipalign（4 字节对齐） | ✅ Verification successful |
| APK 体积 | 70.8 MB（debug 83.8 MB） |
| dex 数量 | 2（debug 为 8，R8 合并效果明显） |
| 文件总数 | 439 |
| arm64-v8a `libopencv_java4.so` | ✅ 20.9 MB |
| arm64-v8a `libmlkit_google_ocr_pipeline.so` | ✅ 11.1 MB |
| arm64-v8a `libtensorflowlite_jni.so` | ✅ 3.4 MB |
| armeabi-v7a 三件套 | ✅ 齐全 |
| 中文 OCR 模型（Hani_ctc 等） | ✅ 25 个模型文件已内嵌 |
| 签名状态 | ⚠️ 未签名，需按第 2、3 节处理 |

**关于「未签名」**：这是正常的。release 未签名包属于半成品，
签名需要你自己的密钥库 —— 密钥库的所有权只应该属于你，不该由构建过程代生成。
