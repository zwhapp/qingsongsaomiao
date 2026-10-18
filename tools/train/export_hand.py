# -*- coding: utf-8 -*-
"""从 best.keras 重新导出 TFLite 并逐一做 Interpreter 加载验证。
教训：动态范围量化(Optimize.DEFAULT)会在 TRANSPOSE_CONV 节点产生
INT8 权重 vs FLOAT32 输入的类型冲突，模型在设备上加载失败。
改用 FLOAT16 量化（无此问题，体积减半），且导出后必须用 Interpreter
真实加载+试跑，通过才算导出成功。
"""
import os, sys
import numpy as np
import tensorflow as tf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train_hand import iou_metric, bce_dice_loss   # noqa: F401  反序列化需要

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out_hand")


def validate(path, n_run=3):
    """Interpreter 真实加载 + 随机输入试跑 + 输出合理性检查。"""
    interp = tf.lite.Interpreter(model_path=path, num_threads=2)
    interp.allocate_tensors()          # ← 设备上炸掉的就是这一步
    inp = interp.get_input_details()[0]
    outd = interp.get_output_details()[0]
    assert list(inp["shape"]) == [1, 256, 256, 3], inp["shape"]
    assert list(outd["shape"]) == [1, 256, 256, 1], outd["shape"]
    for i in range(n_run):
        x = np.random.rand(1, 256, 256, 3).astype(np.float32)
        interp.set_tensor(inp["index"], x)
        interp.invoke()
        y = interp.get_tensor(outd["index"])
        assert y.shape == (1, 256, 256, 1) and np.isfinite(y).all(), (y.shape, y.dtype)
    print("  VALIDATE OK:", os.path.basename(path),
          "%.2f MB" % (os.path.getsize(path) / 1048576), flush=True)


def main():
    best = tf.keras.models.load_model(
        os.path.join(OUT, "best.keras"),
        custom_objects={"iou_metric": iou_metric, "bce_dice_loss": bce_dice_loss})

    # 1) float32
    conv = tf.lite.TFLiteConverter.from_keras_model(best)
    p32 = os.path.join(OUT, "hand_seg_float32.tflite")
    open(p32, "wb").write(conv.convert())

    # 2) float16（动态范围量化已证实在 TransposeConv 上炸，弃用）
    conv = tf.lite.TFLiteConverter.from_keras_model(best)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.target_spec.supported_types = [tf.float16]
    p16 = os.path.join(OUT, "hand_seg_fp16.tflite")
    open(p16, "wb").write(conv.convert())

    for p in [p32, p16]:
        validate(p)
    print("ALL EXPORTS VALIDATED", flush=True)


if __name__ == "__main__":
    main()
