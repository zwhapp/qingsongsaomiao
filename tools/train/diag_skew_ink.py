# -*- coding: utf-8 -*-
"""墨迹投影端正法 —— 第三版设计，目标：真机失败案例 + 全部基准图零回归。

核心思想：文档倾斜 = 文字行方向。只取「亮纸内部的深色笔画」做墨迹掩膜：
  paper = 灰度 85 分位（与 ScanEnhancer 的纸面估计同一口径）
  bright = gray >= 0.75*paper            （纸面）
  interior = erode(bright, 7)            （往内缩 7px，排除纸边过渡带）
  ink = (gray < 0.60*paper) & interior   （纸内的深色笔画）
再对 ink 掩膜做 ±45° 投影方差搜索。黑背景/纸边/摩尔纹全部进不了掩膜。

对照：
  方法A = 现行 Kotlin（Hough ±45 + 聚类），真机上失败原因不明
  方法B = 裸投影（已证伪：真实照片上退化为 +48° 页面假峰）
  方法C = 墨迹投影（本文件）
"""
import cv2
import numpy as np
import math

SKEW_MAX_EDGE = 1000


def methodA_hough(img):
    """现行 Kotlin estimateSkewAngle 复刻。"""
    h, w = img.shape[:2]
    k = min(1.0, SKEW_MAX_EDGE / max(h, w))
    work = cv2.resize(img, (max(int(w*k), 1), max(int(h*k), 1))) if k < 1.0 else img
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    bin_ = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                 cv2.THRESH_BINARY_INV, 31, 15.0)
    klen = int(min(40.0, max(8.0, work.shape[1]/30.0)))
    bin_ = cv2.morphologyEx(bin_, cv2.MORPH_CLOSE,
                            cv2.getStructuringElement(cv2.MORPH_RECT, (klen, 1)))
    lines = cv2.HoughLinesP(bin_, 1, math.pi/180, 60,
                            minLineLength=work.shape[1]/6.0, maxLineGap=20.0)
    if lines is None or len(lines) == 0:
        return 0.0
    cands = []
    for x1, y1, x2, y2 in np.asarray(lines).reshape(-1, 4):
        ang = math.degrees(math.atan2(y2-y1, x2-x1))
        if abs(ang) <= 45.0:
            cands.append((ang, math.hypot(x2-x1, y2-y1)))
    if not cands:
        return 0.0
    peak = max(range(-11, 11),
               key=lambda b: sum(l for a, l in cands if b*4 <= a < (b+1)*4))
    center = peak*4.0 + 2.0
    cluster = sorted([(a, l) for a, l in cands if abs(a-center) <= 8.0])
    if not cluster:
        return 0.0
    total = sum(l for _, l in cluster)
    acc = 0.0
    for a, l in cluster:
        acc += l
        if acc >= total/2:
            return a
    return cluster[-1][0]


def _ink_mask(gray):
    """亮纸内部的深色笔画掩膜。输入 float32/uint8 灰度。

    ★ 关键：纸面「区域」必须用闭运算填充文字孔洞后得到 —— 直接对亮像素
    掩膜腐蚀会把文字像素排除在区域外，墨迹掩膜恒空（第一版踩坑）。
    """
    g = gray.astype(np.float32)
    paper = float(np.percentile(g, 85))
    if paper < 40:          # 几乎全黑 → 无文档
        return None
    bright = (g >= 0.75*paper).astype(np.uint8)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(bright, 8)
    if n <= 1:
        return None
    # 只保留最大的亮区（纸面）
    largest = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    page = (lab == largest).astype(np.uint8)
    # 闭运算填掉文字/线条孔洞 + 四边形近似补边缘凹陷，得到完整纸面区域
    page = cv2.morphologyEx(page, cv2.MORPH_CLOSE, np.ones((25, 25), np.uint8))
    # 往内缩 7px，排除纸边过渡带
    interior = cv2.erode(page, np.ones((7, 7), np.uint8))
    ink = ((g < 0.60*paper) & (interior > 0)).astype(np.uint8)
    if ink.sum() < 0.002 * ink.size:   # 墨迹太少 → 不是文本文档
        return None
    return ink


def _proj_score(ink, angle_deg, smooth_h=None):
    """高频残差方差：文字行水平时行投影呈尖锐周期峰（高频强）；
    几何退化（墨迹向对角带集中）是平滑单峰（高频弱）→ 天然免疫。
    var/mean 打分在 ±45° 端有单调几何偏差（实测合成 -15° 案例被 +45 假峰压过），弃用。
    """
    h, w = ink.shape
    c, s = math.cos(math.radians(angle_deg)), math.sin(math.radians(angle_deg))
    nw = int(abs(c)*w + abs(s)*h) + 2
    nh = int(abs(s)*w + abs(c)*h) + 2
    M = cv2.getRotationMatrix2D((w/2.0, h/2.0), angle_deg, 1.0)
    M[0, 2] += nw/2.0 - w/2.0
    M[1, 2] += nh/2.0 - h/2.0
    rot = cv2.warpAffine(ink, M, (nw, nh), flags=cv2.INTER_NEAREST, borderValue=0)
    proj = rot.sum(axis=1, dtype=np.float64)
    if smooth_h is None:
        smooth_h = max(7, nh // 30) | 1   # 平滑窗 ≈ 行距量级
    ps = cv2.boxFilter(proj.reshape(-1, 1).astype(np.float32), -1,
                       (1, smooth_h), borderType=cv2.BORDER_REPLICATE).ravel()
    resid = proj - ps
    return float(resid.var())


def methodC_ink_proj(img):
    """墨迹投影法（C）：粗扫 ±45° 步 3°，细扫 ±3° 步 0.25°。"""
    h, w = img.shape[:2]
    k = min(1.0, SKEW_MAX_EDGE / max(h, w))
    work = cv2.resize(img, (max(int(w*k), 1), max(int(h*k), 1)),
                      interpolation=cv2.INTER_AREA) if k < 1.0 else img
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    ink = _ink_mask(gray)
    if ink is None:
        return 0.0
    # 400px 上做旋转搜索（纯性能）
    ih, iw = ink.shape
    k2 = min(1.0, 400.0 / max(ih, iw))
    if k2 < 1.0:
        ink = cv2.resize(ink, (max(int(iw*k2), 1), max(int(ih*k2), 1)),
                         interpolation=cv2.INTER_NEAREST)
    best_a, best_s = 0.0, -1.0
    for a in range(-45, 46, 3):
        sc = _proj_score(ink, float(a))
        if sc > best_s:
            best_s, best_a = sc, float(a)
    fine_a, fine_s = best_a, best_s
    for a in np.arange(best_a-3.0, best_a+3.01, 0.25):
        sc = _proj_score(ink, float(a))
        if sc > fine_s:
            fine_s, fine_a = sc, float(a)
    return float(fine_a)


if __name__ == '__main__':
    import os
    PROJ = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
    BUG = os.path.join(PROJ, '_bug151')
    imgs = [(os.path.join(BUG, 'input.jpg'), 'bug151原图(斜15°)'),
            (os.path.join(BUG, 'dev_result_crop.jpg'), '真机输出(斜18°)')]
    for n in ['book2_raw.jpg', 'photo_moved.jpg', 'photo_full.jpg',
              'text_photo_moved.jpg', 'text_photo_full.jpg']:
        p = os.path.join(PROJ, 'tools', n)
        if os.path.exists(p):
            imgs.append((p, n))
    print(f"{'图片':24} {'A现行Hough':>10} {'C墨迹投影':>10}")
    print('-'*50)
    for p, name in imgs:
        img = cv2.imread(p)
        if img is None:
            continue
        aA = methodA_hough(img)
        aC = methodC_ink_proj(img)
        mark = ' ← 变化' if abs(aA - aC) > 2.0 else ''
        print(f'{name:24} {aA:+9.2f}° {aC:+9.2f}°{mark}')
    # 合成旋转回归：把水平基准图转 -15°，两法都应回到 -15 附近
    print('\n合成旋转回归（把基准图人为转 -15°，应检出 -15°）:')
    for n in ['photo_full.jpg', 'text_photo_full.jpg', 'book2_raw.jpg']:
        p = os.path.join(PROJ, 'tools', n)
        if not os.path.exists(p):
            continue
        img = cv2.imread(p)
        h, w = img.shape[:2]
        M = cv2.getRotationMatrix2D((w/2, h/2), 15, 1.0)  # cv2 正角=逆时针；-15°顺时针倾斜
        cos, sin = abs(M[0, 0]), abs(M[0, 1])
        nw, nh = int(h*sin + w*cos), int(h*cos + w*sin)
        M[0, 2] += nw/2 - w/2
        M[1, 2] += nh/2 - h/2
        rot = cv2.warpAffine(img, M, (nw, nh), borderValue=(0, 0, 0))
        aA = methodA_hough(rot)
        aC = methodC_ink_proj(rot)
        print(f'  {n:24} {aA:+9.2f}° {aC:+9.2f}°')
