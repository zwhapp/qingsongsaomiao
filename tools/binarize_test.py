# 对比二值化方案：全局 Otsu（现状） vs 局部自适应（拟改）
# 用真实失败照片验证「左下角字迹不清」是否被修复。
import numpy as np, cv2, os, sys

D = r"C:/Users/zwhco/WorkBuddy/2026-10-14-11-24-59/DocScanPro"
OUT = os.path.join(D, "tools")


def estimate_background(bgr):
    """复刻 ScanEnhancer.estimateBackground：大核闭运算 + 高斯模糊。"""
    longest = max(bgr.shape[:2])
    k = int(min(max(longest // 25, 21), 151))
    if k % 2 == 0:
        k += 1
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    bg = cv2.morphologyEx(bgr, cv2.MORPH_CLOSE, kernel)
    bg = cv2.GaussianBlur(bg, (0, 0), k / 2.0)
    return np.maximum(bg, 1.0)


def pipeline_before(img):
    """现状：normalize → gray → CLAHE → USM → 全局 Otsu → median3 + open2"""
    bg = estimate_background(img.astype(np.float32))
    norm = np.clip(img.astype(np.float32) / bg * 255.0, 0, 255).astype(np.uint8)
    gray = cv2.cvtColor(norm, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(2.5, (8, 8))
    gray = clahe.apply(gray)
    # USM amount=0.6
    blurred = cv2.GaussianBlur(gray, (0, 0), 1.2)
    gray = cv2.addWeighted(gray, 1.6, blurred, -0.6, 0.0)
    _, th = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    th = cv2.medianBlur(th, 3)
    k2 = cv2.getStructuringElement(cv2.MORPH_RECT, (2, 2))
    th = cv2.morphologyEx(th, cv2.MORPH_OPEN, k2)
    return th, gray


def pipeline_after(img):
    """拟改：normalize → gray → 局部小核 Otsu（自适应窗口） → 形态学"""
    bg = estimate_background(img.astype(np.float32))
    norm = np.clip(img.astype(np.float32) / bg * 255.0, 0, 255).astype(np.uint8)
    gray = cv2.cvtColor(norm, cv2.COLOR_BGR2GRAY)
    # 轻微去噪，避免局部阈值把小噪点也提成文字
    gray = cv2.bilateralFilter(gray, 5, 40, 40)
    # 局部自适应阈值：窗口按图像尺寸取，保证窗口内既有文字又有纸面
    longest = max(gray.shape[:2])
    win = int(min(max(longest // 30, 25), 61))
    if win % 2 == 0:
        win += 1
    th = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, win, 12
    )
    th = cv2.medianBlur(th, 3)
    k2 = cv2.getStructuringElement(cv2.MORPH_RECT, (2, 2))
    th = cv2.morphologyEx(th, cv2.MORPH_OPEN, k2)
    return th, gray


def stats(th, name):
    """黑白比例 + 以连通域数量粗略估文字量。"""
    black = float(np.count_nonzero(th == 0)) / th.size
    n, _, st, _ = cv2.connectedComponentsWithStats((255 - th), 8)
    # 只统计面积 8~600 的小块（文字笔画）
    small = sum(1 for i in range(1, n) if 8 <= st[i, cv2.CC_STAT_AREA] <= 600)
    print(f"  {name}: 黑像素 {black*100:.1f}%  文字状连通块 {small}")
    return black, small


for name in ["shared_book.jpg", "book2.jpg", "photo_full.jpg"]:
    p = os.path.join(OUT, name)
    if not os.path.exists(p):
        print(f"--- {name} 不存在，跳过 ---")
        continue
    img = cv2.imread(p)
    print(f"=== {name}  {img.shape[1]}x{img.shape[0]} ===")
    b, gb = pipeline_before(img)
    a, ga = pipeline_after(img)
    stats(b, "改前(全局Otsu)")
    stats(a, "改后(局部自适应)")
    cv2.imwrite(os.path.join(OUT, f"bin_before_{name}"), b)
    cv2.imwrite(os.path.join(OUT, f"bin_after_{name}"), a)
    # 拼一张对比图，左右各半
    crop = (slice(0, img.shape[0]), slice(0, img.shape[1]))
    side = np.hstack([cv2.cvtColor(b, cv2.COLOR_GRAY2BGR), cv2.cvtColor(a, cv2.COLOR_GRAY2BGR)])
    cv2.imwrite(os.path.join(OUT, f"bin_cmp_{name}"), side)
