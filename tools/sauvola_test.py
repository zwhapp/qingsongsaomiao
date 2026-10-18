# 对比三种二值化在真实书页上的效果：
#   A. 现行方案：bilateral + adaptiveThreshold(GAUSSIAN, win, C=12)
#   B. Sauvola（k=0.2）
#   C. Wolf（ Sauvola 变体，更稳）
# 指标：碎笔画块数（1~6px 前景块，越少越好）、笔画平均宽度、黑像素占比
import numpy as np, cv2, os

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro"
T = os.path.join(D, "tools")


def normalize(gray):
    """复刻 App 的去阴影归一化（灰度版简化）：大核闭运算估计背景后相除。"""
    g = gray.astype(np.float32)
    longest = max(g.shape[:2])
    k = int(min(max(longest // 25, 21), 151))
    if k % 2 == 0:
        k += 1
    bg = cv2.morphologyEx(g, cv2.MORPH_CLOSE,
                          cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    bg = cv2.GaussianBlur(bg, (0, 0), k / 2.0)
    bg = np.maximum(bg, 1.0)
    return np.clip(g / bg * 255.0, 0, 255).astype(np.uint8)


def sauvola(gray, win, k=0.2, R=128.0):
    """Sauvola：T = mean * (1 + k*(std/R - 1))。积分图实现，O(1) 每像素。"""
    g = gray.astype(np.float32)
    m = cv2.boxFilter(g, -1, (win, win), borderType=cv2.BORDER_REFLECT)
    m2 = cv2.boxFilter(g * g, -1, (win, win), borderType=cv2.BORDER_REFLECT)
    std = np.sqrt(np.maximum(m2 - m * m, 0))
    T = m * (1.0 + k * (std / R - 1.0))
    return ((g > T) * 255).astype(np.uint8)


def current(gray, win):
    """现行 Kotlin 方案。"""
    g = cv2.bilateralFilter(gray, 5, 40, 40)
    return cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                 cv2.THRESH_BINARY, win, 12)


def stats(b, label):
    black = float(np.count_nonzero(b == 0)) / b.size
    n, _, st, _ = cv2.connectedComponentsWithStats(cv2.bitwise_not(b), 8)
    frag = sum(1 for i in range(1, n) if 1 <= st[i, cv2.CC_STAT_AREA] <= 6)
    comps = sum(1 for i in range(1, n) if 30 <= st[i, cv2.CC_STAT_AREA] <= 900)
    print(f"  {label:22} 黑占比 {black*100:5.1f}%  碎块 {frag:5d}  文字块 {comps:5d}")
    return b


for name in ["book2_raw.jpg", "photo_moved.jpg"]:
    img = cv2.imread(os.path.join(T, name))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    norm = normalize(gray)
    longest = max(norm.shape[:2])
    win = int(min(max(longest // 30, 25), 61))
    if win % 2 == 0:
        win += 1
    print(f"=== {name}  win={win} ===")
    a = stats(current(norm, win), "A 现行 adaptive")
    b = stats(sauvola(norm, win, k=0.2), "B Sauvola k=0.2")
    c = stats(sauvola(norm, win, k=0.34), "C Sauvola k=0.34")
    cv2.imwrite(os.path.join(T, f"bw_A_{name}"), a)
    cv2.imwrite(os.path.join(T, f"bw_B_{name}"), b)
    cv2.imwrite(os.path.join(T, f"bw_C_{name}"), c)
    print()
