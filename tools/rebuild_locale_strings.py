# -*- coding: utf-8 -*-
"""
Rebuild app/src/main/res/values-XX/strings.xml from values/strings.xml (template order).

For each locale: start from default English template, overlay existing translations from the
current values-XX/strings.xml, then overlay tools/patches/{locale}.json (optional).

Usage:
  python tools/rebuild_locale_strings.py es
  python tools/rebuild_locale_strings.py all
"""
from __future__ import annotations

import html as html_module
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/res/values/strings.xml"
PATCH_DIR = ROOT / "tools" / "patches"


def unescape_android_inner(raw: str) -> str:
    s = raw.strip()
    s = html_module.unescape(s)
    s = s.replace("\\'", "'")
    s = s.replace('\\"', '"')
    s = s.replace("\\n", "\n")
    return s


def escape_android_inner(text: str) -> str:
    """Escape XML specials, newlines, and apostrophes (aapt2 treats unescaped ' in <string> as invalid).

    Real newlines inside <string> bodies break aapt2's values compiler (NPE); use \\n in XML instead.
    """
    s = text.replace("\r\n", "\n").replace("\r", "\n")
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("\n", "\\n")
    return re.sub(r"(?<!\\)'", r"\\'", s)


def parse_strings_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    text = path.read_text(encoding="utf-8")
    pat = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.DOTALL)
    out: dict[str, str] = {}
    for m in pat.finditer(text):
        if 'translatable="false"' in m.group(2):
            continue
        out[m.group(1)] = unescape_android_inner(m.group(3))
    return out


def parse_string_arrays_file(path: Path) -> dict[str, list[str]]:
    if not path.exists():
        return {}
    text = path.read_text(encoding="utf-8")
    out: dict[str, list[str]] = {}
    pat = re.compile(
        r'<string-array name="([^"]+)"[^>]*>(.*?)</string-array>',
        re.DOTALL,
    )
    item_pat = re.compile(r"<item>(.*?)</item>", re.DOTALL)
    for m in pat.finditer(text):
        name = m.group(1)
        items = []
        for im in item_pat.finditer(m.group(2)):
            s = unescape_android_inner(im.group(1))
            items.append(s)
        out[name] = items
    return out


def replace_string_block(template: str, name: str, new_inner_escaped: str) -> str:
    pat = re.compile(
        rf'(<string name="{re.escape(name)}"[^>]*>)(.*?)(</string>)',
        re.DOTALL,
    )

    def repl(m: re.Match[str]) -> str:
        return m.group(1) + new_inner_escaped + m.group(3)

    new_t, n = pat.subn(repl, template, count=1)
    if n != 1:
        print("WARN: string not found in template:", name, file=sys.stderr)
    return new_t


def replace_string_array_block(template: str, name: str, items: list[str]) -> str:
    pat = re.compile(
        rf'(<string-array name="{re.escape(name)}"[^>]*>)(.*?)(</string-array>)',
        re.DOTALL,
    )
    # Use Android inner escaping (apostrophe, newlines) — saxutils alone breaks aapt2 on e.g. d'écran in <item>.
    body = "".join(f"\n        <item>{escape_android_inner(it)}</item>" for it in items)
    body += "\n    "

    def repl(m: re.Match[str]) -> str:
        return m.group(1) + body + m.group(3)

    new_t, n = pat.subn(repl, template, count=1)
    if n != 1:
        print("WARN: string-array not found in template:", name, file=sys.stderr)
    return new_t


def rebuild_one(locale_code: str) -> None:
    patch_path = PATCH_DIR / f"{locale_code}.json"
    strings_patch: dict[str, str] = {}
    arrays_patch: dict[str, list[str]] = {}
    if patch_path.exists():
        data = json.loads(patch_path.read_text(encoding="utf-8"))
        strings_patch = data.get("strings") or {}
        arrays_patch = data.get("arrays") or {}

    target_dir = ROOT / "app/src/main/res" / f"values-{locale_code}"
    target_file = target_dir / "strings.xml"

    base_strings = parse_strings_file(BASE)
    base_arrays = parse_string_arrays_file(BASE)
    existing = parse_strings_file(target_file)
    existing_arrays = parse_string_arrays_file(target_file)

    merged_strings = {**base_strings, **existing, **strings_patch}
    merged_arrays = {**base_arrays, **existing_arrays, **arrays_patch}

    template = BASE.read_text(encoding="utf-8")
    for name in base_strings:
        value = merged_strings.get(name, base_strings[name])
        if value is None:
            value = base_strings[name]
        template = replace_string_block(template, name, escape_android_inner(value))
    for arr_name in base_arrays:
        items = merged_arrays.get(arr_name, base_arrays[arr_name])
        template = replace_string_array_block(template, arr_name, items)

    target_dir.mkdir(parents=True, exist_ok=True)
    target_file.write_text(template, encoding="utf-8")
    print(
        "Wrote",
        target_file.relative_to(ROOT),
        "patch:",
        patch_path.name if patch_path.exists() else "(none)",
        flush=True,
    )


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    arg = sys.argv[1]
    codes = [
        "ar", "de", "es", "fr", "hi", "in", "it", "ja", "ko",
        "pt", "ru", "tr", "vi", "zh-rCN", "zh-rTW",
    ]
    if arg == "all":
        for c in codes:
            rebuild_one(c)
    else:
        rebuild_one(arg)


if __name__ == "__main__":
    main()
