# -*- coding: utf-8 -*-
"""Emit JSON of all default strings (name -> text) for translation tooling."""
import json
import re
import pathlib

root = pathlib.Path(__file__).resolve().parents[1]
path = root / "app/src/main/res/values/strings.xml"
text = path.read_text(encoding="utf-8")
# Simple extraction: <string name="x" ...>value</string> (non-greedy, no nested strings)
pat = re.compile(
    r'<string\s+name="([^"]+)"([^>]*)>(.*?)</string>',
    re.DOTALL,
)
out = {}
for m in pat.finditer(text):
    name, attrs, val = m.group(1), m.group(2), m.group(3)
    if "translatable=\"false\"" in attrs:
        continue
    # collapse whitespace for single-line JSON preview
    v = val.strip().replace("\n", " ")
    while "  " in v:
        v = v.replace("  ", " ")
    out[name] = v
outp = root / "tools" / "en_strings.json"
outp.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print("Wrote", len(out), "strings to", outp)
