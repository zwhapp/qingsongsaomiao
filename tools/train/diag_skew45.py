# 验证 skew 检测强化：窗口 ±20° → ±45°，长度加权中位数 → 主导方向加权。
# 对比新旧算法在本例图 + 5 张基准图上的角度，确保不回归。
import os, math
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))
T = os.path.join(PROJ, "tools")
BUG = os.path.join(PROJ, "_bug151")


def prep(img, skew_max_edge=1000):
    h, w = img.shape[:2]
    k = min(1.0, skew_max_edge / max(h, w))
    work = cv2.resize(img, (max(int(w * k), 1), max(int(h * k), 1))) if k < 1.0 else img
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    bin_ = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                 cv2.THRESH_BINARY_INV, 31, 15.0)
    klen = int(min(40.0, max(8.0, work.shape[1] / 30.0)))
    ker = cv2.getStructuringElement(cv2.MORPH_RECT, (klen, 1))
    bin_ = cv2.morphologyEx(bin_, cv2.MORPH_CLOSE, ker)
    return bin_, work.shape[1]


def hough(bin_, w):
    lines = cv2.HoughLinesP(bin_, 1, math.pi / 180, 60,
                            minLineLength=w / 6.0, maxLineGap=20.0)
    out = []
    if lines is None:
        return out
    for x1, y1, x2, y2 in np.asarray(lines).reshape(-1, 4):
        ang = math.degrees(math.atan2(y2 - y1, x2 - x1))
        out.append((ang, math.hypot(x2 - x1, y2 - y1)))
    return out


def old_alg(cands, win=20.0):
    sel = [(a, l) for a, l in cands if abs(a) <= win]
    if not sel:
        return 0.0
    sel.sort(key=lambda t: t[0])
    total = sum(l for _, l in sel)
    acc = 0.0
    for a, l in sel:
        acc += l
        if acc >= total / 2:
            return a
    return sel[-1][0]


def new_alg(cands, win=45.0):
    sel = [(a, l) for a, l in cands if abs(a) <= win]
    if not sel:
        return 0.0
    # 主导方向：4° 一格的长度直方图，取峰值格 ±8° 内的线做长度加权中位数
    peak = max(range(int(-win / 4), int(win / 4)),
               key=lambda i: sum(l for a, l in sel if int(a // 4) == i))
    cluster = [(a, l) for a, l in sel if abs(a - (peak * 4 + 2)) <= 8]
    cluster.sort(key=lambda t: t[0])
    total = sum(l for _, l in cluster)
    acc = 0.0
    for a, l in cluster:
        acc += l
        if acc >= total / 2:
            return a
    return cluster[-1][0]


IMAGES = [(os.path.join(BUG, "input.jpg"), "bug151(斜15°)")]
for n in ["book2_raw.jpg", "photo_moved.jpg", "photo_full.jpg",
          "text_photo_moved.jpg", "text_photo_full.jpg"]:
    p = os.path.join(T, n)
    if os.path.exists(p):
        IMAGES.append((p, n))

print(f"{'image':24} {'旧(±20)':>9} {'新(±45)':>9}")
print("-" * 48)
for p, name in IMAGES:
    img = cv2.imread(p)
    if img is None:
        continue
    bin_, w = prep(img)
    cands = hough(bin_, w)
    o, n = old_alg(cands), new_alg(cands)
    flag = "  ← 变化" if abs(o - n) > 1.5 else ""
    print(f"{name:24} {o:8.2f}° {n:8.2f}°{flag}")
