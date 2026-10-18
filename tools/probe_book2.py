import cv2, numpy as np, os
D = r"C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro\tools"
img = cv2.imread(os.path.join(D, "book2.jpg"))
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

# S2 参数
blurred = cv2.GaussianBlur(gray, (5,5), 0)
edges = cv2.Canny(blurred, 40, 120, apertureSize=3, L2gradient=True)
kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5,5))
closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
closed = cv2.dilate(closed, kernel, iterations=1)
cv2.imwrite(os.path.join(D, "book2_edges.jpg"), closed)
cnts, _ = cv2.findContours(closed.copy(), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
ranked = sorted(cnts, key=cv2.contourArea, reverse=True)[:6]
for i, c in enumerate(ranked):
    area = cv2.contourArea(c)
    peri = cv2.arcLength(c, True)
    approx = cv2.approxPolyDP(c, 0.02*peri, True)
    print(f"#{i}: area_ratio={area/image_area:.3f} pts={len(approx)}")
vis = work.copy()
cv2.drawContours(vis, ranked, -1, (0,0,255), 3)
cv2.imwrite(os.path.join(D, "book2_contours.jpg"), vis)
