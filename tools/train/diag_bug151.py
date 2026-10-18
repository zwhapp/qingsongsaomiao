# 复现 v1.5.1 自用版问题1：扫描照片倾斜（检测失败 → 不矫正）。
# 对 _bug151/input.jpg 跑 ML(GPL) + 文本种子两级检测，画框可视化 + 矫正输出。
import os, sys
import numpy as np
import cv2
from ai_edge_litert.interpreter import Interpreter

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))
GPL = os.path.join(PROJ, "_model_dl", "model.tflite")
BUG = os.path.join(PROJ, "_bug151")
OUT = os.path.join(BUG, "diagout"); os.makedirs(OUT, exist_ok=True)
INPUT_SIZE, INFER_MAX_EDGE = 256, 2000


def order_corners(pts):
    pts = np.asarray(pts, dtype=float)
    s = pts.sum(axis=1)
    tl, br = pts[np.argmin(s)], pts[np.argmax(s)]
    m = np.ones(len(pts), bool); m[np.argmin(s)] = False; m[np.argmax(s)] = False
    rem = pts[m]
    d = rem[:, 1] - rem[:, 0]
    return np.array([tl, rem[np.argmin(d)], br, rem[np.argmax(d)]])


def ml_quad(model_path, img, tag):
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
    cv2.imwrite(os.path.join(OUT, f"ml_prob_{tag}.jpg"),
                (prob * 255).astype(np.uint8))
    pf = cv2.resize(prob, (work.shape[1], work.shape[0]))
    binary = (pf > 0.5).astype(np.uint8) * 255
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, k)
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k)
    cv2.imwrite(os.path.join(OUT, f"ml_bin_{tag}.jpg"), binary)
    cs, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cs:
        print(f"[{tag}] ML: 无轮廓")
        return None
    c = max(cs, key=cv2.contourArea)
    ia = work.shape[0] * work.shape[1]
    a = cv2.contourArea(c)
    print(f"[{tag}] ML: 最大轮廓面积占比 {a/ia:.3f}（守卫区间 0.15~0.97）")
    if a < ia * 0.15 or a > ia * 0.97:
        return None
    ap = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
    n = len(ap)
    if n == 4:
        q = ap.reshape(-1, 2).astype(float)
    elif 5 <= n <= 12:
        q = cv2.boxPoints(cv2.minAreaRect(c)).astype(float)
    else:
        print(f"[{tag}] ML: approx 点数 {n} 不合规")
        return None
    return order_corners(q * np.array([sx, sy]))


def text_quad(img, tag):
    h, w = img.shape[:2]
    k = min(1.0, 1200.0 / max(h, w))
    work = cv2.resize(img, (max(int(w * k), 1), max(int(h * k), 1)),
                      interpolation=cv2.INTER_AREA) if k < 1.0 else img
    wh, ww = work.shape[:2]
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    kern = cv2.getStructuringElement(cv2.MORPH_RECT, (15, 15))
    bh = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT, kern)
    _, strokes = cv2.threshold(bh, 20, 255, cv2.THRESH_BINARY)
    cv2.imwrite(os.path.join(OUT, f"ts_strokes_{tag}.jpg"), strokes)
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
    print(f"[{tag}] TEXT: 种子块 {'有' if ok else '无'}")
    cv2.imwrite(os.path.join(OUT, f"ts_blocks_{tag}.jpg"), blocks)
    if not ok:
        return None
    estW, estH = max(int(ww * 0.15), 8), max(int(wh * 0.15), 8)
    small = cv2.resize(gray, (estW, estH), interpolation=cv2.INTER_AREA)
    kd = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    paperSmall = cv2.dilate(small, kd)
    paperSmall = cv2.GaussianBlur(paperSmall, (0, 0), 3)
    paper = cv2.resize(paperSmall, (ww, wh), interpolation=cv2.INTER_LINEAR)
    paperMask = (gray >= (paper.astype(np.float32) * 0.92)).astype(np.uint8)
    cv2.imwrite(os.path.join(OUT, f"ts_papermask_{tag}.jpg"), paperMask)
    n2, lab2, stats2, _ = cv2.connectedComponentsWithStats(paperMask, 8)
    overlap = np.bincount(lab2.ravel(), weights=textMask.ravel() > 0, minlength=n2)
    best, bestOv = -1, 0
    for i in range(1, n2):
        if stats2[i, 4] < imageArea * 0.05:
            continue
        if overlap[i] > bestOv:
            bestOv, best = overlap[i], i
    if best < 0:
        print(f"[{tag}] TEXT: 无合格纸面连通域")
        return None
    fg = (lab2 == best).astype(np.uint8) * 255
    k11 = cv2.getStructuringElement(cv2.MORPH_RECT, (11, 11))
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, k11)
    cv2.imwrite(os.path.join(OUT, f"ts_fg_{tag}.jpg"), fg)
    cs, _ = cv2.findContours(fg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cs:
        return None
    c = max(cs, key=cv2.contourArea)
    r = cv2.contourArea(c) / imageArea
    print(f"[{tag}] TEXT: 纸面占比 {r:.3f}（守卫 0.10~0.97）")
    if r < 0.10 or r > 0.97:
        return None
    ap = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
    n = len(ap)
    if n == 4:
        q = ap.reshape(-1, 2).astype(float)
    elif 5 <= n <= 12:
        q = cv2.boxPoints(cv2.minAreaRect(c)).astype(float)
    else:
        print(f"[{tag}] TEXT: approx 点数 {n} 不合规")
        return None
    return order_corners(q / np.array([ww, wh]) * np.array([w, h]))


def warp_approx(img, quad, out_long=4000):
    """近似 DocumentGeometry.warpToRect：目标尺寸取四边形边长均值，限制 maxEdge。"""
    q = quad.astype(np.float32)
    wTop = np.linalg.norm(q[1] - q[0]); wBot = np.linalg.norm(q[2] - q[3])
    hL = np.linalg.norm(q[3] - q[0]); hR = np.linalg.norm(q[2] - q[1])
    ow, oh = (wTop + wBot) / 2, (hL + hR) / 2
    sc = min(1.0, out_long / max(ow, oh))
    ow, oh = int(ow * sc), int(oh * sc)
    dst = np.array([[0, 0], [ow - 1, 0], [ow - 1, oh - 1], [0, oh - 1]], dtype=np.float32)
    M = cv2.getPerspectiveTransform(q, dst)
    return cv2.warpPerspective(img, M, (ow, oh))


img = cv2.imread(os.path.join(BUG, "input.jpg"))
h, w = img.shape[:2]
print(f"输入: {w}x{h}")

qM = ml_quad(GPL, img, "main")
qT = text_quad(img, "main")
vis = img.copy()
for q, color, name in [(qM, (0, 255, 0), "ML"), (qT, (0, 128, 255), "TEXT")]:
    if q is not None:
        cv2.polylines(vis, [q.astype(np.int32)], True, color, 6)
        print(f"{name} quad: {q.astype(int).tolist()}")
cv2.imwrite(os.path.join(OUT, "quads.jpg"), vis)

if qM is not None:
    cv2.imwrite(os.path.join(OUT, "warp_ml.jpg"), warp_approx(img, qM))
if qT is not None:
    cv2.imwrite(os.path.join(OUT, "warp_text.jpg"), warp_approx(img, qT))
print("done ->", OUT)
