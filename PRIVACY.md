# 隐私政策（PRIVACY）

> 最后更新：2026-10-24。适用于「轻松扫描」（QingSongScan，包名
> `com.openscan.docscan`）各版本。本文是工程事实说明，不构成法律意见；
> 上架应用商店前请交由法务复核并按平台要求补充联系与主体信息。

## 一、核心承诺

**扫描、增强、OCR、生成 PDF 全部在本机完成，不向任何服务器上传用户内容。**
App 不含账号系统、不含统计 SDK、不含崩溃上报、不含广告 SDK。

## 二、应用申请的权限与用途

| 权限 | 用途 | 是否可拒绝 |
|---|---|---|
| `CAMERA` | 拍照扫描文档 | 拒绝后无法拍照，相册导入仍可用 |
| `WRITE_EXTERNAL_STORAGE`（maxSdk 28） | 仅 Android 9 及以下：保存导出文件 | 系统按版本自动忽略 |
| `READ_EXTERNAL_STORAGE`（maxSdk 28） | 仅 Android 9 及以下：读取导入图片 | 同上 |
| `INTERNET` | **仅分发版**的「检查更新」：请求 GitHub Releases 公开 API 获取最新版本号 | 拒绝后仅不能检查更新，其余功能不受影响 |
| `REQUEST_INSTALL_PACKAGES` | 安装从 GitHub 下载的更新包 | 拒绝后仅不能自动安装更新 |

自用版构建不含检查更新逻辑。

## 三、数据流向明细

### 本机处理（不离开设备）

- 拍摄 / 导入的图片、裁剪与增强结果、OCR 文本、生成的 PDF。
- 上述内容仅存于应用私有目录与用户主动导出的公共目录
  （`Pictures/轻松扫描/`、`Download/轻松扫描/`）。
- OCR 由 ML Kit **bundled** 模型在设备端完成，不依赖 Google Play 服务，
  不产生任何网络请求。
- 卸载应用即删除其私有目录内的全部数据。

### 唯一的网络请求（分发版）

- `GET https://api.github.com/repos/<owner>/<repo>/releases/latest`
  （或镜像域名），仅读取公开的版本号与下载地址。
- 请求不携带设备标识、不携带用户内容、不携带任何个人数据。
- 下载更新包时同样只从 GitHub / 镜像获取文件。

### 不出现在任何请求中的内容

设备 IMEI / OAID、位置、通讯录、已扫描文档、OCR 结果、使用习惯。

## 四、第三方组件

随 App 分发的第三方组件清单见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。
其中 ML Kit 为 Google 专有组件（受 ML Kit Terms 约束），但其 bundled
模型在设备端本地推理；其余均为 Apache-2.0 开源组件。

## 五、儿童与敏感信息

应用不面向儿童定向设计，不采集任何个人身份信息，因此不存在儿童数据
与敏感个人信息的处理行为。

## 六、联系方式

仓库 Issue：https://github.com/zwhapp/qingsongsaomiao/issues
