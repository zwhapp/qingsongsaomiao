# 文档分割模型重训：轻量 U-Net，合成数据，输出与 GPL 原模型完全一致的 I/O 规格。
#
# I/O 契约（与 DocSegmenter.kt 严格对应，改动会直接破坏 App）：
#   输入  [1,256,256,3] float32 RGB 0..1
#   输出  [1,256,256,1] float32 logits（Kotlin 端自行做 sigmoid）
#
# 训练：tf.data 读合成 PNG → 轻量 U-Net（约 2.1M 参数）→ BCE(from_logits)
# 导出：TFLite 动态范围量化（权重量化、激活 float32，Interpreter 无需特殊配置）
#
# 用法：python tools/train/train.py [--epochs 20] [--batch 16]
import argparse
import os
import numpy as np
import tensorflow as tf
from tensorflow.keras import layers, models

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data", "synth")
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)

IMG_SIZE = 256


def build_datasets(batch, data_dir=DATA):
    img_dir = os.path.join(data_dir, "images")
    msk_dir = os.path.join(data_dir, "masks")
    names = sorted(os.listdir(img_dir))
    n = len(names)
    rng = np.random.default_rng(7)
    perm = rng.permutation(n)
    n_val = max(int(n * 0.08), 32)
    val_idx = set(perm[:n_val].tolist())

    def pair(i):
        return (os.path.join(img_dir, names[i]), os.path.join(msk_dir, names[i]))

    train_pairs = [pair(i) for i in range(n) if i not in val_idx]
    val_pairs = [pair(i) for i in sorted(val_idx)]

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

    train_imgs = [p[0] for p in train_pairs]
    train_msks = [p[1] for p in train_pairs]
    val_imgs = [p[0] for p in val_pairs]
    val_msks = [p[1] for p in val_pairs]

    ds_tr = (tf.data.Dataset.from_tensor_slices((train_imgs, train_msks))
             .shuffle(min(len(train_pairs), 20000))
             .map(load_pair, num_parallel_calls=tf.data.AUTOTUNE)
             .map(augment, num_parallel_calls=tf.data.AUTOTUNE)
             .batch(batch).prefetch(tf.data.AUTOTUNE))
    ds_val = (tf.data.Dataset.from_tensor_slices((val_imgs, val_msks))
              .map(load_pair, num_parallel_calls=tf.data.AUTOTUNE)
              .batch(batch).prefetch(tf.data.AUTOTUNE))
    return ds_tr, ds_val, len(train_pairs), len(val_pairs)


def build_model():
    inputs = layers.Input((IMG_SIZE, IMG_SIZE, 3))
    c1 = layers.Conv2D(32, 3, padding="same", use_bias=False)(inputs)
    c1 = layers.BatchNormalization()(c1)
    c1 = layers.ReLU()(c1)
    c1 = layers.Conv2D(32, 3, padding="same", use_bias=False)(c1)
    c1 = layers.BatchNormalization()(c1)
    c1 = layers.ReLU()(c1)

    p1 = layers.MaxPooling2D()(c1)
    c2 = layers.Conv2D(64, 3, padding="same", use_bias=False)(p1)
    c2 = layers.BatchNormalization()(c2)
    c2 = layers.ReLU()(c2)
    c2 = layers.Conv2D(64, 3, padding="same", use_bias=False)(c2)
    c2 = layers.BatchNormalization()(c2)
    c2 = layers.ReLU()(c2)

    p2 = layers.MaxPooling2D()(c2)
    c3 = layers.Conv2D(128, 3, padding="same", use_bias=False)(p2)
    c3 = layers.BatchNormalization()(c3)
    c3 = layers.ReLU()(c3)
    c3 = layers.Conv2D(128, 3, padding="same", use_bias=False)(c3)
    c3 = layers.BatchNormalization()(c3)
    c3 = layers.ReLU()(c3)

    p3 = layers.MaxPooling2D()(c3)
    c4 = layers.Conv2D(256, 3, padding="same", use_bias=False)(p3)
    c4 = layers.BatchNormalization()(c4)
    c4 = layers.ReLU()(c4)
    c4 = layers.Conv2D(256, 3, padding="same", use_bias=False)(c4)
    c4 = layers.BatchNormalization()(c4)
    c4 = layers.ReLU()(c4)

    u3 = layers.Conv2DTranspose(128, 2, strides=2, padding="same")(c4)
    u3 = layers.Concatenate()([u3, c3])
    d3 = layers.Conv2D(128, 3, padding="same", use_bias=False)(u3)
    d3 = layers.BatchNormalization()(d3)
    d3 = layers.ReLU()(d3)
    d3 = layers.Conv2D(128, 3, padding="same", use_bias=False)(d3)
    d3 = layers.BatchNormalization()(d3)
    d3 = layers.ReLU()(d3)

    u2 = layers.Conv2DTranspose(64, 2, strides=2, padding="same")(d3)
    u2 = layers.Concatenate()([u2, c2])
    d2 = layers.Conv2D(64, 3, padding="same", use_bias=False)(u2)
    d2 = layers.BatchNormalization()(d2)
    d2 = layers.ReLU()(d2)
    d2 = layers.Conv2D(64, 3, padding="same", use_bias=False)(d2)
    d2 = layers.BatchNormalization()(d2)
    d2 = layers.ReLU()(d2)

    u1 = layers.Conv2DTranspose(32, 2, strides=2, padding="same")(d2)
    u1 = layers.Concatenate()([u1, c1])
    d1 = layers.Conv2D(32, 3, padding="same", use_bias=False)(u1)
    d1 = layers.BatchNormalization()(d1)
    d1 = layers.ReLU()(d1)
    d1 = layers.Conv2D(32, 3, padding="same", use_bias=False)(d1)
    d1 = layers.BatchNormalization()(d1)
    d1 = layers.ReLU()(d1)

    # logits 输出（无激活）：与原 GPL 模型契约一致，Kotlin 端做 sigmoid
    outputs = layers.Conv2D(1, 1, activation=None)(d1)
    return models.Model(inputs, outputs, name="doc_seg_unet")


def iou_metric(y_true, y_pred):
    p = tf.cast(y_pred > 0.0, tf.float32)          # logits>0 等价 sigmoid>0.5
    inter = tf.reduce_sum(y_true * p)
    union = tf.reduce_sum(tf.clip_by_value(y_true + p, 0.0, 1.0))
    return inter / (union + 1e-6)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--data", default=DATA, help="数据目录（含 images/ 与 masks/）")
    ap.add_argument("--out", default=OUT, help="模型与日志输出目录")
    args = ap.parse_args()
    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)

    ds_tr, ds_val, n_tr, n_val = build_datasets(args.batch, args.data)
    print(f"训练 {n_tr} 张 / 验证 {n_val} 张")

    model = build_model()
    model.summary()
    n_params = model.count_params()
    print(f"参数量: {n_params/1e6:.2f}M  (量化后约 {n_params/1e6:.1f}MB)")

    steps_per_epoch = max(n_tr // args.batch, 1)
    lr = tf.keras.optimizers.schedules.CosineDecay(1e-3, args.epochs * steps_per_epoch)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(lr),
        loss=tf.keras.losses.BinaryCrossentropy(from_logits=True),
        metrics=[iou_metric],
    )

    ckpt = tf.keras.callbacks.ModelCheckpoint(
        os.path.join(out_dir, "best.keras"), monitor="val_iou_metric",
        mode="max", save_best_only=True, verbose=1)
    csv = tf.keras.callbacks.CSVLogger(os.path.join(out_dir, "log.csv"))

    model.fit(ds_tr, validation_data=ds_val, epochs=args.epochs,
              callbacks=[ckpt, csv], verbose=2)

    # 导出：float32 版 + 动态范围量化版
    best = tf.keras.models.load_model(os.path.join(out_dir, "best.keras"),
                                      custom_objects={"iou_metric": iou_metric})
    conv = tf.lite.TFLiteConverter.from_keras_model(best)
    open(os.path.join(out_dir, "doc_seg_float32.tflite"), "wb").write(conv.convert())

    conv = tf.lite.TFLiteConverter.from_keras_model(best)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    open(os.path.join(out_dir, "doc_seg_quant.tflite"), "wb").write(conv.convert())

    for f in ["doc_seg_float32.tflite", "doc_seg_quant.tflite"]:
        p = os.path.join(out_dir, f)
        print(f"导出 {f}: {os.path.getsize(p)/1048576:.2f} MB")


if __name__ == "__main__":
    main()
