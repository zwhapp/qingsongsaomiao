import cv2, numpy as np, os
D = r"C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro\tools"

def order_corners(pts):
    pts = [tuple(p) for p in pts]
    s = sorted(pts, key=lambda p: p[0]+p[1])
    tl, br = s[0], s[-1]
    rem = [p for p in pts if p != tl and p != br]
    if len(rem) != 2: return None
    d = sorted(rem, key=lambda p: p[1]-p[0])
    return np.array([tl, d[0], br, d[1]], dtype=np.float32)

def run(name):
    img = cv2.imread(os.path.join(D, name))
    h, w = img.shape[:2]
    k = 1080/max(h, w)
    work = cv2.resize(img, (int(w*k), int(h*k)))
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    image_area = work.shape[0]*work.shape[1]
    blurred = cv2.GaussianBlur(gray, (9,9), 0)
    _, mask = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY+cv2.THRESH_OTSU)
    # 开运算断开细连接，再闭运算填小洞
    ker = cv2.getStructuringElement(cv2.MORPH_RECT, (25,25))
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, ker)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, ker)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask)
    print(f"--- {name}: {n-1} components ---")
    best = None
    for i in range(1, n):
        x, y, cw, ch, area = stats[i]
        if area < image_area*0.10: continue
        comp = (labels == i).astype(np.uint8)
        cnts, _ = cv2.findContours(comp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        c = max(cnts, key=cv2.contourArea)
        hull = cv2.convexHull(c)
        hull_area = cv2.contourArea(hull)
        fill = area/hull_area if hull_area > 0 else 0
        extent = area/(cw*ch)
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02*peri, True)
        # 矩形度评分：填充率 × 矩形贴合度
        rect = cv2.minAreaRect(c)
        rect_area = rect[1][0]*rect[1][1]
        rect_fit = area/rect_area if rect_area > 0 else 0
        score = fill * rect_fit
        print(f"  comp#{i}: area={area/image_area:.2f} bbox={cw}x{ch} fill={fill:.2f} extent={extent:.2f} rect_fit={rect_fit:.2f} score={score:.3f} pts={len(approx)}")
        if best is None or score > best[0]:
            best = (score, i, comp, c, approx)
    if best:
        score, i, comp, c, approx = best
        q = order_corners(cv2.boxPoints(cv2.minAreaRect(c)))
        vis = work.copy()
        cv2.polylines(vis, [q.astype(int)], True, (0,255,0), 4)
        cv2.polylines(vis, [approx.reshape(-1,2)], True, (255,0,0), 2)
        out = os.path.join(D, f"cc_{name}")
        cv2.imwrite(out, vis)
        print(f"  -> best comp#{i} score={score:.3f}, saved {out}")

run("photo_full.jpg")
run("photo_moved.jpg")
