# 手写/印刷分割模型训练：轻量 U-Net，合成数据（gen_handwriting.py 生成）。
#
# I/O 契约（与将要写的 HandwritingRemover.kt 对应）：
#   输入  [1,256,256,3] float32 RGB 0..1
#   输出  [1,256,256,1] float32 logits（Kotlin 端自行 sigmoid）
#
# 模型：单卷积/层 U-Net（通道 16/32/64/128，约 0.26M 参数），
#       量化后 ~0.3MB，比 doc_segmentation 小一个量级。
# 损失：BCE(from_logits) + 0.5*Dice —— 手写像素稀疏（3~8%），纯 BCE 易漏细线。
#
# 用法：python tools/train/train_hand.py --epochs 20 --batch 16
import argparse
import os
import numpy as np
import tensorflow as tf
from tensorflow.keras import layers, models

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data", "handsynth")
OUT = os.path.join(HERE, "out_hand")

IMG_SIZE = 256


def build_datasets(batch, data_dir):
    img_dir = os.path.join(data_dir, "images")
    msk_dir = os.path.join(data_dir, "masks")
    names = sorted(os.listdir(img_dir))
    n = len(names)
    rng = np.random.default_rng(7)
    perm = rng.permutation(n)
    n_val = max(int(n * 0.06), 32)
    val_idx = set(perm[:n_val].tolist())

    train_pairs = [(os.path.join(img_dir, names[i]), os.path.join(msk_dir, names[i]))
                   for i in range(n) if i not in val_idx]
    val_pairs = [(os.path.join(img_dir, names[i]), os.path.join(msk_dir, names[i]))
                 for i in sorted(val_idx)]

    def load_pair(ip, mp):
        img = tf.io.decode_png(tf.io.read_file(ip), channels=3)
        img = tf.cast(img, tf.float32) / 255.0
        msk = tf.io.decode_png(tf.io.read_file(mp), channels=1)
        msk = tf.cast(msk, tf.float32) / 255.0
        return img, msk

    def augment(img, msk):
        if tf.random.uniform(()) > 0.5:
            img = tf.image.flip_left_right(img)
            msk = tf.image.flip_left_right(msk)
        if tf.random.uniform(()) > 0.5:
            img = tf.image.flip_up_down(img)
            msk = tf.image.flip_up_down(msk)
        img = tf.image.random_brightness(img, 0.15)
        img = tf.image.random_contrast(img, 0.85, 1.15)
        return tf.clip_by_value(img, 0.0, 1.0), msk

    ds_tr = (tf.data.Dataset.from_tensor_slices(tuple(map(list, zip(*train_pairs))))
             .shuffle(min(len(train_pairs), 20000))
             .map(load_pair, num_parallel_calls=tf.data.AUTOTUNE)
             .map(augment, num_parallel_calls=tf.data.AUTOTUNE)
             .batch(batch).prefetch(tf.data.AUTOTUNE))
    ds_val = (tf.data.Dataset.from_tensor_slices(tuple(map(list, zip(*val_pairs))))
              .map(load_pair, num_parallel_calls=tf.data.AUTOTUNE)
              .batch(batch).prefetch(tf.data.AUTOTUNE))
    return ds_tr, ds_val, len(train_pairs), len(val_pairs)


def conv_block(x, ch):
    x = layers.Conv2D(ch, 3, padding="same", use_bias=False)(x)
    x = layers.BatchNormalization()(x)
    x = layers.ReLU()(x)
    return x


def build_model():
    inputs = layers.Input((IMG_SIZE, IMG_SIZE, 3))
    c1 = conv_block(inputs, 16)
    p1 = layers.MaxPooling2D()(c1)
    c2 = conv_block(p1, 32)
    p2 = layers.MaxPooling2D()(c2)
    c3 = conv_block(p2, 64)
    p3 = layers.MaxPooling2D()(c3)
    c4 = conv_block(p3, 128)

    u3 = layers.Conv2DTranspose(64, 2, strides=2, padding="same")(c4)
    d3 = conv_block(layers.Concatenate()([u3, c3]), 64)
    u2 = layers.Conv2DTranspose(32, 2, strides=2, padding="same")(d3)
    d2 = conv_block(layers.Concatenate()([u2, c2]), 32)
    u1 = layers.Conv2DTranspose(16, 2, strides=2, padding="same")(d2)
    d1 = conv_block(layers.Concatenate()([u1, c1]), 16)

    # logits 输出：Kotlin 端 sigmoid（与 doc_segmentation 契约同风格）
    outputs = layers.Conv2D(1, 1, activation=None)(d1)
    return models.Model(inputs, outputs, name="hand_seg_unet")


def iou_metric(y_true, y_pred):
    p = tf.cast(y_pred > 0.0, tf.float32)
    inter = tf.reduce_sum(y_true * p)
    union = tf.reduce_sum(tf.clip_by_value(y_true + p, 0.0, 1.0))
    return inter / (union + 1e-6)


def bce_dice_loss(y_true, y_pred):
    bce = tf.keras.losses.binary_crossentropy(y_true, y_pred, from_logits=True)
    prob = tf.sigmoid(y_pred)
    inter = tf.reduce_sum(y_true * prob, axis=[1, 2, 3])
    summ = tf.reduce_sum(y_true + prob, axis=[1, 2, 3])
    dice = 1.0 - (2.0 * inter + 1.0) / (summ + 1.0)
    return tf.reduce_mean(bce) + 0.5 * tf.reduce_mean(dice)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--data", default=DATA)
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    ds_tr, ds_val, n_tr, n_val = build_datasets(args.batch, args.data)
    print(f"训练 {n_tr} 张 / 验证 {n_val} 张", flush=True)

    model = build_model()
    n_params = model.count_params()
    print(f"参数量: {n_params/1e6:.3f}M (量化后约 {n_params/1e6:.1f}MB)", flush=True)

    steps = max(n_tr // args.batch, 1)
    lr = tf.keras.optimizers.schedules.CosineDecay(1e-3, args.epochs * steps)
    model.compile(optimizer=tf.keras.optimizers.Adam(lr),
                  loss=bce_dice_loss, metrics=[iou_metric])

    ckpt = tf.keras.callbacks.ModelCheckpoint(
        os.path.join(args.out, "best.keras"), monitor="val_iou_metric",
        mode="max", save_best_only=True, verbose=1)
    csv = tf.keras.callbacks.CSVLogger(os.path.join(args.out, "log.csv"))

    model.fit(ds_tr, validation_data=ds_val, epochs=args.epochs,
              callbacks=[ckpt, csv], verbose=2)

    best = tf.keras.models.load_model(
        os.path.join(args.out, "best.keras"),
        custom_objects={"iou_metric": iou_metric, "bce_dice_loss": bce_dice_loss})

    conv = tf.lite.TFLiteConverter.from_keras_model(best)
    open(os.path.join(args.out, "hand_seg_float32.tflite"), "wb").write(conv.convert())
    conv = tf.lite.TFLiteConverter.from_keras_model(best)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    open(os.path.join(args.out, "hand_seg_quant.tflite"), "wb").write(conv.convert())

    for f in ["hand_seg_float32.tflite", "hand_seg_quant.tflite"]:
        p = os.path.join(args.out, f)
        print(f"导出 {f}: {os.path.getsize(p)/1048576:.2f} MB", flush=True)


if __name__ == "__main__":
    main()
