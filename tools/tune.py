import cv2, numpy as np, os
D = r"C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro\tools"
img = cv2.imread(os.path.join(D, "photo_full.jpg"))
h, w = img.shape[:2]
k = 1080/max(h, w)
work = cv2.resize(img, (int(w*k), int(h*k)))
gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
image_area = work.shape[0]*work.shape[1]

def order_corners(pts):
    pts = [tuple(p) for p in pts]
    s = sorted(pts, key=lambda p: p[0]+p[1])
    tl, br = s[0], s[-1]
    rem = [p for p in pts if p != tl and p != br]
    if len(rem) != 2: return None
    d = sorted(rem, key=lambda p: p[1]-p[0])
    return np.array([tl, d[0], br, d[1]], dtype=np.float32)

def try_params(blur_k, canny_lo, canny_hi, close_k, dilate_iter):
    blurred = cv2.GaussianBlur(gray, (blur_k, blur_k), 0)
    edges = cv2.Canny(blurred, canny_lo, canny_hi, apertureSize=3, L2gradient=True)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (close_k, close_k))
    closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
    closed = cv2.dilate(closed, kernel, iterations=dilate_iter)
    contours, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    results = []
    for c in contours:
        area = cv2.contourArea(c)
        if area < image_area*0.12: continue
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02*peri, True)
        n = len(approx)
        quad = None
        if n == 4:
            quad = order_corners(approx.reshape(-1,2))
        elif 5 <= n <= 12:
            quad = order_corners(cv2.boxPoints(cv2.minAreaRect(c)))
        if quad is None: continue
        tl, tr, br, bl = quad
        wt, wb = np.hypot(*(tr-tl)), np.hypot(*(br-bl))
        hl, hr = np.hypot(*(bl-tl)), np.hypot(*(br-tr))
        if min(wt,wb,hl,hr) < 1: continue
        sq = (min(wt,wb)/max(wt,wb))*(min(hl,hr)/max(hl,hr))
        score = sq*0.6 + (area/image_area)*0.4
        results.append((score, n, area/image_area, quad))
    return results, closed

best_overall = None
for blur_k in [15, 21, 31]:
    for lo, hi in [(60,180), (40,120), (30,90)]:
        for close_k, dil in [(9,1), (15,2)]:
            res, closed = try_params(blur_k, lo, hi, close_k, dil)
            tag = f"blur={blur_k} canny={lo}/{hi} close={close_k}x{dil}"
            if res:
                res.sort(reverse=True)
                s, n, ar, q = res[0]
                print(f"{tag} -> {len(res)} cand, best score={s:.3f} pts={n} area={ar:.2f}")
                if best_overall is None or s > best_overall[0]:
                    best_overall = (s, tag, q, closed)
            else:
                print(f"{tag} -> none")

if best_overall:
    s, tag, q, closed = best_overall
    print("\nBEST:", tag, "score", round(s,3), q.astype(int).tolist())
    vis = work.copy()
    cv2.polylines(vis, [q.astype(int)], True, (0,255,0), 4)
    cv2.imwrite(os.path.join(D, "best_vis.jpg"), vis)
