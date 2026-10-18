# 轻松扫描（QingSongScan）

> 曾用名「极简扫描 / DocScanPro」。因应用商店存在同名扫描类应用，自 2026-10-16 起更名；
> 包名与签名未变，旧版可直接覆盖安装。

一个**完全离线**的安卓文档扫描增强 App。聚焦六项能力：文档矫正、去阴影、高清放大、多页扫描、中文 OCR、可搜索 PDF。

| 能力 | 实现方式 | 代码位置 |
|---|---|---|
| 文档矫正 | Canny 边缘检测 + 多边形逼近 + 透视变换 | `core/DocumentGeometry.kt` |
| 去阴影 | 形态学背景估计 + 光照归一化分除 | `core/ScanEnhancer.kt` |
| 高清放大 | TFLite 超分模型（有则启用）/ Lanczos + USM（后备） | `core/SuperResolution.kt` |
| 中文 OCR | ML Kit 内置模型（随 APK 打包，不依赖 Google Play 服务） | `core/OcrEngine.kt` |
| 可搜索 PDF | 手写 PDF 结构 + 嵌入字体 + 不可见文字层 | `core/SearchablePdfWriter.kt` |
| 多页会话 | 内存草稿 + 排序/删除后一次性落盘 | `ui/screen/ScanSession.kt` |
| 全流程编排 | 可组合管线 + 分级降级 | `core/ScanPipeline.kt` |

**不声明 `INTERNET` 权限**，从系统层面保证数据不出本机。

---

## 一、最快出 APK 的方式

> **旧的 `build-apk.bat` / `build-apk.ps1` 已废弃**，移到了 `_deprecated/`。
> 那两个脚本是「本机什么都没装」时代的产物，现在环境齐了，直接用官方 `gradlew` 更快更可靠。
> 完整打包说明见 **[`BUILD-APK.md`](BUILD-APK.md)**。

### 方式 A：命令行（推荐，最快）

```powershell
cd DocScanPro
$env:JAVA_HOME = 'D:\Android\JDK17'     # 每个新终端都要先设，原因见下
.\gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（实测 83.8 MB，可直接装机）。

**为什么必须设 `JAVA_HOME`**：`gradlew.bat` 是批处理脚本，它在启动 JVM 之前就要找到 java，
找不到就直接退出 —— 此时还没轮到 `gradle.properties` 里的 `org.gradle.java.home` 生效。
在 Android Studio 里构建则不需要这一步，IDE 用自带 JBR 启动 Gradle。

想一劳永逸（设一次，重开终端后生效）：

```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME', 'D:\Android\JDK17', 'User')
```

### 方式 B：Android Studio（推荐长期用这个）

1. `File → Open`，选择 `DocScanPro` 目录
2. 等 Gradle Sync 完成
3. 手机开 USB 调试连电脑，点绿色三角直接装到手机
4. 要独立安装包：`Build → Generate Signed App Bundle / APK`

本机已完成安装，路径与步骤见
[`INSTALL-ANDROID-STUDIO.md`](INSTALL-ANDROID-STUDIO.md)，配套两个脚本：

```powershell
# 1. 静默安装 IDE
powershell -ExecutionPolicy Bypass -File .\install-studio.ps1

# 2. 装 SDK 组件
powershell -ExecutionPolicy Bypass -File .\setup-sdk.ps1
```

装完 `local.properties` 会被自动生成并指向 `D:\Android\Sdk`。

---

## 二、环境要求

| 项目 | 要求 |
|---|---|
| 操作系统 | Windows 10/11 |
| JDK | **17**（必须是 17~22，原因见第十节；本机在 `D:\Android\JDK17`） |
| Android SDK | API 35（本机在 `D:\Android\Sdk`） |
| Gradle | 8.9（wrapper 已带 `gradle-wrapper.jar`，无需另装） |
| 最低运行版本 | Android 8.0（API 26） |
| 磁盘空间 | 建议预留 10GB（依赖缓存 + 构建产物） |

> ML Kit 中文模型的 AAR 声明 `minSdkVersion=21`，本工程的 `minSdk=26` 完全覆盖。

### macOS / Linux

本工程没有任何平台专属的构建逻辑，把 `JAVA_HOME` 指向 JDK 17 后直接跑：

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
```

`local.properties` 里的 `sdk.dir` 需要改成对应平台的路径
（Windows 用双反斜杠转义，macOS/Linux 用正斜杠，例如 `sdk.dir=/Users/me/Library/Android/sdk`）。

归档在 `_deprecated/` 下的两个 PowerShell 脚本是 Windows 专用，其它平台不必理会。

---

## 三、工程结构

```
DocScanPro/
├── build-apk.bat / build-apk.ps1    # ★ 一键构建脚本
├── gradlew / gradlew.bat            # Gradle 启动器（自动补齐 wrapper jar）
├── app/src/main/java/com/openscan/docscan/
│   ├── DocScanApplication.kt        # Application，OpenCV 预热
│   ├── MainActivity.kt              # 入口 + 相机权限
│   ├── core/                        # ★ 图像处理核心（全部离线）
│   │   ├── OpenCvLoader.kt          # OpenCV 初始化封装
│   │   ├── DocumentGeometry.kt      # 边缘检测 + 透视矫正
│   │   ├── ScanEnhancer.kt          # 去阴影 / 二值化 / 灰度
│   │   ├── SuperResolution.kt       # 超分放大（双通路）
│   │   ├── OcrEngine.kt             # 离线中文 OCR
│   │   ├── SearchablePdfWriter.kt   # 可搜索 PDF 生成
│   │   └── ScanPipeline.kt          # 处理管线编排
│   ├── data/
│   │   ├── ScanDatabase.kt          # Room：文档 + 页面
│   │   └── DocumentStore.kt         # 文件存储 + PDF/文本导出 + OCR 入口
│   └── ui/
│       ├── AppNavHost.kt            # 路由（含多页状态机）
│       ├── theme/                   # Material 3 主题
│       └── screen/
│           ├── HomeScreen.kt        # 首页
│           ├── CameraScreen.kt      # CameraX 拍照（支持连拍）
│           ├── ReviewScreen.kt      # 四角拖拽裁剪矫正
│           ├── EnhanceScreen.kt     # 效果选择 + 预览 + 加入文档
│           ├── ScanSession.kt       # ★ 多页扫描会话状态
│           ├── PageManagerScreen.kt # ★ 页面排序/删除 + 保存
│           ├── DocumentListScreen.kt# 文档列表 + OCR 结果 + 导出
│           └── ImportActivity.kt    # 系统分享入口
└── app/src/main/assets/             # 放超分模型（可选）
```

---

## 四、依赖说明（都是确定可用的）

### OpenCV

用的是**官方 Maven 包** `org.opencv:opencv:4.10.0`，Apache-2.0 许可。
不需要手动导入 SDK 模块，Gradle 会自动处理。

**体积控制**：`app/build.gradle.kts` 里配了

```kotlin
ndk {
    abiFilters += listOf("arm64-v8a", "armeabi-v7a")
}
```

只打包 ARM 架构，覆盖 99% 以上的真机。
官方 AAR 各架构体积对比：

| 架构 | libopencv_java4.so | 是否保留 |
|---|---|---|
| arm64-v8a | 19 MB | ✅ 保留 |
| armeabi-v7a | 14 MB | ✅ 保留 |
| x86 | 38 MB | ❌ 剔除 |
| x86_64 | 52 MB | ❌ 剔除 |

保留 ARM 后约 35MB（APK 内为压缩后体积，实际更小）；
如果全架构打包会到 125MB。

**要在模拟器上跑**，把 `abiFilters` 那两行注释掉即可。

### 中文 OCR：为什么选 ML Kit

| 方案 | 模型来源 | 无 GMS 设备 | 集成成本 |
|---|---|---|---|
| **ML Kit bundled（当前选择）** | 打包进 APK | ✅ 可用 | 低 |
| ML Kit unbundled | 运行时从 Play 下载 | ❌ 不可用 | 低 |
| PaddleOCR + NCNN | 手动转模型 + JNI | ✅ 可用 | 高 |

关键点是 **bundled**：模型随 APK 分发，不依赖 Google Play 服务。
国内设备普遍没有 GMS，用 unbundled 版本会直接不可用。

已验证的依赖传递链：

```
com.google.mlkit:text-recognition-chinese:16.0.1
  └── com.google.mlkit:text-recognition-bundled-common:17.0.0   ← 模型在这里
  └── com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1
        └── com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
```

> `play-services-*` 这几个包看着像"需要 Play 服务"，实际上只是 ML Kit
> 的代码复用包（提供了 `InputImage`、`Text` 等公共类）。
> AAR 里已经声明 `minSdkVersion=21`，且不含任何需要 GMS 运行时的逻辑。

### 全部依赖坐标

已逐个核验存在性：

| 库 | 坐标 | 仓库 |
|---|---|---|
| OpenCV | `org.opencv:opencv:4.10.0` | Maven Central |
| ML Kit 中文 OCR | `com.google.mlkit:text-recognition-chinese:16.0.1` | Google |
| Compose BOM | `androidx.compose:compose-bom:2024.11.00` | Google |
| CameraX | `androidx.camera:camera-*:1.4.0` | Google |
| Room | `androidx.room:room-*:2.6.1` | Google |
| TFLite | `org.tensorflow:tensorflow-lite:2.16.1` | Maven Central |
| TFLite Support | `org.tensorflow:tensorflow-lite-support:0.4.4` | Maven Central |
| Coil | `io.coil-kt:coil-compose:2.7.0` | Maven Central |

---

## 五、多页扫描怎么用

导航图就是一条闭环：

```
HOME ──拍照──▶ CAMERA ──▶ REVIEW ──▶ ENHANCE ──▶ PAGES
                 ▲                                  │
                 └────────────「继续拍」─────────────┘
                                                    │
                                       「完成保存」──▶ DOC_DETAIL
```

1. 首页点「拍照扫描」→ 拍第一页
2. 裁剪页拖四角调边缘 → 下一步
3. 增强页选效果 → **「加入文档」**
4. 到页面管理页，此时可以：
   - 点「继续拍」回去拍下一页（顶部会显示"已扫 N 页"）
   - 用 ↑↓ 调整页序（这就是最终 PDF 的页序）
   - 用 🗑 删掉拍坏的页
5. 点「完成保存」→ 一次性写成一份多页文档

**草稿存在哪**：`ScanSession` 是内存单例。退出 App 会丢，这是刻意的取舍——
把几十张全分辨率位图塞进 `Bundle` 会直接触发 `TransactionTooLargeException`。

---

## 六、可搜索 PDF 的原理

### 为什么不用 Android 的 `PdfDocument`

```kotlin
// 这样写出来的 PDF 搜不到任何字：
canvas.drawText("测试", x, y, paint)
```

`PdfDocument` 把文字**渲染成矢量路径**再写进内容流，产出的 PDF 里
没有任何文字对象——`Ctrl+F` 搜不到、鼠标选不中、复制不了。

### 所以手写 PDF 结构

`SearchablePdfWriter` 直接产出 PDF 对象树：

```
1 0 obj  Catalog
2 0 obj  Pages
3 0 obj  Font（Type0 / Identity-H）
4 0 obj  FontDescriptor
5 0 obj  CIDFont（CIDFontType2）
6 0 obj  FontFile2（嵌入的中文 TTF）
...      每页：Page + Contents（图像 XObject + 文字层）
xref / trailer / startxref
```

每页叠两层：

| 层 | 内容 | 可见性 |
|---|---|---|
| 图像 XObject | 用户看到的扫描图（JPEG/DCTDecode） | 可见 |
| 文本对象 | OCR 结果的文字 | **不可见**（渲染模式 `3 Tr`） |

文字用 `Identity-H` 编码，每个字符写成 4 位十六进制 UTF-16BE 码位，
再配一张 **ToUnicode CMap** 告诉阅读器"字形索引 → Unicode"的映射。
没有这张表，选中复制出来就是乱码。

坐标要翻转：PDF 原点在**左下角**，OCR 给的是左上角坐标，
所以 `y = pageHeight - box.bottom`。

`"3 Tr"` 是 PDF 的"渲染模式 3"，官方定义就是 *Neither fill nor stroke text*
——字在文件里、能被索引和选中，但屏幕上不画。

### 字体处理

从系统里找含中文字形的 TTF：

```
/system/fonts/NotoSansCJK-Regular.ttc          ← 首选，多数设备有
/system/fonts/NotoSansSC-Regular.otf
/system/fonts/DroidSansFallbackFull.ttf
/system/fonts/DroidSansFallback.ttf
```

`.ttc` 是字体集合（TrueType Collection），PDF 不接受这个容器格式，
所以要解析 `ttcf` 头、读出第一个字体的偏移、把它的表重新拼成独立 TTF。

> 当前是**整份字体嵌入**，没有做子集化（subset），所以 PDF 会大几 MB。
> 子集化能把字体压到几十 KB，但需要重建 TTF 表结构，复杂度高。
> 这是一个明确的优化点。

---

## 七、启用高清放大（超分模型）

工程**默认不内置模型文件**（体积和授权原因）。两条路径自动切换：

### 路径 1：经典重采样（默认，开箱可用）

Lanczos4 迭代放大 + 反锐化掩膜。文字清晰度比普通双线性插值好，
但不会有"生成"出的细节。**不需要任何额外文件。**

### 路径 2：神经超分（放模型即启用）

把兼容的 TFLite 超分模型放到 `app/src/main/assets/`，
文件名可以是以下任意一个（按优先级查找）：

- `sr_2x.tflite`
- `super_resolution.tflite`
- `edsr_2x.tflite`
- `lapsrn_2x.tflite`

**模型规格要求**（见 `SuperResolution.kt`）：

- 单输入单输出
- 输入：`[1, H, W, 3]` float32，NHWC，值域 `0..1`
- 输出：`[1, H*2, W*2, 3]` float32，NHWC，值域 `0..1`
- 放大倍数固定 2x（改 `SCALE_FACTOR` 常量可调整）

### 模型从哪来

1. **Real-ESRGAN** 轻量版，导出为 TFLite
2. **EDSR** 小倍数版本，TensorFlow 有转换脚本
3. 用文档数据集（如 DocUNet）自训练一个轻量 SR 网络

> ⚠️ 注意模型授权。对外分发前务必确认模型权重的许可证允许商用。
> 很多学术超分模型是非商用许可。

---

## 八、算法说明（想调参数看这里）

### 去阴影的原理

这是整个 App 里最值得说的一块。普通做法是固定阈值二值化，
一到暗光或手机投影场景就废掉。这里用的是**背景估计 + 光照归一化**：

```
1. 大核形态学闭运算 → 估计出纸张的"背景亮度场"
   （核大小 = 图像长边 / 25，自适应，能抹掉文字只留光照）
2. 原图 ÷ 背景场 × 255 → 乘性光照干扰被除掉
3. 按模式做后续处理：
   - 去阴影：轻微提亮 + USM
   - 黑白：CLAHE 增强 + Otsu 阈值 + 中值滤波去噪
   - 灰度：CLAHE 局部对比度
```

调参入口在 `ScanEnhancer.estimateBackground()` 里的 `longest / 25`，
以及 `k.coerceIn(21, 151)` 的上下界。

### 边缘检测的容错

`detectDocumentQuad()` 在以下情况会返回 `null`（不报错）：

- 没找到面积占比 > 15% 的四边形
- 拟合出的四边形不够"方"（对边长度差异过大）

调用方（`ReviewScreen`）收到 `null` 会回退成内缩 2% 的全幅矩形，
用户可以手动拖。这个降级设计比"检测失败就卡住"体验好得多。

### 放大策略

`upscaleClassic()` 用**迭代 2x** 而不是一次 4x。
两轮 Lanczos 的中间结果比单轮 4 倍插值更锐利，代价是耗时略长。
4x 在 3000×4000 输出上大约耗时 1-3 秒（中端机）。

### OCR 准确率怎么提高

OCR 建立在**增强后的图**上，所以顺序很重要：

1. 图像先矫正（把透视拉正）
2. 再增强（去阴影，把对比度拉起来）
3. 最后才 OCR

实测「黑白文档」模式对 OCR 最友好——文字变纯黑、背景变纯白后，
识别率明显高于原图。如果某页识别不出来，先切到黑白模式重试。

---

## 九、验证清单

APK 装上后按这个顺序验证：

**基础链路**

- [ ] 拍一张纸质文档，裁剪页能自动框住纸张边缘
- [ ] 拖动四个圆点，边框跟随手指移动
- [ ] 切到「去阴影」，原来的阴影块明显消失
- [ ] 切到「黑白文档」，文字变纯黑、背景变纯白
- [ ] 切到「高清增强」，预览明显变清晰
- [ ] 长按预览画面，能看到原图对比

**多页扫描**

- [ ] 点「加入文档」后进入页面管理页，显示"已扫描 1 页"
- [ ] 点「继续拍」，拍照页顶部出现"已扫 1 页"角标
- [ ] 再拍一页，页面管理页显示 2 页
- [ ] 用 ↑↓ 调换页序，缩略图跟着换位
- [ ] 删掉一页，剩下那页页码自动变成"第 1 页"
- [ ] 「完成保存」后进详情页，能看到两页

**OCR 与 PDF**

- [ ] 详情页点「识别文字」，弹窗显示识别结果
- [ ] 多页时能切换页签看不同页的文字
- [ ] 点「复制全文」，能粘贴到别的地方
- [ ] 保持「导出可搜索 PDF」开关打开，点 PDF 导出
- [ ] **把导出的 PDF 传到电脑，用阅读器 `Ctrl+F` 搜一个你确认出现过的词**
      ← 这是验证文字层的关键一步
- [ ] 关掉开关再导一次，对比文件大小（可搜索版会大一些）

**离线验证**

- [ ] **开启飞行模式**，以上全部仍能正常工作

最后一条是关键。全部功能在飞行模式下可用，才说明这套实现真的是离线的。

---

## 十、构建验证

### ✅ 已实际编译通过

本工程**已在真机构建环境上跑通完整 Gradle 构建**：

```
BUILD SUCCESSFUL
app/build/outputs/apk/debug/app-debug.apk   80.0 MB
545 个文件 / 8 个 dex
签名: C=US, O=Android, CN=Android Debug（apksigner verify 通过）
native-code: arm64-v8a + armeabi-v7a
```

验证命令：

```powershell
cd DocScanPro
$env:JAVA_HOME = "D:\Android\JDK17"
.\gradlew assembleDebug
```

### 首次编译暴露的 27 个错误（重要教训）

之前几轮只做了静态分析，并据自研校验器得出"0 诊断"的结论。
**装上 SDK 真跑一次之后，编译器报出 27 个错误 —— 静态检查器一个都没抓到。**
其中还包括我自己"修复"时引入的新错误。这说明：

> **自研静态校验器不能作为"可编译"的依据。有 SDK 就必须真跑一次 `assembleDebug`。**

修复清单（完整）：

| 错误 | 文件 | 根因与修法 |
|---|---|---|
| `Unresolved reference: localeFilters` | `app/build.gradle.kts` | 属性名和位置都错。AGP 8.7.3 里应写在 `defaultConfig {}` 用 `resourceConfigurations`。**用 javap 反编译 `gradle-api-8.7.3.jar` 核实**：`BaseFlavor.getResourceConfigurations()` 存在，`AndroidResources` 接口里没有 `localeFilters` |
| `Cannot access 'RowColumnParentData?.weight': it is internal` ×6 | 6 个 UI 文件 | `weight` 是 `RowScope`/`ColumnScope` 的**接口成员**，靠作用域接收者解析，**根本不需要 import**；加了 `import ...layout.weight` 反而指向同名的 internal 属性。修法：删掉这些 import |
| `Unresolved reference 'COLOR_RGBA2RGBA'` | `SuperResolution.kt` | OpenCV **没有**这个常量（javap 核实 4.10.0 只有 RGBA2BGR/BGRA/GRAY/RGB）。且 RGBA→RGBA 是无操作，直接删除该次 `cvtColor` |
| `Argument type mismatch: Int but Double expected` ×2 | `DocumentGeometry.kt` | `Size` 构造签名是 `Size(double, double)`，而 `outW/outH` 是 Int。Kotlin 不做隐式拓宽，需 `.toDouble()` |
| `Unresolved reference 'BoltOff'` ×2 | `CameraScreen.kt` | 该图标不存在。改用语义更贴切的 **`FlashOn` / `FlashOff`** |
| `Unresolved reference 'systemBars'` | `ReviewScreen.kt` | 写成全限定名 `WindowInsets.systemBars`，但它是 `WindowInsets.Companion` 的**扩展属性**，全限定名写不出来。需 import `...layout.systemBars`，并同时 import `WindowInsets` 类型 |
| `This material API is experimental` ×16 | 5 个 UI 文件 | TopAppBar/Scaffold 需 opt-in。改为在 `kotlinOptions` 里统一加 `-opt-in=androidx.compose.material3.ExperimentalMaterial3Api`，而不是逐个 composable 加 `@OptIn` |

### 另一个真实缺陷：依赖偷偷加了联网权限

构建产物的清单里出现了 `android.permission.INTERNET` 和 `ACCESS_NETWORK_STATE`，
**而本应用的核心承诺就是"完全离线"**。根因是 ML Kit 的 AAR 在清单合并时自动注入。

已在工程 manifest 中显式剔除：

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

修后 `aapt2 dump badging` 确认仅剩：

```
CAMERA
WRITE_EXTERNAL_STORAGE (maxSdkVersion=28)
READ_EXTERNAL_STORAGE  (maxSdkVersion=28)
```

> **教训：清单合并会掺进依赖声明的权限。必须检查最终 APK 的 badging，
> 不能只看自己写的 manifest。**

### 自研静态校验器的结果（仅供参考）

这些检查在静态层面仍然有价值，但**不能替代真实编译**：

| 检查项 | 方法 | 结果 |
|---|---|---|
| Kotlin 括号/结构配对 | 剥离字符串与注释后统计 `{}()[]` | 22/22 个文件配对 |
| 项目内 import 可解析 | 提取全部顶层声明反查 import | 0 悬空 |
| 字符串拼接优先级陷阱 | 语句块内确认存在 `+` 后再判括号保护 | 0 真实命中 |
| 旧单例/死代码引用 | 搜索 `CapturedHolder`/`PendingCapture`/`saveDocument` | 已彻底移除 |
| Gradle 版本目录引用 | 解析 TOML 后比对 `libs.*` 属性访问 | 26 库 + 4 插件全部命中，0 冗余 |
| 依赖坐标存在性 | curl 探测 POM/AAR | 全部 HTTP 200 |

### API 签名都用 javap 核对过

不是靠记忆写的。凡是用到第三方 API，都把 AAR 下下来反编译核对，例如：

```
$ javap -cp classes.jar com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
public final class ...ChineseTextRecognizerOptions
    implements com.google.mlkit.vision.text.TextRecognizerOptionsInterface

$ javap -cp classes.jar androidx.compose.foundation.layout.RowScope
public interface ...RowScope {
  public abstract androidx.compose.ui.Modifier weight(Modifier, float, boolean);
  ...
}
```

后者正是判断"`weight` 不需要 import"的依据。

> 补充：`text-recognition-chinese:16.0.1` 的 AAR 里只有一个 `ModuleDescriptor`，
> 真正的 `ChineseTextRecognizerOptions` 在传递依赖
> `play-services-mlkit-text-recognition-chinese` 里。这是正常的包拆分，
> Gradle 会自动拉齐，不需要手动加依赖。

---

## 十一、已知限制与后续可做的事

### 当前版本限制

1. **PDF 字体未子集化**。见第六节末尾，会让 PDF 大几 MB。
2. **没有手写擦除**。需要笔画分类模型 + 图像修补（inpainting），是独立课题。
3. **超分模型需自备**。见第七节。
4. **无竖排/繁简混排优化**。ML Kit 中文模型对竖排文本的框选不够准。
5. **未做 UI 自动化测试**。核心算法建议补单元测试
   （`ScanEnhancer` 的纯函数部分容易测）。
6. **多页草稿只在内存**。App 被系统杀掉后草稿会丢。

### 发布到商店前需要处理

- 换掉 `applicationId`（当前是 `com.openscan.docscan`）
- 生成正式签名 keystore
- 隐私政策网页（即使完全离线，各商店都要求提供）
- **软件著作权**：国内商店（含应用宝）上架必交，申请需 15-30 个工作日
- 若内置第三方超分模型，确认许可证允许分发
- **检查 ML Kit 的许可条款**：ML Kit 用的是 Google 的 ML Kit Terms，
  与 Apache-2.0 不同，商业化分发前请确认符合你的使用场景

> 补充：纯离线单机工具可豁免 **ICP 备案**和**工信部 APP 备案**。
> 本 App 未声明 `INTERNET` 权限，正好符合豁免条件。
> 但**软著仍是硬性要求**，无法豁免。

---

## 十二、故障排查

### 构建相关

| 现象 | 原因与对策 |
|---|---|
| 卡在 "Downloading ..." 很久 | 首次要下 1.5GB。挂代理，或换网络 |
| `Could not resolve org.opencv:opencv` | 代理拦了 Maven Central。检查代理白名单 |
| `Could not resolve com.google.mlkit:*` | 代理拦了 Google Maven。检查代理白名单 |
| `Could not find gradle-wrapper.jar` | 运行 `build-apk.bat` 会自动生成 |
| `SDK location not found` | 确认 `local.properties` 里有 `sdk.dir` |
| 构建 OOM | 编辑 `gradle.properties`，调小 `org.gradle.jvmargs` |
| Release 包崩溃 / OCR 报找不到识别器 | ProGuard 把 ML Kit 裁了。检查 `proguard-rules.pro` 里的 `com.google.mlkit.**` 保留规则 |
| 脚本输出乱码 | 脚本已改为英文输出，若仍异常请反馈 |

### 运行相关

| 现象 | 原因与对策 |
|---|---|
| 装到手机提示"解析包错误" | 手机是 x86 架构（极少见）。注释掉 `abiFilters` 重新构建 |
| 拍照后一直转圈 | OpenCV 初始化失败。看 logcat 里 `OpenCvLoader` 的输出 |
| 边缘检测总是不准 | 属正常——手动拖四角即可，或调 `MIN_SCORE` 阈值 |
| 去阴影效果不明显 | 调 `estimateBackground` 里的核大小系数 |
| OCR 识别不出文字 | 先在增强页切「黑白文档」，再回来识别 |
| PDF 能看但搜不到字 | 确认导出时「可搜索 PDF」开关是打开的；再确认系统字体目录里有 `NotoSansCJK-Regular.ttc`，看 logcat 里 `SearchablePdfWriter` 的警告 |
| 导出的 PDF 特别大 | 字体未子集化 + 图像质量 88。调 `SearchablePdfWriter` 里的 JPEG quality |
| 安装提示签名冲突 | 先卸载手机上已装的同名应用 |

### 拿不准的时候

用 Android Studio 打开工程，`Build` 面板会给出精确到行的错误信息，
比看命令行输出直观得多。`Logcat` 面板能看到运行时日志。


## 许可证

- 本仓库代码以 [Apache-2.0](LICENSE) 许可发布。
- `assets/handwriting_seg.tflite`（手写分割模型）为本项目自研，
  训练数据 100% 程序生成，随代码同许可发布。
- `assets/doc_segmentation.tflite` **不在本仓库中**：它来自 GPL-3.0
  项目（训练数据 CC BY-NC-SA），只能自用、不得再分发。是否引入由
  使用者自行判断并承担合规责任，详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
  —— 没有该文件时工程也能完整构建，检测链自动降级为纯 OpenCV 方案。
- 签名密钥（`*.jks`、`keystore.properties`）不在仓库中，请自行生成，
  可参考 `gen-keystore.ps1`。
