# DocScanPro 分发合规审查报告

审查对象：`DocScanPro-v1.3.0-7-release.apk`（versionCode 7 / versionName 1.3.0，75.8 MB）
审查日期：2026-10-15
审查范围：随包分发的全部代码、模型、原生库、字体、图标、资源

> 本文是工程侧合规盘点，不是法律意见。涉及对外商用分发时，建议由法务复核关键结论。

---

## 整改状态（2026-10-16 更新）

| 原缺口 | 现状 | 处置 |
|---|---|---|
| 🔴 内置 GPL-3.0 / CC BY-NC-SA 分割模型 | 🟢 **已解除** | v1.3.1 起从 `assets/` 移除该模型（归档至 `_model_dl/`，不随包分发）；改用纯 OpenCV 的 `TextSeedDetector` + 传统边缘检测，5/5 命中、真实 IoU 0.785 |
| 🔴 无 THIRD-PARTY-NOTICES | 🟢 **已补齐** | 新增 `THIRD-PARTY-NOTICES.md`（项目根 + `app/src/main/assets/`），含全部依赖署名与 Apache-2.0 许可证全文 |
| 🔴 App 内无限可声明页 | 🟢 **已补齐** | v1.4.0 新增 `LicensesScreen`（首页 → 关于与开源许可），完整呈现上述声明文件 |
| 🟡 商标风险（应用名 / 包名） | 🟡 **部分处理** | **应用名已改**：极简扫描 → **轻松扫描**（v1.4.1 / v1.5.1-selfuse 起）。包名 `com.openscan.docscan` 与签名证书 `CN=OpenScan` 为兼容旧装与签名一致性暂未变更，上架前需另行决策（见第四节） |
| 🟡 无 LICENSE 文件 | ⚪ **未处理** | 产品仍为闭源，保留所有权利；已在 NOTICES 第七节声明 |
| 🟡 无隐私政策 | ⚪ **未处理** | 应用商店上架前必须补 |

**结论**：v1.4.1 已不存在许可层面的硬阻断，可对外分发。剩余为商标（包名/证书）与商店合规事项。

### 附：更名记录（2026-10-16）

为避开应用商店中已有的「极简扫描」同名扫描类应用，应用显示名由「极简扫描」改为
「**轻松扫描**」（英文 QingSongScan）。改动范围与保持不变项——

| 类别 | 项 | 处置 |
|---|---|---|
| 用户可见 | 桌面名称、首页标题（`strings.xml`） | ✅ 已改 |
| 用户可见 | 自检报告标题（`SelfCheckScreen`） | ✅ 已改 |
| 用户可见 | 导出目录 `Pictures/轻松扫描`、`Download/轻松扫描` | ✅ 已改 |
| 用户可见 | 应用内许可页所呈现的 NOTICES 应用名 | ✅ 已改 |
| 工程内部 | 主题资源名 `Theme.DocScanPro` → `Theme.QingSongScan` | ✅ 已改 |
| 工程内部 | Gradle 工程名、APK 输出文件名前缀 | ✅ 已改 |
| **保持** | `applicationId` / `namespace` = `com.openscan.docscan` | ⛔ 未改，改了即为新应用、无法覆盖安装、旧数据丢失 |
| **保持** | 签名证书 `CN=OpenScan, O=OpenScan` | ⛔ 未改，需重新签发 keystore，会导致必须卸载重装 |
| **保持** | 工程目录名 `DocScanPro/` | ⛔ 未改，仅影响本地路径 |

**旧版本可直接覆盖安装、扫描记录保留**（包名与签名均未变动）。

> 注意：导出目录名变了，此前已导出的文件仍留在 `Pictures/极简扫描/`，不会被自动迁移。

### 附：自用版 v1.5.1-selfuse（2026-10-16）

另有**自用构建** `QingSongScan-v1.5.1-selfuse-12-release.apk`（75.8 MB），
把 GPL-3.0 的 FairScan 分割模型放回了 `assets/doc_segmentation.tflite`
（4.9 MB，未压缩存储以便 mmap），检测链第一级 ML 分割重新生效 ——
即"效果最好"的完整形态。

| 项 | 合规版 v1.4.1 | 自用版 v1.5.1-selfuse |
|---|---|---|
| 内置分割模型 | ❌ 不打包 | ✅ GPL-3.0 模型（4.9 MB） |
| 检测链第一级 | 文本种子 | **ML 分割**（5/5 命中、边界最贴合） |
| APK 体积 | 70.9 MB | 75.8 MB |
| 可对外分发 | ✅ | ❌ **不可**（详见 THIRD-PARTY-NOTICES.md 5.2） |

**法律依据**：GPL-3.0 义务由"传播（conveying）"触发。本地安装、运行、修改
属于 GPL-3.0 明确许可的行为，自用不触发任何对外义务。同时已在包内保留上游
版权声明与许可信息（`assets/THIRD-PARTY-NOTICES.md`）。

**回退方式**：删除 `app/src/main/assets/doc_segmentation.tflite` 后重新构建即回到
合规形态，代码无需改动。

---

## 〇、结论速览

| 项目 | 状态 | 说明 |
|---|---|---|
| **内置 ML 分割模型** | 🔴 **阻断** | GPL-3.0 授权 + 训练数据 CC BY-NC-SA 4.0（禁止商用） |
| 应用自身代码（7302 行 Kotlin / 26 文件） | 🟢 可分发 | 全部本项目自研，无第三方代码拷贝 |
| 第三方依赖（AndroidX / OpenCV / TFLite / Coil 等） | 🟢 可分发 | 均为 Apache-2.0 等宽松许可 |
| ML Kit 中文 OCR | 🟡 可分发 | 闭源 Google 组件，受 ML Kit Terms 约束，允许随 App 分发 |
| PDF 字体嵌入 | 🟢 低风险 | 仅用 AOSP 自带 Noto Sans CJK / Droid Sans Fallback |
| 算法实现 | 🟢 无障碍 | 全部是公开发表的学术算法，独立实现 |
| **许可声明文件** | 🔴 **缺失** | 无 LICENSE、无 THIRD-PARTY-NOTICES、无限可声明页、META-INF 许可被剥离 |

**一句话**：代码本身干净、适合分发；**唯一的硬阻断是那个 4.9 MB 的分割模型**。解决它 + 补齐许可声明，即可商用分发。

---

## 一、🔴 阻断级问题：内置 ML 分割模型

### 事实

| 项 | 内容 |
|---|---|
| 文件 | `app/src/main/assets/doc_segmentation.tflite`（4.9 MB，已打包进 APK） |
| 架构 | DeepLabV3Plus + MobileNetV2 编码器，PyTorch → 量化 TFLite |
| **来源** | https://github.com/pynicolas/fairscan-segmentation-model |
| **仓库许可** | **GNU GPL-3.0**（README「License」节原文：*This repository is released under the GNU GPLv3 license.*） |
| 上游应用 | FairScan（https://github.com/pynicolas/FairScan） |
| **训练数据** | https://github.com/pynicolas/fairscan-dataset |
| **数据许可** | **CC BY-NC-SA 4.0**（README「License」节原文：*Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International*） |
| 使用位置 | `core/DocSegmenter.kt`（源码第 25–29 行已注明来源与 GPLv3） |

用 `strings` 解析 tflite 确认模型内部含 `segmentation_models_pytorch.decoders.deeplabv3` / `encoders.mobilenet` 图层名与 `__main__.NHWCWrapper` 导出包装，与上游仓库描述完全一致——**确认是 FairScan 模型，不是同名巧合**。

### 为什么是阻断级

**① GPL-3.0 是强 copyleft。**
把 GPLv3 作品打包进 APK 对外分发，若被认定为「结合作品（combined work）」，则整个 APK 都要按 GPLv3 授权，即必须公开全部源码。对本项目而言这意味着放弃闭源。

**② 即便退一步按「聚合分发（mere aggregation）」解释，义务依然存在。**
如果认为「模型是独立数据文件、运行时加载」不构成衍生，则 GPLv3 的义务从「传染全部源码」降为：
- 随分发提供 **GPLv3 许可证全文**
- 提供该模型的**完整对应源码**（对模型权重而言，即上游训练脚本 + 训练数据可获取方式）
- **保留**原作者版权声明
- 不得附加任何额外限制

当前 APK 内**没有** GPLv3 全文，也**没有**保留 FairScan 版权声明（`DocSegmenter.kt` 里的注释只是代码注释，用户拿不到）。属于不合规。

**③ 训练数据的 NonCommercial 是更硬的雷。**
上游数据集是 **CC BY-NC-SA 4.0**：禁止商业使用 + 相同方式共享。模型权重是从这批数据训练出来的。
「模型权重是否构成训练数据的衍生作品」目前在各国法域**尚无定论**，但：
- 权利人完全可以主张权重承载了数据的表达
- 一旦被主张，商业分发将失去抗辩基础
- ShareAlike 条款还可能要求你的衍生成果也按 CC BY-NC-SA 开放

**工程上取保守解释是唯一稳妥做法**：只要训练数据带 NC，就不能商用分发该权重。

### 处置方案（三选一）

| 方案 | 做法 | 代价 | 适用 |
|---|---|---|---|
| **A. 换模型（推荐）** | 用 Apache-2.0 / MIT 许可的文档分割模型；或自采数据（或 CC0 / CC-BY 数据）自训练 | 需要重新验证准确率 | 想保持闭源商用 |
| **B. 走 GPLv3 合规** | 整个 App 以 GPLv3 开源，附许可全文与 FairScan 版权声明 | 必须开源全部源码 | 产品本来就打算开源 |
| **C. 移除模型** | 删掉 `doc_segmentation.tflite`，退回传统边缘检测 | 复杂场景（摊开书、花纹桌布）检测能力下降 | 最快解除风险 |

**方案 C 的落地成本很低**：代码已有优雅降级路径——`DocSegmenter.obtainInterpreter()` 返回 null 时，`ScanPipeline` 会自动回退到 `DocumentGeometry.detectDocumentQuad`（传统边缘检测）。删掉 assets 里的模型文件 + 同步删除 `SelfCheck` 的第 6 项检查即可，不会崩溃。

---

## 二、第三方依赖许可清单（已核实为 APK 内实际存在）

### 宽松许可，可直接分发

| 组件 | 版本 | 许可证 |
|---|---|---|
| AndroidX（core-ktx / lifecycle / activity / navigation） | 1.15.0 / 2.8.7 / 1.9.3 / 2.8.4 | Apache-2.0 |
| Jetpack Compose（BOM、ui、material3、material-icons-extended） | 2024.11.00 | Apache-2.0 |
| CameraX（core / camera2 / lifecycle / view） | 1.4.0 | Apache-2.0 |
| Room（runtime / ktx） | 2.6.1 | Apache-2.0 |
| ExifInterface | 1.3.7 | Apache-2.0 |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 |
| Kotlin 标准库 / AGP / KSP | 2.0.21 / 8.7.3 | Apache-2.0 |
| **OpenCV** (`org.opencv:opencv`) | 4.10.0 | **Apache-2.0**（4.5.0 起由 BSD-3 改为 Apache-2.0；`libopencv_java4.so` 23.7+16.6 MB） |
| **TensorFlow Lite** (`org.tensorflow:tensorflow-lite` / `-support`) | 2.16.1 / 0.4.4 | Apache-2.0 |
| Coil（coil-compose） | 2.7.0 | Apache-2.0 |
| OkHttp（Coil 传递依赖） | — | Apache-2.0 |
| play-services-basement / tasks（ML Kit 传递依赖） | — | Apache-2.0 |

### 需要额外注意的

| 组件 | 许可证 | 义务 / 风险 |
|---|---|---|
| **ML Kit 中文 OCR** (`com.google.mlkit:text-recognition-chinese:16.0.1`) | **闭源**，受 Google ML Kit Terms / Google APIs ToS 约束 | 允许把模型随 App 内置分发、离线运行；**不允许**把模型文件单独抽出来分发或做其他用途。不是开源，但不影响「随 App 分发」。（`README.md` 第 542 行已提示需复核条款） |
| Public Suffix List 数据（okhttp 内 `NOTICE`） | MPL-2.0 | 该 NOTICE 文件**未被剔除**，已随包分发，符合要求 |

### 特别说明：这个包里的 NOTICE 被主动删了

`app/build.gradle.kts` 第 154–162 行：

```kotlin
packaging {
    resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        excludes += "/META-INF/DEPENDENCIES"
        excludes += "/META-INF/LICENSE*"     // ← 问题
        excludes += "/META-INF/NOTICE*"      // ← 问题
    }
}
```

剔除 `/META-INF/LICENSE*` 与 `NOTICE*` 是 App 打包的常见做法（避免重复文件冲突），但**Apache-2.0 第 4 条 (a)(d) 要求分发时提供许可证副本、并保留 NOTICE 文件**。当前 APK 里除 okhttp 那一个 NOTICE 外，**没有任何许可证全文**。

**正确做法**：保留剔除（避免冗余），但在应用内提供「开源许可」页面（或随包附带 `THIRD-PARTY-NOTICES.md`），列出全部依赖及其许可证全文。Google 官方方案是 `com.google.android.gms:play-services-oss-licenses` + `oss-licenses-plugin`。

---

## 三、算法出处（全部为公开发表的学术算法，实现为本项目独立编写）

**核心结论：算法本身不受版权保护，受保护的是具体实现代码。** 本项目的 7302 行 Kotlin（26 个文件）均为独立实现，未拷贝任何外部源码——源码中不存在任何第三方版权头（`grep -il "copyright\|SPDX\|Licensed under"` 仅命中 `DocSegmenter.kt` 的模型来源注释）。

| 位置 | 算法 | 出处（原始文献） | 备注 |
|---|---|---|---|
| `ScanEnhancer.estimateBackground` | 大核形态学闭运算 + 高斯模糊估计光照场 → 除法归一化 | 经典「平场校正 / 同态滤波」思想，无单一出处 | 通用图像处理技术 |
| `ScanEnhancer.flattenResidual` | 两级平坦化（多尺度背景场残差补偿） | 本项目自研 | — |
| `ScanEnhancer.adaptiveBinarize` | **Sauvola 局部阈值**<br>`T = m·(1 + k·(s/R − 1))` | J. Sauvola, M. Pietikäinen, *"Adaptive document image binarization"*, **Pattern Recognition 33(2):225–236, 2000** | 相关前作：W. Niblack, *An Introduction to Digital Image Processing*, 1986（局部均值+标准差法） |
| （备选阈值法） | **Otsu 最大类间方差** | N. Otsu, *"A Threshold Selection Method from Gray-Level Histograms"*, **IEEE Trans. SMC 9(1):62–66, 1979** | 离线评估中作为对照，未采用 |
| `ScanEnhancer.applyLocalContrast` | **CLAHE 限制对比度自适应直方图均衡** | K. Zuiderveld, *"Contrast Limited Adaptive Histogram Equalization"*, **Graphics Gems IV, Academic Press, 1994, pp. 474–485** | OpenCV `Imgproc.createCLAHE` 现成实现 |
| `ScanEnhancer.applyUnsharpMask` | USM 反锐化掩膜 | 传统暗房技术，无版权归属 | `Core.addWeighted` 两行实现 |
| `DocumentGeometry.resizeLanczos` | **Lanczos 重采样** | C. Lanczos (1950s)；OpenCV `INTER_LANCZOS4` 现成实现 | — |
| `DocumentGeometry.detectDocumentQuad` | 边缘检测 + 轮廓 + 四边形拟合（`approxPolyDP`） | D. Douglas, T. Peucker, *"Algorithms for the reduction of the number of points..."*, **Cartographica 10(2):112–122, 1973** | OpenCV 现成实现 |
| `DocumentGeometry.estimateSkewAngle` | Hough / 最小二乘直线拟合估倾角 | 经典技术 | — |
| `DocSegmenter` 所用模型架构 | **DeepLabV3+** | L.-C. Chen, Y. Zhu, G. Papandreou, F. Schroff, H. Adam, *"Encoder-Decoder with Atrous Separable Convolution for Semantic Image Segmentation"*, **ECCV 2018, arXiv:1802.02611** | 仅论文架构，本项目未使用其代码 |
| 同上 | **MobileNetV2** 编码器 | M. Sandler, A. Howard, M. Zhu, A. Zhmoginov, L.-C. Chen, *"MobileNetV2: Inverted Residuals and Linear Bottlenecks"*, **CVPR 2018** | 同上 |
| `OcrEngine` | 二值图笔画修复（反相→闭运算→高斯→反相） | 本项目自研启发式 | — |
| `SearchablePdfWriter` | 双层 PDF（图像层 + 不可见文本层），TTC→TTF 容器解析 | PDF 1.7 规范（ISO 32000-1）；`ttcf` 头为 TrueType Collection 规范 | 手写 PDF 对象流，未引入第三方 PDF 库 |

**关于「AI 生成代码」的补充**：部分实现由 AI 辅助编写。多数司法辖区（含中国）目前将 AI 生成内容视为**无人类作者、不产生独立著作权**，但**不影响你对该代码的使用与分发**，也不构成对第三方的侵权。风险点在于：不能主张他人侵犯你对 AI 生成片段的著作权。如果需要强势的著作权保护，建议对核心算法（两级平坦化、笔画修复）做人工重写与加固。

---

## 四、其他缺口与风险

### 1. 🔴 无 LICENSE 文件
项目根目录、`app/` 下均无 `LICENSE` / `COPYING`。你的**自己的代码**目前处于「保留全部权利」的默认状态，用户拿到源码（如果给）无权使用。要分发应先明确选择：
- 想保持闭源 → 无需 LICENSE，但需在 App 内加免责声明与隐私说明
- 想开源 → 加 `LICENSE`（MIT / Apache-2.0 / GPL-3.0 按产品意图选）
- **注意**：无 LICENSE 文件的情况下若用了 GPLv3 模型，会与「保留全部权利」直接冲突

### 2. 🔴 无 THIRD-PARTY-NOTICES、App 内无限可声明页
已核实：`ui/screen/` 下 9 个页面（Camera/Enhance/Home/Import/DocumentList/PageManager/Review/SelfCheck/ScanSession）**没有任何一个**含「开源许可」或「关于」入口；`strings.xml` 也没有相关文案。需补。

### 3. 🟡 PDF 字体嵌入（低风险，但值得记录）
`SearchablePdfWriter.loadChineseFont()` 从**设备**读取：
```
/system/fonts/NotoSansCJK-Regular.ttc      ← SIL OFL-1.1
/system/fonts/NotoSansSC-Regular.otf       ← SIL OFL-1.1
/system/fonts/DroidSansFallbackFull.ttf    ← Apache-2.0
/system/fonts/DroidSansFallback.ttf        ← Apache-2.0
/system/fonts/NotoSansCJKsc-Regular.otf    ← SIL OFL-1.1
```
- **两种许可都明确允许把字体嵌入文档**（OFL-1.1 FAQ 说明：文档内嵌字体不使文档成为衍生作品，无需开源文档）。**结论：合规。**
- 列表里**没有**三星/华为等厂商私有字体，避免了一个常见坑（厂商字体常禁止嵌入）——当前实现是安全的。
- 若某设备五个路径都不存在，代码静默不嵌入字体（PDF 仍可用，只是文本层定位会退化），无授权风险。
- 唯一建议：**不要**把字体文件本身作为「资源」随 App 分发（当前没有这么做）。

### 4. 🟡 商标风险（应用名）
- ~~应用名「**极简扫描**」：国内各大应用商店存在同名或近名扫描类 App。~~ → **已更名**为「**轻松扫描**」，规避同名冲突。更名前建议仍做一次商标检索（第9类/第42类），确认「轻松扫描」无在先注册。
- 「**OpenScan**」：签名证书 `CN=OpenScan, O=OpenScan`，且 `applicationId = com.openscan.docscan`。OpenScan 亦是德国一个开源扫描项目（OpenScan GmbH / OpenScan.eu）的名称，存在**在先权利冲突**可能。应用显示名已避开，但包名与证书仍带此前缀。
- 包名前缀 `com.openscan.*` 一旦上架即锁定（签名一致性要求），改名成本高，**建议上架前就定好**。若决定更换，需要一并重新签发 keystore，届时旧版用户必须卸载重装。

### 5. 🟢 图标
`res/drawable/ic_launcher_foreground.xml`、`mipmap-anydpi-v26/ic_launcher*.xml` 均为本项目自建的矢量图，无第三方素材。已核实无外部字体/图标库资产。

### 6. 🟢 无外部代码拷贝
26 个 Kotlin 文件、7302 行，无任何第三方版权头、无 copy-paste 痕迹。唯一引用外部成果的是分割模型（已在第一节处理）。

---

## 五、最小整改清单（要能合法对外分发）

按优先级：

- [ ] **1. 处理 GPLv3 / NC 模型** ← 唯一的硬阻断
  - 换 Apache-2.0/MIT 模型，或走 GPLv3 开源，或删除模型退回传统边缘检测
- [ ] **2. 补 `LICENSE`**（决定自己的代码授权方式）
- [ ] **3. 补 `THIRD-PARTY-NOTICES.md`** + App 内「开源许可」页面
  - 覆盖第二节全部依赖；可引入 `play-services-oss-licenses` 自动聚合
- [ ] **4. 调整 `build.gradle.kts`** 的 `packaging.resources.excludes`
  - 保留剔除避免冲突，但必须在 App 内提供等价许可声明（第 3 项）
- [x] **5. 商标检索与品牌更名** — 应用名已改为「轻松扫描」（2026-10-16）
  - 剩余：包名前缀 `com.openscan.*` 与签名证书 `CN=OpenScan` 的处置决策（见第四节）
- [ ] **6. 补隐私政策**（涉及相机权限、相册读写、本地存储）
  - 虽然承诺「100% 离线、不上传」，各大应用商店仍强制要求隐私政策文本

---

## 附：核查方法（可复现）

```bash
# 模型来源与授权
strings -a app/src/main/assets/doc_segmentation.tflite | grep -o "encoders\.[a-z0-9_]*"
grep -n -A6 "FairScan" app/src/main/java/com/openscan/docscan/core/DocSegmenter.kt

# APK 内容与许可文件现状
unzip -l outputs/DocScanPro-v1.3.0-7-release.apk | grep -i "license\|notice"
unzip -l outputs/DocScanPro-v1.3.0-7-release.apk | sort -k1 -nr | head -14

# 依赖清单
cat gradle/libs.versions.toml

# 第三方版权头（应为空）
grep -rniL "copyright\|SPDX\|Licensed under" app/src/main/java/ | wc -l

# 字体候选
sed -n '360,370p' app/src/main/java/com/openscan/docscan/core/SearchablePdfWriter.kt
```

**上游授权页（人工复核用）**
- FairScan 分割模型：https://github.com/pynicolas/fairscan-segmentation-model （GPL-3.0）
- FairScan 数据集：https://github.com/pynicolas/fairscan-dataset （CC BY-NC-SA 4.0）
- OpenCV 许可证：https://opencv.org/license/
- ML Kit 条款：https://developers.google.com/ml-kit/terms
- OFL-1.1 字体嵌入 FAQ：https://openfontlicense.org/ofl-faq/
