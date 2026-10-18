# 开源许可说明

---

## 一、说明

本应用在开发与分发过程中使用了下列第三方开源软件与组件。我们在此保留并致谢所有上游作者与贡献者，  
并按照各自的许可证要求，随本应用提供完整的许可声明与许可证副本。

**构建变体说明**：本项目有两个构建变体，第三方组件清单不同——

| 构建      | versionName         | 内置分割模型     | 可对外分发     |
| ------- | ------------------- | ---------- | --------- |
| **合规版** | v1.4.15             | 否          | ✅ 可以      |
| **自用版** | v1.5.14-selfuse     | 是（GPL-3.0） | ❌ **不可以** |

本应用**完全离线运行**：所有图像处理、文字识别、PDF 生成均在本机完成，不向任何服务器上传数据。

> 本文件同时随 APK 打包（`assets/THIRD-PARTY-NOTICES.md`），并可在应用内  
> **首页 → 开源许可说明** 中随时查阅。

---

## 二、依赖总览

| 组件                                                              | 版本             | 许可证                        |
| --------------------------------------------------------------- | -------------- | -------------------------- |
| AndroidX 基础库（core-ktx / annotation / collection）                | 1.15.0         | Apache-2.0                 |
| AndroidX Lifecycle（runtime-ktx / viewmodel-compose）             | 2.8.7          | Apache-2.0                 |
| AndroidX Activity Compose                                       | 1.9.3          | Apache-2.0                 |
| Jetpack Compose（BOM / ui / graphics / foundation）               | 2024.11.00     | Apache-2.0                 |
| Compose Material 3                                              | 2024.11.00     | Apache-2.0                 |
| Compose Material Icons Extended                                 | 2024.11.00     | Apache-2.0                 |
| AndroidX Navigation Compose                                     | 2.8.4          | Apache-2.0                 |
| AndroidX CameraX（core / camera2 / lifecycle / view）             | 1.4.0          | Apache-2.0                 |
| AndroidX ExifInterface                                          | 1.3.7          | Apache-2.0                 |
| AndroidX Room（runtime / ktx）                                    | 2.6.1          | Apache-2.0                 |
| Kotlin 标准库（stdlib / reflect）                                    | 2.0.21         | Apache-2.0                 |
| kotlinx.coroutines                                              | 1.9.0          | Apache-2.0                 |
| **OpenCV**（`org.opencv:opencv`）                                 | 4.10.0         | **Apache-2.0**             |
| **TensorFlow Lite**（tensorflow-lite / tensorflow-lite-support）  | 2.16.1 / 0.4.4 | Apache-2.0                 |
| Coil（coil-compose）                                              | 2.7.0          | Apache-2.0                 |
| OkHttp / Okio（Coil 传递依赖）                                        | —              | Apache-2.0                 |
| Google Play Services Basement / Tasks（传递依赖）                     | —              | Apache-2.0                 |
| Public Suffix List 数据（OkHttp 内置）                                | —              | MPL-2.0                    |
| **ML Kit 文字识别（中文）** `com.google.mlkit:text-recognition-chinese` | 16.0.1         | **闭源专有**（Google ML Kit 条款） |
| **FairScan 文档分割模型**（自用版内置，`assets/doc_segmentation.tflite`）     | 1.2.0          | ⚠️ **GPL-3.0**             |

---

## 三、Apache License 2.0 组件

下列组件均以 **Apache License, Version 2.0** 授权。其完整许可证全文见本文档  
**第八节**。版权归各自所有者所有：

- Copyright (C) The Android Open Source Project（AndroidX、Jetpack Compose、CameraX、Room、ExifInterface）
- Copyright (C) JetBrains s.r.o.（Kotlin、kotlinx.coroutines）
- Copyright (C) OpenCV Foundation / Intel Corporation / Willow Garage（OpenCV）
- Copyright (C) The TensorFlow Authors（TensorFlow Lite）
- Copyright (C) Coil Contributors（Coil）
- Copyright (C) Square, Inc.（OkHttp / Okio）
- Copyright (C) Google LLC（Play Services Basement / Tasks）

根据 Apache-2.0 第 4 条，本应用分发时：

1. **已提供本许可证副本**（见第八节全文）；
2. 未修改上述组件的源文件内容（除 GPL 之外的组件均以二进制形式原样引入）；
3. 已保留上述全部版权、专利、商标与署名声明（本节及第二节清单）。

---

## 四、MPL-2.0 组件

**Public Suffix List**（随 OkHttp 分发的 `publicsuffixes.gz` 与 `NOTICE` 文件）

- 许可证：Mozilla Public License 2.0（MPL-2.0）
- 说明：该数据文件的 `NOTICE` 文件未被剥离，已随 APK 原样分发
- 许可证全文：<https://www.mozilla.org/MPL/2.0/>

---

## 五、需要特别说明的组件

### 5.1 ML Kit 文字识别（中文）— 闭源专有

- 包名：`com.google.mlkit:text-recognition-chinese:16.0.1`
- 提供方：Google LLC
- 授权方式：**非开源**，受 Google ML Kit 使用条款与 Google APIs 服务条款约束
- 使用条款：<https://developers.google.com/ml-kit/terms>

**允许的使用方式**：Google ML Kit 条款允许将识别模型随应用内置、在设备上离线运行。  
本应用正是以这种方式使用：模型随 APK 打包，运行时完全离线，用户输入的图像仅在本机  
内存中处理，不会上传至 Google 或任何第三方。

**限制**：不得将 ML Kit 的模型文件单独抽取、重新分发或用于其他用途。本应用未从事上述行为。

### 5.2 FairScan 文档分割模型 — GPL-3.0（仅自用版内置）

- 文件：`assets/doc_segmentation.tflite`（4.9 MB，随自用版 APK 打包）
- 架构：DeepLabV3+ 解码器 + MobileNetV2 编码器，PyTorch 训练后导出为量化 TFLite
- 输入输出：`[1,256,256,3]` float32 RGB → `[1,256,256,1]` float32 logits
- 来源：<https://github.com/pynicolas/fairscan-segmentation-model>
- 上游应用：FairScan（<https://github.com/pynicolas/FairScan）>
- **许可证：GNU General Public License v3.0**
- 训练数据：<https://github.com/pynicolas/fairscan-dataset> （**CC BY-NC-SA 4.0**）
- 用途：文档区域分割（逐像素识别纸张边界），检测链第一级

**版权与许可保留**：本文件按要求保留了上游版权声明与许可信息，  
GPL-3.0 许可证全文见 <https://www.gnu.org/licenses/gpl-3.0.txt> 。

**为什么内置该模型的构建不可对外分发**：

1. **GPL-3.0 是强 copyleft**：把它随 APK 分发给他人，若被认定为"结合作品  
   （combined work）"，整个 APK 都需按 GPL-3.0 授权 —— 即必须公开全部源码。
2. **训练数据禁止商用**：上游数据集为 CC BY-NC-SA 4.0（禁止商业使用 +  
   相同方式共享）。模型权重是否构成训练数据的衍生作品虽无定论，但权利人  
   完全可以如此主张，商业分发将失去抗辩基础。

**为何自用可以**：GPL-3.0 的义务由"传播（conveying，即向他人分发副本）"触发。  
在自有设备上安装、运行、修改属于 GPL-3.0 明确许可的行为，不触发任何对外义务。  
本项目使用的是自研代码 + 同架构重训尝试（`tools/train/`），未修改上游模型本身。

**如需对外分发**：删除 `app/src/main/assets/doc_segmentation.tflite` 后重新构建  
即可回到合规版 —— 代码无需任何改动，检测链会自动降级到纯 OpenCV 的文本种子  
检测（实测 5/5 命中、与 ML 基准平均 IoU 0.786）。

**关于手写分割模型（`assets/handwriting_seg.tflite`，两个变体均有）**：该模型为  
本项目**全自研**——训练数据 100% 由 `tools/train/gen_handwriting.py` 程序生成  
（系统字体渲染印刷页 + 程序合成手写笔迹，无任何第三方素材），架构与权重均为  
自研自训，不包含第三方版权material，故**不受上述 GPL 限制**，合规版与自用版  
均可随包分发。两变体的判别法不变：仅以 `doc_segmentation.tflite` 是否在包内区分。

---

## 六、运行时使用但不随包分发的资源

### 系统中文字体

生成可搜索 PDF（双层 PDF）时，本应用会从**设备系统**中查找下列字体文件，用于嵌入文本层：

| 路径                                        | 许可证                       |
| ----------------------------------------- | ------------------------- |
| `/system/fonts/NotoSansCJK-Regular.ttc`   | SIL Open Font License 1.1 |
| `/system/fonts/NotoSansCJKsc-Regular.otf` | SIL Open Font License 1.1 |
| `/system/fonts/NotoSansSC-Regular.otf`    | SIL Open Font License 1.1 |
| `/system/fonts/DroidSansFallbackFull.ttf` | Apache-2.0                |
| `/system/fonts/DroidSansFallback.ttf`     | Apache-2.0                |

**说明**：

- 这些字体**不随本应用分发**，仅由应用在运行时从用户设备上读取；
- SIL OFL-1.1 明确允许将字体嵌入文档，且嵌入行为不会使文档本身成为衍生作品、  
  不要求文档开源（见 OFL FAQ：<https://openfontlicense.org/ofl-faq/）；>
- 若设备上不存在上述任一字体，应用会静默跳过字体嵌入，PDF 仍可正常生成与查看，仅文本层定位精度略有下降。

### OpenCV 内部集成的第三方组件

OpenCV 官方声明自 4.5.0 版起整体采用 Apache-2.0 许可。其预编译原生库  
（`libopencv_java4.so`）内部集成的第三方组件——包括 libjpeg-turbo、libpng、zlib、  
Protocol Buffers 等——均采用 BSD / MIT / zlib 类宽松许可，允许随应用再分发。

---

## 七、本项目自研代码

本应用的 Kotlin 源码（29 个文件）、界面设计、矢量图标、文档处理算法实现均为本项目独立编写，  
未拷贝任何第三方源码。**版权归本项目作者所有，保留所有权利（All Rights Reserved）。**

自用版内置的文档分割模型（见 5.2）与 ML Kit 模型均为**二进制原样引用**，  
不涉及任何第三方源码拷贝；本项目的自研代码不因引用它们而受其许可约束  
（详见第五节关于 GPL 边界的说明）。

本应用使用的算法（Sauvola 局部二值化、Otsu 阈值、CLAHE、Lanczos 重采样、  
Douglas-Peucker 多边形简化、USM 反锐化掩膜等）均为公开发表的学术成果，  
其**实现**由本项目独立完成，详见第九节的文献引用。

---

## 八、许可证全文


### Apache License, Version 2.0

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

### GNU General Public License v3.0（GPL-3.0）

仅适用于**自用版**内置的 FairScan 文档分割模型（`assets/doc_segmentation.tflite`）。

- 许可证全文：https://www.gnu.org/licenses/gpl-3.0.txt
- 官方译文索引：https://www.gnu.org/licenses/translations.html
- 上游源码：https://github.com/pynicolas/fairscan-segmentation-model

> 本项目**未修改**该模型文件，按原样引用。自用版不对外分发，故此处不内嵌
> 约 35 KB 的许可证全文，仅提供官方地址；若将来需要对外分发，必须从上述
> 地址获取 GPL-3.0 全文并随分发包一并提供，同时提供模型的完整对应源码。

### Mozilla Public License 2.0（MPL-2.0）

仅适用于 OkHttp 内置的 Public Suffix List 数据文件（及其 `NOTICE`）。

许可证全文：https://www.mozilla.org/MPL/2.0/
源码获取：https://publicsuffix.org/list/

### SIL Open Font License 1.1（OFL-1.1）

适用于设备系统中可能被读取的 Noto Sans CJK 系列字体（**不随本应用分发**）。

许可证全文：https://openfontlicense.org/open-font-license-official-text/
常见问题：https://openfontlicense.org/ofl-faq/

### Google ML Kit 使用条款

适用于随本应用分发的 ML Kit 中文文字识别组件（闭源）。

条款全文：https://developers.google.com/ml-kit/terms
第三方声明：https://developers.google.com/ml-kit/more-info/third-party-licenses

---

## 九、算法文献引用

本应用的图像处理与文档分析算法均为独立实现，所依据的公开文献如下：

1. **Sauvola 局部二值化**（黑白文档模式）
   J. Sauvola, M. Pietikäinen, *"Adaptive document image binarization"*,
   Pattern Recognition, 33(2):225–236, 2000.
   （前作：W. Niblack, *An Introduction to Digital Image Processing*, 1986）

2. **Otsu 最大类间方差阈值**
   N. Otsu, *"A Threshold Selection Method from Gray-Level Histograms"*,
   IEEE Transactions on Systems, Man, and Cybernetics, 9(1):62–66, 1979.

3. **CLAHE 限制对比度自适应直方图均衡**
   K. Zuiderveld, *"Contrast Limited Adaptive Histogram Equalization"*,
   Graphics Gems IV, Academic Press, 1994, pp. 474–485.

4. **Lanczos 重采样**
   C. Lanczos, *"Evaluation of Noisy Data"*,
   Journal of the Society for Industrial and Applied Mathematics, Series B, 1(1):76–85, 1964.

5. **Douglas-Peucker 多边形逼近**
   D. H. Douglas, T. K. Peucker, *"Algorithms for the Reduction of the Number of
   Points Required to Represent a Digitized Line or its Caricature"*,
   Cartographica, 10(2):112–122, 1973.

6. **USM 反锐化掩膜 / 平场校正**
   经典暗房与图像处理技术，无单一出处。

> 说明：算法本身不受著作权保护（著作权保护的是具体实现代码）。
> 本应用上述算法的实现代码均由本项目独立编写，未引用或拷贝任何第三方源码。

---

## 十、第三方组件源码获取

| 组件 | 源码地址 |
|---|---|
| AndroidX / Jetpack | https://github.com/androidx/androidx |
| Kotlin | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | https://github.com/Kotlin/kotlinx.coroutines |
| OpenCV | https://github.com/opencv/opencv |
| TensorFlow Lite | https://github.com/tensorflow/tensorflow |
| Coil | https://github.com/coil-kt/coil |
| OkHttp | https://github.com/square/okhttp |
| Public Suffix List | https://github.com/publicsuffix/list |
| ML Kit（条款与声明） | https://developers.google.com/ml-kit/terms |
| FairScan 分割模型（GPL-3.0） | https://github.com/pynicolas/fairscan-segmentation-model |
| FairScan 数据集（CC BY-NC-SA 4.0） | https://github.com/pynicolas/fairscan-dataset |

---

## 十一、免责声明

本应用按「现状」提供，不附带任何明示或暗示的担保，包括但不限于对适销性、
特定用途适用性及非侵权性的担保。在任何情况下，作者均不对因使用本应用而
产生的任何直接、间接、附带、特殊或后果性损害承担责任。

用户应自行确保其使用本应用扫描、存储、分享的文档内容合法，并遵守所在地法律法规。

**自用版构建（v1.5.14-selfuse）仅限自用，不得对外分发、上传应用商店或提供给第三方**，
理由见第五节 5.2。

---

*如对本文档有疑问，或发现遗漏的署名，请通过应用内反馈渠道与我们联系。*
