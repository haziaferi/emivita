#!/usr/bin/env python3
"""Computable code-health checks over the Kotlin sources (SPEC §7 R82).

`--check` decides only what a script can decide and exits 1 on any finding;
`--report OUT.md` writes the full inventory, including the rows that need a
human judgement. `--graph FILE.json` emits the symbol-reference graph of one
file for the split optimizer.

The comment rules come from §10 and the owner's 2026-10-12 ruling: a comment
records a decision and says why, and no comment block runs longer than three
sentences. Density itself is not a defect; length, staleness and restatement
are.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import defaultdict

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

SENTENCE_CAP = 3
BIG_FILE_LINES = 500

# Entry points nothing in the sources references by name: the manifest, the
# Compose runtime or an androidx registry calls them.
ENTRY_SUPERTYPES = (
    "Activity", "Application", "BroadcastReceiver", "Service", "GlanceAppWidget",
    "GlanceAppWidgetReceiver", "Worker", "CoroutineWorker", "Migration", "TypeConverter",
)
ENTRY_ANNOTATIONS = ("@Preview", "@Dao", "@Database", "@Entity", "@TypeConverter", "@Test", "@Before", "@After")

# Kotlin resolves these by convention, not by name, so they are never written
# out: `getValue`/`setValue` back every `var x by remember { ... }`. A textual
# census cannot see them and must not call their imports unused.
CONVENTION_IMPORTS = frozenset("""
getValue setValue provideDelegate get set invoke iterator hasNext next contains
compareTo equals hashCode toString rangeTo rangeUntil plus minus times div rem
mod unaryPlus unaryMinus not inc dec plusAssign minusAssign timesAssign
divAssign remAssign component1 component2 component3 component4 component5
""".split())

DECL = re.compile(
    r"^(?P<kw>(?:(?:private|internal|public|abstract|open|sealed|data|enum|annotation|value|suspend|inline|external|expect|actual)\s+)*)"
    r"(?P<kind>fun|val|var|class|object|interface|typealias)\s+"
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)
IMPORT = re.compile(r"^import\s+([A-Za-z0-9_.]+)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?")
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def sanitize(text: str, drop_comments: bool = False) -> str:
    """Blank string literals, keeping `${...}` template code, which is live Kotlin.

    One pass has to know about comments and literals together: a quote inside a
    comment must not open a literal, and a `//` inside a literal must not open a
    comment. Nested quotes are real Kotlin too (`"${"%02d".format(h)}"`), and
    mis-pairing them is what once ran a three-line comment to end of file.
    """
    out = list(text)
    n = len(text)

    def blank(a: int, b: int) -> None:
        for k in range(max(a, 0), min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    def skip_template(k: int) -> int:
        """Index just past the `}` closing the `${` at k, nested literals included."""
        depth, j = 0, k + 1
        while j < n:
            if text[j] == '"':
                j = do_string(j)
                continue
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    return j + 1
            j += 1
        return n

    def do_string(i: int) -> int:
        """Blank the literal at i, preserving template code; return the next index."""
        triple = text.startswith('"""', i)
        width = 3 if triple else 1
        blank(i, i + width)
        j = i + width
        while j < n:
            if triple and text.startswith('"""', j):
                blank(j, j + 3)
                return j + 3
            if not triple and (text[j] == '"' or text[j] == "\n"):
                blank(j, j + 1)
                return j + 1
            if not triple and text[j] == "\\":
                blank(j, j + 2)
                j += 2
                continue
            if text.startswith("${", j):
                end = skip_template(j)
                blank(j, j + 2)
                blank(end - 1, end)
                j = end
                continue
            if text[j] == "$" and j + 1 < n and (text[j + 1].isalpha() or text[j + 1] == "_"):
                blank(j, j + 1)
                j += 1
                while j < n and (text[j].isalnum() or text[j] == "_"):
                    j += 1
                continue
            blank(j, j + 1)
            j += 1
        return n

    i = 0
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            if drop_comments:
                blank(i, end)
            i = end
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            if drop_comments:
                blank(i, end)
            i = end
            continue
        if text[i] == '"':
            i = do_string(i)
            continue
        if text[i] == "'":
            j = i + 1
            while j < n and text[j] != "'" and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            blank(i, min(j + 1, n))
            i = j + 1
            continue
        i += 1
    return "".join(out)


def strip_literals(text: str) -> str:
    """Literals blanked, comments left readable (for comment_spans)."""
    return sanitize(text, drop_comments=False)


def code_only(text: str) -> str:
    """Literals and comments both blanked (for the identifier census)."""
    return sanitize(text, drop_comments=True)


def comment_spans(text: str):
    """Yield (start_line, end_line, body, kind) for every comment block, literals excluded."""
    src = strip_literals(text)
    lines = src.split("\n")
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped.startswith("//"):
            start = i
            body = []
            while i < len(lines) and lines[i].strip().startswith("//"):
                body.append(lines[i].strip()[2:].strip())
                i += 1
            yield (start + 1, i, " ".join(body).strip(), "line")
            continue
        if stripped.startswith("/*"):
            start = i
            body = []
            kind = "kdoc" if stripped.startswith("/**") else "block"
            while i < len(lines):
                cleaned = re.sub(r"^\s*/?\*+/?", "", lines[i])
                cleaned = re.sub(r"\*/\s*$", "", cleaned)
                body.append(cleaned.strip())
                if "*/" in lines[i]:
                    i += 1
                    break
                i += 1
            yield (start + 1, i, " ".join(b for b in body if b).strip(), kind)
            continue
        i += 1


def count_sentences(body: str) -> int:
    """Sentences in a comment body, ignoring KDoc tags and abbreviation dots."""
    body = re.sub(r"@\w+", " ", body)
    body = re.sub(r"\b(e\.g|i\.e|vs|etc|cf|no|Nr|approx|min|max|v)\.", r"\1", body)
    body = re.sub(r"\b([A-Z])\.", r"\1", body)
    body = re.sub(r"(\d)\.(\d)", r"\1\2", body)
    parts = [p for p in re.split(r"[.!?](?:\s|$)|;\s(?=[A-Z])", body) if p.strip()]
    return len(parts)


def kt_files(root: str, *rel):
    for base in rel:
        for dirpath, _dirs, names in os.walk(os.path.join(root, base)):
            for name in names:
                if name.endswith(".kt"):
                    yield os.path.join(dirpath, name)


def rel(root: str, path: str) -> str:
    return os.path.relpath(path, root).replace("\\", "/")


def top_level_decls(text: str):
    """(name, kind, start_line, end_line) for declarations at indentation zero."""
    src = strip_literals(text)
    lines = src.split("\n")
    found = []
    for idx, line in enumerate(lines):
        m = DECL.match(line)  # anchored, so an indented declaration never matches
        if not m:
            continue
        depth = 0
        end = idx
        opened = False
        for j in range(idx, len(lines)):
            depth += lines[j].count("{") - lines[j].count("}")
            if "{" in lines[j]:
                opened = True
            end = j
            if opened and depth <= 0:
                break
            if not opened:
                # A declaration with no body ends as soon as its parentheses
                # balance, including on its own line.
                paren = sum(lines[k].count("(") - lines[k].count(")") for k in range(idx, j + 1))
                if paren <= 0 and lines[j].strip():
                    break
        found.append((m.group("name"), m.group("kind"), idx + 1, end + 1))
    return found


def is_entry_point(text: str, name: str, start: int) -> bool:
    lines = text.split("\n")
    header = "\n".join(lines[max(0, start - 6) : start + 1])
    if any(a in header for a in ENTRY_ANNOTATIONS):
        return True
    decl_line = lines[start - 1] if start - 1 < len(lines) else ""
    return any(sup in decl_line for sup in ENTRY_SUPERTYPES)


def analyse(root: str):
    main = sorted(kt_files(root, "app/src/main/java"))
    tests = sorted(kt_files(root, "app/src/test/java", "app/src/androidTest/java"))
    texts = {p: open(p, "rb").read().decode("utf-8") for p in main + tests}
    stripped = {p: code_only(t) for p, t in texts.items()}

    # One identifier census over every file, so reference counts cost one pass.
    # Comments are excluded: a symbol named only in prose is not a use of it.
    census = defaultdict(int)
    per_file = {}
    for path, src in stripped.items():
        names = IDENT.findall(src)
        per_file[path] = names
        for name in set(names):
            census[name] += names.count(name)

    # A comment may legitimately name an XML attribute value, the content of a
    # string literal, or an alternative the code deliberately rejected. Claiming
    # one is stale therefore needs the widest evidence, not the code census.
    wide = set()
    for body in texts.values():
        wide.update(IDENT.findall(body))
    for dirpath, _dirs, names in os.walk(os.path.join(root, "app/src/main")):
        for nm in names:
            if nm.endswith((".xml", ".pro", ".kts")):
                with open(os.path.join(dirpath, nm), "rb") as fh:
                    wide.update(IDENT.findall(fh.read().decode("utf-8", "replace")))

    findings = {"long_comments": [], "stale_comments": [], "restating": [],
                "dead": [], "big_files": [], "unused_imports": [], "duplicates": []}

    for path in main + tests:
        text = texts[path]
        name = rel(root, path)
        lines = text.split("\n")

        for start, end, body, kind in comment_spans(text):
            if not body:
                continue
            n = count_sentences(body)
            if n > SENTENCE_CAP:
                findings["long_comments"].append(
                    {"file": name, "line": start, "sentences": n, "lines": end - start + 1,
                     "head": body[:90]})
            # A comment naming a symbol that exists nowhere is describing code
            # that moved or was renamed.
            for token in set(re.findall(r"`([A-Za-z_][A-Za-z0-9_]*)`", body)):
                if token not in wide:
                    findings["stale_comments"].append(
                        {"file": name, "line": start, "symbol": token, "head": body[:70]})
            nxt = next((l.strip() for l in lines[end : end + 2] if l.strip()), "")
            if nxt and kind == "line" and n <= 1:
                words = {w.lower() for w in IDENT.findall(body)}
                code = {w.lower() for w in IDENT.findall(nxt)}
                if code and len(words & code) >= max(2, len(code) - 1) and len(words) <= len(code) + 2:
                    findings["restating"].append({"file": name, "line": start, "comment": body[:60], "code": nxt[:60]})

        if path in main and len(lines) > BIG_FILE_LINES:
            decls = top_level_decls(text)
            findings["big_files"].append(
                {"file": name, "lines": len(lines), "top_level": len(decls),
                 "largest": sorted(((e - s + 1, n) for n, _k, s, e in decls), reverse=True)[:5]})

        for idx, line in enumerate(lines):
            m = IMPORT.match(line.strip())
            if not m:
                continue
            symbol = m.group(2) or m.group(1).rsplit(".", 1)[-1]
            if symbol == "*" or symbol in CONVENTION_IMPORTS:
                continue
            body_names = per_file[path]
            if body_names.count(symbol) <= 1:
                findings["unused_imports"].append({"file": name, "line": idx + 1, "symbol": symbol})

    # Dead code: declared in main, referenced only where it is declared.
    for path in main:
        text = texts[path]
        name = rel(root, path)
        for sym, kind, start, end in top_level_decls(text):
            if is_entry_point(text, sym, start):
                continue
            total = census.get(sym, 0)
            here = per_file[path].count(sym)
            if total <= here and total <= 1:
                findings["dead"].append({"file": name, "line": start, "kind": kind, "symbol": sym,
                                         "size": end - start + 1})

    # Duplicate blocks: identical six-line windows after whitespace collapse.
    windows = defaultdict(list)
    for path in main:
        body = [re.sub(r"\s+", " ", l.strip()) for l in stripped[path].split("\n")]
        for i in range(len(body) - 6):
            chunk = tuple(body[i : i + 6])
            if sum(bool(c) for c in chunk) < 6 or all(len(c) < 12 for c in chunk):
                continue
            windows[chunk].append((rel(root, path), i + 1))
    # Overlapping windows describe one duplicated block, so report only the
    # first window of each run: the one whose shift-back is not also duplicated.
    placesets = {}
    for chunk, places in windows.items():
        if len(places) >= 2:
            placesets[tuple(sorted(places))] = chunk[0]
    for key, head in sorted(placesets.items()):
        earlier = tuple(sorted((f, ln - 1) for f, ln in key))
        if earlier in placesets:
            continue
        run = 6
        while tuple(sorted((f, ln + run - 5) for f, ln in key)) in placesets:
            run += 1
        findings["duplicates"].append({"places": list(key), "lines": run, "head": head[:70]})

    return findings, {"main_files": len(main), "test_files": len(tests)}


def emit_graph(root: str, target: str, out: str):
    """Symbol-reference graph of one file: nodes are top-level decls, edges are references."""
    path = os.path.join(root, target)
    text = open(path, "rb").read().decode("utf-8")
    src = strip_literals(text).split("\n")
    decls = top_level_decls(text)
    raw = text.split("\n")
    # `private` at top level means file-private, so moving such a declaration
    # away from its callers forces a widening to `internal`.
    nodes = [{"symbol": n, "kind": k, "start": s, "end": e, "lines": e - s + 1,
              "private": "private" in raw[s - 1]} for n, k, s, e in decls]
    names = {n["symbol"] for n in nodes}
    edges = defaultdict(int)
    for node in nodes:
        body = "\n".join(src[node["start"] : node["end"]])
        for token in IDENT.findall(body):
            if token in names and token != node["symbol"]:
                edges[(node["symbol"], token)] += 1
    payload = {"file": target, "total_lines": len(src), "nodes": nodes,
               "edges": [{"from": a, "to": b, "weight": w} for (a, b), w in sorted(edges.items())]}
    with open(out, "wb") as fh:
        fh.write(json.dumps(payload, indent=2).encode("utf-8"))
    print(f"{target}: {len(nodes)} top-level declarations, {len(payload['edges'])} edges -> {out}")


def selftest() -> int:
    """Each case is a bug this tool actually shipped on 2026-10-12 (§7 R82)."""
    cases = []

    kept = strip_literals('val n = "backup_${fmt(STAMP)}.zip"')
    cases.append(("template code survives", "STAMP" in kept and "backup" not in kept))

    decls = top_level_decls('private val STAMP = Fmt.of("x")\n\nfun later() {\n    use(STAMP)\n}\n')
    stamp = [d for d in decls if d[0] == "STAMP"]
    cases.append(("braceless val ends on its line", bool(stamp) and stamp[0][3] == 1))
    cases.append(("later() is found too", any(d[0] == "later" for d in decls)))

    cases.append(("four sentences counted", count_sentences("One. Two. Three. Four.") == 4))
    cases.append(("abbreviations are not sentences", count_sentences("Use e.g. this i.e. that.") == 1))
    # A `@param` clause is documentation and counts; only the tag token itself
    # must not become a sentence of its own.
    cases.append(("kdoc tag is not its own sentence", count_sentences("@param x the thing.") == 1))

    spans = list(comment_spans('// a\n// b\nfun f() {}\n/** doc. */\nval v = 1\n'))
    cases.append(("line block merged", spans and spans[0][2] == "a b"))
    cases.append(("kdoc block found", any(k == "kdoc" for _s, _e, _b, k in spans)))
    cases.append(("comment inside a literal is not a comment",
                  not list(comment_spans('val s = "// not a comment"\n'))))

    # The three lexer confusions that produced wrong numbers on 2026-10-12.
    nested = 'fun f() = "${"%02d".format(h)}:${"%02d".format(m)}"\n/** Doc. */\nfun g() {}\n'
    spans = list(comment_spans(nested))
    cases.append(("nested quotes inside a template do not swallow the next comment",
                  len(spans) == 1 and spans[0][0] == 2 and spans[0][1] == 2))
    cases.append(("identifiers inside a template still count",
                  "format" in IDENT.findall(code_only(nested))))
    cases.append(("an apostrophe in a comment opens nothing",
                  len(list(comment_spans("// don't stop\nval after = 1\n"))) == 1))
    cases.append(("comments leave no identifiers in the census",
                  "Ghost" not in IDENT.findall(code_only("// Ghost\nval real = 1\n"))))

    cases.append(("delegation imports exempt", "getValue" in CONVENTION_IMPORTS))

    failed = [name for name, ok in cases if not ok]
    for name, ok in cases:
        print(f"  {'ok  ' if ok else 'FAIL'} {name}")
    if failed:
        print(f"\n{len(failed)} self-test(s) failed", file=sys.stderr)
        return 1
    print(f"\n{len(cases)} self-tests passed")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true", help="exit 1 on any mechanical finding")
    ap.add_argument("--report", metavar="OUT.md")
    ap.add_argument("--graph", nargs=2, metavar=("KT_FILE", "OUT.json"))
    ap.add_argument("--selftest", action="store_true", help="prove each detector can still fire")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    root = os.path.normpath(os.path.abspath(args.root))
    while not os.path.isdir(os.path.join(root, "app/src/main/java")):
        parent = os.path.dirname(root)
        if parent == root:
            print("no app/src/main/java above --root", file=sys.stderr)
            return 2
        root = parent

    if args.graph:
        emit_graph(root, args.graph[0], args.graph[1])
        return 0

    findings, counts = analyse(root)
    # Only checks with no known false-positive class gate the build; a stale
    # comment or a duplicate window is a reading aid, not a verdict.
    hard = ["long_comments", "unused_imports", "dead"]
    totals = {k: len(v) for k, v in findings.items()}

    if args.report:
        lines = ["# Code health report", "",
                 f"{counts['main_files']} main files, {counts['test_files']} test files.", ""]
        for key in ("long_comments", "stale_comments", "restating", "dead", "unused_imports",
                    "big_files", "duplicates"):
            lines.append(f"## {key} ({totals[key]})")
            lines.append("")
            for item in findings[key]:
                lines.append("- " + json.dumps(item, ensure_ascii=False))
            lines.append("")
        with open(args.report, "wb") as fh:
            fh.write("\n".join(lines).encode("utf-8"))
        print(f"report -> {args.report}")

    for key in ("long_comments", "stale_comments", "restating", "dead", "unused_imports",
                "big_files", "duplicates"):
        print(f"{key:16} {totals[key]}")

    if args.check:
        bad = sum(totals[k] for k in hard)
        if bad:
            print(f"\n{bad} finding(s) a script can decide; see --report", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
