# -*- coding: utf-8 -*-
"""去手写阈值标定：测真实文档（暖黄纸）与彩色笔迹/荧光笔的色度分布。

背景：v1.5.13 首版 INK_CHROMA_THRESH=28 把"暖黄/米色纸面"也判成彩色，
jvmcheck 的无损性对拍立刻暴露（book2 黑占比 7.05%→9.40%）：纸面被擦成
纯白后局部均值抬高，Sauvola 阈值随之上移，反而把更多像素判成墨。

本脚本回答两个问题：
  1. 真实纸面的色度上界是多少（阈值必须高于它）？
  2. 荧光笔/彩色笔的色度下界是多少（阈值必须低于它）？
两者之间的空隙就是可用阈值区间。

用法：python diag_ink_chroma.py
"""
import os

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))          # tools/train
TOOLS = os.path.dirname(HERE)                              # tools
PROJ = os.path.dirname(TOOLS)                              # DocScanPro

IMAGES = [
    ("text_photo_full.jpg", os.path.join(TOOLS, "text_photo_full.jpg")),
    ("book2_raw.jpg", os.path.join(TOOLS, "book2_raw.jpg")),
    ("photo_full.jpg", os.path.join(TOOLS, "photo_full.jpg")),
]


def normalize_like_app(bgr):
    """近似 app 的第一级背景归一（大核高斯代形态学闭运算，量级一致即可）。"""
    long_edge = max(bgr.shape[:2])
    sigma = max(long_edge / 72.0, 1.0)          # 核=long/12，sigma=核/6
    bg = cv2.GaussianBlur(bgr.astype(np.float32), (0, 0), sigma)
    out = bgr.astype(np.float32) / np.maximum(bg, 1.0) * 255.0
    return np.clip(out, 0, 255).astype(np.uint8)


def chroma(bgr):
    mx = bgr.max(axis=2).astype(np.int16)
    mn = bgr.min(axis=2).astype(np.int16)
    return (mx - mn).astype(np.uint8)


def main():
    print("== 真实文档：归一化后纸面色度分布（阈值必须高于这些数字）==")
    for name, path in IMAGES:
        if not os.path.exists(path):
            print("  MISSING %s" % path)
            continue
        bgr = cv2.imread(path)
        if bgr is None:
            print("  READ FAIL %s" % path)
            continue
        c = chroma(normalize_like_app(bgr))
        ps = [50, 75, 90, 95, 99, 99.9]
        pct = np.percentile(c, ps)
        above = {t: float((c > t).mean() * 100.0) for t in (28, 50, 70, 90, 110)}
        print("  %-22s 分位 %s" % (
            name, "  ".join("P%g=%d" % (p, v) for p, v in zip(ps, pct))))
        print("  %-22s 超过阈值的像素占比 %s" % (
            "", "  ".join(">%d: %.2f%%" % (t, v) for t, v in above.items())))

    print()
    print("== 彩色笔迹/荧光笔参考色度（阈值必须低于这些数字）==")
    # BGR 顺序（cv2 读入即 BGR）
    samples = [
        ("荧光笔·黄(淡)", (60, 250, 250)),
        ("荧光笔·黄(浓)", (0, 240, 255)),
        ("荧光笔·粉", (200, 150, 255)),
        ("荧光笔·绿", (120, 255, 150)),
        ("圆珠笔·蓝", (200, 40, 20)),
        ("圆珠笔·黑蓝", (90, 20, 10)),
        ("签字笔·红", (30, 20, 210)),
        ("印章·朱红", (20, 20, 220)),
    ]
    for name, bgrpx in samples:
        arr = np.array([[bgrpx]], dtype=np.uint8)
        c = chroma(arr)[0, 0]
        mx = int(arr.max())
        print("  %-14s BGR=%-16s chroma=%3d  max=%3d" % (
            name, str(bgrpx), c, mx))

    print()
    print("== 参考：印刷黑字（阈值必须高于它，否则误删）==")
    for name, bgrpx in [("纯黑", (10, 10, 12)), ("带暖色偏黑字", (12, 14, 20)),
                        ("灰黑", (60, 62, 65))]:
        arr = np.array([[bgrpx]], dtype=np.uint8)
        print("  %-12s BGR=%-16s chroma=%3d" % (
            name, str(bgrpx), chroma(arr)[0, 0]))

    # ---------------------------------------------------------------
    # 关键：饱和度 sat = chroma / max 对"归一化放大"近似不变 ——
    # Core.divide 逐通道除以背景，chroma 与 max 同倍放大，比值不动。
    # 因此"暖黄纸 + 暗部放大"这两类误报都能被 sat 挡住，而绝对 chroma
    # 再配一个下限用来挡住印刷黑字（黑字 max 极小，sat 反而虚高）。
    # ---------------------------------------------------------------
    print()
    print("== 复合判据扫描：真实文档上被判为彩色的像素占比（越接近 0 越好）==")
    cands = [(28, 0.0), (60, 0.0), (70, 0.0), (60, 0.35), (60, 0.5),
             (75, 0.5), (75, 0.6), (90, 0.6)]
    print("  %-14s %s" % ("(chroma,sat)", "  ".join("%-20s" % n for n, _ in IMAGES)))
    for t, s in cands:
        row = []
        for name, path in IMAGES:
            if not os.path.exists(path):
                row.append("MISSING")
                continue
            bgr = cv2.imread(path)
            n = normalize_like_app(bgr).astype(np.float32)
            mx = n.max(axis=2)
            mn = n.min(axis=2)
            c = mx - mn
            sat = c / np.maximum(mx, 1.0)
            flag = (c >= t) & (sat >= s) if s > 0 else (c >= t)
            row.append("%.2f%%" % (flag.mean() * 100.0))
        print("  %-14s %s" % ("(>=%d,>=%.2f)" % (t, s),
                              "  ".join("%-20s" % v for v in row)))

    print()
    print("== 复合判据对真实笔迹的召回（越接近 100% 越好）==")
    inks = [("荧光·黄淡", (60, 250, 250)), ("荧光·粉", (200, 150, 255)),
            ("圆珠·蓝", (200, 40, 20)), ("圆珠·黑蓝", (90, 20, 10)),
            ("签字·红", (30, 20, 210)), ("印章·朱红", (20, 20, 220))]
    for t, s in cands:
        hits = []
        for name, bgrpx in inks:
            a = np.array([[bgrpx]], dtype=np.float32)
            c = float(a.max() - a.min())
            sat = c / max(float(a.max()), 1.0)
            hits.append(name if ((c >= t) and (sat >= s if s > 0 else True)) else "·")
        print("  (>=%d,>=%.2f) 命中 %d/%d : %s" % (
            t, s, sum(1 for h in hits if h != "·"), len(inks), " ".join(hits)))


if __name__ == "__main__":
    main()
