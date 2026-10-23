import { makeHandler } from "./core.mjs";

// APK 真实下载地址（r4.sgwbox 文件分享页）。
// 说明：count 统计「通过本后端发起的下载次数」。对外部托管的文件，
// 这是诚实可用的「真实下载发起次数」近似（点击/扫码都经过本接口）。
const SHARE_URL =
  "https://r4.sgwbox.com/fileshare/?tcid=n3686A123003D8&code=efkrcBI7E_2JXYwcujCpnQACxnACM9JD3lpiz_8xcdSpABcL&cmd=list";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const handler = makeHandler({ store: env.COUNTER, shareUrl: SHARE_URL });
    return handler.handle(url.pathname, request);
  },
};
