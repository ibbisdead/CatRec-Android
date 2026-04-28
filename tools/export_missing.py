# -*- coding: utf-8 -*-
import json
import re
import pathlib

root = pathlib.Path(__file__).resolve().parents[1]
en_path = root / "tools" / "en_strings.json"
en = json.loads(en_path.read_text(encoding="utf-8"))


def keys_in_locale(path: pathlib.Path):
    if not path.exists():
        return set()
    t = path.read_text(encoding="utf-8")
    return set(re.findall(r'<string name="([^"]+)"', t))


def arr_in_locale(path: pathlib.Path):
    if not path.exists():
        return set()
    t = path.read_text(encoding="utf-8")
    return set(re.findall(r'<string-array name="([^"]+)"', t))


res = root / "app/src/main/res"
base_arr = set(re.findall(r'<string-array name="([^"]+)"', (res / "values" / "strings.xml").read_text(encoding="utf-8")))

for d in sorted(res.iterdir()):
    if not d.is_dir() or not d.name.startswith("values-"):
        continue
    f = d / "strings.xml"
    mk = sorted(set(en.keys()) - keys_in_locale(f))
    ma = sorted(base_arr - arr_in_locale(f))
    out = root / "tools" / f"MISSING_{d.name}.txt"
    lines = [f"# {d.name} missing {len(mk)} strings, {len(ma)} arrays\n"]
    for k in mk:
        lines.append(f"{k}\t{en.get(k, '')}\n")
    out.write_text("".join(lines), encoding="utf-8")
    print("Wrote", out.name, len(mk), "strings")
