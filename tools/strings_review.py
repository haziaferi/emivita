#!/usr/bin/env python3
"""Mechanical checks and a per-screen review sheet for a translation of strings.xml.

    python tools/strings_review.py --check                 # deterministic findings, exit 1 on any
    python tools/strings_review.py --sheet out.md          # review sheet, grouped by screen
    python tools/strings_review.py --lang it ...           # default language is it

Why this exists (docs/SPEC.md §7 R81). `TranslationCompletenessTest` proves a translation is
*complete*: every key present, the `%1$s`/`%d` arguments preserved, nothing left byte-identical
to English outside its allow-list. It cannot say whether the translation is *right*, and the
wording review that answers that was owed from R67 to R81 because reading 560 strings against
hand-taken screenshots is slow enough to keep being deferred. This tool splits the review in two:

- `--check` is the half a script can decide: placeholder parity the test's regex misses (`%%`,
  `%f`, `%.1f`), glyph consistency inside the translation (a stray `\\'` beside typographic
  apostrophes, `...` beside `…`, a spaced hyphen doing an en dash's job), allow-list entries whose
  value is no longer identical, and `--` inside an XML comment body (§10). It is a gate: it prints
  `file:line key: finding` and exits 1.
- `--sheet` is the half that needs a reader: every key with its English and translated value,
  grouped by the screen composable that references it (scanned from the Kotlin sources, so a
  string moves with its screen and is read in context), with computed flags that say *where to
  look first* -- length ratio on short labels (truncation candidates), Title Case, user-addressed
  gendered forms, glossary drift (§7 R67: aderenza/scorta/rinvia/farmaco), and an English term
  rendered without any of its agreed Italian stems. Flags are hints for the reader, never gates.

Keys are paired by `name`, never by line: the translation keeps the English key order but its
plurals are clustered, so line numbers do not correspond. The sheet is written to wherever it is
asked for and belongs in a scratch directory, not the repository (§10: no standalone reports).
"""

import argparse
import glob
import html
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
APP_SRC = os.path.join(ROOT, "app", "src", "main", "java")
COMPLETENESS_TEST = os.path.join(
    ROOT, "app", "src", "test", "java", "com", "hazia", "emivita", "i18n", "TranslationCompletenessTest.kt"
)

# Canonical reading order: the screens as the owner walks them, then what is shown outside a
# screen. Each entry is (label, [globs relative to APP_SRC]); a file's top-level `fun` names become
# sub-groups in file order, so SettingsDetailScreens.kt yields its four screens separately.
SCREENS = [
    ("Today", ["com/hazia/emivita/feature/today/*.kt"]),
    ("Medicines", ["com/hazia/emivita/feature/medicines/MedicinesScreen.kt"]),
    ("Add / edit medicine", ["com/hazia/emivita/feature/medicines/AddMedicineScreen.kt"]),
    ("Medicine detail", ["com/hazia/emivita/feature/medicines/MedicineDetailScreen.kt"]),
    ("Stock & expiry", ["com/hazia/emivita/feature/medicines/StockExpirationScreen.kt"]),
    ("Add / edit reminder", ["com/hazia/emivita/feature/reminders/*.kt"]),
    ("History", ["com/hazia/emivita/feature/history/*.kt"]),
    ("Settings", ["com/hazia/emivita/feature/settings/SettingsScreen.kt"]),
    ("Settings detail screens", ["com/hazia/emivita/feature/settings/SettingsDetailScreens.kt"]),
    ("Backup & sync", ["com/hazia/emivita/feature/settings/BackupSyncScreen.kt"]),
    ("Medical ID", ["com/hazia/emivita/feature/settings/MedicalIdScreen.kt"]),
    ("Other settings files", ["com/hazia/emivita/feature/settings/*.kt"]),
    ("Alarm screens", ["com/hazia/emivita/feature/alarm/*.kt"]),
    ("Dialogs (disclaimer, recovery, lock)", [
        "com/hazia/emivita/feature/disclaimer/*.kt",
        "com/hazia/emivita/feature/recovery/*.kt",
        "com/hazia/emivita/feature/lock/*.kt",
    ]),
    ("Widgets", ["com/hazia/emivita/feature/widget/*.kt"]),
    ("Navigation", ["com/hazia/emivita/navigation/*.kt"]),
    ("Shared components", ["com/hazia/emivita/ui/components/*.kt"]),
    ("Icons, units, theme names", ["com/hazia/emivita/ui/icons/*.kt", "com/hazia/emivita/ui/theme/*.kt"]),
    ("Notifications", ["com/hazia/emivita/core/notification/*.kt"]),
    ("PDF exports", ["com/hazia/emivita/core/data/export/*.kt"]),
    ("Formatting (status, reminder types)", ["com/hazia/emivita/core/format/*.kt"]),
    ("Other code", ["com/hazia/emivita/**/*.kt"]),
]

# Placeholder syntax as Formatter accepts it; the completeness test only matches `%(n$)?[sd]`.
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXeEgGcboaAhHn%]")

# §7 R67 glossary: the agreed renderings, and the calques the translation must not drift into.
BANNED_TERMS = {
    r"\bstock\b": "scorta (R67: never 'stock')",
    r"\bposticip\w*": "rinvia (R67: snooze is 'rinvia', not 'posticipa')",
    r"\badesion\w*": "aderenza (R67: adherence is 'aderenza')",
    r"\bmedicin[ae]\b": "farmaco (R67: medicine is 'farmaco')",
}
# English term (whole word, case-insensitive) -> Italian stems any one of which must appear.
TERM_STEMS = {
    "medicine": ("farmac",),
    "medicines": ("farmac",),
    "reminder": ("promemoria",),
    "reminders": ("promemoria",),
    "dose": ("dos",),
    "doses": ("dos",),
    "take": ("prend", "assun", "presa", "prese"),
    "taken": ("pres", "assun"),
    "skip": ("salt",),
    "skipped": ("salt",),
    "snooze": ("rinvi",),
    "snoozed": ("rinvi",),
    "clear": ("azzer", "cancell", "svuot"),
    "cancel": ("annull",),
    "delete": ("elimin",),
    "archive": ("archiv",),
    "archived": ("archiv",),
    "stock": ("scort",),
    "expiry": ("scad",),
    "expiration": ("scad",),
    "expires": ("scad",),
    "expired": ("scad",),
    "backup": ("backup", "copia"),
    "notification": ("notific",),
    "notifications": ("notific",),
    "alarm": ("allarm", "sveglia"),
    "missed": ("manc", "pers"),
    "adherence": ("aderenz",),
}
# Adjectives and participles that agree with the *user's* gender when addressed directly.
GENDERED = re.compile(
    r"\b(?:sicur|pront|cert|registrat|conness|autenticat|iscritt|pregat|invitat|benvenut|"
    r"autorizzat|abilitat|avvisat|avvertit)[oa]\b",
    re.IGNORECASE,
)
TITLE_CASE = re.compile(r"(?:^|\s)[A-ZÀ-Ý][a-zà-ÿ]+\s+[A-ZÀ-Ý][a-zà-ÿ]+")


class Entry:
    __slots__ = ("kind", "name", "value", "line", "header", "items")

    def __init__(self, kind, name, value, line, header, items=None):
        self.kind, self.name, self.value, self.line, self.header, self.items = kind, name, value, line, header, items


ELEMENT = re.compile(
    r"<!--(?P<comment>.*?)-->"
    r"|<string name=\"(?P<sname>[^\"]+)\"[^>]*>(?P<svalue>.*?)</string>"
    r"|<plurals name=\"(?P<pname>[^\"]+)\">(?P<pbody>.*?)</plurals>",
    re.DOTALL,
)
ITEM = re.compile(r"<item quantity=\"(\w+)\">(.*?)</item>", re.DOTALL)


def parse(path):
    """Sequential parse keeping line numbers and the last comment header seen before each key."""
    with open(path, "rb") as f:
        text = f.read().decode("utf-8")
    entries, comments, header = {}, [], ""
    for m in ELEMENT.finditer(text):
        line = text.count("\n", 0, m.start()) + 1
        if m.group("comment") is not None:
            body = " ".join(m.group("comment").split())
            comments.append((line, body))
            header = body
        elif m.group("sname"):
            entries[m.group("sname")] = Entry("string", m.group("sname"), m.group("svalue"), line, header)
        else:
            items = {q: v for q, v in ITEM.findall(m.group("pbody"))}
            items_line = {q: line + m.group("pbody").count("\n", 0, m.group("pbody").find(f'quantity="{q}"')) + 1 for q in items}
            entries[m.group("pname")] = Entry("plurals", m.group("pname"), None, line, header, (items, items_line))
    return entries, comments


def unescape(value):
    """What the user reads: XML entities and Android escapes resolved, newlines shown."""
    return html.unescape(value).replace("\\'", "'").replace('\\"', '"').replace("\\n", " ⏎ ")


def placeholders(value):
    return sorted(PLACEHOLDER.findall(value))


def identical_allowlist():
    with open(COMPLETENESS_TEST, encoding="utf-8") as f:
        src = f.read()
    m = re.search(r"private val identical = setOf\((.*?)\n\s*\)", src, re.DOTALL)
    if not m:
        return []
    return re.findall(r"\"([^\"]+)\"", m.group(1))


# ---------------------------------------------------------------- --check

def check(lang):
    en, en_comments = parse(os.path.join(RES, "values", "strings.xml"))
    tr_path = os.path.join(RES, f"values-{lang}", "strings.xml")
    tr, tr_comments = parse(tr_path)
    findings = []
    rel = os.path.relpath(tr_path, ROOT).replace("\\", "/")
    en_rel = "app/src/main/res/values/strings.xml"

    def note(path, line, name, what):
        findings.append(f"{path}:{line} {name}: {what}")

    for name, e in en.items():
        t = tr.get(name)
        if t is None or t.kind != e.kind:
            continue  # completeness is the test's job
        if e.kind == "string":
            pe, pt = placeholders(e.value), placeholders(t.value)
            if pe != pt:
                note(rel, t.line, name, f"placeholders {pt} differ from English {pe}")
        else:
            # A quantity may legitimately drop the count ("ultimo giorno"), so
            # the rule is subset, not equality: nothing the English never had.
            allowed = set()
            for v in e.items[0].values():
                allowed.update(placeholders(v))
            for q, v in t.items[0].items():
                extra = [ph for ph in placeholders(v) if ph not in allowed]
                if extra:
                    note(rel, t.items[1][q], f"{name}.{q}", f"placeholders {extra} that no English quantity has")

    glyph_rules = [
        (re.compile(r"\\'"), "escaped straight apostrophe; the file uses ’ (R67)"),
        (re.compile(r"(?<!\.)\.\.\.(?!\.)"), "three dots; the file uses …"),
        (re.compile(r"\S - \S"), "spaced hyphen as a dash; the file uses – for incisi"),
        (re.compile(r"\S  +\S"), "double space"),
        (re.compile(r"\s$"), "trailing whitespace"),
    ]
    for name, t in tr.items():
        values = [(t.line, t.value)] if t.kind == "string" else [(t.items[1][q], v) for q, v in t.items[0].items()]
        for line, v in values:
            if len(v) >= 2 and v[0] == v[-1] == '"':
                v = v[1:-1]  # quoted to keep leading spaces (PDF indentation); not a glyph finding
            for rx, what in glyph_rules:
                if rx.search(v):
                    note(rel, line, name, what)

    for key in identical_allowlist():
        base, _, quantity = key.partition(".")
        e, t = en.get(base), tr.get(base)
        if e is None or t is None:
            note(COMPLETENESS_TEST.replace(ROOT + os.sep, "").replace("\\", "/"), 0, key, "allow-listed key does not exist")
            continue
        ev = e.value if not quantity else e.items[0].get(quantity)
        tv = t.value if not quantity else t.items[0].get(quantity)
        if ev != tv:
            note(rel, t.line, key, f"allow-listed as identical but translated ({unescape(tv)!r} vs {unescape(ev)!r})")

    for path, comments in ((en_rel, en_comments), (rel, tr_comments)):
        for line, body in comments:
            if "--" in body:
                note(path, line, "<!-- -->", "comment body contains -- (§10)")

    for f in findings:
        print(f)
    print(f"{len(findings)} finding(s) in values-{lang}")
    return 1 if findings else 0


# ---------------------------------------------------------------- --sheet

FUN = re.compile(r"^(?:@\w+(?:\([^)]*\))?\s*\n)*(?:internal |private |public )?fun (\w+)", re.MULTILINE)
REF = re.compile(r"R\.(?:string|plurals)\.(\w+)")


def references():
    """key -> (screen label, sub-group, file) for the first reference in canonical order."""
    seen_files, owner = set(), {}
    for label, globs in SCREENS:
        files = []
        for g in globs:
            files += sorted(glob.glob(os.path.join(APP_SRC, g), recursive=True))
        for path in files:
            path = os.path.normpath(path)  # `**` globs return the other separator on Windows
            if path in seen_files:
                continue
            seen_files.add(path)
            with open(path, encoding="utf-8") as f:
                src = f.read()
            funs = [(m.start(), m.group(1)) for m in FUN.finditer(src)]
            rel = os.path.relpath(path, APP_SRC).replace("\\", "/")
            for m in REF.finditer(src):
                key = m.group(1)
                fun = next((name for start, name in reversed(funs) if start <= m.start()), "(top level)")
                owner.setdefault(key, []).append((label, fun, rel))
    return owner


def flags_for(name, e, t):
    en_v, it_v = unescape(e.value), unescape(t.value)
    flags = []
    if len(en_v) <= 20 and len(en_v) > 0 and len(it_v) / len(en_v) > 1.35:
        flags.append(f"LEN×{len(it_v) / len(en_v):.2f}")
    if TITLE_CASE.search(it_v) and not name.startswith("a11y_"):
        flags.append("TITLE")
    if GENDERED.search(it_v):
        flags.append("GENDER")
    low_it = it_v.lower()
    for rx, what in BANNED_TERMS.items():
        if re.search(rx, low_it):
            flags.append(f"TERM:{what}")
    en_words = set(re.findall(r"[a-z]+", en_v.lower()))
    for term, stems in TERM_STEMS.items():
        if term in en_words and not any(s in low_it for s in stems):
            flags.append(f"TERM?{term}")
    return flags


def sheet(lang, out_path):
    en, _ = parse(os.path.join(RES, "values", "strings.xml"))
    tr, _ = parse(os.path.join(RES, f"values-{lang}", "strings.xml"))
    owner = references()
    order = [label for label, _ in SCREENS]
    groups = {}  # (screen index, screen, fun) -> rows
    for name, e in en.items():
        t = tr.get(name)
        if t is None:
            continue
        refs = owner.get(name)
        if refs:
            label, fun, rel = refs[0]
            key = (order.index(label), label, fun)
            shared = len({r[0] for r in refs}) > 1
        else:
            key = (len(order), "Not referenced from Kotlin (manifest, header: " + (e.header[:60] or "none") + ")", "")
            shared = False
        if e.kind == "string":
            rows = [(name, e.value, t.value, flags_for(name, e, t), shared)]
        else:
            rows = []
            for q, v in t.items[0].items():
                ev = e.items[0].get(q) or e.items[0].get("other", "")
                fake_e, fake_t = Entry("string", name, ev, 0, ""), Entry("string", name, v, 0, "")
                rows.append((f"{name}.{q}", ev, v, flags_for(name, fake_e, fake_t), shared))
        groups.setdefault(key, []).extend(rows)

    def cell(v):
        return unescape(v).replace("|", "\\|").replace("\n", " ")

    total = flagged = 0
    lines = [f"# values-{lang} review sheet", "", "Flags: LEN×r = translated/English length ratio on a label ≤ 20 chars; TITLE = Title Case; "
             "GENDER = user-addressed gendered form; TERM: = R67 glossary calque; TERM?x = English term without any agreed Italian stem. "
             "`shared` = also referenced from another screen. Flags aim the reading; every row still gets read.", ""]
    body, summary = [], []
    for key in sorted(groups):
        rows = groups[key]
        n_flag = sum(1 for r in rows if r[3])
        total += len(rows)
        flagged += n_flag
        title = f"{key[1]} › {key[2]}" if key[2] else key[1]
        summary.append(f"- {title}: {n_flag}/{len(rows)} flagged")
        body += [f"## {title}", "", "| key | EN | IT | flags |", "|---|---|---|---|"]
        for name, ev, tv, fl, shared in rows:
            mark = " `shared`" if shared else ""
            body.append(f"| `{name}`{mark} | {cell(ev)} | {cell(tv)} | {' '.join(fl)} |")
        body.append("")
    lines += [f"**{flagged}/{total} rows flagged.**", ""] + summary + [""] + body
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    print(f"{total} rows, {flagged} flagged -> {out_path}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--lang", default="it")
    ap.add_argument("--check", action="store_true", help="deterministic findings; exit 1 on any")
    ap.add_argument("--sheet", metavar="OUT.md", help="write the per-screen review sheet")
    args = ap.parse_args()
    if not args.check and not args.sheet:
        ap.error("nothing to do: pass --check and/or --sheet OUT.md")
    rc = 0
    if args.check:
        rc = check(args.lang)
    if args.sheet:
        rc = max(rc, sheet(args.lang, args.sheet))
    return rc


if __name__ == "__main__":
    sys.exit(main())
