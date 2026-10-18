# 完整复刻 ScanPipeline.detectQuad + DocSegmenter + DocumentGeometry 的 Kotlin 链路。
# 目标：找出「真机自动检测不到边缘」在 ML 修复之后仍失败的确切环节。
import numpy as np, cv2, os, sys
from ai_edge_litert.interpreter import Interpreter

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro"
T = os.path.join(D, "tools")
MODEL = os.path.join(D, "_model_dl/model.tflite")

m = Interpreter(model_path=MODEL)
m.allocate_tensors()
IN = m.get_input_details()[0]
OUT = m.get_output_details()[0]

INPUT_SIZE = 256
INFER_MAX_EDGE = 2000


# ---------- 复刻 DocSegmenter.detectQuad ----------
def ml_detect_quad(img_bgr):
    ih, iw = img_bgr.shape[:2]
    # work = 若长边 > 2000 则缩
    if max(iw, ih) > INFER_MAX_EDGE:
        k = INFER_MAX_EDGE / max(iw, ih)
        work = cv2.resize(img_bgr, (max(int(iw * k), 1), max(int(ih * k), 1)))
    else:
        work = img_bgr
    scaleX = iw / work.shape[1]
    scaleY = ih / work.shape[0]

    # 位图 → float32 RGB/255（模型输入）
    rgb = cv2.cvtColor(cv2.resize(work, (INPUT_SIZE, INPUT_SIZE)), cv2.COLOR_BGR2RGB)
    rgb = rgb.astype(np.float32) / 255.0

    m.set_tensor(IN['index'], rgb[None, ...])
    m.invoke()
    logits = m.get_tensor(OUT['index'])          # [1,256,256,1]
    prob = 1.0 / (1.0 + np.exp(-logits[0, :, :, 0]))

    prob_full = cv2.resize(prob, (work.shape[1], work.shape[0]))
    binary = (prob_full > 0.5).astype(np.uint8) * 255
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, k)
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k)

    cnts, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None, "no contours", binary
    ia = work.shape[0] * work.shape[1]
    best = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(best)
    ratio = area / ia
    if area < ia * 0.15 or area > ia * 0.97:
        return None, f"area guard reject ratio={ratio:.3f}", binary

    peri = cv2.arcLength(best, True)
    approx = cv2.approxPolyDP(best, 0.02 * peri, True)
    n = len(approx)
    if n == 4:
        q = approx.reshape(-1, 2).astype(float)
    elif 5 <= n <= 12:
        q = cv2.boxPoints(cv2.minAreaRect(best)).astype(float)
    else:
        return None, f"approx pts={n} out of range", binary

    # 映射回原图
    q = q * np.array([scaleX, scaleY])
    return order_corners(q), f"OK area_ratio={ratio:.3f} pts={n}", binary


def order_corners(pts):
    """复刻 DocumentGeometry.orderCorners：sum 求 tl/br，diff 求 tr/bl。"""
    pts = np.asarray(pts, dtype=float)
    s = pts.sum(axis=1)
    tl = pts[np.argmin(s)]
    br = pts[np.argmax(s)]
    mask = np.ones(len(pts), bool)
    mask[np.argmin(s)] = False
    mask[np.argmax(s)] = False
    rem = pts[mask]
    d = rem[:, 1] - rem[:, 0]
    tr = rem[np.argmin(d)]
    bl = rem[np.argmax(d)]
    return np.array([tl, tr, br, bl])


# ---------- 复刻 DocumentGeometry.detectDocumentQuad ----------
def downscale(src, max_edge=1000):
    ih, iw = src.shape[:2]
    if max(iw, ih) <= max_edge:
        return src
    k = max_edge / max(iw, ih)
    return cv2.resize(src, (max(int(iw * k), 1), max(int(ih * k), 1)))


def edge_detect_once(src, canny_lo, canny_hi, min_score, require_convex):
    work = downscale(src)
    sx = src.shape[1] / work.shape[1]
    sy = src.shape[0] / work.shape[0]
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, canny_lo, canny_hi, 3, L2gradient=True)
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, k)
    closed = cv2.dilate(closed, k, iterations=1)
    cnts, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    ia = work.shape[0] * work.shape[1]
    best, best_score = None, 0.0
    for c in cnts:
        area = cv2.contourArea(c)
        if area < ia * 0.12:
            continue
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02 * peri, True)
        if len(approx) != 4:
            continue
        if require_convex and not cv2.isContourConvex(approx):
            continue
        quad_area = cv2.contourArea(approx)
        rect = cv2.minAreaRect(approx)
        rw, rh = rect[1]
        rect_area = rw * rh
        if rect_area <= 1:
            continue
        rectangularity = min(quad_area / rect_area, 1.0)
        score = rectangularity * min(area / ia, 1.0)
        if score > best_score:
            best_score = score
            best = approx.reshape(-1, 2).astype(float)
    if best is None or best_score < min_score:
        return None, best_score
    return order_corners(best) * np.array([sx, sy]), best_score


def edge_detect(src):
    for lo, hi, ms, cv in [(60, 180, 0.28, True), (40, 120, 0.20, True), (30, 90, 0.14, False)]:
        q, sc = edge_detect_once(src, lo, hi, ms, cv)
        if q is not None:
            return q, f"edge hit score={sc:.3f} (canny {lo}/{hi})"
    return None, "edge all stages failed"


# ---------- 主流程 ----------
print(f"model: {os.path.basename(MODEL)}")
print(f"  input {IN['shape']} {IN['dtype']}   output {OUT['shape']} {OUT['dtype']}")
print()

for name in ["book2_raw.jpg", "photo_moved.jpg", "photo_full.jpg",
             "text_photo_moved.jpg", "text_photo_full.jpg"]:
    p = os.path.join(T, name)
    if not os.path.exists(p):
        continue
    img = cv2.imread(p)
    print(f"=== {name}  {img.shape[1]}x{img.shape[0]} ===")

    q, why, mask = ml_detect_quad(img)
    if q is not None:
        print(f"  ML  : {why}")
        print(f"        quad = {q.astype(int).tolist()}")
        vis = img.copy()
        cv2.polylines(vis, [q.astype(int)], True, (0, 0, 255), 6)
        cv2.imwrite(os.path.join(T, f"e2e_ml_{name}"), vis)
    else:
        print(f"  ML  : FAILED -> {why}")
        cv2.imwrite(os.path.join(T, f"e2e_mlmask_{name}"), mask)

    q2, why2 = edge_detect(img)
    print(f"  EDGE: {why2}")
    if q2 is not None:
        print(f"        quad = {q2.astype(int).tolist()}")
    print()
