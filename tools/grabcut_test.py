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

    # 种子：文字密度区域 = 可能前景
    bh = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (15,15)))
    _, tb = cv2.threshold(bh, 20, 255, cv2.THRESH_BINARY)
    dens = cv2.GaussianBlur(tb, (61,61), 0)
    _, dm = cv2.threshold(dens, 12, 255, cv2.THRESH_BINARY)
    ker = cv2.getStructuringElement(cv2.MORPH_RECT, (31,31))
    dm = cv2.morphologyEx(dm, cv2.MORPH_CLOSE, ker)
    dm = cv2.morphologyEx(dm, cv2.MORPH_OPEN, ker)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(dm)
    i = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    seed = (labels == i).astype(np.uint8)

    # 缩小一半跑 GrabCut 提速
    small = cv2.resize(work, (work.shape[1]//2, work.shape[0]//2))
    seed_s = cv2.resize(seed, (small.shape[1], small.shape[0]), interpolation=cv2.INTER_NEAREST)
    gc_mask = np.full(small.shape[:2], cv2.GC_PR_BGD, np.uint8)
    gc_mask[seed_s > 0] = cv2.GC_PR_FGD
    gc_mask[seed_s == 0] = cv2.GC_PR_BGD
    # 确定背景：离种子区域很远的四角
    h2, w2 = gc_mask.shape
    ys, xs = np.where(seed_s > 0)
    cx, cy = xs.mean(), ys.mean()
    for (yy, xx) in [(5,5),(5,w2-6),(h2-6,5),(h2-6,w2-6)]:
        cv2.circle(gc_mask, (xx,yy), 30, cv2.GC_BGD, -1)
    bgd = np.zeros((1,65), np.float64); fgd = np.zeros((1,65), np.float64)
    cv2.grabCut(small, gc_mask, None, bgd, fgd, 5, cv2.GC_INIT_WITH_MASK)
    fg = np.where((gc_mask==cv2.GC_FGD)|(gc_mask==cv2.GC_PR_FGD), 255, 0).astype(np.uint8)
    fg = cv2.resize(fg, (work.shape[1], work.shape[0]), interpolation=cv2.INTER_LINEAR)
    ker2 = cv2.getStructuringElement(cv2.MORPH_RECT, (21,21))
    fg = cv2.morphologyEx(fg, cv2.MORPH_OPEN, ker2)
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, ker2)
    cnts, _ = cv2.findContours(fg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts: print(name, "empty"); return
    c = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(c)
    print(f"{name}: grabcut fg area_ratio={area/image_area:.2f}")
    q = order_corners(cv2.boxPoints(cv2.minAreaRect(c)))
    approx = cv2.approxPolyDP(c, 0.02*cv2.arcLength(c, True), True)
    vis = work.copy()
    cv2.polylines(vis, [q.astype(int)], True, (0,255,0), 4)
    if len(approx) >= 4:
        cv2.polylines(vis, [approx.reshape(-1,2)], True, (255,0,0), 2)
    out = os.path.join(D, f"gc_{name}")
    cv2.imwrite(out, vis)
    print("  saved", out, "approx pts:", len(approx))

run("photo_full.jpg")
run("photo_moved.jpg")
