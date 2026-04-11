# -*- coding: utf-8 -*-
"""
Generate tools/patches/{locale}.json using deep-translator (Google).

Translates strings (and changelog arrays) that still match the English default in the
current values-XX/strings.xml — use after `rebuild_locale_strings.py` so new keys exist.

Run from repo root:
  python tools/auto_translate_locales.py
  python tools/auto_translate_locales.py pt ru
"""
from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

from deep_translator import GoogleTranslator

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/res/values/strings.xml"
PATCH_DIR = ROOT / "tools" / "patches"
PATCH_DIR.mkdir(parents=True, exist_ok=True)

KEEP_ENGLISH_KEYS = frozenset({"brand_catrect"})


def skip_translation_key(key: str, en_val: str) -> bool:
    """@string refs, native language names, hex placeholders — stay same as default."""
    t = en_val.strip()
    if t.startswith("@string/") or t.startswith("@plurals/"):
        return True
    if key.startswith("language_"):
        return True
    if re.fullmatch(r"[0-9A-Fa-f]{6}", t):
        return True
    if t in {"PNG", "WebP", "JPEG", "JPG", "GIF"}:
        return True
    if re.fullmatch(r"%\d+\$[a-zA-Z]\s*/\s*%\d+\$[a-zA-Z]", t):
        return True
    return False


def is_probably_universal(en_val: str) -> bool:
    """Short tokens / aspect labels that often stay identical to English in UI."""
    t = en_val.strip()
    if t in {
        "FPS", "Mbps", "Hz", "kbps", "GIF", "OK", "Beta", "CPU", "GPU", "RAM", "MP4", "WebP", "PNG",
        "JPEG", "H.264", "H.265", "HEVC", "CBR", "HD", "SD",
    }:
        return True
    if re.fullmatch(r"\d+\s*min", t, re.I):
        return True
    if re.fullmatch(r"\d+:\d+(\s*\([^)]+\))?", t):
        return True
    return False

LOCALE_TO_GOOGLE = {
    "ar": "ar",
    "de": "de",
    "es": "es",
    "fr": "fr",
    "hi": "hi",
    "in": "id",
    "it": "it",
    "ja": "ja",
    "ko": "ko",
    "pt": "pt",
    "ru": "ru",
    "tr": "tr",
    "vi": "vi",
    "zh-rCN": "zh-CN",
    "zh-rTW": "zh-TW",
}


def parse_strings(text: str) -> dict[str, str]:
    pat = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.DOTALL)
    out: dict[str, str] = {}
    for m in pat.finditer(text):
        if 'translatable="false"' in m.group(2):
            continue
        inner = m.group(3).strip()
        inner = inner.replace("\\'", "'").replace('\\"', '"')
        inner = inner.replace("\\n", "\n")
        inner = inner.replace("&apos;", "'").replace("&quot;", '"')
        inner = inner.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        out[m.group(1)] = inner
    return out


def parse_string_arrays(text: str) -> dict[str, list[str]]:
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
            s = im.group(1).strip()
            s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            s = s.replace("&quot;", '"').replace("&apos;", "'")
            items.append(s)
        out[name] = items
    return out


def translate_batch(translator: GoogleTranslator, texts: list[str], batch_size: int = 28) -> list[str]:
    del batch_size
    result: list[str] = []
    for t in texts:
        if not t.strip():
            result.append(t)
            continue
        try:
            result.append(translator.translate(t))
        except Exception as e:
            print("translate error:", repr(t)[:50], e, flush=True)
            result.append(t)
        time.sleep(0.012)
    return result


def read_patch_file(patch_path: Path) -> tuple[dict[str, str], dict[str, list[str]]]:
    if not patch_path.exists():
        return {}, {}
    patch_raw = json.loads(patch_path.read_text(encoding="utf-8"))
    return patch_raw.get("strings") or {}, patch_raw.get("arrays") or {}


def patch_marks_resolved_loanword(
    key: str,
    patch_strings: dict[str, str],
    en_strings: dict[str, str],
) -> bool:
    """Patch entry means done: same text as EN, or null (= use default / rebuild base)."""
    if key not in patch_strings:
        return False
    pv = patch_strings.get(key)
    if pv is None:
        return True
    return pv == en_strings[key]


def patch_array_same_as_en(
    aname: str,
    patch_arrays: dict[str, list[str]],
    en_arrays: dict[str, list[str]],
) -> bool:
    if aname not in patch_arrays:
        return False
    return patch_arrays[aname] == en_arrays[aname]


def still_en_string_keys(
    en_strings: dict[str, str],
    loc_strings: dict[str, str],
    patch_strings: dict[str, str],
) -> list[str]:
    # Loanwords (FAQ, Export, …) often translate to the same text as English; we still store
    # them in the patch. Skip re-calling the API when patch already records that choice.
    out: list[str] = []
    for k in en_strings:
        if k in KEEP_ENGLISH_KEYS:
            continue
        if loc_strings.get(k, en_strings[k]) != en_strings[k]:
            continue
        if patch_marks_resolved_loanword(k, patch_strings, en_strings):
            continue
        if skip_translation_key(k, en_strings[k]):
            continue
        if is_probably_universal(en_strings[k]):
            continue
        out.append(k)
    return sorted(out)


def still_en_array_names(
    en_arrays: dict[str, list[str]],
    loc_arrays: dict[str, list[str]],
    patch_arrays: dict[str, list[str]],
) -> list[str]:
    out: list[str] = []
    for an in en_arrays:
        if loc_arrays.get(an) != en_arrays[an]:
            continue
        if patch_array_same_as_en(an, patch_arrays, en_arrays):
            continue
        out.append(an)
    return sorted(out)


def build_strings_out(
    still_en_keys: list[str],
    en_strings: dict[str, str],
    translator: GoogleTranslator,
) -> dict[str, str]:
    strings_out: dict[str, str] = {}
    if not still_en_keys:
        return strings_out
    need_idx = [i for i, k in enumerate(still_en_keys) if k not in KEEP_ENGLISH_KEYS]
    batch_src = [en_strings[still_en_keys[i]] for i in need_idx]
    batch_dst = translate_batch(translator, batch_src) if batch_src else []
    dst: list[str] = []
    bi = 0
    for k in still_en_keys:
        if k in KEEP_ENGLISH_KEYS:
            dst.append(en_strings[k])
        else:
            dst.append(batch_dst[bi])
            bi += 1
    for k, v in zip(still_en_keys, dst):
        strings_out[k] = v
    return strings_out


def write_merged_patch(
    patch_path: Path,
    base_strings: dict[str, str],
    base_arrays: dict[str, list[str]],
    strings_out: dict[str, str],
    arrays_out: dict[str, list[str]],
) -> None:
    merged_strings = {**base_strings, **strings_out}
    merged_arrays = {**base_arrays, **arrays_out}
    patch_path.write_text(
        json.dumps({"strings": merged_strings, "arrays": merged_arrays}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def process_locale(
    folder: str,
    google_target: str,
    res: Path,
    en_strings: dict[str, str],
    en_arrays: dict[str, list[str]],
) -> None:
    loc_file = res / f"values-{folder}" / "strings.xml"
    loc_text = loc_file.read_text(encoding="utf-8") if loc_file.exists() else ""
    loc_strings = parse_strings(loc_text)
    loc_arrays = parse_string_arrays(loc_text)

    patch_path = PATCH_DIR / f"{folder}.json"
    patch_strings, patch_arrays = read_patch_file(patch_path)

    keys = still_en_string_keys(en_strings, loc_strings, patch_strings)
    arrays = still_en_array_names(en_arrays, loc_arrays, patch_arrays)

    if not keys and not arrays:
        print(folder, "up to date", flush=True)
        return

    print(
        folder,
        "translate",
        len(keys),
        "strings,",
        len(arrays),
        "arrays ->",
        google_target,
        flush=True,
    )
    translator = GoogleTranslator(source="en", target=google_target)
    strings_out = build_strings_out(keys, en_strings, translator)
    arrays_out = {aname: translate_batch(translator, en_arrays[aname]) for aname in arrays}
    write_merged_patch(patch_path, patch_strings, patch_arrays, strings_out, arrays_out)
    print("  wrote", patch_path.name, flush=True)
    time.sleep(0.4)


def main() -> None:
    base_text = BASE.read_text(encoding="utf-8")
    en_strings = parse_strings(base_text)
    en_arrays = parse_string_arrays(base_text)

    res = ROOT / "app/src/main/res"

    only = [a for a in sys.argv[1:] if not a.startswith("-")]
    locales = LOCALE_TO_GOOGLE.items()
    if only:
        locales = [(k, v) for k, v in LOCALE_TO_GOOGLE.items() if k in only]

    for folder, google_target in locales:
        process_locale(folder, google_target, res, en_strings, en_arrays)

    print("Done. Run: python tools/rebuild_locale_strings.py all")


if __name__ == "__main__":
    main()
