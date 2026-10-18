# -*- coding: utf-8 -*-
"""碎笔修复正式 A/B：以真机输出为基准，验证「先放大到 4000 再二值化 + 预模糊」。"""
import cv2
import numpy as np
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from diag_printpage import binarize, lift_paper_white_gray, imwrite, OUT

SRC = os.path.join(OUT, "diag_input.jpg")      # 真机二值化输入 1333x1920
DEV = os.path.join(OUT, "diag_output_bw.jpg")  # 真机最终输出 1048x1509


def run_chain(gray_src, pre_sigma):
    g = gray_src
    if pre_sigma > 0:
        g = cv2.GaussianBlur(g, (0, 0), pre_sigma)
    b, sw, mk = binarize(g, "adaptive")
    # 复刻 App：低分辨率二值化结果放大到长边 4000
    if max(b.shape[:2]) < 4000:
        f = 4000.0 / max(b.shape[:2])
        b = cv2.resize(b, None, fx=f, fy=f, interpolation=cv2.INTER_CUBIC)
    return b, sw, mk


def main():
    src = cv2.imread(SRC, cv2.IMREAD_GRAYSCALE)
    dev = cv2.imread(DEV, cv2.IMREAD_GRAYSCALE)
    H, W = src.shape[:2]
    print(f"输入 {W}x{H}  真机输出 {dev.shape[1]}x{dev.shape[0]}")

    # 相对裁片区域（正文）
    ry0, ry1, rx0, rx1 = 0.46, 0.63, 0.05, 0.90

    panels = []
    # 基准：真机输出
    dc = dev[int(ry0 * dev.shape[0]):int(ry1 * dev.shape[0]),
             int(rx0 * dev.shape[1]):int(rx1 * dev.shape[1])]
    panels.append(("真机 v1.5.9 输出", dc, None))

    # A: 旧顺序（1333 直接二值化 → 放大）
    b, sw, mk = run_chain(src, 0)
    panels.append((f"旧顺序 1x  (sw={sw:.1f} mk={mk})", b, (W, H)))

    # B/C/D: 先放大 4000 再二值化
    f = 4000.0 / max(H, W)
    up = cv2.resize(src, None, fx=f, fy=f, interpolation=cv2.INTER_LANCZOS4)
    print(f"放大后 {up.shape[1]}x{up.shape[0]}")
    for s in (0.0, 1.0, 1.5, 2.0):
        b, sw, mk = run_chain(up, s)
        panels.append((f"先放大4k σ={s}  (sw={sw:.1f} mk={mk})", b, up.shape[:2]))

    Z = 1.6
    crops = []
    for label, img, ref_shape in panels:
        hh, ww = img.shape[:2]
        sc = (ref_shape[0] / H) if ref_shape else (hh / (H * f) * f)  # 兼容
        # 真机输出与放大结果都按各自分辨率取相对区域
        crop = img[int(ry0 * hh):int(ry1 * hh), int(rx0 * ww):int(rx1 * ww)]
        crop = cv2.resize(crop, None, fx=Z, fy=Z, interpolation=cv2.INTER_NEAREST)
        crop = cv2.copyMakeBorder(crop, 0, 34, 0, 0, cv2.BORDER_CONSTANT, value=255)
        cv2.putText(crop, label, (8, crop.shape[0] - 10),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, 0, 2)
        crops.append(crop)
    wmax = max(c.shape[1] for c in crops)
    crops = [cv2.copyMakeBorder(c, 0, 0, 0, wmax - c.shape[1], cv2.BORDER_CONSTANT, value=200)
             for c in crops]
    out = np.vstack(crops)
    imwrite(os.path.join(OUT, "diag_blur_crops2.jpg"), out)
    print("saved diag_blur_crops2.jpg", out.shape)

    # 量化
    for label, img, _ in panels:
        ink = 255 - img
        ncc = cv2.connectedComponents(ink, connectivity=8)[0] - 1
        print(f"{label}: 黑占比 {ink.mean()/255*100:.2f}%  黑连通域 {ncc}")


if __name__ == "__main__":
    main()
