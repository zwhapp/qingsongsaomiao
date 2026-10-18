import cv2, numpy as np, os
D = r"C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro\tools"
img = cv2.imread(os.path.join(D, "photo_full.jpg"))
h, w = img.shape[:2]
k = 1080/max(h, w)
work = cv2.resize(img, (int(w*k), int(h*k)))
gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
blurred = cv2.GaussianBlur(gray, (5,5), 0)

for lo, hi in [(60,180), (30,90)]:
    edges = cv2.Canny(blurred, lo, hi, apertureSize=3, L2gradient=True)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5,5))
    closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
    closed = cv2.dilate(closed, kernel, iterations=1)
    contours, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    image_area = work.shape[0]*work.shape[1]
    print(f"=== canny {lo}/{hi}: {len(contours)} contours ===")
    ranked = sorted(contours, key=cv2.contourArea, reverse=True)[:8]
    for i, c in enumerate(ranked):
        area = cv2.contourArea(c)
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02*peri, True)
        print(f"  #{i}: area_ratio={area/image_area:.3f} pts_after_approx={len(approx)} convex={cv2.isContourConvex(approx) if len(approx)>=3 else '-'}")
    vis = work.copy()
    cv2.drawContours(vis, ranked, -1, (0,0,255), 3)
    cv2.imwrite(os.path.join(D, f"contours_{lo}.jpg"), vis)
    cv2.imwrite(os.path.join(D, f"edges_{lo}.jpg"), closed)
print("gray stats:", gray.min(), gray.max(), gray.mean().round(1))
# 左页亮度 vs 背景亮度采样
print("left page sample (y=500,x=300):", gray[500,300], " background(y=150,x=150):", gray[150,150])
