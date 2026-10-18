# -*- coding: utf-8 -*-
"""整页 100% 观感对比：真机 vs 先放大4k × σ。"""
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

panels = [("device", dev, None)]
for s in (0.0, 1.0, 1.5):
    g = cv2.GaussianBlur(up, (0,0), s) if s > 0 else up
    b, sw, mk = binarize(g, "adaptive")
    panels.append((f"up4k s{s}", b, up.shape[:2]))

TW = 760
crops = []
for label, img, _ in panels:
    hh, ww = img.shape[:2]
    sc = TW / ww
    small = cv2.resize(img, (TW, int(hh*sc)), interpolation=cv2.INTER_AREA)
    small = cv2.copyMakeBorder(small, 0, 30, 0, 0, cv2.BORDER_CONSTANT, value=255)
    cv2.putText(small, label, (8, small.shape[0]-8), cv2.FONT_HERSHEY_SIMPLEX, 0.9, 0, 2)
    crops.append(small)
out = np.hstack(crops)
imwrite(os.path.join(OUT, "diag_page_cmp.jpg"), out)
print("saved", out.shape)
