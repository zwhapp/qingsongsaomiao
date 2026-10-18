# -*- coding: utf-8 -*-
"""分析摩尔纹区 vs 文字笔画区的灰度特征分布，找可分离的判据。

用户原图（moire_src.jpg 1600x2829）区域划分（手工标定）：
  波纹区：纸面中部无文字但波纹密布
  文字区：上部正文段落
  干净区：右下大片空白
特征：
  d_weak = mean - g          （低于局部均值的深度，波纹/淡笔画都在这区分）
  d_norm = (mean - g) / std  （按局部噪声归一）
  看 ROC 式分离度。
"""
import cv2
import numpy as np
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from diag_bin_hyst import estimate_background, flatten_residual, lift_paper_white_gray

bgr = cv2.imread(os.path.join(HERE, "_moire", "moire_src.jpg"))
h, w = bgr.shape[:2]
# 裁掉屏幕黑边（约 2%）
bgr = bgr[int(h*0.03):int(h*0.97), int(w*0.02):int(w*0.98)]

bg = estimate_background(bgr)
norm = np.clip(bgr.astype(np.float32) / np.maximum(bg, 1) * 255, 0, 255).astype(np.uint8)
norm = flatten_residual(norm)
gray = lift_paper_white_gray(cv2.cvtColor(norm, cv2.COLOR_BGR2GRAY))
g = gray.astype(np.float32)

H, W = gray.shape
win = int(np.clip(max(H, W) // 65, 15, 201))
if win % 2 == 0:
    win += 1
mean = cv2.boxFilter(g, -1, (win, win))
ms = cv2.boxFilter(g * g, -1, (win, win))
std = np.sqrt(np.maximum(ms - mean * mean, 0))

# 区域（按裁剪后比例）：y,x 都是 [0,1]
def roi(y0, y1, x0, x1):
    return (slice(int(H*y0), int(H*y1)), slice(int(W*x0), int(W*x1)))

regions = {
    "波纹区(中部)": roi(0.42, 0.85, 0.30, 0.72),
    "文字区(上部)": roi(0.11, 0.40, 0.02, 0.95),
    "干净区(右下)": roi(0.60, 0.95, 0.78, 0.98),
}

for name, sl in regions.items():
    gg = g[sl]; mm = mean[sl]; ss = std[sl]
    d_weak = (mm - gg)              # >0 表示低于局部均值（墨侧）
    d_norm = d_weak / np.maximum(ss, 1e-6)
    print(f"== {name}  (std.avg={ss.mean():.1f})")
    for q in (50, 75, 90, 95, 99):
        print(f"  d_weak  p{q}: {np.percentile(d_weak, q):7.1f}    d_norm p{q}: {np.percentile(d_norm, q):6.2f}")
    # Sauvola 阈值（k=0.18/0.22/0.28）下的墨占比
    for k in (0.18, 0.22, 0.28):
        t = mm * (1 - k) + mm * ss * (k / 128.0)
        print(f"  k={k}: 墨占比 {(gg < t).mean()*100:5.1f}%")
    print()
