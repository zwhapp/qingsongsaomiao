# -*- coding: utf-8 -*-
import importlib.util, os, sys
import numpy as np, cv2

spec = importlib.util.spec_from_file_location("ev", "eval_real.py")
ev = importlib.util.module_from_spec(spec)
# 注入 argv 让其选定模型，并阻止断言失败
sys.argv = ["eval_real.py", "out/doc_seg_float32.tflite"]
try:
    spec.loader.exec_module(ev)   # 模块级主流程会照常打印表格
except AssertionError:
    pass

GPL_MODEL, NEW = ev.GPL_MODEL, ev.NEW
detect_with, IMAGES, T = ev.detect_with, ev.IMAGES, ev.T

out = "evalout"; os.makedirs(out, exist_ok=True)
for name in IMAGES:
    p = os.path.join(T, name)
    if not os.path.exists(p): continue
    img = cv2.imread(p)
    qG, _ = detect_with(GPL_MODEL, img)
    qN, _ = detect_with(NEW, img)
    vis = img.copy()
    for q, c, lab in [(qG,(0,0,255),"GPL"),(qN,(0,255,0),"NEW")]:
        if q is None: continue
        cv2.polylines(vis,[np.round(q).astype(np.int32)],True,c,5)
        cv2.putText(vis,lab,tuple(np.round(q[0]).astype(int)+[8,-12]),
                    cv2.FONT_HERSHEY_SIMPLEX,1.2,c,3,cv2.LINE_AA)
    s = 1400.0/max(vis.shape[:2])
    cv2.imwrite(os.path.join(out,f"cmp_{name}"),
                cv2.resize(vis,None,fx=s,fy=s,interpolation=cv2.INTER_AREA),
                [cv2.IMWRITE_JPEG_QUALITY,92])
    print("wrote",name)
