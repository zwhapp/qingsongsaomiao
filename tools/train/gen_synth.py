# 合成训练数据生成器 v3：修正第二轮「只认文字行」的域差距。
#
# v3 相对 v2 的三处关键修改（对应 tools/train/evalout/heat_diag.jpg 的诊断）：
#   1. 干扰物重设计 —— v2 的干扰物是白纸（含淡文字），与文档外观无法区分，
#      模型被迫抓住「有文字=文档」这个假线索，真实照片上只圈文字块。
#      v3 干扰物全部改为视觉可区分物体：深色/彩色杂物（书、手机、杯垫的抽象
#      几何块）与「页边露出的纸条」（细长亮条，明显小于主文档）。
#   2. 超采样渲染 —— 场景先在 768px 渲染再 INTER_AREA 缩到 256，
#      复现真实相机 4000px→256 的重下采样统计（文字变成细糊条而非实心条）。
#   3. 真实域锚点 —— 用自研 TextSeed 检测器（无许可问题）给 4 张自有真实
#      照片打伪标签，每张增广 16 份混入训练集，锚定真实图像统计。
#
# 设计不变的部分：
#   前景文档 —— 程序渲染，不使用任何第三方素材，数据 100% 自有；
#   掩码     —— 透视前的精确四边形顶点直接生成，标注零人工。
#
# 用法：python tools/train/gen_synth.py --out data/synth --count 5000 --seed 11
import argparse
import json
import os
import numpy as np
import cv2

OUT_SIZE = 256          # 模型输入尺寸
RENDER = 768            # 场景渲染尺寸（超采样后缩到 OUT_SIZE）
BG_MIN = 25             # 背景亮度下限
PAPER_RANGE = (238, 252)
REAL_LABELS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "real_labels.json")
REAL_PHOTOS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
REAL_COPIES = 16        # 每张真实锚点照片的增广份数


def fractal_noise(h, w, rng, octaves=4):
    """值噪声叠加以近似布/木/地毯纹理。"""
    acc = np.zeros((h, w), np.float32)
    amp = 1.0
    total = 0.0
    for o in range(octaves):
        gh = max(int(h / (2 ** (octaves - o))), 2)
        gw = max(int(w / (2 ** (octaves - o))), 2)
        base = rng.random((gh, gw)).astype(np.float32) * 255
        acc += cv2.resize(base, (w, h), interpolation=cv2.INTER_CUBIC) * amp
        total += amp
        amp *= 0.55
    return acc / total


def make_background(rng):
    """随机背景：程序纹理 / 纯色+噪声 / 真实照片补丁。"""
    h, w = RENDER + 180, RENDER + 180       # 略大，透视后裁齐
    kind = rng.integers(0, 3)
    if kind == 0:                             # 程序纹理
        bg = fractal_noise(h, w, rng)
        tint = rng.random(3) * np.array([0.35, 0.55, 0.8]) + 0.15
        bgr = bg[..., None] * np.array([tint[2], tint[1], tint[0]], np.float32)
        img = np.clip(bgr, 0, 255).astype(np.uint8)
    elif kind == 1:                           # 纯色 + 噪声
        base = rng.uniform(BG_MIN, 200, 3)
        img = np.ones((h, w, 3), np.float32) * base
        img += rng.normal(0, 12, (h, w, 3))
        img = np.clip(img, 0, 255).astype(np.uint8)
    else:                                     # 真实照片补丁（自有照片）
        patch = load_real_patch(rng)
        if patch is None:
            bg = fractal_noise(h, w, rng)
            img = np.clip(bg[..., None] * np.array([0.6, 0.5, 0.45]), 0, 255).astype(np.uint8)
        else:
            img = patch
    return img


REAL_POOL = []


def load_real_patch(rng):
    """从 tools/ 的真实照片随机裁一块无文档区域做背景（自 photo，无版权负担）。"""
    if not REAL_POOL:
        return None
    p = REAL_POOL[rng.integers(0, len(REAL_POOL))]
    ph, pw = p.shape[:2]
    if ph < 200 or pw < 200:
        return None
    ch = min(600, ph)
    cw = min(600, pw)
    y0 = rng.integers(0, ph - ch + 1)
    x0 = rng.integers(0, pw - cw + 1)
    crop = p[y0:y0 + ch, x0:x0 + cw]
    return cv2.resize(crop, (RENDER + 180, RENDER + 180), interpolation=cv2.INTER_AREA)


def make_document(rng):
    """渲染一张假文档 + 返回纸面四角（文档自身坐标系）。"""
    pw = int(rng.uniform(0.55, 0.95) * RENDER)
    ph = int(rng.uniform(0.6, 0.95) * RENDER)
    paper_val = rng.uniform(*PAPER_RANGE)
    doc = np.full((ph, pw), paper_val, np.float32)

    # 纸张纤维纹理：轻微多分辨率噪声，让纸面不是死平的
    doc += (fractal_noise(ph, pw, rng, octaves=3) - 127.5) * rng.uniform(0.02, 0.08)

    # 随机文字行：黑色条，随机行长/行距/缩进
    margin = rng.uniform(0.06, 0.14, 2)
    x0 = int(pw * margin[0])
    x1 = pw - int(pw * margin[0])
    y = int(ph * margin[1])
    line_h = max(int(rng.uniform(0.018, 0.035) * ph), 2)
    gap = max(int(rng.uniform(0.012, 0.03) * ph), 2)
    ink = rng.uniform(15, 60)
    while y + line_h < ph - margin[1] * ph:
        indent = 0 if rng.random() < 0.7 else int(rng.uniform(0, 0.15) * pw)
        len_ratio = rng.uniform(0.45, 1.0)
        xe = min(x0 + indent + int((x1 - x0 - indent) * len_ratio), x1)
        doc[y:y + line_h, x0 + indent:xe] = ink
        if rng.random() < 0.08:
            doc[max(y - line_h, 0):y, x0:x0 + int(rng.uniform(0.2, 0.5) * (x1 - x0))] = ink
        y += line_h + gap

    # 可选元素：表格线 / 图片框
    if rng.random() < 0.3:
        ty = int(rng.uniform(0.3, 0.7) * ph)
        doc[ty:ty + max(line_h // 2, 1), x0:x1] = ink
    if rng.random() < 0.25:
        iy = int(rng.uniform(0.35, 0.65) * ph)
        ih = int(rng.uniform(0.12, 0.25) * ph)
        ix0, ix1 = x0, x0 + int((x1 - x0) * rng.uniform(0.4, 0.9))
        doc[iy:iy + ih, ix0:ix1] = rng.uniform(120, 200)

    # 随机光照：纸面线性渐变 + 局部暗斑
    gx = rng.uniform(-40, 40)
    gy = rng.uniform(-40, 40)
    xx, yy = np.meshgrid(np.linspace(-1, 1, pw), np.linspace(-1, 1, ph))
    doc += (gx * xx + gy * yy).astype(np.float32)
    if rng.random() < 0.5:
        cx, cy = rng.uniform(0, pw), rng.uniform(0, ph)
        dist = np.sqrt((xx * pw - cx) ** 2 + (yy * ph - cy) ** 2)
        doc -= rng.uniform(20, 60) * np.exp(-(dist / (0.5 * pw)) ** 2)
    doc = np.clip(doc, 0, 255).astype(np.uint8)
    tint = np.array([rng.uniform(0.96, 1.06), rng.uniform(0.98, 1.03), 1.0], np.float32)
    doc3 = np.clip(doc[..., None].astype(np.float32) * tint, 0, 255).astype(np.uint8)

    corners = np.array([[0, 0], [pw - 1, 0], [pw - 1, ph - 1], [0, ph - 1]], np.float32)
    return doc3, corners


def paste_distractors(bg, rng):
    """往背景上贴视觉上可与文档区分的干扰物。

    ★ v2 教训：干扰物若是「白纸（含文字）」，与文档不可区分，模型会退化为
    「有文字=文档」的假线索。v3 只用两类：
      a) 深色/彩色杂物块 —— 书本封面/手机/杯垫的抽象几何体，暗且带色；
      b) 页边纸条 —— 沿画面边缘的细长亮条（模拟扇开书页露出的边），
         明显小于任何合理文档。
    """
    H, W = bg.shape[:2]
    out = bg.copy()

    def paste(patch):
        ph, pw = patch.shape[:2]
        if pw >= W and ph >= H:
            return
        ang = rng.uniform(0, 180)
        M = cv2.getRotationMatrix2D((pw / 2, ph / 2), ang, 1.0)
        cos, sin = abs(M[0, 0]), abs(M[0, 1])
        nw, nh = int(pw * cos + ph * sin), int(pw * sin + ph * cos)
        M[0, 2] += nw / 2 - pw / 2
        M[1, 2] += nh / 2 - ph / 2
        rot = cv2.warpAffine(patch, M, (nw, nh))
        x0 = int(rng.integers(0, max(W - nw, 1)))
        y0 = int(rng.integers(0, max(H - nh, 1)))
        x1, y1 = min(x0 + nw, W), min(y0 + nh, H)
        sub = rot[:y1 - y0, :x1 - x0].astype(np.float32)
        alpha = (sub.max(axis=2) > 0).astype(np.float32)[..., None]
        region = out[y0:y1, x0:x1].astype(np.float32)
        out[y0:y1, x0:x1] = np.clip(sub * alpha + region * (1 - alpha), 0, 255).astype(np.uint8)

    style = rng.random()
    if style < 0.55:
        # a) 深色/彩色杂物块（1~3 个）
        for _ in range(rng.integers(1, 4)):
            w = int(rng.uniform(0.12, 0.45) * RENDER)
            h = int(rng.uniform(0.12, 0.45) * RENDER)
            base = rng.uniform(20, 120, 3)                    # 暗色
            p = np.ones((h, w, 3), np.float32) * base
            p += rng.normal(0, 8, (h, w, 3))                  # 材质噪声
            # 边缘描一道更暗的边，模拟封面轮廓
            t = max(int(min(h, w) * 0.05), 2)
            p[:t], p[-t:], p[:, :t], p[:, -t:] = base * 0.6, base * 0.6, base * 0.6, base * 0.6
            paste(np.clip(p, 0, 255).astype(np.uint8))
    else:
        # b) 页边纸条：沿一条边贴 2~5 条细长亮条，大部分越出画面
        edge = rng.integers(0, 4)
        for _ in range(rng.integers(2, 6)):
            length = int(rng.uniform(0.5, 1.1) * (RENDER if edge % 2 == 0 else RENDER))
            thick = int(rng.uniform(0.02, 0.07) * RENDER)     # 明显细
            val = rng.uniform(190, 245)
            p = np.full((thick, length, 3), val, np.float32)
            p += rng.normal(0, 6, p.shape)
            # 放到对应边缘上（中心越出画面一半以上）
            if edge == 0:
                cy, cx = rng.uniform(-0.1, 0.15) * H, rng.uniform(0, W)
            elif edge == 1:
                cy, cx = rng.uniform(0.85, 1.1) * H, rng.uniform(0, W)
            elif edge == 2:
                cy, cx = rng.uniform(0, H), rng.uniform(-0.1, 0.15) * W
                p = p.transpose(1, 0, 2)
            else:
                cy, cx = rng.uniform(0, H), rng.uniform(0.85, 1.1) * W
                p = p.transpose(1, 0, 2)
            ph2, pw2 = p.shape[:2]
            y0, x0 = int(cy - ph2 / 2), int(cx - pw2 / 2)
            ys, xs = max(y0, 0), max(x0, 0)
            ye, xe = min(y0 + ph2, H), min(x0 + pw2, W)
            if ye <= ys or xe <= xs:
                continue
            sub = p[ys - y0:ye - y0, xs - x0:xe - x0]
            out[ys:ye, xs:xe] = np.clip(sub, 0, 255).astype(np.uint8)
    return out


def random_perspective(doc, doc_corners, bg, rng):
    """把文档随机透视贴到背景上。返回合成图与目标坐标系的精确角点。"""
    H, W = bg.shape[:2]
    cx = rng.uniform(0.32, 0.68) * W
    cy = rng.uniform(0.32, 0.68) * H
    scale = rng.uniform(0.22, 0.85) * min(W, H)   # 放宽下限：小文档也要会检
    ang = rng.uniform(-0.35, 0.35)
    ca, sa = np.cos(ang), np.sin(ang)
    w2 = rng.uniform(0.75, 1.1) * scale / 2
    h2 = rng.uniform(0.75, 1.1) * scale / 2
    base = np.array([[-w2, -h2], [w2, -h2], [w2, h2], [-w2, h2]], np.float32)
    jitter = rng.uniform(-0.12, 0.12, (4, 2)) * np.array([[1, 1], [-1, 1], [-1, -1], [1, -1]], np.float32)
    dst = base + jitter
    dst = dst @ np.array([[ca, sa], [-sa, ca]], np.float32).T + np.array([cx, cy], np.float32)

    M = cv2.getPerspectiveTransform(doc_corners, dst.astype(np.float32))
    dsize = (W, H)
    warped = cv2.warpPerspective(doc, M, dsize, flags=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    mask = np.zeros((H, W), np.uint8)
    cv2.fillPoly(mask, [np.round(dst).astype(np.int32)], 255)
    mask3 = cv2.merge([mask, mask, mask]).astype(np.float32) / 255.0
    comp = warped * mask3 + bg * (1 - mask3)
    return np.clip(comp, 0, 255).astype(np.uint8), dst.astype(np.float32)


def augment(img, rng):
    """相机端增广：噪声 / 模糊 / JPEG / 亮度。"""
    if rng.random() < 0.8:
        img = np.clip(img.astype(np.float32) + rng.normal(0, rng.uniform(2, 9)), 0, 255).astype(np.uint8)
    if rng.random() < 0.5:
        k = rng.choice([3, 5])
        img = cv2.GaussianBlur(img, (k, k), 0)
    if rng.random() < 0.6:
        ok, enc = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, int(rng.uniform(45, 92))])
        if ok:
            img = cv2.imdecode(enc, cv2.IMREAD_COLOR)
    if rng.random() < 0.5:
        gain = rng.uniform(0.8, 1.25)
        bias = rng.uniform(-18, 18)
        img = np.clip(img.astype(np.float32) * gain + bias, 0, 255).astype(np.uint8)
    return img


def quad_to_mask(quad, shape):
    m = np.zeros(shape[:2], np.uint8)
    cv2.fillPoly(m, [np.round(quad).astype(np.int32)], 255)
    return m


def load_real_photos(tools_dir):
    """加载真实照片，抠掉中心区域（那里是文档）后只留背景边缘。"""
    pool = []
    for name in ["book2_raw.jpg", "photo_full.jpg", "text_photo_full.jpg"]:
        p = os.path.join(tools_dir, name)
        if not os.path.exists(p):
            continue
        img = cv2.imread(p)
        if img is None:
            continue
        h, w = img.shape[:2]
        img = cv2.resize(img, (1200, int(1200 * h / w)), interpolation=cv2.INTER_AREA)
        h, w = img.shape[:2]
        pool.append(img[:int(h * 0.09)])
        pool.append(img[int(h * 0.96):])
        pool.append(img[int(h * 0.09):int(h * 0.96), :int(w * 0.07)])
        pool.append(img[int(h * 0.09):int(h * 0.96), int(w * 0.96):])
    return pool


def make_real_anchor_samples(rng):
    """真实域锚点样本：自研检测器伪标签 + 几何/光度增广。

    返回 (img, mask) 列表，图像已是 256×256（与推理输入同口径）。
    只用 IoU≥0.86 的三张（photo_full 扇开书两页连通，伪标签不可靠，跳过）。
    """
    out = []
    if not os.path.exists(REAL_LABELS):
        return out
    labels = json.load(open(REAL_LABELS, encoding="utf-8"))
    use = {k: v for k, v in labels.items()
           if k in ("book2_raw.jpg", "photo_moved.jpg", "text_photo_moved.jpg")}
    for name, meta in use.items():
        p = os.path.join(REAL_PHOTOS, name)
        img = cv2.imread(p)
        if img is None:
            continue
        base = cv2.resize(img, (OUT_SIZE, OUT_SIZE), interpolation=cv2.INTER_AREA)
        q = np.array(meta["quad256"], np.float32)
        for _ in range(REAL_COPIES):
            im = base.copy()
            qq = q.copy()
            # 围绕中心的随机仿射（缩放/旋转/平移），图像与四边形同步变换
            scale = rng.uniform(0.8, 1.2)
            ang = np.deg2rad(rng.uniform(-6, 6))
            tx, ty = rng.uniform(-0.06, 0.06) * OUT_SIZE, rng.uniform(-0.06, 0.06) * OUT_SIZE
            c, s = np.cos(ang) * scale, np.sin(ang) * scale
            M = np.array([[c, -s, OUT_SIZE / 2 * (1 - c) + OUT_SIZE / 2 * s + tx],
                          [s, c, OUT_SIZE / 2 * (1 - s) - OUT_SIZE / 2 * c + ty]], np.float32)
            im = cv2.warpAffine(im, M, (OUT_SIZE, OUT_SIZE))
            qq = qq @ M[:, :2].T + M[:, 2]
            im = augment(im, rng)
            out.append((im, quad_to_mask(np.clip(qq, 0, OUT_SIZE - 1), im.shape)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "data", "synth"))
    ap.add_argument("--count", type=int, default=5000)
    ap.add_argument("--seed", type=int, default=11)
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    img_dir = os.path.join(args.out, "images")
    msk_dir = os.path.join(args.out, "masks")
    os.makedirs(img_dir, exist_ok=True)
    os.makedirs(msk_dir, exist_ok=True)

    global REAL_POOL
    REAL_POOL = load_real_photos(REAL_PHOTOS)
    print(f"真实背景补丁素材: {len(REAL_POOL)} 张")

    # 真实域锚点样本排最前（train.py 按文件名切分验证集时会随机洗牌，无泄漏问题：
    # 划分发生在文件级，同一张照片的增广副本会同时进训练与验证 —— 这里刻意为之，
    # 锚点样本的作用是把真实域统计带进训练，验证集 IoU 不作真实域指标用）
    anchors = make_real_anchor_samples(rng)
    idx = 0
    for im, mk in anchors:
        cv2.imwrite(os.path.join(img_dir, f"{idx:06d}.png"), im)
        cv2.imwrite(os.path.join(msk_dir, f"{idx:06d}.png"), mk)
        idx += 1
    print(f"真实锚点样本: {len(anchors)} 张")

    neg = 0
    for i in range(args.count):
        bg = make_background(rng)
        mode = rng.random()
        if mode < 0.08:
            # ---- 负样本（8%）：画面里没有目标文档，掩码全零 ----
            r = rng.random()
            if r < 0.35:
                bg = paste_distractors(bg, rng)
            img = bg
            mask = None
            neg += 1
        else:
            # ---- 正样本：35% 先铺干扰物再贴主文档 ----
            if rng.random() < 0.35:
                bg = paste_distractors(bg, rng)
            doc, corners = make_document(rng)
            img, quad = random_perspective(doc, corners, bg, rng)
            mask = quad
        # 超采样缩到模型输入尺寸（复现真实相机的重下采样统计）
        img = cv2.resize(img, (OUT_SIZE + 60, OUT_SIZE + 60), interpolation=cv2.INTER_AREA)
        img = augment(img, rng)[:OUT_SIZE, :OUT_SIZE]
        if mask is None:
            mask = np.zeros((OUT_SIZE, OUT_SIZE), np.uint8)
        elif mask is not None and mask.ndim == 1:      # quad 数组
            mask = quad_to_mask(np.clip(mask, 0, OUT_SIZE - 1), img.shape)
        else:                                          # 渲染坐标系下的 quad → 256 空间
            f = (OUT_SIZE + 60) / (RENDER + 180)
            mask = quad_to_mask(np.clip(mask * f, 0, OUT_SIZE - 1), img.shape)
        cv2.imwrite(os.path.join(img_dir, f"{idx:06d}.png"), img)
        cv2.imwrite(os.path.join(msk_dir, f"{idx:06d}.png"), mask)
        idx += 1
        if (i + 1) % 1000 == 0:
            print(f"  {i + 1}/{args.count}")
    print(f"其中负样本: {neg} 张 ({neg / args.count:.0%})")

    # 概览拼图便于人工抽查
    n_show = min(24, idx)
    tiles = [cv2.imread(os.path.join(img_dir, f"{i:06d}.png")) for i in range(n_show)]
    tiles_m = [cv2.imread(os.path.join(msk_dir, f"{i:06d}.png"), cv2.IMREAD_GRAYSCALE) for i in range(n_show)]
    rows = []
    for r in range(0, n_show, 6):
        row = []
        for c in range(6):
            if r + c >= n_show:
                break
            t = tiles[r + c].copy()
            m = tiles_m[r + c]
            cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            cv2.polylines(t, [np.round(cnts[0]).astype(np.int32) if cnts else np.zeros((1, 1, 2), np.int32)],
                          True, (0, 0, 255), 1)
            row.append(t)
        rows.append(np.hstack(row))
    grid = np.vstack(rows)
    cv2.imwrite(os.path.join(args.out, "preview.jpg"), grid, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"完成: {idx} 张 -> {args.out}  预览: preview.jpg")


if __name__ == "__main__":
    main()
