// 与运行环境无关的计数核心逻辑。
// store 只需提供 async get(key) / async put(key, value)。
//  - Cloudflare Workers 中 store = env.COUNTER（KV 命名空间）
//  - 本地测试 / 自建服务中可换成 Map / Redis / 文件 等
//
// 设计：单计数器 "downloads"。
//  - GET /dl     -> 计数 +1 然后 302 跳转到真实文件分享页（下载按钮/扫码都走这里）
//  - GET /count  -> 返回 { count: N }（页面读取展示）
//  - GET /health -> 返回 ok（页面加载时探活，不可达则降级为直链）
// 不需要 /create：首访 get 返回 null 视为 0，第一次 /dl 写入 1。

const KEY = "downloads";

export function makeHandler({ store, shareUrl }) {
  const getN = async () => {
    const v = await store.get(KEY);
    if (v === null || v === undefined) return 0;
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
  };

  return {
    async handle(pathname) {
      if (pathname === "/dl") {
        const n = (await getN()) + 1;
        await store.put(KEY, n);
        return new Response(null, {
          status: 302,
          headers: { Location: shareUrl, "cache-control": "no-store" },
        });
      }
      if (pathname === "/count") {
        const n = await getN();
        return new Response(JSON.stringify({ count: n }), {
          status: 200,
          headers: {
            "content-type": "application/json; charset=utf-8",
            "cache-control": "no-store",
            "access-control-allow-origin": "*",
          },
        });
      }
      if (pathname === "/health") {
        return new Response("ok", {
          status: 200,
          headers: { "access-control-allow-origin": "*" },
        });
      }
      return new Response("not found", {
        status: 404,
        headers: { "access-control-allow-origin": "*" },
      });
    },
  };
}
