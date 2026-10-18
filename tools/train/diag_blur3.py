# -*- coding: utf-8 -*-
"""单字级高倍对比：σ = 0 / 1.0 / 1.5（先放大 4000）。"""
import cv2, numpy as np, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from diag_printpage import binarize, imwrite, OUT

SRC = os.path.join(OUT, "diag_input.jpg")
DEV = os.path.join(OUT, "diag_output_bw.jpg")

src = cv2.imread(SRC, cv2.IMREAD_GRAYSCALE)
dev = cv2.imread(DEV, cv2.IMREAD_GRAYSCALE)
H, W = src.shape[:2]
f = 4000.0 / max(H, W)
up = cv2.resize(src, None, fx=f, fy=f, interpolation=cv2.INTER_LANCZOS4)

ry0, ry1, rx0, rx1 = 0.465, 0.545, 0.06, 0.55   # 一行的前半段，高倍看
Z = 3.0
panels = [("dev", dev, None)]
for s in (0.0, 1.0, 1.5, 2.0):
    g = cv2.GaussianBlur(up, (0, 0), s) if s > 0 else up
    b, sw, mk = binarize(g, "adaptive")
    panels.append((f"s{s}", b, up.shape[:2]))

crops = []
for label, img, _ in panels:
    hh, ww = img.shape[:2]
    crop = img[int(ry0*hh):int(ry1*hh), int(rx0*ww):int(rx1*ww)]
    crop = cv2.resize(crop, None, fx=Z, fy=Z, interpolation=cv2.INTER_NEAREST)
    crop = cv2.copyMakeBorder(crop, 0, 30, 0, 0, cv2.BORDER_CONSTANT, value=255)
    cv2.putText(crop, label, (8, crop.shape[0]-8), cv2.FONT_HERSHEY_SIMPLEX, 0.9, 0, 2)
    crops.append(crop)
wmax = max(c.shape[1] for c in crops)
crops = [cv2.copyMakeBorder(c, 0,0,0,wmax-c.shape[1], cv2.BORDER_CONSTANT, value=200) for c in crops]
out = np.vstack(crops)
imwrite(os.path.join(OUT, "diag_blur_zoom.jpg"), out)
print("saved", out.shape)
