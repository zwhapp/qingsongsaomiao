# 逐行复刻 DocSegmenter.detectQuad 的 Kotlin 后处理，验证 mask→quad 无 bug
import numpy as np, cv2, os
from ai_edge_litert.interpreter import Interpreter

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro"
m = Interpreter(model_path=os.path.join(D, "_model_dl/model.tflite"))
m.allocate_tensors()
inp, out = m.get_input_details()[0], m.get_output_details()[0]

def detect_quad_kotlin_style(path):
    img = cv2.imread(path)
    ih, iw = img.shape[:2]
    # Kotlin: work = if maxEdge>2000 scale; here images < 2000 so work=img
    work = img
    sw, sh = iw/work.shape[1], ih/work.shape[0]
    # getPixels ARGB→RGB/255
    rgb = cv2.cvtColor(cv2.resize(work, (256,256)), cv2.COLOR_BGR2RGB).astype(np.float32)/255.0
    m.set_tensor(inp['index'], rgb[None,...])
    m.invoke()
    logits = m.get_tensor(out['index'])  # [1,256,256,1]
    prob = 1/(1+np.exp(-logits[0,:,:,0]))
    # resize to work size
    prob = cv2.resize(prob, (work.shape[1], work.shape[0]))
    # threshold 0.5
    binary = (prob > 0.5).astype(np.uint8)*255
    # open/close 9x9
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (9,9))
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, k)
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k)
    # findContours EXTERNAL
    cnts, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    print(f"  contours: {len(cnts)}")
    if not cnts: return None
    ia = work.shape[0]*work.shape[1]
    best = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(best)
    print(f"  best area_ratio: {area/ia:.3f} (valid 0.15..0.97)")
    if area < ia*0.15 or area > ia*0.97:
        print("  -> REJECTED by area guard"); return None
    peri = cv2.arcLength(best, True)
    approx = cv2.approxPolyDP(best, 0.02*peri, True)
    print(f"  approx pts: {len(approx)}")
    if len(approx) == 4:
        q = approx.reshape(-1,2)
    elif 5 <= len(approx) <= 12:
        q = cv2.boxPoints(cv2.minAreaRect(best)).astype(int)
    else:
        print("  -> REJECTED: pts out of range"); return None
    print(f"  quad: {q.tolist()}")
    vis = work.copy()
    cv2.polylines(vis, [np.array(q)], True, (0,255,0), 4)
    outp = os.path.join(D, "tools", os.path.basename(path).replace(".jpg", "_kotlin_quad.jpg"))
    cv2.imwrite(outp, vis)
    return q

for name in ["book2.jpg", "photo_full.jpg"]:
    print(f"=== {name} ===")
    r = detect_quad_kotlin_style(os.path.join(D, "tools", name))
    print("  RESULT:", "OK" if r is not None else "NULL")
