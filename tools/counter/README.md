# 轻松扫描 · APK 下载计数器（自建后端）

把资料库介绍页的「访问次数」升级为**真正的 APK 下载次数**。

APK 托管在外部网盘（r4.sgwbox.com），静态页面本身无法感知「谁下载了」。
因此需要一个极轻量的自建后端：下载按钮 → 后端 `/dl`（计数 +1 后 302 跳转到真实文件）
→ 后端 `/count` 供页面读取展示。

- 后端挂了：页面自动降级为「直链下载」，用户照样能下，只是不计数。
- 计数口径：**通过本接口发起的下载次数**（点击 / 扫码都算一次），对外部托管文件是诚实的最佳近似。

---

## 方案 A（推荐，最快上线）：Cloudflare Workers + KV

免费、无需信用卡、一条命令部署，KV 自带持久化。

```bash
# 1. 安装并登录 wrangler（需 Cloudflare 账号，邮箱注册即可）
npm i -g wrangler
wrangler login

# 2. 创建 KV 命名空间，记下输出的 id
wrangler kv namespace create COUNTER
# 例：{ "id": "3a4b5c6d7e8f...", ... }

# 3. 把上面 id 填进 wrangler.toml 的 id 字段

# 4. 部署
wrangler deploy
# 部署完成后会得到一个地址，如 https://qingsong-dl-counter.<sub>.workers.dev
```

部署后把这个 `https://...workers.dev` 地址告诉 Agent，由它写回介绍页的 `BACKEND` 常量，
后端即生效（页面计数显示「APK 下载 X 次」）。

> 国内可达性：Cloudflare 在大陆偶有慢/被墙。若你的用户主要在国内容量敏感，
> 建议在 CF 控制台给该 Worker 绑一个**已备案的国内可达自定义域名**（或走方案 B）。

---

## 方案 B（国内最稳）：腾讯云 SCF / 阿里云 FC

面向国内容量最佳，但需要云账号与少量配置。

要点：
- 函数入口复用 `core.mjs` 的 `makeHandler`，把 `store` 换成**带持久化的存储**
  （SCF/FC 本身无状态，不能直接用内存）：
  - 腾讯云：SCF + **云 Redis**（或 COS 对象存一个计数文件）；
  - 阿里云：FC + **Tablestore / Redis**。
- 用 API 网关 / HTTP 触发，对外暴露 `https://<你的域名>/dl` 与 `/count`。
- 把该域名作为 `BACKEND` 写回页面即可。

`core.mjs` 与运行环境无关，仅需 `store` 满足 `async get / async put`，
因此同一份逻辑可直接对接任意云函数 + 任意持久化后端，无需改写业务代码。

---

## 本地自测

```bash
node local-test.mjs
# 期望：8 通过, 0 失败（验证 /dl 自增+302、/count 读值、CORS、/health、404）
```

---

## 文件说明

| 文件 | 作用 |
|---|---|
| `core.mjs` | 与运行环境无关的计数核心（增/读/探活），被 Worker 与云函数复用 |
| `worker.js` | Cloudflare Workers 入口，绑定 KV `COUNTER` |
| `wrangler.toml` | Workers 部署配置（需填 KV id） |
| `local-test.mjs` | 本地逻辑测试 |
