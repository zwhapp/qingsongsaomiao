import numpy as np, cv2, os
from ai_edge_litert.interpreter import Interpreter

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro"
MODEL = os.path.join(D, "_model_dl/model.tflite")

m = Interpreter(model_path=MODEL)
m.allocate_tensors()
inp = m.get_input_details()[0]
out = m.get_output_details()[0]

def infer(img_bgr, norm='div255'):
    """输入 BGR 图，返回与图同尺寸的概率图 float32 [0,1]"""
    h, w = img_bgr.shape[:2]
    rgb = cv2.cvtColor(cv2.resize(img_bgr, (256, 256)), cv2.COLOR_BGR2RGB)
    a = rgb.astype(np.float32)
    if norm == 'div255':
        a = a / 255.0
    elif norm == 'minus1':
        a = a / 127.5 - 1.0
    x = a[None, ...]
    m.set_tensor(inp['index'], x)
    m.invoke()
    logits = m.get_tensor(out['index'])[0, :, :, 0]
    # 输出是 logits → sigmoid 得到概率
    prob = 1.0 / (1.0 + np.exp(-logits))
    return prob, cv2.resize(prob, (w, h), interpolation=cv2.INTER_LINEAR), x, logits

def postprocess(prob, img_bgr, tag):
    h, w = prob.shape
    area = h*w
    vis = img_bgr.copy()
    for th in [0.3, 0.5, 0.7]:
        mask = (prob > th).astype(np.uint8)*255
        k = np.ones((15,15), np.uint8)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, k)
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, k)
        n, labels, stats, _ = cv2.connectedComponentsWithStats(mask)
        comps = [(stats[i][4], i) for i in range(1, n) if stats[i][4] > area*0.05]
        comps.sort(reverse=True)
        print(f"  [{tag}] th={th}: {len(comps)} comps >=5%", [(round(c[0]/area,2)) for c in comps[:3]])
        if comps:
            c = (labels == comps[0][1]).astype(np.uint8)
            cnts, _ = cv2.findContours(c, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            cn = max(cnts, key=cv2.contourArea)
            rect = cv2.minAreaRect(cn)
            box = cv2.boxPoints(rect).astype(int)
            cv2.polylines(vis, [box], True, (0,255,0), 4)
            cv2.drawContours(vis, [cn], -1, (255,0,0), 2)
    cv2.imwrite(os.path.join(D, "tools", f"ml_{tag}.jpg"), vis)
    # 概率热力图
    heat = cv2.applyColorMap((prob*255).astype(np.uint8), cv2.COLORMAP_JET)
    cv2.imwrite(os.path.join(D, "tools", f"mlheat_{tag}.jpg"), heat)
    print(f"  [{tag}] prob range: {prob.min():.3f}..{prob.max():.3f} mean={prob.mean():.3f}")

for norm in ['div255', 'minus1']:
    print(f"########## 归一化方式: {norm} ##########")
    for name in ["book2.jpg", "photo_full.jpg"]:
        p = os.path.join(D, "tools", name)
        if not os.path.exists(p): continue
        img = cv2.imread(p)
        print(f"=== {name} {img.shape} ===")
        prob, up, x, logits = infer(img, norm)
        postprocess(up, img, f"{name.replace('.jpg','')}_{norm}")
