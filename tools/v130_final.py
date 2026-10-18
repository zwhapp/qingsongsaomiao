# v1.3.0 端到端验证：完全复刻 Kotlin ScanEnhancer 的三种模式
#   1) 去阴影：一级背景核 + 二级平坦化 + 提白 + Lab-CLAHE + USM
#   2) 灰度  ：同上后转灰 + 提白 + CLAHE
#   3) 黑白  ：同上后 Sauvola(CLAHE 预处理) + median3
# 度量：
#   纸面落差   = 空白区局部纸面 − 文字密区局部纸面（越接近 0 越好）
#   碎块       = 面积 1..6 的连通白块（笔画断裂指标，越少越好）
#   黑占比     = 二值图黑像素百分比（过高说明背景被抹黑）
import numpy as np, cv2, os, time

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro/tools"
OUT = os.path.join(D, "v130out")
os.makedirs(OUT, exist_ok=True)

BG_EDGE, BG_KDIV, BG_KMAX, BG_SDIV = 1000, 12, 151, 6.0
RS_EDGE, RS_KDIV, RS_KMAX, RS_SDIV = 600, 8, 151, 8.0
RS_GAIN_MIN, RS_GAIN_MAX = 1.0, 1.5
PAPER_TARGET, PAPER_PCT, LIFT_MAX = 252.0, 85.0, 1.25
CLAHE_CLIP = 2.0
COLOR_LIFT = 3.0
SAUVOLA_K, SAUVOLA_R = 0.28, 128.0


def est_bg(x, edge, kdiv, kmax, sdiv, sdiv_ratio):
    h, w = x.shape[:2]
    longest = max(h, w)
    scaled = longest > edge
    small = cv2.resize(x, None, fx=edge / float(longest), fy=edge / float(longest),
                       interpolation=cv2.INTER_AREA) if scaled else x.copy()
    sl = max(small.shape[:2])
    k = int(np.clip(sl // kdiv, 31, kmax))
    if k % 2 == 0:
        k += 1
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    bg = cv2.morphologyEx(small, cv2.MORPH_CLOSE, ker)
    bg = cv2.GaussianBlur(bg, (0, 0), max(k / sdiv, 3.0))
    if scaled:
        bg = cv2.resize(bg, (w, h), interpolation=cv2.INTER_LINEAR)
    return np.maximum(bg, 1.0), k


def pct_white(img, is_gray=False):
    g = img if is_gray else cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    v = np.percentile(g, PAPER_PCT)
    if v <= 1:
        return img
    gain = min(PAPER_TARGET / v, LIFT_MAX)
    if gain <= 1.0:
        return img
    return np.clip(img.astype(np.float32) * gain, 0, 255).astype(np.uint8)


def clahe_bgr(bgr, clip=CLAHE_CLIP):
    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2Lab)
    l, a, b = cv2.split(lab)
    l = cv2.createCLAHE(clip, (8, 8)).apply(l)
    return cv2.cvtColor(cv2.merge([l, a, b]), cv2.COLOR_Lab2BGR)


def clahe_gray(g, clip=CLAHE_CLIP):
    return cv2.createCLAHE(clip, (8, 8)).apply(g)


def usm(g_or_bgr, amount=0.5):
    b = cv2.GaussianBlur(g_or_bgr, (0, 0), 1.2)
    return np.clip(cv2.addWeighted(g_or_bgr, 1 + amount, b, -amount, 0), 0, 255).astype(np.uint8)


def normalize(bgr, two_scale=True):
    """一级分除 + 二级平坦化，返回 uint8 BGR。"""
    x = bgr.astype(np.float32)
    bg1, k1 = est_bg(x, BG_EDGE, BG_KDIV, BG_KMAX, BG_SDIV, 6.0)
    n = np.clip(x / bg1 * 255.0, 0, 255)
    k2 = 0
    if two_scale:
        bg2, k2 = est_bg(n, RS_EDGE, RS_KDIV, RS_KMAX, RS_SDIV, 8.0)
        m = float(bg2.mean())
        gain = np.clip(m / bg2, RS_GAIN_MIN, RS_GAIN_MAX)
        n = np.clip(n * gain, 0, 255)
    return n.astype(np.uint8), k1, k2


def sauvola(g, win, kk=SAUVOLA_K):
    f = g.astype(np.float32)
    m = cv2.boxFilter(f, -1, (win, win), borderType=cv2.BORDER_REFLECT)
    m2 = cv2.boxFilter(f * f, -1, (win, win), borderType=cv2.BORDER_REFLECT)
    std = np.sqrt(np.maximum(m2 - m * m, 0))
    return ((f > m * (1.0 + kk * (std / SAUVOLA_R - 1.0))) * 255).astype(np.uint8)


def enhance(bgr, mode):
    n, _, _ = normalize(bgr, two_scale=True)
    if mode == "ORIGINAL":
        return n
    if mode == "DE_SHADOW":
        n = pct_white(n)
        n = np.clip(n.astype(np.float32) + COLOR_LIFT, 0, 255).astype(np.uint8)
        n = clahe_bgr(n)
        return usm(n, 0.5)
    if mode == "GRAYSCALE":
        g = cv2.cvtColor(n, cv2.COLOR_BGR2GRAY)
        g = pct_white(g, True)
        g = clahe_gray(g)
        return cv2.cvtColor(g, cv2.COLOR_GRAY2BGR)
    if mode == "BLACK_WHITE":
        g = cv2.cvtColor(n, cv2.COLOR_BGR2GRAY)
        g = pct_white(g, True)
        # ★ v1.3.0 终版：不做 CLAHE（会加粗笔画、放大纸纹），窗口 L/65
        win = int(np.clip(max(g.shape[:2]) // 65, 15, 201))
        win += 0 if win % 2 else 1
        b = sauvola(g, win, SAUVOLA_K)
        return cv2.cvtColor(cv2.medianBlur(b, 3), cv2.COLOR_GRAY2BGR)
    raise ValueError(mode)


def paper_gap(bgr, tile=96):
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    H, W = g.shape
    dense, blank = [], []
    for y in range(0, H - tile + 1, tile):
        for x0 in range(0, W - tile + 1, tile):
            t = g[y:y + tile, x0:x0 + tile]
            ink = float((t < 128).mean())
            paper = float(np.percentile(t, 90))
            if paper < 100:
                continue
            if 0.05 <= ink <= 0.35:
                dense.append(paper)
            elif ink < 0.01:
                blank.append(paper)
    if not dense or not blank:
        return 0.0, 0.0
    return float(np.mean(dense)), float(np.mean(blank))


def frags(b):
    b = cv2.cvtColor(b, cv2.COLOR_BGR2GRAY) if b.ndim == 3 else b
    n, _, st, _ = cv2.connectedComponentsWithStats(cv2.bitwise_not(b), 8)
    return sum(1 for i in range(1, n) if 1 <= st[i, cv2.CC_STAT_AREA] <= 6)


for name in ["book2_raw.jpg", "text_photo_full.jpg", "photo_full.jpg"]:
    p = os.path.join(D, name)
    if not os.path.exists(p):
        continue
    img = cv2.imread(p)
    f = 4000 / max(img.shape[:2])
    big = cv2.resize(img, None, fx=f, fy=f, interpolation=cv2.INTER_CUBIC)
    print(f"\n===== {name}  {big.shape[1]}x{big.shape[0]} =====")
    _, k1, k2 = normalize(big, True)
    print(f"  背景核：一级 {k1}@1000  二级 {k2}@600  (等效 4000px: "
          f"{k1*4000//1000} / {k2*4000//600})")
    for mode in ["DE_SHADOW", "GRAYSCALE", "BLACK_WHITE"]:
        t0 = time.time()
        out = enhance(big, mode)
        dt = (time.time() - t0) * 1000
        d, b = paper_gap(out)
        line = (f"  {mode:12} 文字区纸面 {d:6.1f}  空白区纸面 {b:6.1f}  "
                f"落差 {b-d:5.1f}")
        if mode == "BLACK_WHITE":
            line += f"  碎块 {frags(out):5d}  黑占比 {np.count_nonzero(out == 0)/out.size*100:5.2f}%"
        print(line + f"   {dt:5.0f}ms")
        cv2.imwrite(os.path.join(OUT, f"{mode}_{name}"), out)
print(f"\n输出目录: {OUT}")
