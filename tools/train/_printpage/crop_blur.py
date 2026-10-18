# -*- coding: utf-8 -*-
import cv2, numpy as np, os
d = os.path.dirname(os.path.abspath(__file__))
os.chdir(d)
src = cv2.imread('diag_input.jpg', cv2.IMREAD_GRAYSCALE)
H, W = src.shape
print('input', W, H)

def binarize(gray, moire_mode):
    # 复刻 App 链：Sauvola 迟滞 + 闭包 + 反相 + CC 去噪 + 摩尔纹滤波 + 黑带 + 桥接(简化)
    g = gray.astype(np.float32)
    h, w = g.shape
    win = 31
    mean = cv2.boxFilter(g, -1, (win, win), borderType=cv2.BORDER_REFLECT)
    var_m = cv2.boxFilter(g*g, -1, (win, win), borderType=cv2.BORDER_REFLECT) - mean*mean
    std = np.sqrt(np.maximum(var_m, 0))
    strong = mean + 0.34*std
    weak = mean + 0.18*std
    ink = (g < strong).astype(np.uint8)
    # 迭代膨胀闭包 within weak
    k3 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3,3))
    for _ in range(6):
        grow = cv2.dilate(ink, k3)
        grow = ((grow > 0) & (g < weak)).astype(np.uint8)
        if (grow == ink).all(): break
        ink = grow
    ink = cv2.morphologyEx(ink, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(5,5)))
    binimg = 255 - ink*255  # 纸=255 墨=0
    binimg = cv2.medianBlur(binimg, 3)
    # CC 去噪
    n, lab, stats, _ = cv2.connectedComponentsWithStats(255-binimg, 8)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] < 8:
            binimg[lab == i] = 255
    return binimg

def chain(sigma):
    im = src
    if True:
        im = cv2.resize(im, None, fx=4000/max(im.shape[:2]), fy=4000/max(im.shape[:2]), interpolation=cv2.INTER_LANCZOS4)
    im = cv2.GaussianBlur(im, (0,0), sigma) if sigma > 0 else im
    return binarize(im, 'adaptive')

# 区域：正文一行
y0, y1, x0, x1 = 900, 1200, 100, 1200
Z = 2
panels = []
labels = ['old(1x s0)', 'up4k s1.0', 'up4k s1.5', 'up4k s2.0']
imgs = [chain(0), chain(1.0), chain(1.5), chain(2.0)]
for im, lb in zip(imgs, labels):
    sc = im.shape[0] / H
    crop = im[int(y0*sc):int(y1*sc), int(x0*sc):int(x1*sc)]
    crop = cv2.resize(crop, None, fx=Z*1.0, fy=Z*1.0, interpolation=cv2.INTER_NEAREST)
    crop = cv2.copyMakeBorder(crop, 0, 30, 0, 0, cv2.BORDER_CONSTANT, value=255)
    cv2.putText(crop, lb, (10, crop.shape[0]-8), cv2.FONT_HERSHEY_SIMPLEX, 0.9, 0, 2)
    panels.append(crop)
wmax = max(p.shape[1] for p in panels)
panels = [cv2.copyMakeBorder(p, 0,0,0, wmax-p.shape[1], cv2.BORDER_CONSTANT, value=200) for p in panels]
out = np.vstack(panels)
cv2.imwrite('diag_blur_crops.jpg', out, [cv2.IMWRITE_JPEG_QUALITY, 92])
print('saved', out.shape)
