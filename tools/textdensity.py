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

    # 1) blackhat: 提取比局部背景暗的小结构（=文字笔画）
    bh = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (15,15)))
    _, tb = cv2.threshold(bh, 20, 255, cv2.THRESH_BINARY)
    # 2) 文字密度：大模糊 + 再阈值
    dens = cv2.GaussianBlur(tb, (61,61), 0)
    _, dm = cv2.threshold(dens, 12, 255, cv2.THRESH_BINARY)
    # 3) 形态学整形 + 最大连通域
    ker = cv2.getStructuringElement(cv2.MORPH_RECT, (31,31))
    dm = cv2.morphologyEx(dm, cv2.MORPH_CLOSE, ker)
    dm = cv2.morphologyEx(dm, cv2.MORPH_OPEN, ker)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(dm)
    if n <= 1:
        print(f"{name}: no text-density region"); return
    i = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    x, y, cw, ch, area = stats[i]
    print(f"{name}: text region area_ratio={area/image_area:.2f} bbox=({x},{y},{cw},{ch})")
    comp = (labels == i).astype(np.uint8)
    cnts, _ = cv2.findContours(comp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    c = max(cnts, key=cv2.contourArea)
    # 矩形拟合 + 多边形逼近两条路都画出来
    q = order_corners(cv2.boxPoints(cv2.minAreaRect(c)))
    approx = cv2.approxPolyDP(c, 0.02*cv2.arcLength(c, True), True)
    vis = work.copy()
    cv2.polylines(vis, [q.astype(int)], True, (0,255,0), 4)
    if len(approx) >= 4:
        cv2.polylines(vis, [approx.reshape(-1,2)], True, (255,0,0), 2)
    out = os.path.join(D, f"text_{name}")
    cv2.imwrite(out, vis)
    print(f"  saved {out}")

run("photo_full.jpg")
run("photo_moved.jpg")
