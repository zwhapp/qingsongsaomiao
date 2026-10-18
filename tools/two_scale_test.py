# v1.3.0 迭代验证（第二轮）：
#   去阴影"文字密集处背景发深" → 两级背景平坦化（first pass 去光照 / second pass 去文字块残余）
#
# 度量口径修正：不再用"文字区所有像素均值"（会被笔画本身拉黑），
# 改为**分块局部高分位**估计纸面亮度，再按文字密度分组比较。
import numpy as np, cv2, os

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro/tools"


# ---------- 与 Kotlin 等价的背景估计（低分辨率 + 形态学闭 + 高斯）----------
def estimate_bg(x, edge, k_div, k_max, sigma_div=6.0):
    """在长边 edge 上估计背景场，再放大回原尺寸。x 为 float32 BGR。"""
    h, w = x.shape[:2]
    longest = max(h, w)
    scaled = longest > edge
    if scaled:
        f = edge / float(longest)
        small = cv2.resize(x, None, fx=f, fy=f, interpolation=cv2.INTER_AREA)
    else:
        small = x.copy()
    sl = max(small.shape[:2])
    k = int(np.clip(sl // k_div, 31, k_max))
    if k % 2 == 0:
        k += 1
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    bg = cv2.morphologyEx(small, cv2.MORPH_CLOSE, ker)
    bg = cv2.GaussianBlur(bg, (0, 0), max(k / sigma_div, 3.0))
    if scaled:
        bg = cv2.resize(bg, (w, h), interpolation=cv2.INTER_LINEAR)
    return np.maximum(bg, 1.0)


def percentile_white(img, p=85.0, target=252.0, max_gain=1.25):
    v = np.percentile(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY), p)
    if v <= 1:
        return img
    gain = min(target / v, max_gain)
    if gain <= 1.0:
        return img
    return np.clip(img.astype(np.float32) * gain, 0, 255).astype(np.uint8)


def local_contrast_bgr(bgr, clip=2.0):
    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2Lab)
    l, a, b = cv2.split(lab)
    l = cv2.createCLAHE(clip, (8, 8)).apply(l)
    return cv2.cvtColor(cv2.merge([l, a, b]), cv2.COLOR_Lab2BGR)


# ---------- 两级平坦化 ----------
def de_shadow_two_scale(bgr, mode="two", r_edge=800, r_kdiv=8, r_kmax=201):
    x = bgr.astype(np.float32)
    bg1 = estimate_bg(x, 1000, 12, 151, 6.0)
    n = np.clip(x / bg1 * 255.0, 0, 255)

    if mode == "two":
        bg2 = estimate_bg(n, r_edge, r_kdiv, r_kmax, 8.0)
        m = float(bg2.mean())
        n = np.clip(n / bg2 * m, 0, 255)

    n8 = n.astype(np.uint8)
    n8 = percentile_white(n8, 85.0, 252.0, 1.25)
    n8 = local_contrast_bgr(n8, 2.0)
    return n8


# ---------- 度量：分块局部高分位纸面亮度 ----------
def paper_gap(bgr, tile=96):
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    H, W = g.shape
    dense, blank = [], []
    for y in range(0, H - tile + 1, tile):
        for x0 in range(0, W - tile + 1, tile):
            t = g[y:y + tile, x0:x0 + tile]
            ink = float((t < 128).mean())
            paper = float(np.percentile(t, 90))
            if paper < 100:          # 整块都是暗的（非纸面），跳过
                continue
            if 0.05 <= ink <= 0.35:
                dense.append(paper)
            elif ink < 0.01:
                blank.append(paper)
    d = float(np.mean(dense)) if dense else 0.0
    b = float(np.mean(blank)) if blank else 0.0
    return d, b, len(dense), len(blank)


for name in ["book2_raw.jpg", "text_photo_full.jpg"]:
    path = os.path.join(D, name)
    if not os.path.exists(path):
        continue
    img = cv2.imread(path)
    f = 4000 / max(img.shape[:2])
    big = cv2.resize(img, None, fx=f, fy=f, interpolation=cv2.INTER_CUBIC)
    print(f"\n===== {name}  →  {big.shape[1]}x{big.shape[0]} =====")
    for mode, label in [("one", "单级(k=151@1000)"), ("two", "两级(+残余平坦化)")]:
        out = de_shadow_two_scale(big, mode)
        d, b, nd, nb = paper_gap(out)
        print(f"  {label:22} 文字区纸面 {d:6.1f}  空白区纸面 {b:6.1f}  "
              f"落差 {b - d:5.1f}   (块数 {nd}/{nb})")
        cv2.imwrite(os.path.join(D, f"ts_{mode}_{name}"), out)
