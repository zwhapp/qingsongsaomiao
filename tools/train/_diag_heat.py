# -*- coding: utf-8 -*-
"""概率热力图诊断：直观显示模型把画面哪一部分判成了文档。

用法: python _diag_heat.py [模型路径]
不带参数时对比 GPL 原版与 tools/train/out/ 下的新模型。
输出: evalout/heat_diag.jpg
"""
import os
import sys
import numpy as np
import cv2
from ai_edge_litert.interpreter import Interpreter

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))
T = os.path.join(PROJ, "tools")
GPL = os.path.join(PROJ, "_model_dl", "model.tflite")
OUT = os.path.join(HERE, "evalout")
IMAGES = ["book2_raw.jpg", "text_photo_moved.jpg", "photo_moved.jpg"]


def infer_p(model, bgr):
    """返回 0~1 前景概率图（输入图会被缩到 256）。"""
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    it = Interpreter(model_path=model)
    it.allocate_tensors()
    ind, outd = it.get_input_details()[0], it.get_output_details()[0]
    it.set_tensor(ind["index"], rgb[None])
    it.invoke()
    y = it.get_tensor(outd["index"])[0][..., 0]
    if outd["dtype"] != np.float32 and outd["quantization"][0]:
        sc, zp = outd["quantization"]
        y = (y.astype(np.float32) - zp) * sc
    return 1 / (1 + np.exp(-np.clip(y, -30, 30)))


def main():
    new = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "out", "doc_seg_float32.tflite")
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for name in IMAGES:
        img = cv2.imread(os.path.join(T, name))
        if img is None:
            continue
        h, w = img.shape[:2]
        k = min(1.0, 2000 / max(h, w))
        work = cv2.resize(img, (int(w * k), int(h * k))) if k < 1 else img
        tiles = []
        for model, lab in [(GPL, "GPL"), (new, "NEW")]:
            p = infer_p(model, cv2.resize(work, (256, 256), interpolation=cv2.INTER_AREA))
            p = cv2.resize(p, (work.shape[1], work.shape[0]), interpolation=cv2.INTER_LINEAR)
            m = (p > 0.5).astype(np.uint8)
            cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            cnts = [c for c in cnts if cv2.contourArea(c) > 100]
            vis = work.copy()
            cv2.drawContours(vis, cnts, -1, (0, 255, 0), 4)
            heat = cv2.applyColorMap((np.clip(p, 0, 1) * 255).astype(np.uint8), cv2.COLORMAP_JET)
            vis = cv2.addWeighted(vis, 0.5, heat, 0.5, 0)
            cv2.putText(vis, f"{lab} max={p.max():.2f} >.5={m.mean()*100:.1f}%", (12, 40),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.1, (255, 255, 255), 3, cv2.LINE_AA)
            s = 700.0 / max(vis.shape[:2])
            tiles.append(cv2.resize(vis, None, fx=s, fy=s))
        hh = max(t.shape[0] for t in tiles)
        ww = max(t.shape[1] for t in tiles)
        tiles = [cv2.copyMakeBorder(t, 0, hh - t.shape[0], 0, ww - t.shape[1],
                                    cv2.BORDER_CONSTANT, value=(30, 30, 30)) for t in tiles]
        rows.append(np.hstack(tiles))
    if rows:
        ww2 = max(r.shape[1] for r in rows)
        rows = [cv2.copyMakeBorder(r, 0, 0, 0, ww2 - r.shape[1],
                                   cv2.BORDER_CONSTANT, value=(30, 30, 30)) for r in rows]
        grid = np.vstack(rows)
        cv2.imwrite(os.path.join(OUT, "heat_diag.jpg"), grid, [cv2.IMWRITE_JPEG_QUALITY, 90])
        print("wrote", os.path.join(OUT, "heat_diag.jpg"), grid.shape)


if __name__ == "__main__":
    main()
