# -*- coding: utf-8 -*-
"""The four palettes, derived from their seeds (docs/SPEC.md §6, Appendix A; §7 R76).

Each palette is three agreed colours per register -- ground, text, primary --
taken from a named visual-craft lexicon pair the owner chose, and everything
else is derived from those three by the same rules for all four, so the
palettes differ only where their seeds do. This file is the one place the
values are typed; `docs/SPEC.md` Appendix A and `ui/theme/Color.kt`'s palette
objects are both printed from it, and `--check` fails when Appendix A's tables
have drifted from what it prints (PaletteSpecConsistencyTest pins the Kotlin
side to the same tables, closing the loop).

  python tools/palette_derive.py            the contrast table for every register
  python tools/palette_derive.py --tables   Appendix A's four sections, as markdown
  python tools/palette_derive.py --kotlin   Color.kt's four palette objects
  python tools/palette_derive.py --check    Appendix A == --tables, exit 1 otherwise
"""
import colorsys, io, re, sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parent.parent

# ---- colour arithmetic (sRGB, WCAG 2.x relative luminance) ------------------
def h2r(h): h = h.lstrip("#"); return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
def r2h(r): return "#%02X%02X%02X" % tuple(max(0, min(255, round(c * 255))) for c in r)
def lum(h):
    def ch(c): return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = h2r(h); return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
def cr_exact(a, b):
    la, lb = lum(a), lum(b); hi, lo = max(la, lb), min(la, lb); return (hi + 0.05) / (lo + 0.05)
def cr(a, b): return round(cr_exact(a, b), 2)
def mix(a, b, t): ra, rb = h2r(a), h2r(b); return r2h(tuple(ra[i] * (1 - t) + rb[i] * t for i in range(3)))
def hsl(h): r, g, b = h2r(h); H, L, S = colorsys.rgb_to_hls(r, g, b); return H, S, L
def setL(h, L): H, S, _ = hsl(h); return r2h(colorsys.hls_to_rgb(H, L, S))
def fitL(fg, bg, target):
    """Move fg's lightness away from bg, keeping hue and saturation, until the pair reaches target."""
    # Unrounded, and a hair above the floor: the Kotlin test compares exact
    # ratios, and a value that rounds to 3.00 from 2.996 would fail there.
    H, S, L = hsl(fg); bg_is_light = lum(bg) > 0.3
    for _ in range(200):
        if cr_exact(fg, bg) >= target + 0.02: return fg
        L += -0.005 if bg_is_light else 0.005; fg = setL(fg, L)
    return fg

# ---- the seeds: (ground, text, primary) per register ------------------------
SEEDS = [
    # enum name, display name, source register, light seed, dark seed
    ("PORCELLANA", "Porcellana", "Ming blue-and-white (visual-craft lexicon, world traditions)",
     ("#F5F6F8", "#143667", "#17488C"), ("#0F141C", "#E4E8EF", "#8FB0E0")),
    ("ARDESIA", "Ardesia", "Slate Scholastic (lexicon, contexts)",
     ("#EEF4F6", "#19282E", "#2D6276"), ("#152328", "#F3F5F6", "#7FB2C6")),
    ("SANGUE_DI_BUE", "Sangue di bue", "Tense · Held Breath (lexicon, moods)",
     ("#E9E7E4", "#17181A", "#6E241F"), ("#0E0F11", "#D8D6D2", "#D9695C")),
    ("SABBIA", "Sabbia", "Serene · Chalk (lexicon, moods)",
     ("#F5F3EF", "#35322D", "#75674F"), ("#1B1917", "#E4DFD6", "#C0AE8E")),
]
ATTENTION = {"light": "#B85C2E", "dark": "#E0925F"}   # burnt orange: the register rules out yellow
ERROR = {"light": "#9E2A25", "dark": "#F09590"}
ON_ERROR = {"light": "#FFFFFF", "dark": "#3A0B08"}

ROLES = ["background", "surface", "surfaceContainer", "surfaceContainerHigh", "onSurface", "onSurfaceVariant",
         "outline", "outlineVariant", "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
         "attention", "error", "onError", "errorContainer", "onErrorContainer"]

def derive(surf, text, prim, light):
    mode = "light" if light else "dark"
    t = {"background": surf, "surface": surf}
    t["surfaceContainer"] = mix(surf, text, 0.045 if light else 0.06)
    t["surfaceContainerHigh"] = mix(surf, text, 0.09 if light else 0.11)
    # Anything that can sit on a card is fitted against the darkest card tier,
    # not the ground: muted text, the primary, the attention and error glyphs.
    high = t["surfaceContainerHigh"]
    prim = fitL(prim, high, 4.5)
    t["onSurface"] = text
    t["onSurfaceVariant"] = fitL(mix(text, surf, 0.55), high, 4.5)
    t["outline"] = fitL(mix(text, surf, 0.62), surf, 3.0)
    t["outlineVariant"] = mix(surf, text, 0.18)
    t["primary"] = prim
    t["onPrimary"] = "#FFFFFF" if light else fitL(mix(prim, "#000000", 0.82), prim, 4.5)
    t["primaryContainer"] = mix(surf, prim, 0.26 if light else 0.42)
    t["onPrimaryContainer"] = fitL(mix(prim, text, 0.45 if light else 0.35), t["primaryContainer"], 7.0)
    t["attention"] = fitL(ATTENTION[mode], high, 4.5)
    t["error"] = fitL(ERROR[mode], high, 4.5)
    t["onError"] = ON_ERROR[mode]
    t["errorContainer"] = mix(surf, t["error"], 0.22 if light else 0.38)
    t["onErrorContainer"] = fitL(mix(t["error"], text, 0.5), t["errorContainer"], 7.0)
    return t

CHECKS = [  # (label, fg, bg, floor)
    ("text on ground", "onSurface", "surface", 4.5),
    ("text on the high card", "onSurface", "surfaceContainerHigh", 4.5),
    ("muted on ground", "onSurfaceVariant", "surface", 4.5),
    ("muted on the high card", "onSurfaceVariant", "surfaceContainerHigh", 4.5),
    ("outline on ground", "outline", "surface", 3.0),
    ("primary on ground", "primary", "surface", 4.5),
    ("onPrimary on primary", "onPrimary", "primary", 4.5),
    ("onPrimaryContainer on primaryContainer", "onPrimaryContainer", "primaryContainer", 4.5),
    ("primaryContainer against ground", "primaryContainer", "surface", 1.3),
    ("attention on ground", "attention", "surface", 4.5),
    ("attention on the high card", "attention", "surfaceContainerHigh", 4.5),
    ("error on ground", "error", "surface", 4.5),
    ("error on the high card", "error", "surfaceContainerHigh", 4.5),
    ("onError on error", "onError", "error", 4.5),
    ("onErrorContainer on errorContainer", "onErrorContainer", "errorContainer", 4.5),
]

def palettes():
    out = []
    for enum, name, source, L, D in SEEDS:
        light, dark = derive(*L, light=True), derive(*D, light=False)
        out.append({"enum": enum, "name": name, "source": source, "light": light, "dark": dark})
    return out

def checks_line(t):
    return " · ".join(f"{label} {cr(t[fg], t[bg])}" for label, fg, bg, _ in CHECKS)

def tables():
    lines = []
    for i, p in enumerate(palettes(), 1):
        lines.append(f"### {i} · {p['name']} — {p['source']}\n")
        lines.append("| Role | Light | Dark |")
        lines.append("|---|---|---|")
        for r in ROLES:
            lines.append(f"| {r} | `{p['light'][r]}` | `{p['dark'][r]}` |")
        lines.append("")
        lines.append(f"Checks — Light: {checks_line(p['light'])}. Dark: {checks_line(p['dark'])}.\n")
    return "\n".join(lines)

def kotlin():
    lines = []
    for p in palettes():
        obj = "".join(w.capitalize() for w in p["enum"].lower().split("_")) + "Palette"
        lines.append(f"// ---------- {p['name']} ----------")
        lines.append(f"// {p['source']}.")
        lines.append(f"object {obj} {{")
        for reg in ("light", "dark"):
            lines.append(f"    val {reg.capitalize()} = PaletteTokens(")
            for r in ROLES:
                lines.append(f"        {r} = Color(0xFF{p[reg][r][1:]}),")
            lines.append("    )")
        lines.append("}\n")
    return "\n".join(lines)

def check():
    spec = io.open(ROOT / "docs" / "SPEC.md", encoding="utf-8").read()
    a = spec.index("## Appendix A —"); b = spec.index("## Appendix B —")
    section = spec[a:b]
    want = tables()
    # Compare every table row and heading the generator prints, in order.
    keep = lambda l: re.match(r"^\| \w+ \| `#", l) or l.startswith("### ") or l.startswith("Checks — ")
    wanted = [l for l in want.splitlines() if keep(l)]
    have = [l for l in section.splitlines() if keep(l)]
    if wanted != have:
        for i, (w, h) in enumerate(zip(wanted, have)):
            if w != h:
                print(f"FAIL: first difference at line {i}\n  generated: {w}\n  appendix : {h}"); return 1
        print(f"FAIL: {len(wanted)} generated lines vs {len(have)} in Appendix A"); return 1
    print(f"PASS: Appendix A matches the generator ({len(wanted)} lines)"); return 0

def main(argv):
    if "--tables" in argv: print(tables()); return 0
    if "--kotlin" in argv: print(kotlin()); return 0
    if "--check" in argv: return check()
    worst_all = 99
    for p in palettes():
        for reg in ("light", "dark", "amoled"):
            t = dict(p["dark"], background="#000000", surface="#000000") if reg == "amoled" else p[reg]
            fails = [(label, cr(t[fg], t[bg]), floor) for label, fg, bg, floor in CHECKS if cr(t[fg], t[bg]) < floor]
            worst = min(cr(t[fg], t[bg]) / floor for _, fg, bg, floor in CHECKS)
            worst_all = min(worst_all, worst)
            print(f"{p['name']:14} {reg:6} " + " ".join(f"{cr(t[fg], t[bg]):>5}" for _, fg, bg, _ in CHECKS) + ("" if not fails else f"   BELOW FLOOR: {fails}"))
    print("columns: " + " | ".join(label for label, *_ in CHECKS))
    print(f"every pair at or above its floor: {'yes' if worst_all >= 1 else 'NO'}")
    return 0 if worst_all >= 1 else 1

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
