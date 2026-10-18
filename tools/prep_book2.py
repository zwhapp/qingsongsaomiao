import cv2, numpy as np, os
SRC_DIR = r"C:\Users\zwhco\.workbuddy\clipboard-images"
D = r"C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro\tools"
sys_path = os.path.join(SRC_DIR, "clipboard-2026-10-14T09-48-20-616Z-2b1bf112.jpg")
img = cv2.imread(sys_path)
h, w = img.shape[:2]
print("screenshot:", img.shape)

# 照片区域：标题栏以下到"重置/下一步"之间（比例裁剪 + 内收 40px 去人工边）
y0, y1 = int(h*0.185), int(h*0.86)
x0, x1 = int(w*0.012), int(w*0.988)
crop = img[y0:y1, x0:x1][40:-40, 40:-40]
cv2.imwrite(os.path.join(D, "book2_raw.jpg"), crop)

# 修补绿色叠加（框线+圆点）：HSV 中绿色高亮像素用邻域中值填充
hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
green = cv2.inRange(hsv, (35, 80, 80), (85, 255, 255))
print("green pixels:", (green>0).sum())
mask = (green > 0).astype(np.uint8) * 255
mask = cv2.dilate(mask, np.ones((7,7), np.uint8))
inp = cv2.inpaint(crop, mask, 5, cv2.INPAINT_TELEA)
cv2.imwrite(os.path.join(D, "book2.jpg"), inp)
print("saved book2.jpg", inp.shape)
