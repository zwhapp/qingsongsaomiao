import { makeHandler } from "./core.mjs";

// 内存版 store，仅用于本地验证核心逻辑（非 KV）。
function memStore() {
  const m = new Map();
  return {
    async get(k) { return m.has(k) ? m.get(k) : null; },
    async put(k, v) { m.set(k, v); },
  };
}

let pass = 0, fail = 0;
function ok(cond, msg) {
  if (cond) { pass++; console.log("  PASS:", msg); }
  else { fail++; console.log("  FAIL:", msg); }
}

const SHARE = "https://r4.sgwbox.com/fileshare/?tcid=x&code=y&cmd=list";
const handler = makeHandler({ store: memStore(), shareUrl: SHARE });

// 1. 初始 count 为 0
let r = await handler.handle("/count");
ok((await r.json()).count === 0, "初始下载计数为 0");

// 2. /dl 返回 302 且 Location 指向真实分享页
r = await handler.handle("/dl");
ok(r.status === 302, "/dl 返回 302");
ok(r.headers.get("Location") === SHARE, "/dl 302 跳转到真实分享页");

// 3. /dl 后 count 自增为 1
r = await handler.handle("/count");
ok((await r.json()).count === 1, "/dl 后计数=1");

// 4. 连续 5 次 /dl 计数累加
for (let i = 0; i < 5; i++) await handler.handle("/dl");
r = await handler.handle("/count");
ok((await r.json()).count === 6, "连续下载后计数=6");

// 5. /count 响应带 CORS 头（页面跨域 fetch 需要）
r = await handler.handle("/count");
ok(r.headers.get("access-control-allow-origin") === "*", "/count 带 CORS 头");

// 6. /health 健康探测
r = await handler.handle("/health");
ok(r.status === 200 && (await r.text()) === "ok", "/health 返回 ok");

// 7. 未知路径 404
r = await handler.handle("/x");
ok(r.status === 404, "未知路径返回 404");

console.log(`\n结果: ${pass} 通过, ${fail} 失败`);
process.exit(fail === 0 ? 0 : 1);
