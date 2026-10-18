# -*- coding: utf-8 -*-
"""诊断：三款模型在真实照片上的原始概率图统计。"""
import os, sys
import numpy as np, cv2
from ai_edge_litert.interpreter import Interpreter

sys.argv = ["x"]
import importlib.util
spec = importlib.util.spec_from_file_location("ev", "eval_real.py")
ev = importlib.util.module_from_spec(spec)
try: spec.loader.exec_module(ev)
except AssertionError: pass

def probs(model, img, edges=ev.INFER_MAX_EDGE, size=ev.INPUT_SIZE):
    h, w = img.shape[:2]
    k = min(1.0, edges / max(h, w))
    work = cv2.resize(img, (int(w*k), int(h*k))) if k < 1 else img
    rgb = cv2.cvtColor(cv2.resize(work, (size, size)), cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    it = Interpreter(model_path=model); it.allocate_tensors()
    ind, outd = it.get_input_details()[0], it.get_output_details()[0]
    dt = ind["dtype"]
    x = rgb if dt == np.float32 else (rgb * 255).astype(dt)
    if dt != np.float32 and ind.get("quantization", (0,0))[0]:
        sc, zp = ind["quantization"]; x = (rgb / sc + zp).astype(dt)
    it.set_tensor(ind["index"], x[None]); it.invoke()
    y = it.get_tensor(outd["index"])[0]
    if outd["dtype"] != np.float32 and outd["quantization"][0]:
        sc, zp = outd["quantization"]; y = (y.astype(np.float32) - zp) * sc
    p = 1/(1+np.exp(-np.clip(y[...,0], -30, 30)))
    return p

models = [("GPL", ev.GPL_MODEL),
          ("new_quant", os.path.join("out","doc_seg_quant.tflite")),
          ("new_f32",   os.path.join("out","doc_seg_float32.tflite"))]
print(f"{'image':20} {'model':10} {'p_max':>7} {'p_mean':>7} {'>0.5占比':>9} {'>0.3占比':>9} {'>0.1占比':>9}")
print("-"*80)
for name in ev.IMAGES:
    p_img = os.path.join(ev.T, name)
    if not os.path.exists(p_img): continue
    img = cv2.imread(p_img)
    for mn, mp in models:
        p = probs(mp, img)
        print(f"{name[:20]:20} {mn:10} {p.max():7.3f} {p.mean():7.3f} "
              f"{(p>0.5).mean()*100:8.1f}% {(p>0.3).mean()*100:8.1f}% {(p>0.1).mean()*100:8.1f}%")
    print()
