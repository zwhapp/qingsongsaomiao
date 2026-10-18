# -*- coding: utf-8 -*-
"""摩尔纹修复实验：迟滞二值化弱阈值把屏幕拍摄的低幅波纹整片吞入。

用户反馈（v1.5.5-selfuse-18 / v1.4.5-19 双版本）：黑白模式输出大片
「波纹状细小线段」。机理：
  屏幕拍摄 → 传感器像素栅格 × 屏幕像素栅格 → 低幅摩尔纹（灰度略低于纸面）
  迟滞弱阈值 kWeak=0.18 → T_weak 偏高 → 波纹像素被判「弱墨」
  → 与文字的 strong 种子连通 → 整片吞入。
旧方案 k=0.28 阈值严，波纹幅度恰好落在不判墨区间，所以以前没暴露。

候选：
  A 现行            迟滞 0.34/0.18
  B 弱阈值收紧      迟滞 0.34/0.22
  C 更紧            迟滞 0.34/0.24
  D 双调            迟滞 0.30/0.22   （强阈值也松一点：保住细笔画种子）
  E 灰度预中值      A + medianBlur(3)（二值化前削波纹幅度）
  F B + 灰度预中值

衡量：全图黑占比（波纹吞入会暴涨）、黑 CC 数、ROI 放大图（波纹区+文字区）。
"""
import cv2
import numpy as np
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "_moire")
os.makedirs(OUT, exist_ok=True)
PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))

import sys
sys.path.insert(0, os.path.join(HERE))
from diag_bin_hyst import (estimate_background, flatten_residual,  # noqa: E402
                           lift_paper_white_gray, sauvola, cc_denoise)


def prep_gray(path):
    bgr = cv2.imread(path)
    bg = estimate_background(bgr)
    norm = np.clip(bgr.astype(np.float32) / np.maximum(bg, 1) * 255, 0, 255).astype(np.uint8)
    norm = flatten_residual(norm)
    gray = cv2.cvtColor(norm, cv2.COLOR_BGR2GRAY)
    return lift_paper_white_gray(gray)


def hysteresis2(gray, k_strong, k_weak, median_pre=False, r=128.0, min_area=4):
    if median_pre:
        gray = cv2.medianBlur(gray, 3)
    g = gray.astype(np.float32)
    longest = max(gray.shape)
    win = int(np.clip(longest // 65, 15, 201))
    if win % 2 == 0:
        win += 1
    mean = cv2.boxFilter(g, -1, (win, win))
    ms = cv2.boxFilter(g * g, -1, (win, win))
    std = np.sqrt(np.maximum(ms - mean * mean, 0))
    ts = mean * (1 - k_strong) + mean * std * (k_strong / r)
    tw = mean * (1 - k_weak) + mean * std * (k_weak / r)
    strong = (g < ts).astype(np.uint8)
    weak = (g < tw).astype(np.uint8)
    # 传递闭包（迭代膨胀，与 Kotlin 端同语义）
    seed = strong.copy()
    kern = np.ones((3, 3), np.uint8)
    prev = -1
    for _ in range(64):
        seed = cv2.dilate(seed, kern)
        seed = cv2.bitwise_and(seed, weak)
        cnt = int(seed.sum())
        if cnt == prev:
            break
        prev = cnt
    out = np.where(seed > 0, 0, 255).astype(np.uint8)
    # CC 去噪：墨掩膜小前景
    ink = (out == 0).astype(np.uint8)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(ink, 8)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] < min_area:
            out[lab == i] = 255
    return out


SRC_MOIRE = os.path.join(OUT, "moire_src.jpg")
BASE = [
    (os.path.join(PROJ, "tools", "text_photo_full.jpg"), "text_photo"),
    (os.path.join(PROJ, "tools", "book2_raw.jpg"), "book2"),
]

METHODS = [
    ("A现34/18", 0.34, 0.18, False),
    ("B紧34/22", 0.34, 0.22, False),
    ("C更紧34/24", 0.34, 0.24, False),
    ("D双调30/22", 0.30, 0.22, False),
    ("E中值34/18", 0.34, 0.18, True),
    ("F中值34/22", 0.34, 0.22, True),
]

cases = [(SRC_MOIRE, "摩尔纹(主案例)")] + BASE
for path, name in cases:
    if not os.path.exists(path):
        print("MISSING", path)
        continue
    gray = prep_gray(path)
    print(f"== {name} ==")
    tiles = []
    for label, ks, kw, mp in METHODS:
        b = hysteresis2(gray, ks, kw, mp)
        ink = float((b == 0).mean())
        n, _ = cv2.connectedComponents((b == 0).astype(np.uint8), 8)
        print(f"  {label:10} 黑占比 {ink*100:6.2f}%  CC {n}")
        t = cv2.resize(b, None, fx=0.5, fy=0.5, interpolation=cv2.INTER_AREA)
        t = cv2.putText(t, label, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 1.0, 128, 2)
        tiles.append(t)
    # 2x3 拼图
    h_, w_ = tiles[0].shape[:2]
    row1 = np.hstack(tiles[:3])
    row2 = np.hstack(tiles[3:])
    sep = np.full((6, row1.shape[1]), 128, np.uint8)
    cv2.imwrite(os.path.join(OUT, f"moire_{name.split('(')[0]}.jpg"), np.vstack([row1, sep, row2]))
    print("  → 全图对比已保存")
    print()
