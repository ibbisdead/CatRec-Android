# -*- coding: utf-8 -*-
import re
import pathlib

def keys(path):
    t = path.read_text(encoding="utf-8")
    return set(re.findall(r'<string name="([^"]+)"', t))

def arr_keys(path):
    t = path.read_text(encoding="utf-8")
    return set(re.findall(r'<string-array name="([^"]+)"', t))

root = pathlib.Path(__file__).resolve().parents[1]
base = root / "app/src/main/res/values/strings.xml"
base_k = keys(base)
base_a = arr_keys(base)
res = root / "app/src/main/res"
for d in sorted(res.iterdir()):
    if not d.is_dir() or not d.name.startswith("values-"):
        continue
    f = d / "strings.xml"
    if not f.exists():
        continue
    k = keys(f)
    a = arr_keys(f)
    mk = sorted(base_k - k)
    ma = sorted(base_a - a)
    if mk or ma:
        print(d.name, "missing strings:", len(mk), "missing arrays:", len(ma))
        for name in mk[:60]:
            print("  ", name)
        if len(mk) > 60:
            print("  ...", len(mk) - 60, "more")
        for name in ma:
            print("  [array]", name)
