# -*- coding: utf-8 -*-
"""校验 jvmcheck/InkSkewCore.kt 的三个被测函数与 app 端 DocumentGeometry.kt
中的同名函数**逐字一致**。不一致即退出码 1 —— 冒烟测试测的必须是真码。

用法：python sync_skew.py   （jvmcheck 的 gradle run 前自动调用，见 build 逻辑或手工跑）
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP_KT = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main",
                                       "java", "com", "openscan", "docscan",
                                       "core", "DocumentGeometry.kt"))
APP_ENH_KT = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main",
                                           "java", "com", "openscan", "docscan",
                                           "core", "ScanEnhancer.kt"))
JVM_KT = os.path.join(HERE, "src", "main", "kotlin", "InkSkewCore.kt")


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def extract_function(src, name):
    """按 'fun name(' 行起点、花括号平衡取整个函数块（含函数签名行）。"""
    lines = src.splitlines()
    for i, line in enumerate(lines):
        if re.match(r"\s*(private\s+)?fun %s\(" % re.escape(name), line):
            depth = 0
            started = False
            block = []
            j = i
            while j < len(lines):
                for ch in lines[j]:
                    if ch == "{":
                        depth += 1
                        started = True
                    elif ch == "}":
                        depth -= 1
                block.append(lines[j])
                j += 1
                if started and depth == 0:
                    return "\n".join(block)
            raise RuntimeError("unbalanced braces in %s" % name)
    raise RuntimeError("function not found: %s" % name)


def extract_const(src, name):
    """按 'const val NAME = <字面量>' 提取整行。"""
    m = re.search(r"^\s*(?:private\s+)?const val %s = .+$" % re.escape(name),
                  src, re.M)
    if not m:
        raise RuntimeError("const not found: %s" % name)
    return m.group(0).strip()


# (源文件, 校验项列表)；函数按花括号平衡提取，常量按行提取
SPECS = [
    (APP_KT, [
        ("fn", "trimDarkEdgesRect"),
        ("const", "TRIM_ABS_MAX"),
        ("const", "TRIM_REL_DIFF"),
        ("const", "TRIM_BAND_DIV"),
        ("const", "TRIM_MAX_INSET_NUM"),
        ("const", "TRIM_KEEP_NUM"),
        ("fn", "estimateSkewFromGray"),
        ("fn", "textInkMask"),
        ("fn", "estimateOrientationFromGray"),
        ("fn", "projectionScore"),
        ("fn", "percentileLevel"),
        ("const", "ORI_TURN_RATIO"),
    ]),
    (APP_ENH_KT, [
        ("fn", "binarizeBlackWhite"),
        ("fn", "sauvolaThreshold"),
        ("fn", "removeSmallComponents"),
        ("fn", "estimateStrokeWidth"),
        ("fn", "removeBorderBands"),
        ("fn", "flattenResidual"),
        ("fn", "estimateBackgroundAt"),
        ("fn", "percentile"),
        ("fn", "eraseColoredInk"),
        ("fn", "filterColoredComponents"),
        ("fn", "eraseMaskedRegion"),
        ("const", "BIN_K_STRONG"),
        ("const", "BIN_K_WEAK"),
        ("const", "BIN_R"),
        ("const", "BIN_MIN_AREA"),
        ("const", "INK_CHROMA_THRESH"),
        ("const", "INK_SAT_THRESH"),
        ("const", "INK_VALUE_THRESH"),
        ("const", "INK_CLOSE_DIV"),
        ("const", "INK_MASK_DIL_DIV"),
        ("const", "INK_KEEP_AREA_BASE"),
        ("const", "INK_KEEP_FILL"),
        ("const", "INK_KEEP_ASPECT"),
        ("const", "BG_ESTIMATE_EDGE"),
        ("const", "BG_K_DIV"),
        ("const", "BG_K_MAX"),
        ("const", "BG_SIGMA_DIV"),
        ("const", "RESIDUAL_EDGE"),
        ("const", "RESIDUAL_K_DIV"),
        ("const", "RESIDUAL_K_MAX"),
        ("const", "RESIDUAL_SIGMA_DIV"),
        ("const", "RESIDUAL_MAX_GAIN"),
        ("const", "RESIDUAL_PAPER_PERCENTILE"),
    ]),
]


def main():
    jvm = read(JVM_KT)
    ok = True
    for src_path, items in SPECS:
        app = read(src_path)
        tag = os.path.basename(src_path)
        for kind, name in items:
            if kind == "fn":
                a = extract_function(app, name)
                b = extract_function(jvm, name)
            else:
                a = extract_const(app, name)
                b = extract_const(jvm, name)
            if a == b:
                print("OK      %s (%s)" % (name, tag))
            else:
                ok = False
                print("DIFF    %s (%s)" % (name, tag))
                la, lb = a.splitlines(), b.splitlines()
                for k in range(max(len(la), len(lb))):
                    x = la[k] if k < len(la) else "<EOF>"
                    y = lb[k] if k < len(lb) else "<EOF>"
                    if x != y:
                        print("  app: %s" % x.strip())
                        print("  jvm: %s" % y.strip())
    if not ok:
        print("\n被测代码与 app 源不一致 —— 先把 app 端的改动原样同步到 InkSkewCore.kt")
        sys.exit(1)
    print("\nsync OK")


if __name__ == "__main__":
    main()
