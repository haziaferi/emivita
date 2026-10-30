"""Screen axes borrowed from the owner's other projects (§7 R131), scored on the sweep's measurement dumps.

The inputs are what `ScreenSweepTest` writes with EMIVITA_MEASURE=1: per stop, `<screen>_w<dp>_fs<10x>.png` (the
first screen) and `.json` (every node it swept: kind merged|leaf, window-pixel bounds, the frame's scroll offset,
text, role, clickable, lines, cut). Each axis names its source as path:line and keeps the source's constants; where
Emivita's geometry needed a change it says which and why. Sources are the owner's projects under `<Builds>`.

    python tools/borrowed_axes.py app/build/measure/screens            # table to stdout
    python tools/borrowed_axes.py DIR --json out.json                  # per screen, per axis
    python tools/borrowed_axes.py --selftest                           # hand-computed cases

Hand-set inputs are in HAND, stated once: the facts a glance should find and the primary action per screen. They are
the owner's to correct; every score that reads them says so.
"""
import argparse
import glob
import json
import math
import os
import re
import statistics
import sys

# What each screen must show at a glance, and its one primary action (HAND-SET, owner to confirm).
HAND = {
    "glance_facts": {
        "today": [r"\b\d{1,2}:\d{2}\b", r"Metformina", r"^(Prendi|Presa|Take|Taken)\b"],
        "medicines": [r"Metformina", r"Paracetamolo", r"\b\d+\b.*compress"],
        "history_list": [r"\d+\s*%", r"Metformina"],
        "medicine_detail": [r"Metformina", r"\b\d+\b.*compress", r"\b\d{1,2}:\d{2}\b"],
    },
    "primary": {
        "today": r"Registra dose|Log dose",
        "medicines": r"Aggiungi farmaco|Add medicine",
        "medicine_detail": r"Aggiungi promemoria|Add reminder",
    },
    # The same action, named as the user reads it, wherever it appears (fl30-action-spread).
    "actions": {
        "back": r"^(Indietro|Back|Torna indietro|Navigate up)$",
        "save": r"^(Salva|Save)$",
        "more": r"^(Altre opzioni|More options)$",
    },
}


def screen_key(name):
    """'today_TIMELINE' -> 'today'; history views keep their own name."""
    return "today" if name.startswith("today_") else name


# ---- reading a dump --------------------------------------------------------------------------------------------


class Dump:
    def __init__(self, path):
        d = json.load(open(path, encoding="utf-8"))
        self.path = path
        self.screen = d["screen"]
        self.width_dp = d["width_dp"]
        self.font = d["font"]
        self.density = d["density"]
        self.nav_top = d["nav_top"]
        self.nodes = d["nodes"]

    def dp(self, px):
        return px / self.density

    def content(self, kind):
        """Each node once, in content pixels: a scrolled node's y plus its frame's offset; a fixed one from frame 0."""
        seen, out = set(), []
        for n in self.nodes:
            if n["kind"] != kind or n["id"] in seen:
                continue
            if not n["in_scroll"] and n["frame"] != 0:
                continue
            seen.add(n["id"])
            off = n["scroll"] if n["in_scroll"] else 0.0
            out.append(dict(n, ct=n["t"] + off, cb=n["b"] + off))
        return out

    def first_screen(self, kind):
        """Frame 0's nodes above the navigation bar."""
        lim = self.nav_top if self.nav_top is not None else 1e9
        return [n for n in self.nodes if n["kind"] == kind and n["frame"] == 0 and n["b"] <= lim + 0.5]

    def label(self, n):
        return n.get("text") or n.get("desc") or ""


# ---- the axes ----------------------------------------------------------------------------------------------------


def action_spread(dumps, pattern):
    """fl30-action-spread, Factotum tools/screens/toast_opt.py:698-746: spread = mean distance of one action's
    position from the centroid of its positions across screens; ways_in = distinct forms it takes. Positions in dp
    (the source is px at 360 wide, Emivita's renders are at 360 dp, so dp keeps the scale). Forms here are the
    node's role plus whether it is named by text or by description. Lower is better for both."""
    pts, forms = [], set()
    for d in dumps:
        for n in d.first_screen("merged"):
            if n["clickable"] and re.search(pattern, d.label(n)):
                pts.append((d.dp((n["l"] + n["r"]) / 2), d.dp((n["t"] + n["b"]) / 2), d.screen))
                forms.add((n.get("role"), "text" if n.get("text") else "desc"))
                break
    if len(pts) < 2:
        return None
    cx = sum(p[0] for p in pts) / len(pts)
    cy = sum(p[1] for p in pts) / len(pts)
    spread = sum(math.hypot(p[0] - cx, p[1] - cy) for p in pts) / len(pts)
    return {"spread_dp": round(spread, 1), "ways_in": len(forms), "screens": len(pts)}


def glance_facts(d):
    """fl30-glance-facts, Factotum tools/screens/t3_where2.py:305-310: glance = count of key facts shown with their
    bottom at or above the fold (the navigation bar's top). The facts are HAND-SET per screen. Higher is better."""
    facts = HAND["glance_facts"].get(screen_key(d.screen))
    if not facts:
        return None
    texts = [d.label(n) for n in d.first_screen("leaf")]
    shown = sum(1 for f in facts if any(re.search(f, t) for t in texts))
    return {"glance": shown, "of": len(facts)}


def seen_at_rest(d):
    """fl30-seen-at-rest, Factotum tools/screens/actions_opt.py:130-133,236-240: is the screen's primary action
    visible at rest (first screen, no scroll, no menu)? 1 or 0, the action HAND-SET. Higher is better."""
    p = HAND["primary"].get(screen_key(d.screen))
    if not p:
        return None
    return int(any(n["clickable"] and re.search(p, d.label(n)) for n in d.first_screen("merged")))


def scan_rows(d):
    """fl30-scan-rows, Factotum tools/screens/t3_opt.py:1091-1101: the rows read, on average, to find a target: the
    headers read up to its own plus its place under it, (g + 1 + within + 1). Targets are the clickable rows of the
    list (content order); a header is a non-clickable text leaf in capitals or with a heading role, Emivita's
    section labels. Lower is better."""
    rows = [n for n in d.content("merged") if n["clickable"] and n["in_scroll"] and d.dp(n["r"] - n["l"]) >= 0.6 * d.width_dp]
    if not rows:
        return None
    heads = [n for n in d.content("leaf") if not n["clickable"] and n["in_scroll"] and n.get("text")
             and (n.get("role") == "Heading" or (n["text"].upper() == n["text"] and re.search(r"[A-Z]", n["text"])))]
    heads = [h for h in heads if not any(r["ct"] - 0.5 <= h["ct"] and h["cb"] <= r["cb"] + 0.5 for r in rows)]
    steps = []
    for r in rows:
        above = [h for h in heads if h["cb"] <= r["ct"] + 0.5]
        g = len(above)
        start = max((h["cb"] for h in above), default=-1e9)
        within = sum(1 for o in rows if start <= o["ct"] < r["ct"])
        steps.append((g if heads else 0) + 1 + within + (1 if heads else 0))
    return {"scan": round(sum(steps) / len(steps), 3), "rows": len(rows), "headers": len(heads)}


def rhythm(d):
    """fac-rhythm, Factotum tools/screens/t3_graphs.py:1163,1295-1297: the vertical gaps between the page's blocks,
    rounded; rhythm = distinct gaps + gaps not a multiple of 4 dp; and edges = distinct left and right edges of the
    blocks (2 dp bins, t3_graphs.py:1297). Blocks here are the scroll container's clickable or text-bearing merged
    nodes that are not inside another kept block (the source took the page's children). Lower is better."""
    cand = [n for n in d.content("merged") if n["in_scroll"] and (n["clickable"] or n.get("text"))]
    cand.sort(key=lambda n: (n["ct"], -(n["r"] - n["l"])))
    blocks = []
    for n in cand:
        if any(b["l"] - 0.5 <= n["l"] and n["r"] <= b["r"] + 0.5 and b["ct"] - 0.5 <= n["ct"] and n["cb"] <= b["cb"] + 0.5 for b in blocks):
            continue
        blocks.append(n)
    if len(blocks) < 2:
        return None
    gaps = [round(d.dp(blocks[i]["ct"] - blocks[i - 1]["cb"])) for i in range(1, len(blocks))]
    gaps = [g for g in gaps if g >= 0]
    off4 = sum(1 for g in gaps if g % 4)
    edges = len({round(d.dp(b["l"]) / 2) * 2 for b in blocks} | {round(d.dp(b["r"]) / 2) * 2 + 10000 for b in blocks})
    return {"rhythm": len(set(gaps)) + off4, "distinct_gaps": len(set(gaps)), "off_grid": off4, "edges": edges}


def column_stray(d):
    """fl30-column-stray, Factotum tools/screens/label_opt.py:627-628,687-697: how far one mark strays from one x
    across rows, max(x) - min(x). The mark here is each row's first text, left edge, over the list's full-width
    clickable rows. In dp. Lower is better."""
    rows = [n for n in d.content("merged") if n["clickable"] and n["in_scroll"] and d.dp(n["r"] - n["l"]) >= 0.6 * d.width_dp]
    leaves = d.content("leaf")
    xs = []
    for r in rows:
        inside = [l for l in leaves if r["l"] - 0.5 <= l["l"] and l["r"] <= r["r"] + 0.5 and r["ct"] - 0.5 <= l["ct"] and l["cb"] <= r["cb"] + 0.5]
        if inside:
            xs.append(d.dp(min(inside, key=lambda l: (l["ct"], l["l"]))["l"]))
    if len(xs) < 2:
        return None
    return {"stray_dp": round(max(xs) - min(xs), 1), "rows": len(xs)}


# ---- salience (needs the PNG) -----------------------------------------------------------------------------------


def _lum(rgb):
    def c(v):
        v /= 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = rgb[:3]
    return 0.2126 * c(r) + 0.7152 * c(g) + 0.0722 * c(b)


def contrast_ratio(a, b):
    la, lb = sorted((_lum(a), _lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def salience_of(area, ratio):
    """craftkit engine/craftkit/compose.py:126-147: area x (0.15 + 0.85 x (ratio - 1) / 20)."""
    return area * (0.15 + 0.85 * (ratio - 1.0) / 20.0)


def _box_colour(img, box):
    """The box's commonest colour, quantised to 8 levels a channel: its fill, as craftkit reads a shape's fill."""
    l, t, r, b = (int(round(v)) for v in box)
    l, t = max(0, l), max(0, t)
    r, b = min(img.width, r), min(img.height, b)
    if r - l < 2 or b - t < 2:
        return None
    crop = img.crop((l, t, r, b)).convert("RGB").resize((max(1, (r - l) // 4), max(1, (b - t) // 4)))
    counts = {}
    pixels = crop.get_flattened_data() if hasattr(crop, "get_flattened_data") else crop.getdata()
    for p in pixels:
        k = tuple(v // 32 for v in p)
        counts[k] = counts.get(k, (0, p))[0] + 1, p
    return max(counts.values())[1]


def signature(d, png):
    """SK30-V2, creative-design-suite skills/visual-craft/SKILL.md:55,84 (signature, restraint), salience from
    engine/craftkit/compose.py:126: each first-screen node's salience against the backdrop (the frame's commonest
    colour, as compose.py:150 takes the largest covering fill); signature_clear when the top one is at least 1.5x the
    second (the port's constant, SK30-V2's proposal). Also reports what the top element is, and, for the hierarchy
    concept, the rank of the screen's HAND-SET primary action among the clickables. Descriptive: one clear outlier =
    a signature; many equal = none."""
    try:
        from PIL import Image
    except ImportError:
        # Said once, not swallowed: a missing axis must not look like a screen with nothing to report (§7.1 rule 17).
        if not getattr(signature, "warned", False):
            print("borrowed_axes: signature skipped, Pillow is not importable (python -I hides user site-packages)", file=sys.stderr)
            signature.warned = True
        return None
    img = Image.open(png).convert("RGB")
    back = _box_colour(img, (0, 0, img.width, (d.nav_top or img.height)))
    items = []
    for n in d.first_screen("merged"):
        if n["r"] - n["l"] >= img.width - 2:   # the window's own containers
            continue
        if not d.label(n):   # an unnamed layout box, not something read: a Compose node is not craftkit's painted shape
            continue
        fill = _box_colour(img, (n["l"], n["t"], n["r"], n["b"]))
        if fill is None:
            continue
        area = d.dp(n["r"] - n["l"]) * d.dp(n["b"] - n["t"])
        items.append((salience_of(area, contrast_ratio(fill, back)), d.label(n)[:40], n["clickable"]))
    if len(items) < 2:
        return None
    items.sort(key=lambda x: -x[0])
    ratio = items[0][0] / max(1e-9, items[1][0])
    out = {"top": items[0][1], "top_vs_second": round(ratio, 2), "signature_clear": ratio >= 1.5}
    p = HAND["primary"].get(screen_key(d.screen))
    if p:
        clicks = [x for x in items if x[2]]
        rank = next((i + 1 for i, x in enumerate(clicks) if re.search(p, x[1])), None)
        out["primary_rank"] = rank
    return out


# ---- running -----------------------------------------------------------------------------------------------------


def score_dir(path):
    dumps = [Dump(p) for p in sorted(glob.glob(os.path.join(path, "*.json")))]
    per = {}
    for d in dumps:
        png = d.path[:-5] + ".png"
        key = os.path.basename(d.path)[:-5]
        per[key] = {
            "glance_facts": glance_facts(d), "seen_at_rest": seen_at_rest(d), "scan_rows": scan_rows(d),
            "rhythm": rhythm(d), "column_stray": column_stray(d),
            "signature": signature(d, png) if os.path.exists(png) else None,
        }
    across = {}
    groups = {}
    for d in dumps:
        groups.setdefault((d.width_dp, d.font), []).append(d)
    for (w, f), ds in sorted(groups.items()):
        for name, pat in HAND["actions"].items():
            r = action_spread(ds, pat)
            if r:
                across["w%d_fs%d %s" % (w, round(f * 10), name)] = r
    return per, across


def selftest():
    """Hand-computed cases for each axis."""
    def node(kind, i, l, t, r, b, text=None, desc=None, clickable=False, scroll=0.0, frame=0, in_scroll=True, role=None):
        return {"kind": kind, "id": i, "frame": frame, "scroll": scroll, "in_scroll": in_scroll, "text": text, "desc": desc,
                "role": role, "clickable": clickable, "editable": False, "lines": 1, "cut": False, "l": l, "t": t, "r": r, "b": b}

    def dump(screen, nodes, nav_top=700.0):
        d = Dump.__new__(Dump)
        d.path, d.screen, d.width_dp, d.font, d.density, d.nav_top, d.nodes = "x.json", screen, 360, 1.0, 1.0, nav_top, nodes
        return d

    fails = []
    # rhythm: blocks at 0-50, 58-100, 108-150, 163-200 -> gaps 8, 8, 13 -> distinct 2 + off-grid 1 = 3
    d = dump("settings", [node("merged", 1, 0, 0, 360, 50, "a", clickable=True), node("merged", 2, 0, 58, 360, 100, "b", clickable=True),
                          node("merged", 3, 0, 108, 360, 150, "c", clickable=True), node("merged", 4, 0, 163, 360, 200, "d", clickable=True)])
    r = rhythm(d)
    if r["rhythm"] != 3 or r["distinct_gaps"] != 2 or r["off_grid"] != 1:
        fails.append("rhythm %s" % r)
    # column stray: first texts at x 16, 16, 24 -> 8
    nodes = []
    for i, (y, x) in enumerate([(0, 16), (60, 16), (120, 24)]):
        nodes += [node("merged", 10 + i, 0, y, 360, y + 50, clickable=True), node("leaf", 20 + i, x, y + 10, x + 100, y + 30, "t%d" % i)]
    c = column_stray(dump("medicines", nodes))
    if c["stray_dp"] != 8.0:
        fails.append("column_stray %s" % c)
    # scan rows: header H, rows r1 r2 under it -> steps 1+1+0+1=3 and 1+1+1+1=4 -> 3.5
    s = scan_rows(dump("settings", [node("leaf", 1, 16, 0, 200, 20, "SEZIONE"), node("merged", 2, 0, 30, 360, 80, "r1", clickable=True),
                                    node("merged", 3, 0, 90, 360, 140, "r2", clickable=True)]))
    if s["scan"] != 3.5:
        fails.append("scan_rows %s" % s)
    # scan rows, flat list of 3 -> (1+2+3)/3 = 2, the source's flat case (t3_opt.py:1094)
    s = scan_rows(dump("medicines", [node("merged", i, 0, 60 * i, 360, 60 * i + 50, "r%d" % i, clickable=True) for i in range(3)]))
    if s["scan"] != 2.0:
        fails.append("scan_rows flat %s" % s)
    # action spread: back at (24,24) and (24,72) -> centroid (24,48), mean distance 24
    a = action_spread([dump("a", [node("merged", 1, 0, 0, 48, 48, desc="Indietro", clickable=True, in_scroll=False)]),
                       dump("b", [node("merged", 1, 0, 48, 48, 96, desc="Indietro", clickable=True, in_scroll=False)])], HAND["actions"]["back"])
    if a["spread_dp"] != 24.0 or a["ways_in"] != 1:
        fails.append("action_spread %s" % a)
    # glance: today with a time and a name above the fold, the take button below it -> 2 of 3
    g = glance_facts(dump("today_TIMELINE", [node("leaf", 1, 0, 0, 100, 20, "08:00"), node("leaf", 2, 0, 30, 100, 50, "Metformina"),
                                             node("leaf", 3, 0, 800, 100, 820, "Prendi")]))
    if g != {"glance": 2, "of": 3}:
        fails.append("glance %s" % g)
    # salience: craftkit's mapping at ratio 1 -> 0.15 x area, at 21 -> 1.0 x area
    if abs(salience_of(100, 1.0) - 15.0) > 1e-9 or abs(salience_of(100, 21.0) - 100.0) > 1e-9:
        fails.append("salience mapping")
    if abs(contrast_ratio((0, 0, 0), (255, 255, 255)) - 21.0) > 1e-6:
        fails.append("contrast ratio")
    for f in fails:
        print("FAIL", f)
    print("selftest:", "PASS" if not fails else "%d failed" % len(fails))
    return 0 if not fails else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dir", nargs="?")
    ap.add_argument("--json")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if not a.dir:
        ap.error("a directory of dumps, or --selftest")
    per, across = score_dir(a.dir)
    if a.json:
        with open(a.json, "w", encoding="utf-8", newline="") as f:
            json.dump({"per_screen": per, "across": across}, f, indent=1, ensure_ascii=False)
    for k, v in per.items():
        bits = []
        for ax, r in v.items():
            if r is None:
                continue
            bits.append("%s=%s" % (ax, ",".join("%s:%s" % kv for kv in r.items()) if isinstance(r, dict) else r))
        print("%-34s %s" % (k, "  ".join(bits)))
    for k, v in across.items():
        print("%-34s action_spread=%s" % (k, ",".join("%s:%s" % kv for kv in v.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
