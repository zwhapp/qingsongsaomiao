# 验证 ML 在「真机尺寸路径」上的表现：
# 真机 loadBitmap 会把长边降到 3200，DocSegmenter 再降到 2000 推理。
# 之前离线测试全用 <1500 的小图，没覆盖这条路径。
import numpy as np, cv2, os
from ai_edge_litert.interpreter import Interpreter

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro"
T = os.path.join(D, "tools")
MODEL = os.path.join(D, "_model_dl/model.tflite")

m = Interpreter(model_path=MODEL); m.allocate_tensors()
IN, OUT = m.get_input_details()[0], m.get_output_details()[0]
INPUT_SIZE, INFER_MAX_EDGE = 256, 2000


def ml_detect(img_bgr):
    ih, iw = img_bgr.shape[:2]
    if max(iw, ih) > INFER_MAX_EDGE:
        k = INFER_MAX_EDGE / max(iw, ih)
        work = cv2.resize(img_bgr, (max(int(iw * k), 1), max(int(ih * k), 1)))
    else:
        work = img_bgr
    scaleX, scaleY = iw / work.shape[1], ih / work.shape[0]

    rgb = cv2.cvtColor(cv2.resize(work, (INPUT_SIZE, INPUT_SIZE)), cv2.COLOR_BGR2RGB)
    m.set_tensor(IN['index'], (rgb.astype(np.float32) / 255.0)[None, ...])
    m.invoke()
    logits = m.get_tensor(OUT['index'])
    prob = 1.0 / (1.0 + np.exp(-logits[0, :, :, 0]))

    pf = cv2.resize(prob, (work.shape[1], work.shape[0]))
    binary = (pf > 0.5).astype(np.uint8) * 255
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, k)
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k)

    cnts, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None, "no contours", None
    ia = work.shape[0] * work.shape[1]
    best = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(best)
    if area < ia * 0.15 or area > ia * 0.97:
        return None, f"area guard ratio={area/ia:.3f}", binary
    peri = cv2.arcLength(best, True)
    approx = cv2.approxPolyDP(best, 0.02 * peri, True)
    n = len(approx)
    if n == 4:
        q = approx.reshape(-1, 2).astype(float)
    elif 5 <= n <= 12:
        q = cv2.boxPoints(cv2.minAreaRect(best)).astype(float)
    else:
        return None, f"approx pts={n}", binary
    return q * np.array([scaleX, scaleY]), f"OK ratio={area/ia:.3f} pts={n}", binary


# 构造几个「真机尺寸」测试：把原图放大到 3000~4000 长边（模拟手机 5000 万像素原图被降采样后）
scales = {"原始": 1.0, "放大到~3000": None, "放大到~3200": None}

for name in ["book2_raw.jpg", "photo_moved.jpg", "text_photo_full.jpg"]:
    p = os.path.join(T, name)
    if not os.path.exists(p):
        continue
    img0 = cv2.imread(p)
    print(f"=== {name} 原始 {img0.shape[1]}x{img0.shape[0]} ===")
    for label, s in scales.items():
        if s is None:
            target = 3000 if "3000" in label else 3200
            k = target / max(img0.shape[:2])
            img = cv2.resize(img0, (int(img0.shape[1] * k), int(img0.shape[0] * k)),
                             interpolation=cv2.INTER_CUBIC)
        else:
            img = img0
        q, why, mask = ml_detect(img)
        status = f"quad={q.astype(int).tolist()}" if q is not None else "NULL"
        print(f"  [{label:11}] {img.shape[1]}x{img.shape[0]}  -> {why}  {status}")
        if q is not None:
            vis = img.copy()
            cv2.polylines(vis, [q.astype(int)], True, (0, 0, 255), max(3, img.shape[1]//200))
            tag = "orig" if s == 1.0 else label.replace("~", "")
            cv2.imwrite(os.path.join(T, f"size_{name.replace('.jpg','')}_{tag}.jpg"), vis)
    print()
