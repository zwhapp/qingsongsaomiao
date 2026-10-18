# v3 模型失败形态诊断：并排输出 原图(GPL绿框+v3红框) 与 v3 概率热力图。
# 用法: python diag_v3.py out_v3/doc_seg_quant.tflite
import os, sys, importlib.util
import numpy as np
import cv2
from ai_edge_litert.interpreter import Interpreter

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("er", os.path.join(HERE, "eval_real.py"))
# 不执行 eval_real 的顶层评测循环：直接复制其函数过于耦合，改为独立实现。

PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))
T = os.path.join(PROJ, "tools")
GPL = os.path.join(PROJ, "_model_dl", "model.tflite")
NEW = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "out_v3", "doc_seg_quant.tflite")
OUT = os.path.join(HERE, "evalout_v3")
os.makedirs(OUT, exist_ok=True)
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


def prob_map(model, img):
    h, w = img.shape[:2]
    sc = min(1.0, INFER_MAX_EDGE / max(h, w))
    r = cv2.resize(img, (int(w * sc), int(h * sc)), interpolation=cv2.INTER_AREA)
    x = cv2.resize(r, (INPUT_SIZE, INPUT_SIZE), interpolation=cv2.INTER_AREA)
    x = cv2.cvtColor(x, cv2.COLOR_BGR2RGB).astype(np.float32)[None] / 255.0
    it = Interpreter(model_path=model); it.allocate_tensors()
    ii = it.get_input_details()[0]["index"]; oi = it.get_output_details()[0]["index"]
    it.set_tensor(ii, x); it.invoke()
    lg = it.get_tensor(oi)[0, :, :, 0]
    p = 1.0 / (1.0 + np.exp(-lg))          # 输出是 logits，必须过 sigmoid
    return p, r.shape[1], r.shape[0]


def quad_from_prob(p, rw, rh, full_w, full_h):
    p = cv2.resize(p, (rw, rh), interpolation=cv2.INTER_LINEAR)
    m = (p > 0.5).astype(np.uint8) * 255
    k = np.ones((9, 9), np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, k)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, k)
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cs:
        return None
    c = max(cs, key=cv2.contourArea)
    area = cv2.contourArea(c) / float(rw * rh)
    if area < 0.15 or area > 0.97:
        return None
    for eps in np.linspace(0.02, 0.06, 5):
        ap = cv2.approxPolyDP(c, eps * cv2.arcLength(c, True), True)
        if len(ap) == 4:
            q = order_corners(ap.reshape(-1, 2))
            return q / np.array([rw, rh], float) * np.array([full_w, full_h], float)
        if 5 <= len(ap) <= 12:
            q = order_corners(cv2.boxPoints(cv2.minAreaRect(c)))
            return q / np.array([rw, rh], float) * np.array([full_w, full_h], float)
    return None


def iou(a, b, hw):
    if a is None or b is None:
        return 0.0
    h, w = hw
    ma = np.zeros((h, w), np.uint8); mb = np.zeros((h, w), np.uint8)
    cv2.fillPoly(ma, [a.astype(np.int32)], 1); cv2.fillPoly(mb, [b.astype(np.int32)], 1)
    inter = np.logical_and(ma, mb).sum(); uni = np.logical_or(ma, mb).sum()
    return inter / uni if uni else 0.0


for name in IMAGES:
    p = os.path.join(T, name)
    if not os.path.exists(p):
        print("skip", name); continue
    img = cv2.imread(p)
    h, w = img.shape[:2]
    pG, rwG, rhG = prob_map(GPL, img); qG = quad_from_prob(pG, rwG, rhG, w, h)
    pN, rwN, rhN = prob_map(NEW, img); qN = quad_from_prob(pN, rwN, rhN, w, h)
    vis = img.copy()
    if qG is not None:
        cv2.polylines(vis, [qG.astype(np.int32)], True, (0, 255, 0), max(2, w // 300))
    if qN is not None:
        cv2.polylines(vis, [qN.astype(np.int32)], True, (0, 0, 255), max(2, w // 300))
    heat = cv2.applyColorMap((np.clip(pN, 0, 1) * 255).astype(np.uint8), cv2.COLORMAP_JET)
    heat = cv2.resize(heat, (w, h), interpolation=cv2.INTER_NEAREST)
    cv2.putText(heat, f"v3 p_max={pN.max():.2f}", (10, 40),
                cv2.FONT_HERSHEY_SIMPLEX, 1.2, (255, 255, 255), 3)
    side = np.hstack([vis, heat])
    side = cv2.resize(side, (1400, int(1400 * side.shape[0] / side.shape[1])))
    cv2.imwrite(os.path.join(OUT, f"d_{name}"), side)
    print(f"{name:22} GPL={'OK' if qG is not None else 'FAIL'} v3={'OK' if qN is not None else 'FAIL'} "
          f"iou={iou(qN, qG, (h, w)):.3f} p_max={pN.max():.2f} area={(pN > 0.5).mean():.3f}")
print("out ->", OUT)
