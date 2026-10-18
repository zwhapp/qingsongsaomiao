# 重训模型 vs GPL 原模型在真实照片上的对比评测。
#
# 评测口径与 tools/alt_compare.py 一致：完整复刻 DocSegmenter.detectQuad
# （256 推理 → 概率图放大 → 0.5 二值化 → open/close 9 → 轮廓 → 面积守卫
#   → approxPolyDP 0.02 → 四边形），只换模型文件。
#
# 用法：python tools/train/eval_real.py [新模型.tflite]
#   不带参数时自动找 tools/train/out/ 下最新的量化模型，与 GPL 原版对比。
import glob
import os
import sys
import numpy as np
import cv2
from ai_edge_litert.interpreter import Interpreter

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))
T = os.path.join(PROJ, "tools")
GPL_MODEL = os.path.join(PROJ, "_model_dl", "model.tflite")
NEW = sys.argv[1] if len(sys.argv) > 1 else None
if NEW is None:
    cands = sorted(glob.glob(os.path.join(HERE, "out", "doc_seg_*.tflite")))
    NEW = cands[-1] if cands else None

IMAGES = ["book2_raw.jpg", "photo_moved.jpg", "photo_full.jpg",
          "text_photo_moved.jpg", "text_photo_full.jpg"]
INPUT_SIZE, INFER_MAX_EDGE = 256, 2000


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
    m = np.zeros(shape[:2], np.uint8)
    cv2.fillPoly(m, [np.round(np.asarray(quad)).astype(np.int32)], 1)
    return m.astype(bool)


def iou(q1, q2, shape):
    if q1 is None or q2 is None:
        return 0.0
    a, b = quad_mask(q1, shape), quad_mask(q2, shape)
    u = (a | b).sum()
    return float((a & b).sum() / u) if u else 0.0


def detect_with(model_path, img):
    m = Interpreter(model_path=model_path)
    m.allocate_tensors()
    inp, out = m.get_input_details()[0], m.get_output_details()[0]
    ih, iw = img.shape[:2]
    if max(iw, ih) > INFER_MAX_EDGE:
        k = INFER_MAX_EDGE / max(iw, ih)
        work = cv2.resize(img, (max(int(iw * k), 1), max(int(ih * k), 1)))
    else:
        work = img
    sx, sy = iw / work.shape[1], ih / work.shape[0]
    rgb = cv2.cvtColor(cv2.resize(work, (INPUT_SIZE, INPUT_SIZE)), cv2.COLOR_BGR2RGB)
    m.set_tensor(inp['index'], (rgb.astype(np.float32) / 255.0)[None, ...])
    m.invoke()
    lg = m.get_tensor(out['index'])
    prob = 1.0 / (1.0 + np.exp(-lg[0, :, :, 0]))
    pf = cv2.resize(prob, (work.shape[1], work.shape[0]))
    binary = (pf > 0.5).astype(np.uint8) * 255
    ker = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, ker)
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, ker)
    cnts, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None, "no contours"
    ia = work.shape[0] * work.shape[1]
    best = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(best)
    if area < ia * 0.15 or area > ia * 0.97:
        return None, f"area guard ratio={area/ia:.3f}"
    approx = cv2.approxPolyDP(best, 0.02 * cv2.arcLength(best, True), True)
    n = len(approx)
    if n == 4:
        q = approx.reshape(-1, 2).astype(float)
    elif 5 <= n <= 12:
        q = cv2.boxPoints(cv2.minAreaRect(best)).astype(float)
    else:
        return None, f"approx pts={n}"
    return order_corners(q * np.array([sx, sy])), f"OK pts={n} ratio={area/ia:.2f}"


assert NEW and os.path.exists(NEW), "未找到新模型，请先运行 train.py"
print(f"新模型: {os.path.basename(NEW)} ({os.path.getsize(NEW)/1048576:.2f} MB)")
print(f"基准  : GPL 原版 (FairScan v1.2.0)\n")
print(f"{'image':22} {'GPL 原版':>10} {'新模型':>10} {'相对基准IoU':>12}")
print("-" * 60)
gpl_hits, new_hits, rel_ious = 0, 0, []
for name in IMAGES:
    p = os.path.join(T, name)
    if not os.path.exists(p):
        continue
    img = cv2.imread(p)
    qG, whyG = detect_with(GPL_MODEL, img)
    qN, whyN = detect_with(NEW, img)
    gpl_hits += qG is not None
    new_hits += qN is not None
    rel = iou(qN, qG, img.shape[:2])
    rel_ious.append(rel)
    print(f"{name:22} {('OK' if qG is not None else 'FAIL'):>10} "
          f"{('OK' if qN is not None else 'FAIL'):>10} {rel:12.3f}")
print("-" * 60)
print(f"命中率  GPL {gpl_hits}/5   新模型 {new_hits}/5")
print(f"与 GPL 基准的平均 IoU: {np.mean(rel_ious):.3f}")
print("判读：IoU>0.85 基本等效；0.7~0.85 边界略偏但可用；<0.7 需要再训或加数据。")
