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

def report(tag, mask):
    mask = mask.astype(np.uint8)*255
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (15,15))
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        print(f"{tag}: no contours"); return
    c = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(c)
    print(f"{tag}: largest area_ratio={area/image_area:.3f}", end=" ")
    if area < image_area*0.15:
        print("(too small)"); return
    rect = cv2.minAreaRect(c)
    box = cv2.boxPoints(rect)
    q = order_corners(box)
    hull_pts = cv2.approxPolyDP(c, 0.02*cv2.arcLength(c, True), True)
    print(f"pts_approx={len(hull_pts)}")
    vis = work.copy()
    cv2.polylines(vis, [q.astype(int)], True, (0,255,0), 4)
    cv2.polylines(vis, [hull_pts.reshape(-1,2).astype(int)], True, (255,0,0), 2)
    cv2.imwrite(os.path.join(D, f"seg_{tag.replace(' ','_').replace('/','_')}.jpg"), vis)

# 1) Otsu
_, otsu = cv2.threshold(cv2.GaussianBlur(gray,(9,9),0), 0, 255, cv2.THRESH_BINARY+cv2.THRESH_OTSU)
print("otsu threshold value:", _)
report("otsu", otsu > 0)

# 2) CIE L*a*b 亮度通道 Otsu
lab = cv2.cvtColor(work, cv2.COLOR_BGR2LAB)
L = lab[:,:,0]
_, lot = cv2.threshold(cv2.GaussianBlur(L,(9,9),0), 0, 255, cv2.THRESH_BINARY+cv2.THRESH_OTSU)
report("lab-L otsu", lot > 0)

# 3) 自适应阈值（页面 vs 背景局部对比）
ad = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 51, 10)
report("adaptive", ad > 0)

# 4) 饱和度：纸张低饱和，桌布花纹高饱和
hsv = cv2.cvtColor(work, cv2.COLOR_BGR2HSV)
S = hsv[:,:,1]
print("S stats:", S.mean().round(1))
_, sot = cv2.threshold(cv2.GaussianBlur(S,(9,9),0), 0, 255, cv2.THRESH_BINARY_INV+cv2.THRESH_OTSU)
report("low-sat", sot > 0)
