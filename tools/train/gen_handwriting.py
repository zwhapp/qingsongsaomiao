# 手写/印刷分割模型 · 合成训练数据生成器
#
# 任务：给「去手写」ML 模型（handwriting_seg）造数据。
#   输入  256x256x3 文档页（透视矫正后的纸面，模拟 app 增强阶段的输入域）
#   掩码  256x256   手写笔迹 = 255，其余 = 0
#
# 目标定义（与应用端融合策略一致）：
#   ★ 任何手写墨迹都算正类 —— 黑色圆珠笔、铅笔灰、蓝黑钢笔、彩色笔全算。
#     应用端 REMOVE_INK = 颜色法(彩色/荧光) ∪ ML掩码(全部手写)，并集覆盖，
#     深蓝等低饱和笔迹颜色法漏掉的由 ML 兜住。
#   印刷文字/表格线/图片框/页码 = 负类（绝不能进掩码）。
#
# 数据 100% 程序生成：
#   印刷页 —— Windows 系统字体 PIL 渲染（仅生成位图用于训练，不分发字体文件）；
#   手写   —— 随机贝塞尔短笔画组合 + 长笔画（下划线/圈划/批注），无第三方素材。
#
# 域匹配要点：
#   app 在长边 3000~4000px 的矫正页上把图缩到 256 推理 → 下采样 12~16x。
#   本生成器渲染宽 1536~2560（对应 6~10x），笔画宽度按比例设定，
#   保证下采样后手写 ≈ 0.6~1.3px 细糊线、印刷正文 ≈ 2.5~3.5px 密排字形块，
#   与真实推理域一致。
#
# 用法：python tools/train/gen_handwriting.py --out data/handsynth --count 4000 --seed 21
#       python tools/train/gen_handwriting.py --out data/handsynth_test --count 300 --seed 99
import argparse
import os
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

OUT_SIZE = 256
RENDER_W_RANGE = (1536, 2560)   # 页面渲染宽度（决定下采样倍率）
ASPECT = 1.414                  # A4 比例
CJK_FONT_DIR = r"C:\Windows\Fonts"
PRINT_FONTS_CN = ["simhei.ttf", "msyh.ttc", "simfang.ttf", "Deng.ttf"]
PRINT_FONTS_EN = ["arial.ttf", "calibri.ttf", "times.ttf"]

# 常用汉字池（覆盖训练渲染足够）
CJK_POOL = (
    "的一是了我不人在他有这上们来到时大地为子中你说生国年着就那和要她出也得里后自以会"
    "家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总"
    "从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世"
    "什两次使身者被高已亲其进此话常与活正感见明问力理尔点文几定本公特做外孩相西果走将"
    "月十实向声车全信重三机工物气每并别真打太新比才便夫再书部水像眼等体却加电主界门利"
    "海受听表德少克代员许先口由死安写性马光白或住难望教命花结乐色更拉东神记处让母父应"
    "直字场平报友关放至张认接告入笑内英军候民岁往何度山觉路带万男边风解叫任金快原吃妈"
    "变通师立象数四失满战远格士音轻目条呢病始达深完今提求清王化空业思切怎非找片罗钱吗"
    "语元喜曾离飞科言干流欢约各即指合反题必该论交终林请医晚制球决传画保读运及则房早院"
    "量苦火布品近坐产答星精视五连司巴奇管类未朋且婚台夜青北队久乎越观落尽形影红爸百令"
    "周吧识步希亚术留市半热送兴造谈容极随演收首根讲整式取照办强石古华拿计您装似足双妻"
    "尼转诉米称丽客南领节衣站黑刻统断福城故历惊脸选包紧争另建维绝树系伤示愿持千史谁准"
    "联妇纪基买志静阿诗独复痛消社算义竟确酒需单治卡幸兰念举仅钟怕共毛句息功官待究跟穿"
    "室易游程号居考突皮哪费倒价图具刚脑永歌响商礼细专黄块脚味灵改据般破引食仍存众注笔"
    "甚某沉血备习校默务土微娘须试怀料调广苏显赛查密议底列富梦错座参八除跑亮假印设线温"
    "虽掉京初养香停际致阳纸李纳验助激够严证帝饭忘趣支春集丈木研班普导顿睡展跳获艺六波"
    "察群皇段急庭创区奥器谢弟店否害草排背止组州朝封睛板角况曲馆育忙质河续哥呼若推境遇"
    "雨标姐充围案伦护冷警贝著雪索剧啊船险烟依斗值帮汉慢佛肯闻唱沙局伴往叶超乘举客马克"
    "劳构吴客队短树境范座圈序券牙版志永习称鲜技象园穿毁案谈射遇环况际"
)
ASCII_POOL = "abcdefghijklmnopqrstuvwxyz"

FONT_CACHE = {}


def get_font(name, size):
    key = (name, size)
    if key not in FONT_CACHE:
        FONT_CACHE[key] = ImageFont.truetype(os.path.join(CJK_FONT_DIR, name), size)
    return FONT_CACHE[key]


def fractal_noise(h, w, rng, octaves=3):
    acc = np.zeros((h, w), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        gh = max(int(h / (2 ** (octaves - o))), 2)
        gw = max(int(w / (2 ** (octaves - o))), 2)
        acc += cv2.resize(rng.random((gh, gw)).astype(np.float32) * 255,
                          (w, h), interpolation=cv2.INTER_CUBIC) * amp
        total += amp
        amp *= 0.55
    return acc / total


def rand_text(rng, n):
    """随机混排中英文片段。"""
    out = []
    while sum(len(s) for s in out) < n:
        if rng.random() < 0.75:
            out.append("".join(rng.choice(list(CJK_POOL)) for _ in range(rng.integers(2, 9))))
        else:
            out.append("".join(rng.choice(list(ASCII_POOL)) for _ in range(rng.integers(3, 8))))
    return " ".join(out)[: n + 8]


def render_printed_page(w, h, rng):
    """渲染印刷页（灰度 float32，白纸黑字）。"""
    page = np.full((h, w), rng.uniform(238, 251), np.float32)
    page += (fractal_noise(h, w, rng, octaves=3) - 127.5) * rng.uniform(0.015, 0.06)

    pil = Image.fromarray(np.clip(page, 0, 255).astype(np.uint8), "L")
    draw = ImageDraw.Draw(pil)

    use_cn = rng.random() < 0.8
    fonts = PRINT_FONTS_CN if use_cn else PRINT_FONTS_EN
    body_size = int(rng.uniform(0.011, 0.017) * w)      # 正文字号
    font = get_font(rng.choice(fonts), body_size)
    ink = int(rng.uniform(10, 55))

    margin_x = int(rng.uniform(0.07, 0.13) * w)
    y = int(rng.uniform(0.05, 0.09) * h)
    line_h = int(body_size * rng.uniform(1.55, 2.1))
    x1 = w - margin_x

    # 标题（可选）
    if rng.random() < 0.55:
        hf = get_font(rng.choice(fonts), int(body_size * rng.uniform(1.4, 2.0)))
        draw.text((margin_x, y), rand_text(rng, rng.integers(3, 8)), font=hf, fill=ink)
        y += int(body_size * 2.6)
        if rng.random() < 0.5:                           # 标题下印刷分隔线
            draw.line([(margin_x, y), (x1, y)], fill=ink, width=max(body_size // 12, 1))
            y += line_h
    y += rng.integers(0, line_h)

    # 正文段落：逐词绘制避免字形截断
    while y + line_h < h * 0.94:
        indent = margin_x if rng.random() < 0.7 else margin_x + int(body_size * 1.5)
        x = indent
        while x < x1:
            word = rand_text(rng, rng.integers(2, 6))
            wd = draw.textlength(word, font=font)
            if x + wd > x1:
                break
            draw.text((x, y), word, font=font, fill=ink)
            x += int(wd) + int(body_size * rng.uniform(0.25, 0.6))
        y += line_h
        if rng.random() < 0.12:                          # 段间空行
            y += line_h

    # 页脚页码
    if rng.random() < 0.4:
        pf = get_font("arial.ttf", int(body_size * 0.9))
        draw.text((w / 2 - 10, h * 0.95), str(rng.integers(1, 300)), font=pf, fill=ink)

    # 表格线（可选）
    if rng.random() < 0.18:
        tx, ty = margin_x, int(rng.uniform(0.3, 0.6) * h)
        for r in range(rng.integers(3, 7)):
            draw.line([(tx, ty + r * line_h), (x1, ty + r * line_h)], fill=ink, width=1)
        for c in range(rng.integers(2, 5)):
            cx = tx + int((x1 - tx) * c / 4)
            draw.line([(cx, ty), (cx, ty + line_h * 5)], fill=ink, width=1)

    arr = np.asarray(pil, np.float32)

    # 照明：线性渐变 + 局部暗斑
    gx, gy = rng.uniform(-35, 35), rng.uniform(-35, 35)
    xx, yy = np.meshgrid(np.linspace(-1, 1, w), np.linspace(-1, 1, h))
    arr += gx * xx + gy * yy
    if rng.random() < 0.5:
        cx, cy = rng.uniform(0, w), rng.uniform(0, h)
        arr -= rng.uniform(15, 45) * np.exp(
            -(((xx * w - cx) / (0.6 * w)) ** 2 + ((yy * h - cy) / (0.6 * h)) ** 2))
    return np.clip(arr, 0, 255)


class HandwritingLayer:
    """手写层：墨迹叠加层 + 掩码层同步绘制。坐标均为渲染分辨率。

    img 用 uint8（0..255 墨强度）+ cv2.line AA 光栅化，保证性能；
    mask 用二值线段（与 img 同宽度），作为监督标签。
    """

    def __init__(self, w, h, strength):
        self.w, self.h = w, h
        self.img = np.zeros((h, w), np.uint8)
        self.mask = np.zeros((h, w), np.uint8)
        self.val = int(np.clip(strength * 255, 30, 255))

    def draw_polyline(self, pts, width_range, rng):
        """折线：分段恒宽（宽度沿程按正弦起伏模拟笔压）。"""
        pts = np.asarray(pts, np.float32)
        n_seg = len(pts) - 1
        for i in range(n_seg):
            t = i / max(n_seg - 1, 1)
            wd = width_range[0] + (width_range[1] - width_range[0]) * (
                0.5 + 0.5 * np.sin(t * np.pi * rng.uniform(0.5, 1.5)))
            wd = max(int(round(wd)), 1)
            p0 = tuple(np.round(pts[i]).astype(int))
            p1 = tuple(np.round(pts[i + 1]).astype(int))
            cv2.line(self.mask, p0, p1, 255, wd)
            cv2.line(self.img, p0, p1, self.val, wd, cv2.LINE_AA)

    def bezier(self, p0, p1, p2, width_range, rng, n=14):
        ts = np.linspace(0, 1, n)
        pts = (((1 - ts) ** 2)[:, None] * p0 + (2 * (1 - ts) * ts)[:, None] * p1
               + (ts ** 2)[:, None] * p2)
        pts += rng.normal(0, 0.6, pts.shape)             # 手抖
        self.draw_polyline(pts, width_range, rng)

    def scribble_char(self, cx, cy, size, rng):
        """一个「手写字」：框内 2~6 段短笔画，模拟汉字书写。"""
        n_stroke = rng.integers(2, 7)
        wr = (max(size * 0.07, 2.0), max(size * 0.15, 3.0))
        for _ in range(n_stroke):
            x0, y0 = cx + rng.uniform(-0.5, 0.5) * size, cy + rng.uniform(-0.5, 0.5) * size
            x1, y1 = cx + rng.uniform(-0.5, 0.5) * size, cy + rng.uniform(-0.5, 0.5) * size
            px, py = cx + rng.uniform(-0.4, 0.4) * size, cy + rng.uniform(-0.4, 0.4) * size
            self.bezier(np.array([x0, y0], np.float32), np.array([px, py], np.float32),
                        np.array([x1, y1], np.float32), wr, rng, n=int(rng.integers(6, 12)))

    def scribble_line(self, x0, x1, y, char_h, rng):
        """一行手写字：字符框沿基线排布，基线上下起伏。"""
        x = x0
        while x < x1:
            s = char_h * rng.uniform(0.8, 1.3)
            self.scribble_char(x, y + rng.uniform(-0.25, 0.25) * char_h, s, rng)
            x += s * rng.uniform(0.9, 1.5)

    def long_stroke(self, kind, x0, x1, y, rng):
        """长笔画：波浪下划线 / 直线划删 / 椭圆圈注 / 箭头 / 页边竖线。"""
        wr = (rng.uniform(2.5, 4.5), rng.uniform(4.5, 8.0))
        if kind == 0:
            amp = rng.uniform(3, 9)
            xs = np.linspace(x0, x1, 40)
            ys = y + amp * np.sin(np.linspace(0, rng.uniform(2, 5) * np.pi, 40)) + rng.normal(0, 1.2, 40)
            self.draw_polyline(np.stack([xs, ys], 1), wr, rng)
        elif kind == 1:
            self.draw_polyline([[x0, y + rng.normal(0, 2)], [x1, y + rng.normal(0, 2)]], wr, rng)
        elif kind == 2:
            cx, cy = (x0 + x1) / 2, y
            rx, ry = (x1 - x0) / 2 * rng.uniform(1.05, 1.3), rng.uniform(18, 40)
            th = np.linspace(0, 2 * np.pi * rng.uniform(0.95, 1.05), 36)
            pts = np.stack([cx + rx * np.cos(th) + rng.normal(0, 2, 36),
                            cy + ry * np.sin(th) + rng.normal(0, 2, 36)], 1)
            self.draw_polyline(pts, wr, rng)
        elif kind == 3:
            mid = [(x0 + x1) / 2 + rng.uniform(-30, 30), y - rng.uniform(15, 45)]
            self.draw_polyline([[x0, y], mid, [x1, y]], wr, rng)
        else:
            ys = np.linspace(y - 60, y + 60, 8)
            xs = x0 + rng.normal(0, 4, 8)
            self.draw_polyline(np.stack([xs, ys], 1), wr, rng)


def compose_handwriting(w, h, rng):
    """在印刷页上布置手写。返回 (墨强度层 uint8, 掩码 uint8)。"""
    strength = rng.uniform(0.55, 1.0)
    hw = HandwritingLayer(w, h, strength)
    char_h = rng.uniform(0.035, 0.06) * w              # 手写字通常比正文大
    line_gap = char_h * rng.uniform(1.4, 2.0)
    mode = rng.random()

    if mode < 0.30:
        # 行间批注：在正文区写 1~3 行（最难的真实场景）
        y = rng.uniform(0.12, 0.8) * h
        for _ in range(rng.integers(1, 4)):
            x0 = rng.uniform(0.05, 0.3) * w
            x1 = min(x0 + rng.uniform(0.3, 0.7) * w, 0.95 * w)
            hw.scribble_line(x0, x1, y, char_h, rng)
            y += line_gap
    elif mode < 0.55:
        # 页边批注：左/右侧多行 + 偶发竖批注线
        side = rng.random() < 0.5
        y = rng.uniform(0.1, 0.3) * h
        for _ in range(rng.integers(2, 6)):
            x0 = (0.01 if side else 0.78) * w + rng.uniform(0, 0.05) * w
            x1 = x0 + rng.uniform(0.1, 0.2) * w
            hw.scribble_line(x0, x1, y, char_h * rng.uniform(0.7, 1.0), rng)
            y += line_gap
        if rng.random() < 0.3:
            hw.long_stroke(4, (0.04 if side else 0.85) * w, 0, y, rng)
    elif mode < 0.70:
        # 标注类：下划线 / 划删 / 圈注 / 箭头 叠在正文区
        for _ in range(rng.integers(1, 4)):
            x0 = rng.uniform(0.08, 0.5) * w
            x1 = x0 + rng.uniform(0.15, 0.4) * w
            hw.long_stroke(int(rng.integers(0, 4)), x0, x1, rng.uniform(0.15, 0.85) * h, rng)
        if rng.random() < 0.5:
            hw.scribble_line(x0, x1, h * rng.uniform(0.2, 0.8), char_h * 0.8, rng)
    elif mode < 0.90:
        # 整页手写（笔记页/信纸）：多行铺满
        y = rng.uniform(0.08, 0.15) * h
        while y < h * 0.9:
            hw.scribble_line(rng.uniform(0.03, 0.1) * w, rng.uniform(0.7, 0.97) * w, y, char_h, rng)
            y += line_gap
    else:
        # 混合：整页 + 圈注
        y = rng.uniform(0.08, 0.15) * h
        while y < h * 0.9:
            hw.scribble_line(rng.uniform(0.03, 0.1) * w, rng.uniform(0.7, 0.97) * w, y, char_h, rng)
            y += line_gap
        x0 = rng.uniform(0.08, 0.5) * w
        hw.long_stroke(2, x0, x0 + rng.uniform(0.15, 0.3) * w, rng.uniform(0.2, 0.8) * h, rng)

    return hw.img, hw.mask


def camera_augment(img, rng):
    """相机端增广：噪声 / 模糊 / JPEG / 曝光。"""
    if rng.random() < 0.8:
        img = np.clip(img.astype(np.float32) + rng.normal(0, rng.uniform(2, 8)), 0, 255).astype(np.uint8)
    if rng.random() < 0.45:
        k = int(rng.choice([3, 5]))
        img = cv2.GaussianBlur(img, (k, k), 0)
    if rng.random() < 0.6:
        ok, enc = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, int(rng.uniform(50, 92))])
        if ok:
            img = cv2.imdecode(enc, cv2.IMREAD_GRAYSCALE)
    if rng.random() < 0.5:
        img = np.clip(img.astype(np.float32) * rng.uniform(0.85, 1.2) + rng.uniform(-15, 15),
                      0, 255).astype(np.uint8)
    return img


def make_sample(rng):
    """一张合成样本。返回 (img256 RGB, mask256, has_hand)。"""
    w = int(rng.uniform(*RENDER_W_RANGE))
    h = int(w * ASPECT)
    page = render_printed_page(w, h, rng)

    has_hand = rng.random() > 0.25
    if has_hand:
        hw_img, hw_mask = compose_handwriting(w, h, rng)
        # 墨色：黑为主，少量蓝黑/彩色（掩码同样标注 —— 应用端并集策略）
        r = rng.random()
        if r < 0.6:
            ink_bgr = np.array([15.0, 15.0, 18.0], np.float32)     # 黑
        elif r < 0.85:
            ink_bgr = np.array([70.0, 40.0, 25.0], np.float32)     # 蓝黑钢笔
        else:
            ink_bgr = rng.uniform(0, 150, 3).astype(np.float32)    # 随机彩色笔
        a = (hw_img.astype(np.float32) / 255.0)[..., None]
        page3 = np.repeat(page[..., None], 3, axis=2)
        page3 = page3 * (1 - a) + ink_bgr[None, None, :] * a
    else:
        page3 = np.repeat(page[..., None], 3, axis=2)
        hw_mask = None

    # 轻微透视残余倾斜
    ang = rng.uniform(-1.2, 1.2)
    M = cv2.getRotationMatrix2D((w / 2, h / 2), ang, 1.0)
    page3 = cv2.warpAffine(page3, M, (w, h), flags=cv2.INTER_LINEAR, borderValue=(235, 235, 235))
    if hw_mask is not None:
        hw_mask = cv2.warpAffine(hw_mask, M, (w, h), flags=cv2.INTER_NEAREST, borderValue=0)

    # 超采样下采样到 256（核心域匹配步骤）
    img = cv2.resize(page3, (OUT_SIZE + 40, OUT_SIZE + 40), interpolation=cv2.INTER_AREA)[:OUT_SIZE, :OUT_SIZE]
    if hw_mask is not None:
        mask = cv2.resize(hw_mask, (OUT_SIZE + 40, OUT_SIZE + 40),
                          interpolation=cv2.INTER_AREA)[:OUT_SIZE, :OUT_SIZE]
    else:
        mask = np.zeros((OUT_SIZE, OUT_SIZE), np.uint8)

    gray = cv2.cvtColor(np.clip(img, 0, 255).astype(np.uint8), cv2.COLOR_RGB2GRAY)
    gray = camera_augment(gray, rng)
    img3 = np.repeat(gray[..., None], 3, axis=2)
    return img3.astype(np.uint8), (mask > 110).astype(np.uint8) * 255, has_hand


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "data", "handsynth"))
    ap.add_argument("--count", type=int, default=4000)
    ap.add_argument("--seed", type=int, default=21)
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    img_dir = os.path.join(args.out, "images")
    msk_dir = os.path.join(args.out, "masks")
    os.makedirs(img_dir, exist_ok=True)
    os.makedirs(msk_dir, exist_ok=True)

    n_pos, n_neg = 0, 0
    for i in range(args.count):
        img, mask, has_hand = make_sample(rng)
        n_pos += int(has_hand)
        n_neg += int(not has_hand)
        cv2.imwrite(os.path.join(img_dir, f"{i:06d}.png"), cv2.cvtColor(img, cv2.COLOR_RGB2BGR))
        cv2.imwrite(os.path.join(msk_dir, f"{i:06d}.png"), mask)
        if (i + 1) % 500 == 0:
            print(f"  {i + 1}/{args.count}", flush=True)

    # 抽查拼图：原图 | 掩码红色叠加
    n_show = min(20, args.count)
    tiles = []
    for i in range(n_show):
        im = cv2.imread(os.path.join(img_dir, f"{i:06d}.png"))
        mk = cv2.imread(os.path.join(msk_dir, f"{i:06d}.png"), cv2.IMREAD_GRAYSCALE)
        ov = im.copy()
        ov[mk > 0] = (0, 0, 255)
        tiles.append(np.hstack([im, ov]))
    rows = [np.hstack(tiles[r:r + 4]) for r in range(0, n_show, 4)]
    cv2.imwrite(os.path.join(args.out, "preview.jpg"), np.vstack(rows),
                [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"完成: {args.count} 张（正 {n_pos} / 负 {n_neg}）-> {args.out}")


if __name__ == "__main__":
    main()
