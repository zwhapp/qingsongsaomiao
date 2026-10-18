import cv2, numpy as np, os
D = r"C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro\tools"

def estimate_skew(img_bgr):
    h, w = img_bgr.shape[:2]
    k = 1000/max(h, w)
    work = cv2.resize(img_bgr, (int(w*k), int(h*k))) if k < 1 else img_bgr.copy()
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    _, bin_ = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV+cv2.THRESH_OTSU)
    klen = min(max(work.shape[1]/30, 8), 40)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (int(klen), 1))
    bin_ = cv2.morphologyEx(bin_, cv2.MORPH_CLOSE, kernel)
    lines = cv2.HoughLinesP(bin_, 1, np.pi/180, 60, minLineLength=work.shape[1]/6, maxLineGap=20)
    if lines is None: return 0.0
    cands = []
    for l in lines.reshape(-1, 4):
        x1, y1, x2, y2 = map(float, l)
        ang = np.degrees(np.arctan2(y2-y1, x2-x1))
        if abs(ang) <= 20:
            cands.append((ang, np.hypot(x2-x1, y2-y1)))
    if not cands: return 0.0
    cands.sort()
    total = sum(l for _, l in cands)
    acc = 0.0
    for ang, l in cands:
        acc += l
        if acc >= total/2: return ang
    return cands[-1][0]

base_full = cv2.imread(os.path.join(D, "book2.jpg"))
# 单页场景（右页，接近真实"矫正后"的输入）：大约 x 150..660, y 210..930（1080 高坐标）
k0 = 1080/base_full.shape[0]
bw, bh_ = base_full.shape[1], base_full.shape[0]
x0, x1 = int(150/k0*1), int(660/k0)
# 直接按比例裁：work 坐标 ≈ 原图坐标 * k0
crop = base_full[int(210*k0):int(930*k0), int(150*k0):int(660*k0)]
cv2.imwrite(os.path.join(D, "book2_page.jpg"), crop)
base = crop
print("single-page crop:", base.shape)
for deg in [0, 3, -3, 7, -7]:
    if deg == 0:
        img = base
    else:
        h, w = base.shape[:2]
        M = cv2.getRotationMatrix2D((w/2, h/2), deg, 1.0)  # OpenCV 正角=逆时针
        cos, sin = abs(M[0,0]), abs(M[0,1])
        nw, nh = int(h*sin+w*cos), int(h*cos+w*sin)
        M[0,2] += nw/2 - w/2; M[1,2] += nh/2 - h/2
        img = cv2.warpAffine(base, M, (nw, nh), borderValue=(255,255,255))
    est = estimate_skew(img)
    print(f"rotated {deg:+d}° (屏幕视觉) -> estimated {est:+.2f}°")
