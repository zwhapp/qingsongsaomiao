# 离线复现 DocScanPro DocumentGeometry.detectDocumentQuad 三级流水线
# 用途：诊断真实照片为何检测不到边缘。参数与 Kotlin 版逐项对齐。
import cv2
import numpy as np
import sys

DETECT_MAX_EDGE = 1080
APPROX_EPSILON = 0.02
MIN_AREA_RATIO = 0.12

STAGES = [
    dict(name="S1", canny_low=60, canny_high=180, min_score=0.28, require_convex=True),
    dict(name="S2", canny_low=40, canny_high=120, min_score=0.20, require_convex=True),
    dict(name="S3", canny_low=30, canny_high=90,  min_score=0.14, require_convex=False),
]

def dist(a, b):
    return float(np.hypot(a[0]-b[0], a[1]-b[1]))

def order_corners(pts):
    pts = [tuple(p) for p in pts]
    by_sum = sorted(pts, key=lambda p: p[0]+p[1])
    tl, br = by_sum[0], by_sum[-1]
    rem = [p for p in pts if p != tl and p != br]
    if len(rem) != 2:
        return None
    by_diff = sorted(rem, key=lambda p: p[1]-p[0])
    tr, bl = by_diff[0], by_diff[-1]
    if tr == bl:
        return None
    return np.array([tl, tr, br, bl], dtype=np.float32)

def score_quad(quad, image_area, contour_area):
    tl, tr, br, bl = quad
    w_top = dist(tl, tr); w_bot = dist(bl, br)
    h_left = dist(tl, bl); h_right = dist(tr, br)
    if min(w_top, w_bot, h_left, h_right) < 1.0:
        return 0.0
    w_ratio = min(w_top, w_bot)/max(w_top, w_bot)
    h_ratio = min(h_left, h_right)/max(h_left, h_right)
    squareness = w_ratio*h_ratio
    area_ratio = contour_area/image_area
    return squareness*0.6 + area_ratio*0.4

def detect_once(work, stage, verbose=True):
    gray = cv2.cvtColor(work, cv2.COLOR_RGBA2GRAY) if work.shape[2]==4 else cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5,5), 0)
    edges = cv2.Canny(blurred, stage["canny_low"], stage["canny_high"], apertureSize=3, L2gradient=True)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5,5))
    closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
    closed = cv2.dilate(closed, kernel, iterations=1)
    contours, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    image_area = work.shape[0]*work.shape[1]
    if verbose:
        print(f"  edges: {edges.shape}, contours total: {len(contours)}")
    best, best_score = None, 0.0
    big = 0
    for c in contours:
        area = cv2.contourArea(c)
        if area < image_area*MIN_AREA_RATIO:
            continue
        big += 1
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, APPROX_EPSILON*peri, True)
        n = len(approx)
        quad = None; score = 0.0; why = ""
        if n == 4:
            ordered = order_corners(approx.reshape(-1,2))
            if ordered is not None:
                if (not stage["require_convex"]) or cv2.isContourConvex(approx.reshape(-1,2)):
                    quad = ordered
                    score = score_quad(ordered, image_area, area)
                else:
                    why = "non-convex"
            else:
                why = "degenerate order"
        elif 5 <= n <= 12:
            rect = cv2.minAreaRect(c)
            box = cv2.boxPoints(rect)
            ordered = order_corners(box)
            if ordered is not None:
                if (not stage["require_convex"]) or cv2.isContourConvex(box.astype(np.int32)):
                    quad = ordered
                    score = score_quad(ordered, image_area, area)*0.9
                else:
                    why = "non-convex(fallback)"
        else:
            why = f"pts={n} out of range"
        if verbose and quad is not None:
            print(f"    candidate: pts={n} area_ratio={area/image_area:.3f} score={score:.3f}")
        elif verbose and big <= 8:
            print(f"    skipped: pts={n} area_ratio={area/image_area:.3f} ({why})")
        if quad is not None and score > best_score:
            best, best_score = quad, score
    if verbose:
        print(f"  area-pass contours: {big}, best_score={best_score:.3f} (min {stage['min_score']})")
    if best is None or best_score < stage["min_score"]:
        return None, best_score
    return best, best_score

def detect(img_bgr, verbose=True):
    h, w = img_bgr.shape[:2]
    longest = max(h, w)
    if longest > DETECT_MAX_EDGE:
        k = DETECT_MAX_EDGE/longest
        work = cv2.resize(img_bgr, (int(w*k), int(h*k)), interpolation=cv2.INTER_AREA)
    else:
        work = img_bgr
    for stage in STAGES:
        if verbose:
            print(f"[{stage['name']}] canny={stage['canny_low']}/{stage['canny_high']} minScore={stage['min_score']} convex={stage['require_convex']}")
        quad, score = detect_once(work, stage, verbose)
        if quad is not None:
            print(f"  -> ACCEPTED at {stage['name']} score={score:.3f}")
            return quad, work
        print(f"  -> {stage['name']} rejected (best {score:.3f} < {stage['min_score']})")
    return None, work

if __name__ == "__main__":
    for path in sys.argv[1:]:
        print("="*70)
        print(path)
        img = cv2.imread(path)
        if img is None:
            print("  READ FAIL"); continue
        print(f"  size: {img.shape[1]}x{img.shape[0]}")
        quad, work = detect(img)
        if quad is None:
            print("  RESULT: NULL（与真机一致）")
        else:
            print("  RESULT: quad =", quad.astype(int).tolist())
        vis = work.copy()
        if quad is not None:
            cv2.polylines(vis, [quad.astype(int)], True, (0,255,0), 3)
        out = path.rsplit(".",1)[0] + "_diag.jpg"
        cv2.imwrite(out, vis)
        print(f"  vis saved: {out}")
