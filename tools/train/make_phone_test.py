# -*- coding: utf-8 -*-
"""生成一张全分辨率手机测试页：印刷正文 + 纯黑手写批注（无彩色）。
复用 gen_handwriting 的渲染函数，输出 JPEG 直接推到模拟器相册。
"""
import os, sys, random
import numpy as np
import cv2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_handwriting import render_printed_page, HandwritingLayer

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "phone_test.jpg")

def main():
    rng = np.random.default_rng(2026)
    w, h = 1700, 2400
    page = render_printed_page(w, h, rng)          # RGB uint8
    page3 = np.repeat(page[..., None], 3, axis=2)

    # 纯黑手写墨层
    hw = HandwritingLayer(w, h, strength=0.85)
    # 1) 行间圈注 + 波浪下划线（正文中部）
    hw.long_stroke(2, 250, 640, 700, rng)          # 椭圆圈注
    hw.long_stroke(0, 260, 900, 780, rng)          # 波浪下划线
    # 2) 左页边竖排批注
    for k in range(6):
        hw.scribble_char(120, 1000 + k * 130, 95, rng)
    # 3) 右上角大字批注两行
    hw.scribble_line(1000, 1500, 320, 110, rng)
    hw.scribble_line(1000, 1420, 450, 110, rng)

    a = (hw.img.astype(np.float32) / 255.0)[..., None]
    ink_bgr = np.array([15.0, 15.0, 18.0], np.float32)   # 纯黑墨
    page3 = page3 * (1 - a) + ink_bgr[None, None, :] * a

    img = np.clip(page3, 0, 255).astype(np.uint8)
    # 轻微倾斜，模拟拍照
    M = cv2.getRotationMatrix2D((w / 2, h / 2), 0.8, 1.0)
    img = cv2.warpAffine(img, M, (w, h), flags=cv2.INTER_LINEAR,
                         borderValue=(238, 238, 238))

    bgr = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)
    cv2.imwrite(OUT, bgr, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print("saved:", OUT, bgr.shape)

if __name__ == "__main__":
    main()
