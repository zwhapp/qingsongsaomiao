# 发布前合规审查报告（工程 / 仓库维度）

- **审查对象**：`DocScanPro/` 整个工程（公开仓库 `github.com/zwhapp/qingsongsaomiao`，
  `private: false`，Apache-2.0）
- **审查日期**：2026-10-24
- **审查范围**：版本库跟踪的全部 329 个文件（含 `tools/` 264 个）、构建脚本、
  依赖清单、文档、资源；并以全 git 历史（8 个提交）交叉验证泄露情况
- **与既有报告的关系**：`COMPLIANCE-AUDIT.md`（2026-10-16）审查的是 **APK 分发**
  维度；本报告审查的是**工程对外共享**维度，二者互补
- 本文是工程侧合规盘点，不是法律意见

> 严重级别：🔴 阻断发布（不解决不应对外共享）／🟡 建议修复（共享前应解决）／
> ⚪ 可选优化／✅ 已通过

---

## 〇、结论速览

| 检查项 | 结论 |
|---|---|
| 许可证文件与版权声明 | ✅ 通过（Apache-2.0 全文 + 版权行，两份 NOTICES 逐字节一致） |
| 第三方依赖许可兼容性 | ✅ 通过（26 库 + 4 插件，无传染性许可；GPL 模型从未入库） |
| 敏感信息泄露 | ✅ 通过（当前树 + 全历史零命中凭据；密钥文件从未入库） |
| 内部资产清理 | 🔴 **1 项阻断**：163 MB 调试图片（含出版物书页照片）在公开仓库 |
| 文档与元信息 | 🟡 5 处文档与现状矛盾，**已全部修正**；补 CHANGELOG / PRIVACY |
| 构建与可发布性 | 🟡 3 处配置问题，**已修正**；`assembleDebug` 实跑验证通过 |

**一句话**：代码与许可层面干净，真正的硬伤是 **163 MB 内部调试图片**——
其中多张是受版权保护的出版物书页照片，且已随公开仓库分发过；另有一批
文档条目停留在旧结论（"不声明 INTERNET 权限"等）与现状矛盾。以上均已处理，
**历史重写与强推已于 2026-10-24 执行完毕**（见第六节），远端已无任何
调试二进制，仓库克隆体积从 ~170 MB 降至 2.2 MB。

---

## 一、🔴 阻断级：163 MB 内部调试图片（含出版物书页照片）

### 事实

`tools/` 下共 **194 个二进制文件**被提交进公开仓库，合计约 **168 MB**：

| 位置 | 数量 | 内容 |
|---|---|---|
| `tools/` 根目录 | ~85 | 算法对比图 / 热力图 / 检测可视化 |
| `tools/train/` | 64 | 训练与调参过程图（`_bin_v2/`、`_moire/`、`_printpage/`） |
| `tools/v130out/` | 24 | 版本调参输出图 |
| `tools/altout/` | 10 | 备选方案对比图 |
| `tools/jvmtest/` | 5 | 第三方二进制与编译产物（见第二节） |

经逐张目验，其中至少三张是**受版权保护的出版物书页照片**：

- `tools/book2.jpg` — 摊开的党史学习教育读本内页（含目录与正文）
- `tools/photo_full.jpg` / `tools/text_photo_full.jpg` — 土地税专题书刊内页
  （可辨识出版字号与脚注，如"中国税务年鉴 2006"字样）

其余图片亦为调试过程产物（二值化前后对比、轮廓可视化、超分热力图等），
不含个人隐私信息，但同属"不应对外"的内部资产。

### 为什么是阻断级

1. **著作权**：整页翻拍受版权保护的出版物并放入公开仓库，属于对作品
   实质性部分的复制与传播，且面向不特定公众无限复制。
2. **仓库已公开**：`zwhapp/qingsongsaomiao` 为 public，上述内容已实际
   暴露；仅从 HEAD 移除不能消除已发生的暴露。
3. **仓库体积**：克隆体积被无意义放大 ~168 MB，影响所有克隆者。

### 已执行的修改

```bash
git rm --cached <194 个文件>     # 仅移出版本库，磁盘文件全部保留
```

`.gitignore` 新增规则（第 54–78 行）：

```
tools/**/*.jpg / *.jpeg / *.png / *.gif / *.bmp / *.mp4
tools/**/*.so / *.jar / *.class
tools/**/dl/
tools/**/*.log
tools/counter/.wrangler/
```

已验证：仍被跟踪的 `tools/` 文件中无一命中上述规则，无误伤；
`tools/book2.jpg` 等文件在磁盘上原样保留，本地复现脚本不受影响。
README 已补充说明"调试图片不在仓库中，克隆者需自备测试图"。

### 历史重写（2026-10-24 已执行）

| 项 | 结果 |
|---|---|
| 工具 | `git filter-repo`（装于本机 Python venv） |
| 重写范围 | `main` + 三个 tag（`v1.4.19` / `v1.4.21` / `v1.4.22`），共 9 个提交 |
| 重写前备份 | `outputs/git-pre-rewrite-backup-20261024.bundle`（160 MB，`git bundle verify` 通过；`outputs/` 已被 gitignore，不会外泄） |
| 推送 | `git push --force origin main` + 三个 tag 强制移动到重写后提交 |
| 验证 | 从 GitHub 全新克隆：`.git` 2.2 MB、9 个提交、**全历史 0 个调试二进制**、138 个跟踪文件、分发关键文件齐全、自用版专属物 0 命中 |
| Release 影响 | 三个 Release 及其 APK 资产（各 71.5 MB）完好，下载地址不变（tag 仅移动引用，资产独立存储） |
| 踩坑 | `--path-glob 'tools/**/*.jpg'` **不匹配** `tools/` 根目录文件（`**` 与 `*` 之间还有一个 `/`，要求至少两级目录），首轮漏掉 86 个根目录图片；补 `tools/*.jpg` 形式后重跑才清零 |

**已知残留**：GitHub 服务端的旧提交对象在其垃圾回收运行前，仍可能通过
旧 SHA 直接访问（GitHub 官方说明：unreachable objects 最终会被清除）。
如需立即清除，可在 GitHub Support 提交仓库 GC 请求。

---

## 二、🟡 建议级：`tools/jvmtest/` 第三方二进制与编译产物

| 文件 | 大小 | 性质 |
|---|---|---|
| `tools/jvmtest/libtensorflowlite_jni.so` | 4.5 MB | 从 `org.tensorflow:tensorflow-lite` AAR 拆出的原生库（Apache-2.0） |
| `tools/jvmtest/tflite.jar` | 61 KB | TFLite Java 绑定 |
| `tools/jvmtest/tflite-api.jar` | 40 KB | TFLite API |
| `tools/jvmtest/ShapeLogic.class`、`TfliteShapeProbe.class` | 8 KB | javac 编译产物 |
| `tools/jvmtest/dl/tflite-desktop.jar` | — | 探测脚本下载物 |

问题：① Apache-2.0 组件再分发未在 `THIRD-PARTY-NOTICES.md` 署名
（该文件只覆盖 App 依赖）；② `.class` 编译产物不应进版本库；
③ 与第一节同批处理。

**已执行**：随 194 个文件一并 `git rm --cached`，`.gitignore` 已加
`tools/**/*.so / *.jar / *.class / dl/`。若后续确有桌面探测需求，
建议在 README 写明"首次运行前从 Maven 下载 tflite AAR 并解压"。

---

## 三、🟡 提示级：个人收款码公开

`app/src/main/assets/sponsor/alipay.jpg`（支付宝收款码）在公开仓库中。
赞助页（`SponsorScreen.kt`）设计为分发版打赏入口，公开收款码通常是有意为之。
**需你确认**：愿意公开该码即可忽略；若该码同时用于个人日常收款，
建议改为专用小号收款码，避免收款码被爬取后遭骚扰或用于伪造转账截图。

---

## 四、✅ 已通过的检查项

### 4.1 许可证与知识产权

| 项 | 证据 |
|---|---|
| LICENSE 存在且完整 | 根目录 `LICENSE`，Apache-2.0 全文 11,361 字节，版权行 `Copyright 2026 OpenScan (QingSongScan) Authors`（附录第 178 行附近） |
| 第三方声明齐备 | `THIRD-PARTY-NOTICES.md` 11 节，覆盖依赖总览 / Apache-2.0 / MPL-2.0 / ML Kit / GPL 模型 / 算法文献；`diff` 确认根目录与 `app/src/main/assets/` 两份**逐字节一致** |
| 无第三方版权头混入 | 全仓库扫描 `copyright / SPDX / Licensed under`，仅命中 NOTICES 与 `DocSegmenter.kt` 对 FairScan 模型来源的说明注释（模型本身未入库） |
| 包内许可可声明 | `LicensesScreen.kt` 提供应用内许可页，呈现上述 NOTICES |

### 4.2 第三方依赖许可

`gradle/libs.versions.toml` 共 26 个库 + 4 个插件，逐一核对：

- **全部宽松/专有但可随 App 分发**：AndroidX / Compose / CameraX / Room /
  kotlinx-coroutines / Kotlin / AGP / KSP / OpenCV 4.10.0（Apache-2.0，
  4.5.0 起已从 BSD-3 切换）/ TFLite 2.16.1 / Coil 2.7.0 → **Apache-2.0**
- **ML Kit 中文 OCR 16.0.1**：Google 专有（ML Kit Terms），bundled 模型
  允许随 App 分发、本地推理 → NOTICES 5.1 已声明
- **无传染性许可**：全清单无 GPL / LGPL / AGPL / SSPL / CC-BY-NC 组件
- **GPL-3.0 分割模型**（FairScan，训练数据 CC BY-NC-SA）：
  `git log --all -- app/src/main/assets/doc_segmentation.tflite` 为空 →
  **从未进入版本库**；`.gitignore` 第 33–37 行有显式排除规则
- `tools/` 的 Python 脚本无任何外部数据下载 URL（训练数据 100% 程序生成）

### 4.3 敏感信息泄露

| 扫描模式 | 范围 | 结果 |
|---|---|---|
| `AKID[0-9A-Za-z]{10}`（腾讯云 SecretId 形态） | 当前树 + 全历史 | 0 命中 |
| `ghp_/gho_/github_pat_` | 当前树 + 全历史 | 0 命中 |
| `BEGIN RSA/EC PRIVATE KEY` | 当前树 + 全历史 | 0 命中 |
| `SecretKey / secret_key / api_key` 赋值 | 当前树 + 全历史 | 0 命中（仅文档与占位符） |
| `keystore.properties` / `*.jks` | 全历史 | 从未提交（仅 `.gitignore` 与文档提及文件名） |
| 内网地址 / 硬编码 IP / localhost | `app/src` + Python 脚本 | 0 命中 |
| 内部工单（jira / confluence / work.weixin / tapd） | 全仓库 | 0 命中 |

补充事实：

- `keystore.properties`（含明文口令）与 `docscan-release.jks` 在磁盘上存在，
  但被 `.gitignore` 第 20–22 行排除，历史中从未出现。
- 未跟踪的 `tools/counter/tcb/` 部署脚本通过 `process.env.TENCENT_SECRET_ID/KEY`
  读取凭据，无硬编码（已逐文件验证）。
- 提交身份统一为 `OpenScan <openscan@users.noreply.github.com>`，
  GitHub noreply 地址，无个人邮箱 / 手机号 / 真实姓名泄露。
- `tools/counter/worker.js:7` 含网盘真实分享链接（tcid + code）：
  该链接同时是公开落地页 `/dl` 的跳转目标，属公开信息，知悉即可。

### 4.4 内部资产清理

| 项 | 结果 |
|---|---|
| 被注释掉的代码块（`// val / fun / if / for / Log.` 等模式） | `app/src` + `tools/jvmcheck` 扫描为 **0** |
| TODO / FIXME / XXX / HACK / TBD | 0 |
| 占位内容（lorem / example.com / 测试文本 / foo bar） | 0 |
| 调试日志 | `app/src` 仅 5 处 `Log.e/i`，均为异常与状态说明，无 `Log.d/v`，无用户数据 |

### 4.5 构建与可发布性

- `AndroidManifest.xml`：权限最小化，`INTERNET` 仅用于分发版检查更新，
  `ACCESS_NETWORK_STATE` 已剔除；无内部专用配置。
- `app/build.gradle.kts`：无 `keystore.properties` 时自动降级为 unsigned
  构建，克隆者不会因缺密钥文件而构建失败。
- `proguard-rules.pro`：保留规则完整覆盖 OpenCV / TFLite / ML Kit / Room /
  Compose，无过度 `-keep` 全包。
- 依赖坐标全部来自 Maven Central / Google Maven，无私有仓库、
  无 `flatDir` 本地依赖、无 snapshot 版本。

### 4.6 构建验证记录（2026-10-24 实跑）

```bash
export JAVA_HOME="D:/Android/JDK17"
./gradlew assembleDebug        # BUILD SUCCESSFUL in 41s，37 tasks
```

产物 `app/build/outputs/apk/debug/QingSongScan-v1.5.21-51-debug.apk`，
`aapt2 dump badging` 实测：

```
package: name='com.openscan.docscan.debug' versionCode='51' versionName='1.5.21'
application-label:'轻松扫描'
uses-permission: CAMERA / WRITE_EXTERNAL_STORAGE(maxSdk 28) / READ_EXTERNAL_STORAGE(maxSdk 28)
                 INTERNET / REQUEST_INSTALL_PACKAGES / <androidx 自动注入的 DYNAMIC_RECEIVER...>
```

- `versionName='1.5.21'` 证实参数化生效（不再默认 `selfuse`）；
- `ACCESS_NETWORK_STATE` 未出现在最终清单（剔除规则仍有效）；
- 唯一告警为 `CameraScreen.kt:397` 的 `setTargetAspectRatio` 弃用提示，非错误。

---

## 五、🟡 建议级：已执行的修改明细

### 5.1 构建配置

| 文件 | 修改前 | 修改后 |
|---|---|---|
| `app/build.gradle.kts:47` | `versionName = "1.5.21-selfuse"`（仓库默认即自用版） | `versionName = (project.findProperty("versionName") as String?) ?: "1.5.21"`，附注释说明自用版构建需 `-PversionName=1.5.21-selfuse` |
| `gradle.properties:24` | `org.gradle.java.home=D:\Android\JDK17` 无任何说明 | 保留该行（删除会破坏维护者 Android Studio 构建），新增「公开克隆者注意」注释块，指引改为自有路径或 `JAVA_HOME` |
| `app/proguard-rules.pro` | 未剥离日志 | 追加 `-assumenosideeffects` 剔除 release 包的 `Log.v/d/i`，保留 `w/e` |

**影响提示**：`versionName` 默认值改为合规版后，**维护者本机构建自用版必须显式传**
`-PversionName=1.5.21-selfuse`（README 末节已写明）。否则产出的包名不带
`selfuse`，`SelfCheck` 第 6 项会按"合规版"断言 assets 里**不应有** GPL 模型
（当前磁盘上确有该模型），自检会报失败——这正是该检查的设计目的。

### 5.2 资源与文案

| 文件 | 问题 | 修改 |
|---|---|---|
| `app/src/main/res/values/strings.xml:62` | `offline_badge` 值为 `100%% 离线处理`：`%%` 仅在格式化字符串中才会还原为 `%`，该串无任何引用且直接显示会出现双百分号 | 改为 `100% 离线处理`（当前第 63 行） |
| `EnhanceScreen.kt:87-93` | 效果标签全部硬编码在 Kotlin，与其它走 `strings.xml` 的文案不一致 | 新增 `strings.xml:35 filter_remove_ink`，`EffectOption` 去掉 `label` 字段，改由 `EFFECT_LABEL_RES` 映射 + `stringResource` 取值 |

### 5.3 文档修正（README.md）

| 位置（修改前） | 问题 | 处置 |
|---|---|---|
| 第 18 行 | 「不声明 `INTERNET` 权限」与 `AndroidManifest.xml:27`（已声明 INTERNET + `REQUEST_INSTALL_PACKAGES`）直接矛盾 | 改为「用户内容全部本机处理；唯一网络行为是分发版检查更新（读 GitHub 公开 API）」，并链接 PRIVACY.md |
| 第 24 行 | 引用 `_deprecated/`，但该目录被 `.gitignore` 排除，克隆者看不到 | 改为「不在仓库中，仅存在于维护者本机」 |
| 第 103 行 | 工程结构图残留 `build-apk.bat / build-apk.ps1`（已废弃且不在仓库） | 从结构图删除 |
| 第 531 行 | 「没有手写擦除」——v1.5.14 起已有「去手写」模式 | 从已知限制中删除 |
| 第 540 行 | 「换掉 applicationId」与既定决策（包名/签名刻意不变）矛盾 | 改为「已刻意保持不变」并说明代价 |
| 第 548–550 行 | 「未声明 INTERNET 权限，符合备案豁免」未考虑新增的更新检查 | 加限定：豁免结论需按最终形态确认，自用版不声明该权限 |
| 第 425 行起 | 「已实际编译通过」数据为 v1.4.x 时期旧值 | 标注为历史记录 |

### 5.4 新增文档

- **`CHANGELOG.md`**：从 git 历史 + 本地 APK 产物归档整理版本线
  （v1.4.0 许可页 / v1.4.1 更名 / v1.4.17 仓库公开 / v1.5.14 去手写 /
  v1.5.18 连拍 / v1.4.22 镜像提速），并注明公开前的内部记录不完整。
- **`PRIVACY.md`**：逐权限说明用途与可否拒绝、数据流向明细
  （本机处理项 / 唯一网络请求 / 不出现在请求中的内容）、第三方组件、
  联系方式。可作商店隐私政策的文本底稿。

---

## 六、待你决策 / 需你处理的事项

1. **GitHub 服务端 GC**：旧提交对象在 GitHub 回收前仍可能经旧 SHA 访问，
   如需立即彻底清除可向其 Support 提交仓库 GC 请求（见第一节）。
2. **收款码是否公开**（第三节）。
3. **凭据轮换（上次已提示，仍未处理）**：
   - 腾讯云 SecretId `AKIDxtdA…HtaMH` / SecretKey（在聊天中明文出现过，
     且带 TCB + SCF FullAccess）→ CAM → 访问密钥 → 禁用并删除
   - GitHub PAT（如仍在用）→ Settings → Developer settings → 删除
4. **`tools/counter/` 内部运维工具的去留**：其中的 worker / TCB 部署脚本
   属内部基础设施运维代码，无凭据泄露，但含 EnvId、KV namespace id 等
   基础设施标识。目前 `tools/counter/README.md` 与 `wrangler.toml` 有
   未提交改动、`tools/counter/tcb/` 整个目录未跟踪——本次推送**不含**它们，
   是否入库由你决定。
5. **自用版构建方式已变**：仓库默认 `versionName` 为合规版 `1.5.21`，
   维护者打自用版必须显式 `-PversionName=1.5.21-selfuse`。

---

## 附：核查方法（可复现）

```bash
# 仓库公开状态
curl -s https://api.github.com/repos/zwhapp/qingsongsaomiao | grep private

# 凭据 / 内部信息（当前树 + 全历史）
git grep -nIE 'AKID[0-9A-Za-z]{10}|ghp_[A-Za-z0-9]{20,}|BEGIN (RSA |EC )?PRIVATE KEY' $(git rev-list --all)

# GPL 模型是否进过历史（空 = 从未提交）
git log --all --oneline -- app/src/main/assets/doc_segmentation.tflite

# 被跟踪的二进制 / 图片（整改后应只剩 app 资产）
git ls-files | grep -Ei '\.(jpg|png|so|jar|class)$'

# 注释代码块 / 占位 / 工单链接
git grep -nE '^\s*//\s*(val |var |fun |if \(|for \()' -- app/src
git grep -niE 'lorem|example\.com|jira|confluence' -- .

# 两份 NOTICES 一致性
diff -q THIRD-PARTY-NOTICES.md app/src/main/assets/THIRD-PARTY-NOTICES.md
```
