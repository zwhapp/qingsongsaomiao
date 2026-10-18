# v1.3.0 迭代验证：
#  A. 去阴影"文字区背景发深" → 加大背景估计核 + 百分位提白
#  B. 黑白清晰度 → CLAHE + USM + Sauvola(k) 组合
import numpy as np, cv2, os

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro/tools"


def estimate_bg(x, k):
    if k % 2 == 0:
        k += 1
    bg = cv2.morphologyEx(x, cv2.MORPH_CLOSE,
                          cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    return cv2.GaussianBlur(bg, (0, 0), k / 2.0)


def percentile_white(img, p=97.0, target=252.0):
    """把 p 分位灰度拉伸到 target（纸面提白），整幅线性缩放。"""
    v = np.percentile(img, p)
    if v <= 1:
        return img
    scale = target / v
    # 只提亮不压暗
    if scale <= 1.0:
        return img
    return np.clip(img.astype(np.float32) * scale, 0, 255).astype(np.uint8)


def de_shadow(bgr, k, use_pct):
    x = bgr.astype(np.float32)
    bg = np.maximum(estimate_bg(x, k), 1.0)
    n = np.clip(x / bg * 255.0, 0, 255).astype(np.uint8)
    if use_pct:
        n = percentile_white(n)
    return n


def stats_dark(bgr):
    """文字区（暗像素密集处）的平均背景亮度——衡量"背景发深"。"""
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    # 用大核闭运算得到"文字遮罩"：文字所在块
    m = cv2.morphologyEx(g, cv2.MORPH_CLOSE,
                         cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (75, 75)))
    text_mask = (m > 200) & (g < 200)     # 纸面里的文字
    outside = (m > 200) & (g >= 200)      # 纸面空白
    return (float(np.mean(g[text_mask])) if text_mask.any() else 0,
            float(np.mean(g[outside])) if outside.any() else 0)


img = cv2.imread(os.path.join(D, "book2_raw.jpg"))
big = cv2.resize(img, None, fx=4000 / max(img.shape[:2]), fy=4000 / max(img.shape[:2]),
                 interpolation=cv2.INTER_CUBIC)
longest = max(big.shape[:2])
print(f"模拟页面 {big.shape[1]}x{big.shape[0]}  longest={longest}")
print()
print("=== A. 去阴影：背景核 & 百分位提白 ===")
for label, k, pct in [
    ("现行 k=151 无提白", 151, False),
    ("核 267 无提白", 267, False),
    ("核 151 + 提白", 151, True),
    ("核 267 + 提白", 267, True),
]:
    out = de_shadow(big, k, pct)
    t, o = stats_dark(out)
    print(f"  {label:20} 文字区均亮 {t:6.1f}  空白区均亮 {o:6.1f}  纸面/文字对比 {o-t:6.1f}")
    cv2.imwrite(os.path.join(D, f"ds_{label.replace(' ', '_').replace('=', '')}.jpg"), out)
print()
print("=== B. 黑白清晰度组合 ===")
norm = de_shadow(big, 267, True)
gray = cv2.cvtColor(norm, cv2.COLOR_BGR2GRAY)


def sauvola(g, win, kk=0.2):
    f = g.astype(np.float32)
    m = cv2.boxFilter(f, -1, (win, win), borderType=cv2.BORDER_REFLECT)
    m2 = cv2.boxFilter(f * f, -1, (win, win), borderType=cv2.BORDER_REFLECT)
    std = np.sqrt(np.maximum(m2 - m * m, 0))
    return ((f > m * (1.0 + kk * (std / 128.0 - 1.0))) * 255).astype(np.uint8)


def usm(g, amount):
    b = cv2.GaussianBlur(g, (0, 0), 1.2)
    return np.clip(cv2.addWeighted(g, 1 + amount, b, -amount, 0), 0, 255).astype(np.uint8)


def frags(b):
    n, _, st, _ = cv2.connectedComponentsWithStats(cv2.bitwise_not(b), 8)
    return sum(1 for i in range(1, n) if 1 <= st[i, cv2.CC_STAT_AREA] <= 6)


win = max(25, min(longest // 30, 201))
win += 0 if win % 2 else 1
clahe = cv2.createCLAHE(2.0, (8, 8))
variants = {
    "现行(CLAHE+Sauvola)": cv2.medianBlur(sauvola(clahe.apply(gray), win), 3),
    "+USM0.5前": cv2.medianBlur(sauvola(clahe.apply(usm(gray, 0.5)), win), 3),
    "+USM0.5前+k0.18": cv2.medianBlur(sauvola(clahe.apply(usm(gray, 0.5)), win, 0.18), 3),
    "+USM0.5前+加粗1px": cv2.dilate(cv2.medianBlur(sauvola(clahe.apply(usm(gray, 0.5)), win), 3),
                                    cv2.getStructuringElement(cv2.MORPH_RECT, (2, 2))),
}
for name, b in variants.items():
    black = np.count_nonzero(b == 0) / b.size * 100
    print(f"  {name:20} 碎块 {frags(b):5d}  黑占比 {black:5.2f}%")
    cv2.imwrite(os.path.join(D, f"bw_{name.replace('+','p').replace('.','_')}.png"), b)
