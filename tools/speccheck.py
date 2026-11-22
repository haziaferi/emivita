# Vendored from dev-engineering-suite 1.21.0, skills/project-spec-writer/scripts/speccheck.py
# (same author). Replace the whole file to update it; never edit it here (SPEC R150).
r"""speccheck: check a SPEC.md (and the SPEC-history.md beside it) against project-spec-writer's format.

Every finding is BLOCK, ASK or REPORT; the exit code is the worst one (2, 1, 0).

    python speccheck.py SPEC.md                 structure, formatting, history sync
    python speccheck.py SPEC.md --base HEAD     also compare with a git revision: changed owner rules (ASK)
                                                and changed rules that code cites as SPEC:Rnn (BLOCK)
    python speccheck.py SPEC.md --write-index   rebuild the "## Rule index" table (and the "## Acceptance list",
                                                when the spec has one), then check

It also prints how many open (Q-) rules the spec holds, so a reply's open count can be taken from it.

Standard library only.
"""
import argparse
import os
import re
import subprocess
import sys

KINDS = "ODIAQ"
FIELDS = ["Rule", "Default", "Why", "Values", "Evidence", "Acceptance", "Not", "Since"]
EVENTS = {"added", "changed", "kind", "retired", "renamed", "migrated"}
HISTORY_MAX = 200
RULE_HEAD = re.compile(r"^###\s+([A-Z])-R(\d+)\s+·\s+(\S.*?)\s*$")
LOOKS_LIKE_RULE = re.compile(r"^###\s+\S*R\d+")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*$")
FIELD = re.compile(r"^\*\*([A-Za-z]+):\*\*(\s|$)")
REF = re.compile(r"(?<![\w-])(?:([A-Z])-)?R(\d{2,})(?!\w)")
CITE = re.compile(r"SPEC[:_]R(\d+)")
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
RELATIVE = re.compile(r"(?i)\b(now|currently|recently|nowadays|for now|at the moment|so far|as of|"
                      r"this (?:batch|pass|session|sprint|round))\b")
# status in a heading: a date, or a parenthetical holding a status word ("Gallery (built)")
STATUS_WORDS = re.compile(r"(?i)\([^)]*\b(built|done|locked|deferred|wip|todo|in progress|status|fixed|resolved|"
                          r"superseded|amended|verified|pending)\b[^)]*\)|\d{4}-\d{2}-\d{2}")
QUOTED = re.compile(r'"[^"\n]*"|“[^”\n]*”')
OLD_TAG = re.compile(r"\[(?:Verified|Assumed|Amended|Reopened|Conflict|Decided|Resolved|Fixed|Added|Proposed|Open|"
                     r"Deferred|S-\d+)[^\]]*\]", re.I)
BOLD = re.compile(r"\*\*[^*\n]+?\*\*|__[^_\n]+?__")
ITALIC = re.compile(r"(?<![*\w])\*(?![\s*])[^*\n]+?(?<![\s*])\*(?![*\w])|(?<![_\w])_(?![\s_])[^_\n]+?(?<![\s_])_(?![_\w])")
CODE_SPAN = re.compile(r"(`+)(.+?)\1")
SEVERITY = {"BLOCK": 2, "ASK": 1, "REPORT": 0}


class Finding:
    def __init__(self, level, path, line, msg):
        self.level, self.path, self.line, self.msg = level, path, line, msg

    def __str__(self):
        return "%-6s %s:%s  %s" % (self.level, os.path.basename(self.path), self.line or "-", self.msg)


class Rule:
    def __init__(self, kind, num, name, line, section):
        self.kind, self.num, self.name, self.line, self.section = kind, num, name, line, section
        self.fields = []          # (label, line)
        self.body = []            # raw lines after the heading
        self.since_date = None
        self.since_parts = []

    @property
    def by_owner(self):
        return len(self.since_parts) > 1 and self.since_parts[1].lower() == "owner"

    @property
    def id(self):
        return "%s-R%02d" % (self.kind, self.num)

    def text(self):
        return re.sub(r"\s+", " ", "\n".join(self.body)).strip()


def strip_code(line):
    return CODE_SPAN.sub(lambda m: " " * len(m.group(0)), line)


def strip_quotes(line):
    """UI copy in quotes is the app's own wording: "Today" or "This week" there is not the spec speaking."""
    return QUOTED.sub(lambda m: " " * len(m.group(0)), line)


def is_emoji(ch):
    """Pictographs and dingbats (U+1F000-1FAFF, U+2600-27BF, U+2B00-2BFF, the emoji selector); not the degree
    sign, the copyright sign or other technical symbols, which are text."""
    o = ord(ch)
    return 0x1F000 <= o <= 0x1FAFF or 0x2600 <= o <= 0x27BF or 0x2B00 <= o <= 0x2BFF or o == 0xFE0F


def split_cells(row):
    """Cells of a Markdown table row; a | inside a code span or escaped as \\| does not split."""
    s = strip_code(row.replace("\\|", "  "))
    s = s.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return s.split("|")


def parse_spec(text):
    """Return (rules, tables, lines, prose) where prose lines are outside code fences, with their numbers."""
    lines = text.split("\n")
    rules, tables, prose = [], [], []
    fence, section, sub_heading, cur, field = None, None, None, None, None
    table = None
    for i, raw in enumerate(lines, 1):
        stripped = raw.strip()
        m = re.match(r"^(`{3,}|~{3,})", stripped)
        if m:
            if fence is None:
                fence = m.group(1)[0] * len(m.group(1))
            elif stripped.startswith(fence):
                fence = None
            if cur:
                cur.body.append(raw)
            continue
        if fence:
            if cur:
                cur.body.append(raw)
            continue
        prose.append((i, raw))
        if stripped.startswith("|"):
            if table is None:
                table = {"start": i, "rows": [], "section": section, "rule": cur, "field": field}
                tables.append(table)
            table["rows"].append((i, raw))
        else:
            table = None
        h = HEADING.match(raw)
        if h:
            level = len(h.group(1))
            if level <= 2:
                section, cur, field = h.group(2), None, None
            elif level == 3:
                rm = RULE_HEAD.match(raw)
                cur, field = None, None
                if rm:
                    cur = Rule(rm.group(1), int(rm.group(2)), rm.group(3), i, section)
                    rules.append(cur)
                sub_heading = h.group(2)
            else:
                field = None
            continue
        if cur is not None:
            cur.body.append(raw)
            fm = FIELD.match(stripped)
            if fm:
                field = fm.group(1)
                cur.fields.append((field, i))
                if field == "Since":
                    parts = [p.strip() for p in stripped[len("**Since:**"):].split("·")]
                    cur.since_date = parts[0] if parts and DATE.match(parts[0]) else None
                    cur.since_parts = parts
    del sub_heading
    return rules, tables, lines, prose


def history_path(spec_path):
    d, b = os.path.split(spec_path)
    stem = os.path.splitext(b)[0]
    return os.path.join(d, stem + "-history.md")


def parse_history(path, out):
    rows = []
    if not os.path.exists(path):
        out.append(Finding("REPORT", path, None, "no history file beside the spec"))
        return rows
    text = open(path, encoding="utf-8").read()
    if not re.search(r"(?i)not a source of current rules", text):
        out.append(Finding("REPORT", path, 1, 'the history does not say it is "not a source of current rules"'))
    for i, raw in enumerate(text.split("\n"), 1):
        if not raw.startswith("- "):
            continue
        if len(raw) > HISTORY_MAX:
            out.append(Finding("REPORT", path, i, "history row is %d characters (max %d): the reasoning belongs "
                                                  "in the rule's Why or Not" % (len(raw), HISTORY_MAX)))
        parts = [p.strip() for p in raw[2:].split(" · ")]
        if len(parts) < 5 or not DATE.match(parts[0]) or not re.match(r"^(R\d+|spec)$", parts[1]) \
                or parts[2] not in EVENTS:
            out.append(Finding("REPORT", path, i, "malformed history row: expected "
                                                  "'- YYYY-MM-DD · Rnn|spec · event · detail · who [· commit]'"))
            continue
        num = int(parts[1][1:]) if parts[1] != "spec" else None
        rows.append({"line": i, "date": parts[0], "num": num, "event": parts[2], "detail": parts[3]})
    dates = [r["date"] for r in rows]
    if dates != sorted(dates):
        out.append(Finding("REPORT", path, None, "history rows are not in date order (newest at the bottom)"))
    return rows


def check_formatting(path, prose, rules, out):
    rule_lines = {r.line for r in rules}
    for i, raw in prose:
        line = strip_code(raw)
        s = line.strip()
        h = HEADING.match(raw)
        if h:
            if len(h.group(1)) >= 2 and i not in rule_lines:
                if LOOKS_LIKE_RULE.match(raw):
                    out.append(Finding("REPORT", path, i, "rule heading must be '### K-Rnn · Name' with K in %s"
                                       % KINDS))
            title = strip_quotes(strip_code(h.group(2)))
            if len(h.group(1)) >= 2 and STATUS_WORDS.search(title):
                out.append(Finding("REPORT", path, i, "status or date in a heading: %r" % title))
        fm = FIELD.match(s)
        rest = s[fm.end():] if fm else s
        if BOLD.search(rest):
            out.append(Finding("REPORT", path, i, "bold outside a field label"))
        if ITALIC.search(rest.replace("**", "")):
            out.append(Finding("REPORT", path, i, "italics (not used: define terms in the glossary)"))
        if "~~" in line:
            out.append(Finding("REPORT", path, i, "strikethrough (not used: old wording leaves the spec)"))
        if s.startswith(">"):
            out.append(Finding("REPORT", path, i, "blockquote (not used: Rule holds the agreed wording)"))
        if re.search(r"(?i)</?u>", line):
            out.append(Finding("REPORT", path, i, "underline (not used)"))
        if any(is_emoji(c) for c in line):
            out.append(Finding("REPORT", path, i, "icon or emoji (never used)"))
        if OLD_TAG.search(line):
            out.append(Finding("REPORT", path, i, "bracket tag %r: use the kind prefix, Evidence and the history"
                               % OLD_TAG.search(line).group(0)))
        if "§" in line:
            out.append(Finding("REPORT", path, i, "§ reference: cite the rule number instead"))
        m = RELATIVE.search(strip_quotes(line))
        if m and not s.startswith("|---"):
            out.append(Finding("REPORT", path, i, "relative time %r: use a date or a rule number" % m.group(0)))


def check_tables(path, tables, out):
    for t in tables:
        rows = t["rows"]
        head = len(split_cells(rows[0][1]))
        for i, raw in rows[1:]:
            n = len(split_cells(raw))
            if n != head:
                out.append(Finding("REPORT", path, i, "table row has %d cells, the header has %d" % (n, head)))
        if len(rows) < 2 or not re.match(r"^\|?\s*:?-{3,}", rows[1][1].strip()):
            out.append(Finding("REPORT", path, t["start"], "table without a header separator row"))
        sec = (t["section"] or "").strip()
        allowed = sec in ("Rule index", "Glossary") or (t["rule"] is not None and t["field"] == "Values")
        if not allowed:
            out.append(Finding("REPORT", path, t["start"], "table outside its three jobs (rule index, glossary, "
                                                           "a rule's Values)"))


def check_rules(path, rules, out):
    seen = {}
    for r in rules:
        if r.kind not in KINDS:
            out.append(Finding("REPORT", path, r.line, "unknown kind prefix %r (use %s)" % (r.kind, KINDS)))
        if r.num in seen:
            out.append(Finding("BLOCK", path, r.line, "R%02d is used twice (also line %d)" % (r.num, seen[r.num])))
        seen.setdefault(r.num, r.line)
        labels = [f for f, _ in r.fields]
        for f, ln in r.fields:
            if f not in FIELDS:
                out.append(Finding("REPORT", path, ln, "unknown field %r" % f))
        known = [f for f in labels if f in FIELDS]
        if known != sorted(known, key=FIELDS.index):
            out.append(Finding("REPORT", path, r.line, "%s: fields out of order (%s)" % (r.id, ", ".join(FIELDS))))
        if len(known) != len(set(known)):
            out.append(Finding("REPORT", path, r.line, "%s: a field appears twice" % r.id))
        if "Rule" not in labels:
            out.append(Finding("REPORT", path, r.line, "%s: no Rule field" % r.id))
        if "Since" not in labels:
            out.append(Finding("REPORT", path, r.line, "%s: no Since field" % r.id))
        elif r.since_date is None:
            out.append(Finding("REPORT", path, r.line, "%s: Since must start with YYYY-MM-DD" % r.id))
        if r.kind == "O" and "Why" not in labels:
            out.append(Finding("REPORT", path, r.line, "%s: an owner rule needs its Why" % r.id))
        if r.kind == "Q" and "Default" not in labels:
            out.append(Finding("REPORT", path, r.line, "%s: an open question needs a Default" % r.id))
        default = field_text(r, "Default") if r.kind == "Q" and "Default" in labels else None
        if r.kind == "Q" and "Default" in labels and (not default
                                                      or re.match(r"(?i)^none proposed:\s*\.?$", default)):
            out.append(Finding("REPORT", path, r.line, "%s: Default is empty: write the proposed answer, or "
                                                       "'none proposed:' and why" % r.id))
        if r.kind in "OD" and "Acceptance" not in labels:
            out.append(Finding("REPORT", path, r.line, "%s: no Acceptance (write how it is checked, or "
                                                       "'none:' and why)" % r.id))
        acc = field_text(r, "Acceptance") or ""
        if re.match(r"(?i)^none:\s*\.?$", acc):
            out.append(Finding("ASK", path, r.line, "%s: Acceptance undecided ('none:' with no reason): ask the "
                                                    "owner how it is checked, or why nothing can be" % r.id))
        if r.kind != "Q" and "Default" in labels:
            out.append(Finding("REPORT", path, r.line, "%s: only an open question has a Default" % r.id))
        rule_text = field_text(r, "Rule")
        if rule_text and len(re.findall(r"[.!?](\s|$)", rule_text)) > 3:
            out.append(Finding("REPORT", path, r.line, "%s: Rule is over three sentences" % r.id))
        if re.search(r"(?i)\b(previously|used to|no longer|was changed|formerly)\b", rule_text or ""):
            out.append(Finding("REPORT", path, r.line, "%s: Rule tells history; state what is true" % r.id))


def field_text(rule, label):
    out, on = [], False
    for raw in rule.body:
        m = FIELD.match(raw.strip())
        if m:
            on = m.group(1) == label
            if on:
                out.append(raw.strip()[m.end():])
            continue
        if on:
            out.append(raw)
    return " ".join(x.strip() for x in out).strip() or None


def check_refs(path, prose, rules, retired, out):
    nums = {r.num for r in rules}
    heads = {r.line for r in rules}
    for i, raw in prose:
        if i in heads:
            continue
        line = raw
        if raw.strip().startswith("|") and re.match(r"^\|\s*[A-Z]-R\d+\s*\|", raw.strip()):
            continue  # rule index rows name the full ID on purpose
        for m in REF.finditer(line):
            num = int(m.group(2))
            if num in retired and num not in nums:
                out.append(Finding("BLOCK", path, i, "R%02d is retired but still referenced" % num))
            elif num not in nums:
                out.append(Finding("BLOCK", path, i, "R%02d does not exist" % num))
            elif m.group(1):
                out.append(Finding("REPORT", path, i, "cite the number alone (R%02d): the kind prefix can change"
                                   % num))


def check_history_sync(path, hpath, rules, hist, text, out):
    by_num = {}
    for row in hist:
        if row["num"] is not None:
            by_num.setdefault(row["num"], []).append(row)
    nums = {r.num for r in rules}
    retired = set()
    for num, rows in by_num.items():
        if rows[-1]["event"] == "retired":
            retired.add(num)
    for r in rules:
        rows = by_num.get(r.num)
        if not rows:
            out.append(Finding("REPORT", path, r.line, "%s has no history row (add an 'added' row)" % r.id))
            continue
        if r.num in retired:
            out.append(Finding("BLOCK", path, r.line, "R%02d was retired and is used again" % r.num))
        if r.since_date and r.since_date != rows[-1]["date"]:
            out.append(Finding("REPORT", path, r.line, "%s: Since %s does not match its newest history row (%s)"
                               % (r.id, r.since_date, rows[-1]["date"])))
    for num, rows in by_num.items():
        if num not in nums and num not in retired:
            out.append(Finding("REPORT", hpath, rows[-1]["line"], "history names R%02d, which is not in the spec and "
                                                                  "was not retired" % num))
    prose = strip_fences(text)
    for row in hist:
        if row["event"] == "renamed":
            m = re.match(r'^"(.+?)"\s*→\s*"(.+?)"$', row["detail"])
            if not m:
                out.append(Finding("REPORT", hpath, row["line"], 'renamed detail must be "old" → "new"'))
                continue
            old = m.group(1)
            hits = [n for n, ln in enumerate(prose.split("\n"), 1)
                    if re.search(r"(?<!\w)%s(?!\w)" % re.escape(old), strip_code(ln))]
            for n in hits:
                out.append(Finding("REPORT", path, n, 'renamed term "%s" is still used' % old))
    return retired


def strip_fences(text):
    out, fence = [], False
    for ln in text.split("\n"):
        if re.match(r"^\s*(`{3,}|~{3,})", ln):
            fence = not fence
            out.append("")
            continue
        out.append("" if fence else ln)
    return "\n".join(out)


def check_index(path, text, rules, out):
    want = index_rows(rules)
    have = read_index(text)
    if have is None:
        out.append(Finding("REPORT", path, None, "no '## Rule index' section (speccheck --write-index adds it)"))
    elif have != want:
        out.append(Finding("REPORT", path, None, "the rule index is out of date (speccheck --write-index)"))


def index_rows(rules):
    return [(r.id, r.name, (r.section or "").strip()) for r in rules]


def read_index(text):
    lines = text.split("\n")
    try:
        start = next(i for i, ln in enumerate(lines) if re.match(r"^##\s+Rule index\s*$", ln))
    except StopIteration:
        return None
    rows = []
    for ln in lines[start + 1:]:
        if ln.startswith("#"):
            break
        if ln.strip().startswith("|") and not re.match(r"^\|?\s*:?-{3,}", ln.strip()):
            cells = [c.strip() for c in split_cells(ln)]
            if cells and cells[0] != "ID":
                rows.append(tuple(cells[:3]))
    return rows


def write_index(path, text, rules):
    lines = text.split("\n")
    table = ["| ID | Name | Section |", "|---|---|---|"] + ["| %s | %s | %s |" % row for row in index_rows(rules)]
    idx = next((i for i, ln in enumerate(lines) if re.match(r"^##\s+Rule index\s*$", ln)), None)
    if idx is None:
        first = next((i for i, ln in enumerate(lines) if ln.startswith("## ")), len(lines))
        new = lines[:first] + ["## Rule index", ""] + table + [""] + lines[first:]
    else:
        new = replace_section(lines, idx, table)
    acc = next((i for i, ln in enumerate(new) if ACCEPTANCE_LIST.match(ln)), None)
    if acc is not None:
        new = replace_section(new, acc, acceptance_rows(rules))
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(new))


def replace_section(lines, idx, body):
    """Replace what sits under the heading at lines[idx], up to the next heading, with body."""
    end = next((j for j in range(idx + 1, len(lines)) if lines[j].startswith("#")), len(lines))
    return lines[:idx + 1] + [""] + body + [""] + lines[end:]


ACCEPTANCE_LIST = re.compile(r"^##\s+Acceptance list\s*$")


def acceptance_rows(rules):
    """One bullet per rule whose Acceptance says how it is checked (not 'none:'), in spec order."""
    rows = []
    for r in rules:
        acc = field_text(r, "Acceptance")
        if acc and not re.match(r"(?i)^none:", acc):
            rows.append("- R%02d · %s: %s" % (r.num, r.name, acc))
    return rows


def read_acceptance_list(text):
    lines = text.split("\n")
    start = next((i for i, ln in enumerate(lines) if ACCEPTANCE_LIST.match(ln)), None)
    if start is None:
        return None
    rows = []
    for ln in lines[start + 1:]:
        if ln.startswith("#"):
            break
        if ln.startswith("- "):
            rows.append(ln.rstrip())
    return rows


def check_acceptance_list(path, text, rules, out):
    have = read_acceptance_list(text)
    if have is not None and have != acceptance_rows(rules):
        out.append(Finding("REPORT", path, None, "the acceptance list is out of date (speccheck --write-index)"))


def open_questions(rules):
    return ["R%02d" % r.num for r in rules if r.kind == "Q"]


def git(cwd, *args):
    return subprocess.run(["git"] + list(args), cwd=cwd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace")


def check_base(path, rules, base, retired, out):
    d = os.path.dirname(os.path.abspath(path))
    top = git(d, "rev-parse", "--show-toplevel")
    if top.returncode:
        out.append(Finding("REPORT", path, None, "--base needs a git repository"))
        return
    top = top.stdout.strip()
    rel = os.path.relpath(os.path.abspath(path), top).replace("\\", "/")
    old = git(top, "show", "%s:%s" % (base, rel))
    old_rules = parse_spec(old.stdout)[0] if old.returncode == 0 else []
    old_by = {r.num: r for r in old_rules}
    new_by = {r.num: r for r in rules}
    changed = set()
    for num in set(old_by) | set(new_by):
        o, n = old_by.get(num), new_by.get(num)
        if o and n and o.text() == n.text() and o.kind == n.kind:
            continue
        changed.add(num)
        if o and (o.kind == "O" or o.by_owner):
            what = "removed" if not n else "changed"
            whose = "owner rule" if o.kind == "O" else "rule the owner decided,"
            out.append(Finding("ASK", path, n.line if n else None, "%s R%02d %s since %s: confirm with the owner"
                               % (whose, num, what, base)))
        elif n and n.kind == "O":
            out.append(Finding("ASK", path, n.line, "R%02d is %s an owner rule since %s: confirm with the owner"
                               % (num, "now" if o else "added as", base)))
    if not changed:
        return
    diff = git(top, "diff", "--name-only", base)
    untracked = git(top, "ls-files", "--others", "--exclude-standard")
    touched = set((diff.stdout + "\n" + untracked.stdout).split())
    for f, nums in citations(top, rel).items():
        for num in sorted(nums & changed):
            if f not in touched:
                out.append(Finding("BLOCK", path, new_by[num].line if num in new_by else None,
                                   "R%02d changed but %s, which cites it, did not" % (num, f)))


def citations(top, spec_rel):
    files = git(top, "ls-files").stdout.split() + git(top, "ls-files", "--others", "--exclude-standard").stdout.split()
    hist_rel = spec_rel[:-3] + "-history.md" if spec_rel.endswith(".md") else None
    found = {}
    for f in files:
        if f in (spec_rel, hist_rel):
            continue
        p = os.path.join(top, f)
        try:
            if os.path.getsize(p) > 2_000_000:
                continue
            with open(p, encoding="utf-8", errors="ignore") as fh:
                nums = {int(m.group(1)) for m in CITE.finditer(fh.read())}
        except OSError:
            continue
        if nums:
            found[f] = nums
    return found


def check_retired_citations(path, retired, out):
    if not retired:
        return
    d = os.path.dirname(os.path.abspath(path))
    top = git(d, "rev-parse", "--show-toplevel")
    if top.returncode:
        return
    top = top.stdout.strip()
    rel = os.path.relpath(os.path.abspath(path), top).replace("\\", "/")
    for f, nums in citations(top, rel).items():
        for num in sorted(nums & retired):
            out.append(Finding("BLOCK", path, None, "%s cites R%02d, which is retired" % (f, num)))


def run(path, base=None, do_write_index=False):
    out = []
    text = open(path, encoding="utf-8").read()
    rules = parse_spec(text)[0]
    if do_write_index:
        write_index(path, text, rules)
        text = open(path, encoding="utf-8").read()
    rules, tables, _lines, prose = parse_spec(text)
    hpath = history_path(path)
    hist = parse_history(hpath, out)
    check_formatting(path, prose, rules, out)
    check_tables(path, tables, out)
    check_rules(path, rules, out)
    retired = check_history_sync(path, hpath, rules, hist, text, out)
    check_refs(path, prose, rules, retired, out)
    check_index(path, text, rules, out)
    check_acceptance_list(path, text, rules, out)
    check_retired_citations(path, retired, out)
    if base:
        check_base(path, rules, base, retired, out)
    out.sort(key=lambda f: (-SEVERITY[f.level], f.path, f.line or 0))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("spec")
    ap.add_argument("--base", help="git revision to compare with (owner-rule changes, cited rules)")
    ap.add_argument("--write-index", action="store_true", help="rebuild the rule index before checking")
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    found = run(a.spec, a.base, a.write_index)
    for f in found:
        print(f)
    worst = max((SEVERITY[f.level] for f in found), default=0)
    counts = {k: sum(1 for f in found if f.level == k) for k in SEVERITY}
    opened = open_questions(parse_spec(open(a.spec, encoding="utf-8").read())[0])
    if opened:
        print("open questions: %d (%s)" % (len(opened), ", ".join(opened)))
    print("speccheck: %d BLOCK, %d ASK, %d REPORT" % (counts["BLOCK"], counts["ASK"], counts["REPORT"]))
    return worst


if __name__ == "__main__":
    sys.exit(main())
