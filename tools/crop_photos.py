import cv2, os
SRC_DIR = r"C:\Users\zwhco\.workbuddy\clipboard-images"
OUT_DIR = r"C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro\tools"
for src, name in [
    ("clipboard-2026-10-14T09-13-45-713Z-75daf2b4.jpg", "photo_moved.jpg"),
    ("clipboard-2026-10-14T09-13-45-717Z-61b53eda.jpg", "photo_full.jpg"),
]:
    img = cv2.imread(os.path.join(SRC_DIR, src))
    h, w = img.shape[:2]
    y0, y1 = int(h*0.185), int(h*0.775)
    x0, x1 = int(w*0.012), int(w*0.988)
    crop = img[y0:y1, x0:x1]
    # 再往内收 35px，去掉截图照片边界形成的人工矩形边
    inner = crop[35:-35, 35:-35]
    cv2.imwrite(os.path.join(OUT_DIR, name), inner)
    print(name, inner.shape)
