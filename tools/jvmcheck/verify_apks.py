import zipfile, hashlib, os, re, sys

OUT = r"C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro\outputs"
pairs = [
    ("QingSongScan-v1.5.4-selfuse-16-release.apk", "v1.5.4-selfuse-16"),
    ("QingSongScan-v1.4.4-17-release.apk", "v1.4.4-17"),
    ("QingSongScan-v1.5.3-selfuse-15-release.apk", "v1.5.3-selfuse-15"),
]

def h(b): return hashlib.sha256(b).hexdigest()[:16]

res = {}
for fn, tag in pairs:
    z = zipfile.ZipFile(os.path.join(OUT, fn))
    names = z.namelist()
    dex = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", n))
    dh = tuple(h(z.read(n)) for n in dex)
    model = "assets/doc_segmentation.tflite" in names
    mh = h(z.read("assets/doc_segmentation.tflite")) if model else "-"
    res[tag] = (dh, model, mh)
    print(f"{tag:22} dex={dh[0]},{dh[1]}  model={'YES' if model else 'no'} {mh}")

a, b = res["v1.5.4-selfuse-16"], res["v1.4.4-17"]
assert a[0] == b[0], "v1.5.4 与 v1.4.4 的 dex 不一致！"
assert a[1] and not b[1], "模型打包状态不符！"
assert a[0] != res["v1.5.3-selfuse-15"][0], "与 v1.5.3 dex 相同 —— 修复没进包！"
print("\nCHECKS PASS: 1.5.4==1.4.4 (同源码), 模型仅自用版, 且 != v1.5.3 (修复已入包)")
