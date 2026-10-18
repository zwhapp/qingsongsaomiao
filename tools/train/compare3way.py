# 三方检测对比：GPL 模型 vs v3 重训模型 vs TextSeedDetector 复刻（方案 E）。
# 输出平均 IoU 表（以 GPL 为基准）+ 三框并排可视化。
import os, sys
import numpy as np
import cv2
from ai_edge_litert.interpreter import Interpreter

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))
T = os.path.join(PROJ, "tools")
GPL = os.path.join(PROJ, "_model_dl", "model.tflite")
V3 = os.path.join(HERE, "out_v3", "doc_seg_quant.tflite")
OUT = os.path.join(HERE, "evalout_v3"); os.makedirs(OUT, exist_ok=True)
IMAGES = ["book2_raw.jpg", "photo_moved.jpg", "photo_full.jpg",
          "text_photo_moved.jpg", "text_photo_full.jpg"]
INPUT_SIZE, INFER_MAX_EDGE = 256, 2000


def order_corners(pts):
    pts = np.asarray(pts, dtype=float)
    s = pts.sum(axis=1)
    tl, br = pts[np.argmin(s)], pts[np.argmax(s)]
    m = np.ones(len(pts), bool); m[np.argmin(s)] = False; m[np.argmax(s)] = False
    rem = pts[m]
    d = rem[:, 1] - rem[:, 0]
    return np.array([tl, rem[np.argmin(d)], br, rem[np.argmax(d)]])


def ml_quad(model_path, img):
    """复刻 DocSegmenter：256 推理 → sigmoid → 放大 → 0.5 → open/close 9 → 守卫 → 四边形"""
    h, w = img.shape[:2]
    sc = min(1.0, INFER_MAX_EDGE / max(h, w))
    work = cv2.resize(img, (max(int(w * sc), 1), max(int(h * sc), 1))) if sc < 1.0 else img
    sx, sy = w / work.shape[1], h / work.shape[0]
    rgb = cv2.cvtColor(cv2.resize(work, (INPUT_SIZE, INPUT_SIZE)), cv2.COLOR_BGR2RGB)
    it = Interpreter(model_path=model_path); it.allocate_tensors()
    ii, oi = it.get_input_details()[0]["index"], it.get_output_details()[0]["index"]
    it.set_tensor(ii, (rgb.astype(np.float32) / 255.0)[None]); it.invoke()
    lg = it.get_tensor(oi)[0, :, :, 0]
    prob = 1.0 / (1.0 + np.exp(-lg))
    pf = cv2.resize(prob, (work.shape[1], work.shape[0]))
    binary = (pf > 0.5).astype(np.uint8) * 255
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, k)
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k)
    cs, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cs:
        return None
    c = max(cs, key=cv2.contourArea)
    ia = work.shape[0] * work.shape[1]
    a = cv2.contourArea(c)
    if a < ia * 0.15 or a > ia * 0.97:
        return None
    ap = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
    n = len(ap)
    if n == 4:
        q = ap.reshape(-1, 2).astype(float)
    elif 5 <= n <= 12:
        q = cv2.boxPoints(cv2.minAreaRect(c)).astype(float)
    else:
        return None
    return order_corners(q * np.array([sx, sy]))


def text_quad(img):
    """复刻 TextSeedDetector.kt：1200 工作边 → blackhat 种子 → 亮度区域生长"""
    h, w = img.shape[:2]
    k = min(1.0, 1200.0 / max(h, w))
    work = cv2.resize(img, (max(int(w * k), 1), max(int(h * k), 1)),
                      interpolation=cv2.INTER_AREA) if k < 1.0 else img
    wh, ww = work.shape[:2]
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    kern = cv2.getStructuringElement(cv2.MORPH_RECT, (15, 15))
    bh = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT, kern)
    _, strokes = cv2.threshold(bh, 20, 255, cv2.THRESH_BINARY)
    density = cv2.GaussianBlur(strokes, (61, 61), 0)
    _, blocks = cv2.threshold(density, 12, 255, cv2.THRESH_BINARY)
    kb = cv2.getStructuringElement(cv2.MORPH_RECT, (31, 31))
    blocks = cv2.morphologyEx(blocks, cv2.MORPH_CLOSE, kb)
    blocks = cv2.morphologyEx(blocks, cv2.MORPH_OPEN, kb)
    imageArea = ww * wh
    n, lab, stats, _ = cv2.connectedComponentsWithStats(blocks, 8)
    textMask = np.zeros((wh, ww), np.uint8)
    ok = False
    for i in range(1, n):
        if stats[i, 4] >= imageArea * 0.004 and stats[i, 2] >= 10 and stats[i, 3] >= 6:
            textMask[lab == i] = 255; ok = True
    if not ok:
        return None
    estW, estH = max(int(ww * 0.15), 8), max(int(wh * 0.15), 8)
    small = cv2.resize(gray, (estW, estH), interpolation=cv2.INTER_AREA)
    kd = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    paperSmall = cv2.dilate(small, kd)
    paperSmall = cv2.GaussianBlur(paperSmall, (0, 0), 3)
    paper = cv2.resize(paperSmall, (ww, wh), interpolation=cv2.INTER_LINEAR)
    paperMask = (gray >= (paper.astype(np.float32) * 0.92)).astype(np.uint8)
    n2, lab2, stats2, _ = cv2.connectedComponentsWithStats(paperMask, 8)
    overlap = np.bincount(lab2.ravel(), weights=textMask.ravel() > 0, minlength=n2)
    best, bestOv = -1, 0
    for i in range(1, n2):
        if stats2[i, 4] < imageArea * 0.05:
            continue
        if overlap[i] > bestOv:
            bestOv, best = overlap[i], i
    if best < 0:
        return None
    fg = (lab2 == best).astype(np.uint8) * 255
    k11 = cv2.getStructuringElement(cv2.MORPH_RECT, (11, 11))
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, k11)
    cs, _ = cv2.findContours(fg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cs:
        return None
    c = max(cs, key=cv2.contourArea)
    r = cv2.contourArea(c) / imageArea
    if r < 0.10 or r > 0.97:
        return None
    ap = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
    n = len(ap)
    if n == 4:
        q = ap.reshape(-1, 2).astype(float)
    elif 5 <= n <= 12:
        q = cv2.boxPoints(cv2.minAreaRect(c)).astype(float)
    else:
        return None
    return order_corners(q / np.array([ww, wh]) * np.array([w, h]))


def quad_iou(a, b, hw):
    if a is None or b is None:
        return 0.0
    h, w = hw
    ma = np.zeros((h, w), np.uint8); mb = np.zeros((h, w), np.uint8)
    cv2.fillPoly(ma, [a.astype(np.int32)], 1); cv2.fillPoly(mb, [b.astype(np.int32)], 1)
    return float(np.logical_and(ma, mb).sum() / np.logical_or(ma, mb).sum())


print(f"{'image':22} {'v3':>8} {'TEXT':>8} {'TEXT命中':>8}")
print("-" * 52)
rows = []
for name in IMAGES:
    p = os.path.join(T, name)
    if not os.path.exists(p):
        continue
    img = cv2.imread(p)
    h, w = img.shape[:2]
    qG = ml_quad(GPL, img)
    q3 = ml_quad(V3, img)
    qT = text_quad(img)
    i3 = quad_iou(q3, qG, (h, w))
    iT = quad_iou(qT, qG, (h, w))
    rows.append((name, i3, iT))
    vis = img.copy()
    for q, col in [(qG, (0, 255, 0)), (q3, (0, 0, 255)), (qT, (255, 0, 0))]:
        if q is not None:
            cv2.polylines(vis, [q.astype(np.int32)], True, col, max(3, w // 300))
    tag = cv2.resize(img, (w // 4, h // 4)) if False else None
    vis = cv2.resize(vis, (1200, int(1200 * h / w)))
    cv2.imwrite(os.path.join(OUT, f"tri_{name}"), vis)
    print(f"{name:22} {i3:8.3f} {iT:8.3f} {'OK' if qT is not None else 'FAIL':>8}")
print("-" * 52)
print(f"{'平均 IoU(GPL基准)':22} {np.mean([r[1] for r in rows]):8.3f} "
      f"{np.mean([r[2] for r in rows]):8.3f}")
print(f"{'命中率':22} {sum(r[1] > 0 for r in rows)}/{len(rows)}   "
      f"{sum(r[2] > 0 for r in rows)}/{len(rows)}")
print("可视化 ->", OUT)
