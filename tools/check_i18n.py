"""The English table covers the German texts of the web pages.

Uses the same matching as static/i18n.js: exact lines, {} patterns, and lines
joined with " · " or ", " translated piece by piece. Run in CI:

    python3 -m unittest tools/check_i18n.py
"""

import os
import re
import unittest
from html.parser import HTMLParser

STATIC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "viewer", "static")
EDGE = re.compile(r"^([\s,:;·–]*)([\s\S]*?)([\s,.:;·–]*)$")


def load(lang="en"):
    exact, patterns, keys = {}, [], []
    with open(os.path.join(STATIC, "i18n", lang + ".tsv"), encoding="utf-8") as f:
        for line in f.read().split("\n"):
            if not line or line[0] == "#" or "\t" not in line:
                continue
            de, en = line.split("\t", 1)
            de = EDGE.match(re.sub(r"\s+", " ", de)).group(2)
            if not de:
                continue
            keys.append((de, en))
            if "{}" in de:
                rx = re.compile("^" + "([\\s\\S]*?)".join(re.escape(p) for p in de.split("{}")) + "$")
                patterns.append((rx, en, len(de.replace("{}", ""))))
            else:
                exact[de] = en
    patterns.sort(key=lambda p: -p[2])
    return exact, patterns, keys


EXACT, PATTERNS, KEYS = load()
# Same in both languages: key names, short labels, names
NEUTRAL = re.compile(r"[A-Z0-9]{1,4}|Esc|Shift|Tab|WASD|Shader|Stashes|Webmatica|live|\.[a-z]+")


def translated(s: str) -> bool:
    """True if i18n.js would find a translation (or nothing needs one)."""
    if not re.search(r"[A-Za-zÄÖÜäöü]", s) or NEUTRAL.fullmatch(s.strip()):
        return True
    m = EDGE.match(re.sub(r"\s+", " ", s))
    core = m.group(2)
    if not core:
        return True
    if core in EXACT or any(rx.match(core) for rx, _en, _n in PATTERNS):
        return True
    for sep in (" · ", ", "):
        if sep in core:
            parts = core.split(sep)
            if all(translated(p) for p in parts):
                return True
    return False


class Texts(HTMLParser):
    """Text nodes and translated attributes, skipping script/style/code and data-noi18n."""
    SKIP = {"script", "style", "code", "textarea"}
    ATTRS = ("title", "placeholder", "aria-label", "alt")
    VOID = {"br", "img", "input", "meta", "link", "hr", "source", "wbr"}

    def __init__(self):
        super().__init__()
        self.stack, self.out = [], []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        skip = tag in self.SKIP or "data-noi18n" in a or (self.stack and self.stack[-1])
        if not skip:
            self.out += [v for k, v in attrs if k in self.ATTRS and v]
        if tag not in self.VOID:
            self.stack.append(bool(skip))

    def handle_endtag(self, tag):
        if tag not in self.VOID and self.stack:
            self.stack.pop()

    def handle_data(self, data):
        if not (self.stack and self.stack[-1]) and data.strip():
            self.out.append(data.strip())


def page_texts(name):
    p = Texts()
    with open(os.path.join(STATIC, name), encoding="utf-8") as f:
        p.feed(f.read())
    return p.out


class I18nTest(unittest.TestCase):
    def test_pages_are_translated(self):
        for page in ("index.html", "editor.html"):
            missing = sorted({t for t in page_texts(page) if not translated(t)})
            self.assertEqual(missing, [], f"{page}: add these lines to static/i18n/en.tsv")

    def test_no_conflicting_duplicates(self):
        seen, clash = {}, []
        for de, en in KEYS:
            en = EDGE.match(en).group(2)
            if de in seen and seen[de] != en:
                clash.append(f"{de}: {seen[de]!r} / {en!r}")
            seen.setdefault(de, en)
        self.assertEqual(clash, [])

    def test_placeholders_match(self):
        bad = [de for de, en in KEYS if de.count("{}") != en.count("{}")]
        self.assertEqual(bad, [])
