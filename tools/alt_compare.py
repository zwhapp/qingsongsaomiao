# 换模型方案评估：三种「文档区域检测」路径在同一批真实照片上的量化对比。
#
#   A) ML 分割模型（当前方案，来源 GPL-3.0 / 数据 CC BY-NC-SA）—— 作基准
#   B) 传统边缘检测 DocumentGeometry.detectDocumentQuad（删模型后的自动回退）
#   C) 文本区域凸包（模拟「复用已有 ML Kit OCR 的文本块」方案，零新增依赖）
#   D) 文本块并集轴对齐矩形（严格复刻 OcrEngine.PageResult.boundingBox 的行为）
#
# 指标：与 A 的四边形 IoU（A 作为准真值）+ 是否命中。
# 复跑：python tools/alt_compare.py
import numpy as np, cv2, os
from ai_edge_litert.interpreter import Interpreter

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro"
T = os.path.join(D, "tools")
OUT = os.path.join(T, "altout")
os.makedirs(OUT, exist_ok=True)
MODEL = os.path.join(D, "_model_dl/model.tflite")

m = Interpreter(model_path=MODEL)
m.allocate_tensors()
IN = m.get_input_details()[0]
OUTD = m.get_output_details()[0]
INPUT_SIZE, INFER_MAX_EDGE = 256, 2000

IMAGES = ["book2_raw.jpg", "photo_moved.jpg", "photo_full.jpg",
          "text_photo_moved.jpg", "text_photo_full.jpg"]


def order_corners(pts):
    pts = np.asarray(pts, dtype=float)
    s = pts.sum(axis=1)
    tl, br = pts[np.argmin(s)], pts[np.argmax(s)]
    mask = np.ones(len(pts), bool)
    mask[np.argmin(s)] = False
    mask[np.argmax(s)] = False
    rem = pts[mask]
    d = rem[:, 1] - rem[:, 0]
    return np.array([tl, rem[np.argmin(d)], br, rem[np.argmax(d)]])


def quad_mask(quad, shape):
    mk = np.zeros(shape[:2], np.uint8)
    cv2.fillPoly(mk, [np.round(np.asarray(quad)).astype(np.int32)], 1)
    return mk.astype(bool)


def iou(q1, q2, shape):
    if q1 is None or q2 is None:
        return 0.0
    a, b = quad_mask(q1, shape), quad_mask(q2, shape)
    u = (a | b).sum()
    return float((a & b).sum() / u) if u else 0.0


# ---------- A) ML 分割（复刻 DocSegmenter.detectQuad） ----------
def ml_detect_quad(img):
    ih, iw = img.shape[:2]
    if max(iw, ih) > INFER_MAX_EDGE:
        k = INFER_MAX_EDGE / max(iw, ih)
        work = cv2.resize(img, (max(int(iw * k), 1), max(int(ih * k), 1)))
    else:
        work = img
    sx, sy = iw / work.shape[1], ih / work.shape[0]
    rgb = cv2.cvtColor(cv2.resize(work, (INPUT_SIZE, INPUT_SIZE)), cv2.COLOR_BGR2RGB)
    m.set_tensor(IN['index'], (rgb.astype(np.float32) / 255.0)[None, ...])
    m.invoke()
    lg = m.get_tensor(OUTD['index'])[0, :, :, 0]
    prob = 1.0 / (1.0 + np.exp(-lg))
    pf = cv2.resize(prob, (work.shape[1], work.shape[0]))
    binary = (pf > 0.5).astype(np.uint8) * 255
    ker = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, ker)
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, ker)
    cnts, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None
    ia = work.shape[0] * work.shape[1]
    best = max(cnts, key=cv2.contourArea)
    if cv2.contourArea(best) < ia * 0.15 or cv2.contourArea(best) > ia * 0.97:
        return None
    approx = cv2.approxPolyDP(best, 0.02 * cv2.arcLength(best, True), True)
    n = len(approx)
    if n == 4:
        q = approx.reshape(-1, 2).astype(float)
    elif 5 <= n <= 12:
        q = cv2.boxPoints(cv2.minAreaRect(best)).astype(float)
    else:
        return None
    return order_corners(q * np.array([sx, sy]))


# ---------- B) 传统边缘（复刻 DocumentGeometry.detectDocumentQuad） ----------
def downscale(src, max_edge=1000):
    ih, iw = src.shape[:2]
    if max(iw, ih) <= max_edge:
        return src
    k = max_edge / max(iw, ih)
    return cv2.resize(src, (max(int(iw * k), 1), max(int(ih * k), 1)))


def edge_once(src, lo, hi, min_score, convex):
    work = downscale(src)
    sx, sy = src.shape[1] / work.shape[1], src.shape[0] / work.shape[0]
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(cv2.GaussianBlur(gray, (5, 5), 0), lo, hi, 3, L2gradient=True)
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    closed = cv2.dilate(cv2.morphologyEx(edges, cv2.MORPH_CLOSE, k), k, iterations=1)
    cnts, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    ia = work.shape[0] * work.shape[1]
    best, best_score = None, 0.0
    for c in cnts:
        if cv2.contourArea(c) < ia * 0.12:
            continue
        approx = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
        if len(approx) != 4 or (convex and not cv2.isContourConvex(approx)):
            continue
        qa = cv2.contourArea(approx)
        rect = cv2.minAreaRect(approx)
        ra = rect[1][0] * rect[1][1]
        if ra <= 1:
            continue
        score = min(qa / ra, 1.0) * min(cv2.contourArea(c) / ia, 1.0)
        if score > best_score:
            best_score, best = score, approx.reshape(-1, 2).astype(float)
    if best is None or best_score < min_score:
        return None
    return order_corners(best * np.array([sx, sy]))


def edge_detect(src):
    for lo, hi, ms, cv in [(60, 180, 0.28, True), (40, 120, 0.20, True), (30, 90, 0.14, False)]:
        q = edge_once(src, lo, hi, ms, cv)
        if q is not None:
            return q
    return None


# ---------- C/D) 文本区域（模拟 OCR 文本块聚合） ----------
def text_blocks(img, thr=20, max_edge=1200):
    """返回 (文本块掩码, 块数, 工作图, 缩放比k)。复刻 OcrEngine 看到的「文本行」粒度。"""
    h, w = img.shape[:2]
    k = min(1.0, max_edge / max(h, w))
    work = cv2.resize(img, (max(int(w * k), 1), max(int(h * k), 1)),
                      interpolation=cv2.INTER_AREA) if k < 1 else img
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    # 笔画提取（比局部背景暗的细结构）
    bh = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (15, 15)))
    _, tb = cv2.threshold(bh, thr, 255, cv2.THRESH_BINARY)
    # 文本密度场 → 文本块
    dens = cv2.GaussianBlur(tb, (61, 61), 0)
    _, dm = cv2.threshold(dens, 12, 255, cv2.THRESH_BINARY)
    ker = cv2.getStructuringElement(cv2.MORPH_RECT, (31, 31))
    dm = cv2.morphologyEx(dm, cv2.MORPH_CLOSE, ker)
    dm = cv2.morphologyEx(dm, cv2.MORPH_OPEN, ker)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(dm)
    ia = work.shape[0] * work.shape[1]
    keep = [i for i in range(1, n)
            if stats[i, cv2.CC_STAT_AREA] >= ia * 0.004
            and stats[i, cv2.CC_STAT_WIDTH] >= 10
            and stats[i, cv2.CC_STAT_HEIGHT] >= 6]
    if not keep:
        return None, 0, work, k
    return np.isin(labels, keep).astype(np.uint8), len(keep), work, k


def text_quad(img, pad_ratio=0.0, join_ratio=0.03):
    """C) 文本块凸包 → 四边形。pad_ratio>0 时按质心外扩。"""
    mask, nblk, work, k = text_blocks(img)
    if mask is None:
        return None
    jk = cv2.getStructuringElement(
        cv2.MORPH_RECT, (max(int(work.shape[1] * join_ratio) | 1, 3),
                         max(int(work.shape[0] * join_ratio) | 1, 3)))
    joined = cv2.morphologyEx(mask * 255, cv2.MORPH_CLOSE, jk)
    cnts, _ = cv2.findContours(joined, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None
    c = max(cnts, key=cv2.contourArea)
    hull = cv2.convexHull(c)
    approx = cv2.approxPolyDP(hull, 0.03 * cv2.arcLength(hull, True), True)
    q = (approx.reshape(-1, 2).astype(float) if len(approx) == 4
         else cv2.boxPoints(cv2.minAreaRect(hull)).astype(float))
    q = order_corners(q)
    if pad_ratio > 0:
        cen = q.mean(0)
        q = cen + (q - cen) * (1.0 + pad_ratio)
    return q / k


def text_bbox_quad(img):
    """D) 严格复刻 OcrEngine：所有文本行的并集 **轴对齐矩形**（无旋转信息）。"""
    h, w = img.shape[:2]
    k = min(1.0, 1200 / max(h, w))
    work = cv2.resize(img, (int(w * k), int(h * k))) if k < 1 else img
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    bh = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (15, 15)))
    _, tb = cv2.threshold(bh, 20, 255, cv2.THRESH_BINARY)
    dens = cv2.GaussianBlur(tb, (61, 61), 0)
    _, dm = cv2.threshold(dens, 12, 255, cv2.THRESH_BINARY)
    ker = cv2.getStructuringElement(cv2.MORPH_RECT, (31, 31))
    dm = cv2.morphologyEx(dm, cv2.MORPH_CLOSE, ker)
    dm = cv2.morphologyEx(dm, cv2.MORPH_OPEN, ker)
    n, _, stats, _ = cv2.connectedComponentsWithStats(dm)
    ia = work.shape[0] * work.shape[1]
    xs, ys, xe, ye = [], [], [], []
    for i in range(1, n):
        x, y, cw, ch, a = stats[i]
        if a < ia * 0.004 or cw < 10 or ch < 6:
            continue
        xs.append(x); ys.append(y); xe.append(x + cw); ye.append(y + ch)
    if not xs:
        return None
    x0, y0, x1, y1 = min(xs), min(ys), max(xe), max(ye)
    q = np.array([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], dtype=float)
    return q / k


# ---------- E) 文本凸包种子 + grabCut 生长到纸张边界（零许可风险的混合方案） ----------
def text_grabcut_quad(img):
    mask, nblk, work, k = text_blocks(img)
    if mask is None:
        return None
    jk = cv2.getStructuringElement(
        cv2.MORPH_RECT, (max(int(work.shape[1] * 0.03) | 1, 3),
                         max(int(work.shape[0] * 0.03) | 1, 3)))
    joined = cv2.morphologyEx(mask * 255, cv2.MORPH_CLOSE, jk)
    cnts, _ = cv2.findContours(joined, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None
    c = max(cnts, key=cv2.contourArea)
    hull = cv2.convexHull(c).reshape(-1, 2).astype(float)

    h, w = work.shape[:2]
    # grabCut 掩码：确定前景 = 凸包收缩后内部；可能前景 = 凸包外扩；
    # 确定背景 = 图像边缘带；其余 = 可能背景
    gmask = np.full((h, w), cv2.GC_PR_BGD, np.uint8)
    cen = hull.mean(0)
    inner = cen + (hull - cen) * 0.92          # 收缩 8% 作为确定前景
    outer = cen + (hull - cen) * 1.25          # 外扩 25% 作为可能前景
    cv2.fillPoly(gmask, [np.round(outer).astype(np.int32)], cv2.GC_PR_FGD)
    cv2.fillPoly(gmask, [np.round(inner).astype(np.int32)], cv2.GC_FGD)
    band = max(int(min(h, w) * 0.02), 2)
    gmask[:band, :] = cv2.GC_BGD
    gmask[-band:, :] = cv2.GC_BGD
    gmask[:, :band] = cv2.GC_BGD
    gmask[:, -band:] = cv2.GC_BGD

    bgd = np.zeros((1, 65), np.float64)
    fgd = np.zeros((1, 65), np.float64)
    rgb = cv2.cvtColor(work, cv2.COLOR_BGR2RGB)
    try:
        cv2.grabCut(rgb, gmask, None, bgd, fgd, 5, cv2.GC_INIT_WITH_MASK)
    except cv2.error:
        return None
    fg = np.where((gmask == cv2.GC_FGD) | (gmask == cv2.GC_PR_FGD), 255, 0).astype(np.uint8)
    # 整形：去小洞、去孤立点
    mk = cv2.getStructuringElement(cv2.MORPH_RECT, (11, 11))
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, mk)
    fg = cv2.morphologyEx(fg, cv2.MORPH_OPEN, mk)
    cnts, _ = cv2.findContours(fg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None
    ia = h * w
    c2 = max(cnts, key=cv2.contourArea)
    if cv2.contourArea(c2) < ia * 0.12:
        return None
    approx = cv2.approxPolyDP(c2, 0.02 * cv2.arcLength(c2, True), True)
    n = len(approx)
    q = (approx.reshape(-1, 2).astype(float) if n == 4 else
         (cv2.boxPoints(cv2.minAreaRect(c2)).astype(float) if 5 <= n <= 12 else None))
    if q is None:
        return None
    return order_corners(q) / k


# ---------- 主流程 ----------
print(f"model={os.path.basename(MODEL)}  in={IN['shape']} out={OUTD['shape']}\n")
print(f"{'image':22} {'A:ML':>6} {'B:EDGE':>8} {'C:hull':>8} {'C+8%':>8} {'E:G+C':>8} {'D:bbox':>8}")
print("-" * 76)
agg = {"B": [], "C": [], "C8": [], "C16": [], "E": [], "D": []}
hitB = 0
for name in IMAGES:
    p = os.path.join(T, name)
    if not os.path.exists(p):
        continue
    img = cv2.imread(p)
    qA = ml_detect_quad(img)
    qB = edge_detect(img)
    qC = text_quad(img, 0.0)
    qC8 = text_quad(img, 0.08)
    qC16 = text_quad(img, 0.16)
    qD = text_bbox_quad(img)
    qE = text_grabcut_quad(img)
    ia = img.shape[:2]
    rB, rC, rC8, rC16, rD = (iou(qB, qA, ia), iou(qC, qA, ia), iou(qC8, qA, ia),
                             iou(qC16, qA, ia), iou(qD, qA, ia))
    rE = iou(qE, qA, ia)
    hitB += qB is not None
    agg["B"].append(rB); agg["C"].append(rC); agg["C8"].append(rC8)
    agg["C16"].append(rC16); agg["D"].append(rD); agg["E"].append(rE)
    print(f"{name:22} {'OK' if qA is not None else 'FAIL':>6} "
          f"{('%.2f' % rB) if qB is not None else 'FAIL':>8} "
          f"{rC:8.2f} {rC8:8.2f} {rE:8.2f} {rD:8.2f}")
    # 可视化
    vis = img.copy()
    for q, c, lab in [(qA, (0, 0, 255), 'A ML'), (qB, (0, 200, 0), 'B EDGE'),
                      (qC8, (255, 128, 0), 'C TEXT'), (qE, (255, 0, 0), 'E G+C'),
                      (qD, (200, 0, 200), 'D BBOX')]:
        if q is None:
            continue
        cv2.polylines(vis, [np.round(q).astype(np.int32)], True, c, 4)
        cv2.putText(vis, lab, tuple(np.round(q[0]).astype(int) + [6, -10]),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, c, 3, cv2.LINE_AA)
    cv2.imwrite(os.path.join(OUT, f"cmp_{name}"), vis)

n = len(agg["B"])
print("-" * 76)
print(f"{'平均 IoU(以 A 为基准)':22} {hitB}/{n}命中 "
      f"{np.mean(agg['B']):8.2f} {np.mean(agg['C']):8.2f} "
      f"{np.mean(agg['C8']):8.2f} {np.mean(agg['E']):8.2f} {np.mean(agg['D']):8.2f}")
print(f"\n可视化输出: {OUT}")
