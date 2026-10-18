# -*- coding: utf-8 -*-
"""影印书页（用户 2026-10-17 原图）离线复现：黑白笔画断续 + 阴影深度诊断。

复刻 Kotlin 端 v1.5.8 全链（与 ScanEnhancer.kt 逐逻辑一致）：
  load(≤3200) → warp(4000) → 背景归一 → 二级平坦化(P85, cap) → 灰度/二值
对比项：
  A. 摩尔纹核：旧 v1.5.7 固定核 4  vs  新 v1.5.8 自适应核（按笔画估宽收缩）
  B. 平坦化上限：2.2 vs 2.6（灰度模式纸面亮度）
输出：tools/train/_printpage/ 下的对比图与量化指标。
"""
import cv2
import numpy as np
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "_printpage", "print_src.jpg")
OUT = os.path.join(HERE, "_printpage")


def imread(p):
    return cv2.imread(p)


def imwrite(p, img):
    ok, buf = cv2.imencode(".jpg", img)
    assert ok, p
    buf.tofile(p)


# ---------- 复刻 ScanEnhancer.estimateBackgroundAt ----------
def estimate_background_at(src, edge, k_div, k_max, sigma_div):
    longest = max(src.shape[:2])
    small = cv2.resize(src, None, fx=edge / longest, fy=edge / longest,
                       interpolation=cv2.INTER_AREA) if longest > edge else src.copy()
    sl = max(small.shape[:2])
    k = int(np.clip(sl // k_div, 31, k_max))
    if k % 2 == 0:
        k += 1
    kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    if small.ndim == 3:
        chans = list(cv2.split(small))
        for i in range(3):
            chans[i] = cv2.morphologyEx(chans[i], cv2.MORPH_CLOSE, kern)
            chans[i] = cv2.GaussianBlur(chans[i], (0, 0), max(k / sigma_div, 3.0))
        small = cv2.merge(chans)
    else:
        small = cv2.morphologyEx(small, cv2.MORPH_CLOSE, kern)
        small = cv2.GaussianBlur(small, (0, 0), max(k / sigma_div, 3.0))
    out = cv2.resize(small, (src.shape[1], src.shape[0]), interpolation=cv2.INTER_LINEAR) \
        if longest > edge else small.copy()
    return np.maximum(out, 1.0)


def percentile8(gray, p):
    hist = np.bincount(gray.ravel(), minlength=256)
    target = gray.size * p / 100.0
    acc = 0
    for i in range(256):
        acc += hist[i]
        if acc >= target:
            return i
    return 255


# ---------- 复刻 flattenResidual（v1.5.7/v1.5.8 自适应版，cap 可调） ----------
def flatten_residual(bgr, cap):
    f32 = bgr.astype(np.float32)
    field = estimate_background_at(f32, 600, 8, 151, 8.0)
    field_gray = cv2.cvtColor(np.clip(field, 0, 255).astype(np.uint8), cv2.COLOR_BGR2GRAY)
    thumb = cv2.resize(field_gray, (160, 160), interpolation=cv2.INTER_AREA)
    p85 = percentile8(thumb, 85.0)
    if p85 <= 1:
        return bgr, p85
    gain = np.clip(p85 / np.maximum(field, 1.0), 1.0, cap)
    out = f32 * gain
    return np.clip(out, 0, 255).astype(np.uint8), p85


def lift_paper_white_gray(gray, target=252.0, pct=85.0, max_gain=1.25):
    thumb = cv2.resize(gray, (160, 160), interpolation=cv2.INTER_AREA)
    p = percentile8(thumb, pct)
    if p <= 1:
        return gray
    gain = min(target / p, max_gain)
    return np.clip(gray.astype(np.float32) * gain, 0, 255).astype(np.uint8) if gain > 1.0 else gray


def clahe(gray, clip=2.0):
    c = cv2.createCLAHE(clip, (8, 8))
    return c.apply(gray)


# ---------- 复刻 binarizeBlackWhite ----------
def sauvola(g32, win, k, r=128.0):
    mean = cv2.boxFilter(g32, cv2.CV_32F, (win, win))
    mean_sq = cv2.boxFilter(g32 * g32, cv2.CV_32F, (win, win))
    std = np.sqrt(np.maximum(mean_sq - mean * mean, 0))
    return mean * (1 - k) + mean * std * k / r


def binarize(gray, moire_mode):
    """moire_mode: 'fixed4' = v1.5.7 固定核；'adaptive' = v1.5.8 笔画自适应"""
    long_edge = max(gray.shape[:2])
    win = int(np.clip(long_edge // 65, 15, 201))
    if win % 2 == 0:
        win += 1
    g32 = gray.astype(np.float32)
    t_strong = sauvola(g32, win, 0.34)
    t_weak = sauvola(g32, win, 0.18)
    strong = (g32 < t_strong).astype(np.uint8) * 255
    weak = (g32 < t_weak).astype(np.uint8) * 255

    # 迭代膨胀闭包（3x3, 收敛即停）
    seed = strong.copy()
    kern3 = cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3))
    prev = -1
    for _ in range(64):
        grown = cv2.dilate(seed, kern3)
        grown = cv2.min(grown, weak)
        cnt = int(cv2.countNonZero(grown))
        seed = grown
        if cnt == prev:
            break
        prev = cnt
    binimg = 255 - seed  # 纸=255 / 墨=0

    # CC 去噪（墨掩膜前景 <4px 删除）
    ink = 255 - binimg
    n, labels, stats, _ = cv2.connectedComponentsWithStats(ink, connectivity=8)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] < 4:
            ink[labels == i] = 0
    binimg = 255 - ink

    # 笔画估宽（距离变换，均值×4，≤800px 缩略图）
    longest = max(ink.shape[:2])
    f = 800.0 / longest if longest > 800 else 1.0
    small = cv2.resize(ink, None, fx=f, fy=f, interpolation=cv2.INTER_AREA) if f < 1.0 else ink.copy()
    small = (small > 127).astype(np.uint8) * 255
    stroke_w = 10.0
    if cv2.countNonZero(small) > 0:
        dist = cv2.distanceTransform((small > 0).astype(np.uint8), cv2.DIST_L2, 3)
        mask = small > 0
        stroke_w = float(dist[mask].mean()) * 4.0

    if moire_mode == "fixed4":
        mk = int(np.clip(long_edge // 1000, 3, 6))
    else:
        res_k = int(np.clip(long_edge // 1000, 3, 6))
        mk = int(np.clip(min(res_k, int(stroke_w - 1)), 1, 6))
    if mk >= 3:
        kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (mk, mk))
        binimg = cv2.morphologyEx(binimg, cv2.MORPH_OPEN, kern)
        binimg = cv2.morphologyEx(binimg, cv2.MORPH_CLOSE, kern)
    return binimg, stroke_w, mk


# ---------- 主流程 ----------
def main():
    src = imread(SRC)
    H, W = src.shape[:2]
    print(f"原图 {W}x{H}")

    # 模拟 App：亮区最大连通域 = 纸面 → minAreaRect 矫正到长边 4000
    gray0 = cv2.cvtColor(src, cv2.COLOR_BGR2GRAY)
    paper_level = np.percentile(gray0, 85)
    _, bright = cv2.threshold(gray0, paper_level * 0.75, 255, cv2.THRESH_BINARY)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(bright, connectivity=8)
    best = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA])) if n > 1 else 0
    page = (labels == best).astype(np.uint8) * 255
    page = cv2.morphologyEx(page, cv2.MORPH_CLOSE,
                            cv2.getStructuringElement(cv2.MORPH_RECT, (25, 25)))
    cnts, _ = cv2.findContours(page, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    rect = cv2.minAreaRect(max(cnts, key=cv2.contourArea))
    box = cv2.boxPoints(rect)
    by_sum = box[np.argsort(box.sum(axis=1))]
    tl, br = by_sum[0], by_sum[-1]
    rest = by_sum[1:-1]
    by_diff = rest[np.argsort(rest[:, 1] - rest[:, 0])]
    tr, bl = by_diff[0], by_diff[-1]
    src_pts = np.float32([tl, tr, br, bl])
    w_top = np.linalg.norm(tr - tl); w_bot = np.linalg.norm(br - bl)
    h_l = np.linalg.norm(bl - tl); h_r = np.linalg.norm(br - tr)
    outW, outH = int(max(w_top, w_bot)), int(max(h_l, h_r))
    scale = 4000.0 / max(outW, outH)
    if scale < 1:
        outW, outH = int(outW * scale), int(outH * scale)
    else:
        outW, outH = int(outW * scale), int(outH * scale)  # App warp 也放大到 4000
    dst_pts = np.float32([[0, 0], [outW - 1, 0], [outW - 1, outH - 1], [0, outH - 1]])
    M = cv2.getPerspectiveTransform(src_pts, dst_pts)
    warped = cv2.warpPerspective(src, M, (outW, outH), flags=cv2.INTER_CUBIC,
                                 borderMode=cv2.BORDER_REPLICATE, borderValue=(255, 255, 255))
    print(f"矫正后 {outW}x{outH}")
    imwrite(os.path.join(OUT, "warped.jpg"), warped)

    # ===== 归一 + 平坦化（cap 2.2 vs 2.6）=====
    bg = estimate_background_at(warped.astype(np.float32), 1000, 12, 151, 6.0)
    normalized = np.clip(warped.astype(np.float32) / np.maximum(bg, 1.0) * 255.0, 0, 255)

    flat22, _ = flatten_residual(normalized.astype(np.uint8), 2.2)
    flat26, _ = flatten_residual(normalized.astype(np.uint8), 2.6)
    imwrite(os.path.join(OUT, "flat_cap22.jpg"), flat22)
    imwrite(os.path.join(OUT, "flat_cap26.jpg"), flat26)

    # ===== 灰度模式（CLAHE 前后）=====
    for tag, flat in (("cap22", flat22), ("cap26", flat26)):
        g = cv2.cvtColor(flat, cv2.COLOR_BGR2GRAY)
        g = lift_paper_white_gray(g)
        g = clahe(g)
        imwrite(os.path.join(OUT, f"gray_{tag}.jpg"), g)
        # 量化：整页纸面 P85 + 最暗 1/8 区域的纸面亮度（分块统计各块 85 分位取最低）
        th = cv2.resize(cv2.cvtColor(flat, cv2.COLOR_BGR2GRAY), (160, 160))
        blocks = [np.percentile(th[y:y+20, x:x+20], 85)
                  for y in range(0, 160, 20) for x in range(0, 160, 20)]
        print(f"灰度 {tag}: 整页纸面P85={percentile8(cv2.resize(g,(160,160),interpolation=cv2.INTER_AREA),85)}, "
              f"块P85 min={min(blocks):.0f} median={np.median(blocks):.0f}")

    # ===== 黑白：旧核 vs 自适应核 =====
    g = cv2.cvtColor(flat26, cv2.COLOR_BGR2GRAY)
    g = lift_paper_white_gray(g)
    # 跳过 CLAHE（黑白模式链里没有）
    bin_old, _, k_old = binarize(g, "fixed4")
    bin_new, stroke_w, k_new = binarize(g, "adaptive")
    print(f"笔画估宽 = {stroke_w:.2f}px  →  旧核 {k_old} / 新核 {k_new}"
          f"{'（跳过滤波）' if k_new < 3 else ''}")
    for tag, b in (("bin_fixed4", bin_old), ("bin_adaptive", bin_new)):
        ink = 255 - b
        ncc = cv2.connectedComponents(ink, connectivity=8)[0] - 1
        print(f"黑白 {tag}: 黑占比 {ink.mean()/255*100:.2f}%  黑连通域 {ncc}")
        imwrite(os.path.join(OUT, f"{tag}.jpg"), b)

    # ===== 放大对比裁片（取正文中段 700x500 区域，2x 放大拼接）=====
    ch, cw = bin_old.shape[0] // 2, bin_old.shape[1] // 2
    y0, x0 = ch - 250, cw - 350
    crop_o = bin_old[y0:y0+500, x0:x0+700]
    crop_n = bin_new[y0:y0+500, x0:x0+700]
    crop_g = cv2.cvtColor(lift_paper_white_gray(cv2.cvtColor(flat26, cv2.COLOR_BGR2GRAY)),
                          cv2.COLOR_GRAY2BGR)[y0:y0+500, x0:x0+700]
    gap = np.full((500, 8, 3), 128, np.uint8)
    row1 = np.hstack([crop_g, gap, cv2.cvtColor(crop_o, cv2.COLOR_GRAY2BGR), gap,
                      cv2.cvtColor(crop_n, cv2.COLOR_GRAY2BGR)])
    cmp_img = cv2.resize(row1, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_NEAREST)
    imwrite(os.path.join(OUT, "cmp_bw_2x.jpg"), cmp_img)
    print("对比图（灰度 | 旧固定核4 | 新自适应核）：cmp_bw_2x.jpg")
    print("完成")


if __name__ == "__main__":
    main()
