"""Escape unescaped apostrophes in Android values XML (<string> and <item> bodies)."""
import re
import sys


def esc(inner: str) -> str:
    return re.sub(r"(?<!\\)'", r"\'", inner)


def fix(content: str) -> str:
    def sub_string(m: re.Match[str]) -> str:
        return m.group(1) + esc(m.group(2)) + m.group(3)

    content = re.sub(
        r"(<string\s[^>]*>)(.*?)(</string>)", sub_string, content, flags=re.DOTALL
    )

    def sub_item(m: re.Match[str]) -> str:
        return m.group(1) + esc(m.group(2)) + m.group(3)

    content = re.sub(r"(<item>)(.*?)(</item>)", sub_item, content, flags=re.DOTALL)
    return content


def main() -> None:
    for path in sys.argv[1:]:
        with open(path, encoding="utf-8") as f:
            original = f.read()
        updated = fix(original)
        if updated != original:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(updated)
            print("updated", path)
        else:
            print("no change", path)


if __name__ == "__main__":
    main()
