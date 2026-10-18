# 手写分割模型离线评估：测试集 IoU + 负样本误检率 + 视觉三联图。
#
# 用法：python tools/train/eval_hand.py [--model out_hand/best.keras] [--data data/handsynth_test]
import argparse
import os
import numpy as np
import cv2
import tensorflow as tf

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.path.join(HERE, "out_hand", "best.keras"))
    ap.add_argument("--data", default=os.path.join(HERE, "data", "handsynth_test"))
    ap.add_argument("--out", default=os.path.join(HERE, "evalout_hand"))
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    model = tf.keras.models.load_model(args.model, compile=False)
    inp = np.zeros((1, 256, 256, 3), np.float32)
    model.predict(inp, verbose=0)   # 触发构建

    img_dir = os.path.join(args.data, "images")
    msk_dir = os.path.join(args.data, "masks")
    names = sorted(os.listdir(img_dir))

    ious, fpos = [], []
    samples = []
    for k, name in enumerate(names):
        img = cv2.imread(os.path.join(img_dir, name))
        gt = cv2.imread(os.path.join(msk_dir, name), cv2.IMREAD_GRAYSCALE)
        x = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        p = model.predict(x[None], verbose=0)[0, ..., 0]
        pred = (p > 0.5).astype(np.uint8)
        gt01 = (gt > 127).astype(np.uint8)

        inter = int(((pred == 1) & (gt01 == 1)).sum())
        union = int(((pred == 1) | (gt01 == 1)).sum())
        if gt01.sum() == 0:
            # 负样本：统计误检像素占比
            fp = pred.sum() / pred.size
            fpos.append(fp)
            iou_v = 1.0 if pred.sum() == 0 else 0.0
        else:
            iou_v = inter / max(union, 1)
            fp = float((pred & (1 - gt01)).sum()) / max(int(gt01.sum()), 1)
            fpos.append(fp)
        ious.append(iou_v)

        # 三联图：原图 | GT红 | 预测红
        ov_gt = img.copy(); ov_gt[gt01 > 0] = (0, 0, 255)
        ov_pr = img.copy(); ov_pr[pred > 0] = (0, 0, 255)
        tri = np.hstack([img, ov_gt, ov_pr])
        samples.append((iou_v, k, name, tri))
        if (k + 1) % 100 == 0:
            print(f"  {k + 1}/{len(names)}", flush=True)

    ious = np.array(ious)
    print(f"\n样本数 {len(ious)}")
    print(f"IoU 均值 {ious.mean():.4f}  中位 {np.median(ious):.4f}  "
          f"P25 {np.percentile(ious, 25):.4f}  最差 {ious.min():.4f}")
    print(f"IoU≥0.7 占比 {(ious >= 0.7).mean():.1%}   IoU≥0.85 占比 {(ious >= 0.85).mean():.1%}")
    fpr = np.mean([f for f in fpos])
    print(f"平均误检像素率(FP/GT或全图): {fpr:.4%}")

    # 视觉抽查：最差 3 / 中位 3 / 最好 2
    samples.sort(key=lambda s: s[0])
    picks = samples[:3] + samples[len(samples) // 2 - 1: len(samples) // 2 + 2] + samples[-2:]
    tiles = [s[3] for s in picks]
    rows = [np.hstack(tiles[r:r + 2]) for r in range(0, len(tiles), 2)]
    cv2.imwrite(os.path.join(args.out, "triptychs.jpg"), np.vstack(rows),
                [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"三联图 -> {os.path.join(args.out, 'triptychs.jpg')}")


if __name__ == "__main__":
    main()
