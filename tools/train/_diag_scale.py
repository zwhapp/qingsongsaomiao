# -*- coding: utf-8 -*-
"""诊断：模型对"裁切放大"的响应 —— 区分尺度敏感 vs 外观统计敏感。"""
import os, sys
import numpy as np, cv2
from ai_edge_litert.interpreter import Interpreter

def infer_p(model, bgr256):
    rgb = cv2.cvtColor(bgr256, cv2.COLOR_BGR2RGB).astype(np.float32)/255.0
    it = Interpreter(model_path=model); it.allocate_tensors()
    ind, outd = it.get_input_details()[0], it.get_output_details()[0]
    it.set_tensor(ind["index"], rgb[None]); it.invoke()
    y = it.get_tensor(outd["index"])[0][...,0]
    if outd["dtype"] != np.float32 and outd["quantization"][0]:
        sc, zp = outd["quantization"]; y = (y.astype(np.float32)-zp)*sc
    return 1/(1+np.exp(-np.clip(y,-30,30)))

NEW = "out/doc_seg_float32.tflite"
GPL = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro/_model_dl/model.tflite"
T = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro/tools"
print(f"{'image':20} {'视图':14} {'new p_max':>9} {'new >0.3':>9} {'GPL p_max':>10} {'GPL >0.3':>9}")
print("-"*76)
for name in ["book2_raw.jpg","photo_moved.jpg","text_photo_moved.jpg"]:
    img = cv2.imread(os.path.join(T,name))
    h,w = img.shape[:2]
    views = {
      "全图(同线上)": img,
      "中心50%裁切":  img[int(h*0.25):int(h*0.75), int(w*0.25):int(w*0.75)],
      "中心35%裁切":  img[int(h*0.32):int(h*0.68), int(w*0.32):int(w*0.68)],
      "整图上采样2x": cv2.resize(img, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC),
    }
    for vn, v in views.items():
        x = cv2.resize(v, (256,256), interpolation=cv2.INTER_AREA)
        pn, pg = infer_p(NEW, x), infer_p(GPL, x)
        print(f"{name[:20]:20} {vn:14} {pn.max():9.3f} {(pn>0.3).mean()*100:8.1f}% "
              f"{pg.max():10.3f} {(pg>0.3).mean()*100:8.1f}%")
    print()
