# -*- coding: utf-8 -*-
"""对比两种端正检测算法在真机失败案例（v1.5.2 输出内容）上的表现。

输入：dev_result_crop.jpg —— 真机 v1.5.2 的黑白输出（斜 15° 报告 + 黑底），
这正是 estimateSkewAngle 在真机上看到的内容形态（文字方向相同）。

算法 A：现行 Kotlin 复刻 —— HoughLinesP + ±45° 窗口 + 4° 直方图聚类 + 加权中位数。
算法 B：投影方差法 —— 文字掩膜按候选角度旋转，行投影的方差最大者即文档方向。
"""
import cv2
import numpy as np
import math

SKEW_MAX_EDGE = 1000


def prep(img):
    """复刻 Kotlin downscaleForSkew + 自适应二值化（文字=白）。"""
    h, w = img.shape[:2]
    k = min(1.0, SKEW_MAX_EDGE / max(h, w))
    if k < 1.0:
        work = cv2.resize(img, (max(int(w * k), 1), max(int(h * k), 1)),
                          interpolation=cv2.INTER_AREA)
    else:
        work = img
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    bin_ = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                 cv2.THRESH_BINARY_INV, 31, 15.0)
    klen = int(min(40.0, max(8.0, work.shape[1] / 30.0)))
    bin_ = cv2.morphologyEx(bin_, cv2.MORPH_CLOSE,
                            cv2.getStructuringElement(cv2.MORPH_RECT, (klen, 1)))
    return work, bin_


def methodA_hough(img):
    """现行 Kotlin estimateSkewAngle 的 1:1 复刻。"""
    work, bin_ = prep(img)
    lines = cv2.HoughLinesP(bin_, 1, math.pi / 180, 60,
                            minLineLength=work.shape[1] / 6.0, maxLineGap=20.0)
    if lines is None or len(lines) == 0:
        return 0.0, 0
    cands = []
    for x1, y1, x2, y2 in np.asarray(lines).reshape(-1, 4):
        ang = math.degrees(math.atan2(y2 - y1, x2 - x1))
        if abs(ang) <= 45.0:
            cands.append((ang, math.hypot(x2 - x1, y2 - y1)))
    if not cands:
        return 0.0, len(lines)
    peak = max(range(-11, 11),
               key=lambda b: sum(l for a, l in cands if b * 4 <= a < (b + 1) * 4))
    center = peak * 4.0 + 2.0
    cluster = sorted([(a, l) for a, l in cands if abs(a - center) <= 8.0])
    if not cluster:
        return 0.0, len(lines)
    total = sum(l for _, l in cluster)
    acc = 0.0
    for a, l in cluster:
        acc += l
        if acc >= total / 2:
            return a, len(lines)
    return cluster[-1][0], len(lines)


def methodB_projection(img):
    """投影方差法：文字掩膜旋转后按行求和，方差峰值 = 文档水平方向。"""
    work, bin_ = prep(img)
    # 缩到 400px 长边再旋转，纯性能考量（方差峰值对分辨率不敏感）
    h, w = bin_.shape
    k = min(1.0, 400.0 / max(h, w))
    if k < 1.0:
        small = cv2.resize(bin_, (max(int(w * k), 1), max(int(h * k), 1)),
                           interpolation=cv2.INTER_NEAREST)
    else:
        small = bin_

    def score_at(angle_deg, im):
        """旋转 angle 后行投影的方差（以均值归一，避免亮度总量影响）。"""
        hh, ww = im.shape
        c, s = math.cos(math.radians(angle_deg)), math.sin(math.radians(angle_deg))
        # 旋转后画布扩到能容下整图
        nw = int(abs(c) * ww + abs(s) * hh) + 2
        nh = int(abs(s) * ww + abs(c) * hh) + 2
        M = cv2.getRotationMatrix2D((ww / 2.0, hh / 2.0), angle_deg, 1.0)
        M[0, 2] += nw / 2.0 - ww / 2.0
        M[1, 2] += nh / 2.0 - hh / 2.0
        rot = cv2.warpAffine(im, M, (nw, nh), flags=cv2.INTER_NEAREST,
                             borderValue=0)
        proj = rot.sum(axis=1, dtype=np.float64)  # 每行白像素总数
        if proj.std() < 1e-6:
            return 0.0
        return float(proj.var() / max(proj.mean(), 1.0))

    # 粗扫 ±45° 步长 3°，再在峰值 ±3° 内步长 0.25° 细化
    best_a, best_s = 0.0, -1.0
    for a in range(-45, 46, 3):
        s = score_at(float(a), small)
        if s > best_s:
            best_s, best_a = s, float(a)
    fine_a, fine_s = best_a, best_s
    a0 = best_a
    steps = np.arange(a0 - 3.0, a0 + 3.01, 0.25)
    for a in steps:
        s = score_at(float(a), small)
        if s > fine_s:
            fine_s, fine_a = s, float(a)
    return fine_a, best_s


if __name__ == '__main__':
    import sys
    for path in sys.argv[1:]:
        img = cv2.imread(path)
        if img is None:
            print(f'{path}: 无法读取')
            continue
        aA, nlines = methodA_hough(img)
        aB, sB = methodB_projection(img)
        print(f'{path}')
        print(f'  A 现行Hough(±45+聚类): {aA:+7.2f}°   (Hough线数={nlines})')
        print(f'  B 投影方差法:          {aB:+7.2f}°   (峰值分={sB:.1f})')
