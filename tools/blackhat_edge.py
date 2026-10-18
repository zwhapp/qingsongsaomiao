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

def score_quad(q, image_area, contour_area):
    tl, tr, br, bl = q
    wt, wb = np.hypot(*(tr-tl)), np.hypot(*(br-bl))
    hl, hr = np.hypot(*(bl-tl)), np.hypot(*(br-tr))
    if min(wt,wb,hl,hr) < 1: return 0.0
    return (min(wt,wb)/max(wt,wb))*(min(hl,hr)/max(hl,hr))*0.6 + (contour_area/image_area)*0.4

def run(name):
    img = cv2.imread(os.path.join(D, name))
    h, w = img.shape[:2]
    k = 1080/max(h, w)
    work = cv2.resize(img, (int(w*k), int(h*k)))
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    image_area = work.shape[0]*work.shape[1]

    # 关键一步：blackhat 提取暗色小结构（文字），从灰度中减除 → 文字变平
    bh = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT,
                          cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (21,21)))
    clean = cv2.subtract(gray, bh)
    blurred = cv2.GaussianBlur(clean, (7,7), 0)

    for lo, hi, ck in [(60,180,5),(40,120,5),(30,90,5),(40,120,9),(30,90,9)]:
        edges = cv2.Canny(blurred, lo, hi, apertureSize=3, L2gradient=True)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (ck,ck))
        closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
        closed = cv2.dilate(closed, kernel, iterations=1)
        cnts, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        found = []
        for c in cnts:
            area = cv2.contourArea(c)
            if area < image_area*0.12: continue
            peri = cv2.arcLength(c, True)
            approx = cv2.approxPolyDP(c, 0.02*peri, True)
            n = len(approx)
            q = None
            if n == 4:
                q = order_corners(approx.reshape(-1,2))
            elif 5 <= n <= 12:
                q = order_corners(cv2.boxPoints(cv2.minAreaRect(c)))
            if q is None: continue
            found.append((score_quad(q, image_area, area), n, area/image_area, q))
        tag = f"{name} canny={lo}/{hi} close={ck}"
        if found:
            found.sort(reverse=True)
            s, n, ar, q = found[0]
            print(f"{tag} -> {len(found)} cand, best score={s:.3f} pts={n} area={ar:.2f}")
            if ar > 0.15 and ar < 0.95:
                vis = work.copy()
                cv2.polylines(vis, [q.astype(int)], True, (0,255,0), 4)
                out = os.path.join(D, f"bh_{name.replace('.jpg','')}_{lo}_{ck}.jpg")
                cv2.imwrite(out, vis)
        else:
            print(f"{tag} -> none")

run("photo_full.jpg")
