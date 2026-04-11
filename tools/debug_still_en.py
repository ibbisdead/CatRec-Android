import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
code = sys.argv[1] if len(sys.argv) > 1 else "ar"


def parse(path: Path):
    t = path.read_text(encoding="utf-8")
    pat = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.DOTALL)
    o = {}
    for m in pat.finditer(t):
        if 'translatable="false"' in m.group(2):
            continue
        inner = m.group(3).strip()
        inner = inner.replace("\\'", "'").replace("\\n", "\n")
        inner = inner.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        o[m.group(1)] = inner
    return o


en = parse(ROOT / "app/src/main/res/values/strings.xml")
loc = parse(ROOT / f"app/src/main/res/values-{code}/strings.xml")
for k in sorted(en):
    if k == "brand_catrect":
        continue
    if loc.get(k, en[k]) == en[k]:
        print(k, "|", en[k][:100].replace("\n", " "))
