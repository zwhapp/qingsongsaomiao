# -*- coding: utf-8 -*-
"""黑白二值化改进实验：解决「屏幕拍摄细笔画字断裂」。

当前线上链路（复刻自 ScanEnhancer.kt）：
  背景归一 → 二级平坦化 → 灰度提白 → Sauvola(k=0.28) → medianBlur(3)

问题：medianBlur(3) 把 1~2px 细笔画当噪声抹断；单一 k 值在
「背景干净」与「淡笔画保留」之间没有双赢解。

候选方案：
  A 现行            Sauvola k=0.28 + median3
  B 仅去 median3    Sauvola k=0.28 + CC面积去噪(<=3px)
  C 迟滞            kStrong=0.34 / kWeak=0.18，连通传递 + CC去噪
  D 迟滞+松         kStrong=0.30 / kWeak=0.14，连通传递 + CC去噪

指标：黑占比、黑连通域个数（同等黑占比下 CC 越多 = 笔画越碎）、
      以及文字密集区放大对比图。
"""
import cv2
import numpy as np
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "_bin_v2")
os.makedirs(OUT, exist_ok=True)
PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))


# ---------- 前置链复刻（与 ScanEnhancer.kt 一致） ----------

def estimate_background(bgr, edge=1000, kdiv=12, kmax=151, sigdiv=6.0):
    h, w = bgr.shape[:2]
    longest = max(h, w)
    f = min(1.0, edge / longest)
    small = cv2.resize(bgr, (max(int(w * f), 1), max(int(h * f), 1)),
                       interpolation=cv2.INTER_AREA) if f < 1.0 else bgr.copy()
    sl = max(small.shape[:2])
    k = int(np.clip(sl // kdiv, 31, kmax))
    if k % 2 == 0:
        k += 1
    kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    small = cv2.morphologyEx(small, cv2.MORPH_CLOSE, kern)
    small = cv2.GaussianBlur(small, (0, 0), max(k / sigdiv, 3.0))
    out = cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR) if f < 1.0 else small
    return np.maximum(out, 1.0)


def flatten_residual(bgr, edge=600, kdiv=8, kmax=151, sigdiv=8.0, max_gain=1.5):
    f32 = bgr.astype(np.float32)
    field = estimate_background(f32, edge, kdiv, kmax, sigdiv)
    m = float(field.mean())
    gain = np.clip(m / np.maximum(field, 1e-6), 1.0, max_gain).astype(np.float32)
    return np.clip(f32 * gain, 0, 255).astype(np.uint8)


def lift_paper_white_gray(gray, pctl=85.0, target=252.0, max_gain=1.25):
    small = cv2.resize(gray, (160, 160), interpolation=cv2.INTER_AREA)
    p = float(np.percentile(small, pctl))
    if p <= 1.0:
        return gray
    gain = min(target / p, max_gain)
    if gain > 1.0:
        gray = np.clip(gray.astype(np.float32) * gain, 0, 255).astype(np.uint8)
    return gray


def sauvola(gray, k=0.28, r=128.0, win_div=65, lo=15, hi=201):
    """与 Kotlin adaptiveBinarize 同构：两次 boxFilter 求 mean/std。"""
    g = gray.astype(np.float32)
    longest = max(gray.shape)
    win = int(np.clip(longest // win_div, lo, hi))
    if win % 2 == 0:
        win += 1
    mean = cv2.boxFilter(g, -1, (win, win))
    mean_sq = cv2.boxFilter(g * g, -1, (win, win))
    std = np.sqrt(np.maximum(mean_sq - mean * mean, 0))
    t = mean * (1 - k) + mean * std * (k / r)
    out = np.where(g > t, 255, 0).astype(np.uint8)
    return out


def cc_denoise(binary, min_area=4):
    """删掉面积 < min_area 的孤立黑噪点（替代 median3，不伤细线）。
    ★ 注意：黑噪点 = 墨掩膜(invert 后)里的小前景；直接对纸=255 的图做
    CC 统计的是纸块，删小前景是 no-op（首版踩坑）。"""
    ink = (binary == 0).astype(np.uint8)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(ink, 8)
    out = binary.copy()
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] < min_area:
            out[lab == i] = 255
    return out


def hysteresis(gray, k_strong=0.34, k_weak=0.18, r=128.0, win_div=65,
               lo=15, hi=201, min_area=4):
    """迟滞二值化：低阈值(k_weak)抓笔画主体，高阈值(k_strong)定种子，
    只保留与种子连通的弱墨迹 —— 笔画连上、背景噪点进不来。

    尾部加**开-闭滤波**（v1.5.6）：屏幕拍摄的摩尔纹是 1~3px 暗细线，
    与文字在亮度上不可分（实测波纹区 std≈文字区 std），只能靠形态结构
    区分 —— close 删除宽度 < 核 的暗细线，open 恢复笔画间的白间隙，
    核 = 长边/1000（夹取 3~6）。实测 @4000px：摩尔纹页黑占比 25.1%→16.1%，
    正常页（text_photo/book2）完全无损。"""
    g = gray.astype(np.float32)
    longest = max(gray.shape)
    win = int(np.clip(longest // win_div, lo, hi))
    if win % 2 == 0:
        win += 1
    mean = cv2.boxFilter(g, -1, (win, win))
    mean_sq = cv2.boxFilter(g * g, -1, (win, win))
    std = np.sqrt(np.maximum(mean_sq - mean * mean, 0))
    t_strong = mean * (1 - k_strong) + mean * std * (k_strong / r)
    t_weak = mean * (1 - k_weak) + mean * std * (k_weak / r)
    strong = (g < t_strong).astype(np.uint8)
    weak = (g < t_weak).astype(np.uint8)
    n, lab = cv2.connectedComponents(weak, 8)
    seed_labels = np.unique(lab[strong > 0])
    keep = np.isin(lab, seed_labels)
    out = np.where(keep, 0, 255).astype(np.uint8)
    out = cc_denoise(out, min_area)
    # 开-闭滤波去摩尔纹（核随分辨率缩放）
    mk = int(np.clip(max(out.shape) // 1000, 3, 6))
    kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (mk, mk))
    out = cv2.morphologyEx(out, cv2.MORPH_OPEN, kern)
    out = cv2.morphologyEx(out, cv2.MORPH_CLOSE, kern)
    return out


# ---------- 评测 ----------

def prep_gray(path):
    bgr = cv2.imread(path)
    assert bgr is not None, path
    bg = estimate_background(bgr)
    norm = np.clip(bgr.astype(np.float32) / np.maximum(bg, 1) * 255, 0, 255).astype(np.uint8)
    norm = flatten_residual(norm)
    gray = cv2.cvtColor(norm, cv2.COLOR_BGR2GRAY)
    return lift_paper_white_gray(gray)


def methods(gray):
    a = cv2.medianBlur(sauvola(gray, 0.28), 3)
    b = cc_denoise(sauvola(gray, 0.28))
    c = hysteresis(gray, 0.34, 0.18)
    d = hysteresis(gray, 0.30, 0.14)
    return [("A现行", a), ("B去median", b), ("C迟滞34/18", c), ("D迟滞30/14", d)]


def crop_text_roi(gray, binimg, size=(520, 260)):
    """取黑像素最密集的窗口，方法间用同一 ROI。"""
    best, bx, by = -1, 0, 0
    ys, xs = np.where(binimg == 0)
    if len(xs) == 0:
        return None
    # 粗网格找最密块
    step = 40
    h, w = gray.shape
    for y in range(0, h - size[1], step):
        for x in range(0, w - size[0], step):
            cnt = int(((xs >= x) & (xs < x + size[0]) & (ys >= y) & (ys < y + size[1])).sum())
            if cnt > best:
                best, bx, by = cnt, x, y
    return bx, by, size[0], size[1]


CASES = [
    (os.path.join(PROJ, "_bug151", "input.jpg"), "屏幕报告(主案例)"),
    (os.path.join(PROJ, "tools", "book2_raw.jpg"), "book2(书页)"),
    (os.path.join(PROJ, "tools", "text_photo_full.jpg"), "text_photo(打印页)"),
    (os.path.join(PROJ, "tools", "photo_full.jpg"), "photo(照片)"),
]

print(f"{'案例':16} {'方法':12} {'黑占比':>8} {'黑CC数':>10}")
rows = []
for path, name in CASES:
    if not os.path.exists(path):
        print("MISSING", path)
        continue
    gray = prep_gray(path)
    ms = methods(gray)
    roi = crop_text_roi(gray, ms[0][1])
    tiles = []
    for label, b in ms:
        ink = float((b == 0).mean())
        n, _ = cv2.connectedComponents((b == 0).astype(np.uint8), 8)
        print(f"{name:16} {label:12} {ink*100:7.2f}% {n:10d}")
        rows.append((name, label, ink, n))
        if roi is not None:
            x, y, w2, h2 = roi
            tile = cv2.resize(b[y:y+h2, x:x+w2], None, fx=1.6, fy=1.6,
                              interpolation=cv2.INTER_NEAREST)
            tiles.append(cv2.putText(tile, label, (8, 28),
                                     cv2.FONT_HERSHEY_SIMPLEX, 0.8, 128, 2))
    if len(tiles) == 4:
        sep = np.full((8, tiles[0].shape[1] * 2), 128, np.uint8)
        grid = np.vstack([np.hstack([tiles[0], tiles[1]]),
                          sep, np.hstack([tiles[2], tiles[3]])])
        fn = os.path.join(OUT, f"cmp_{name.split('(')[0]}.jpg")
        cv2.imwrite(fn, grid)
        print("  →", fn)
    print()

print("汇总：同等黑占比下 CC 数显著下降 = 笔画更连贯。")
