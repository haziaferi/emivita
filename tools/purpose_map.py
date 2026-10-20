#!/usr/bin/env python3
"""The purpose map: Emivita's screens by what they are for (docs/SPEC.md §7 R86).

    python tools/purpose_map.py           writes docs/purpose-map.md; exit 1 if a check fails
    python tools/purpose_map.py --check   checks only, and fails if the page is stale

The same format as Factotum's docs/screens/purpose-map: edit docs/purpose-map.json,
never the page. The checks keep the map tied to the code and to SPEC:

  * every screen the code can show has a place: each EmivitaRoute, each
    SettingsDestination, each activity and widget in the manifest, each
    notification channel, and each top-level *Dialog / *Sheet / *Card composable
    (a card that is only part of a screen is listed under "parts");
  * every place names something that still exists, so a rename fails here;
  * every screen names a known purpose, every overlap a known screen;
  * every SPEC reference resolves: "§9 #N" to a roadmap item, "Rn" to a ledger row.
"""

import glob
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SRC = os.path.join(ROOT, "docs", "purpose-map.json")
OUT = os.path.join(ROOT, "docs", "purpose-map.md")
SPEC = os.path.join(ROOT, "docs", "SPEC.md")
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "com", "hazia", "emivita")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")

# The activity that hosts every route; it is not a screen of its own.
HOST = {"activity:MainActivity"}


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def census():
    """Every screen-like thing the code can show, as kind:name tokens."""
    found = set()
    route = read(os.path.join(JAVA, "navigation", "EmivitaRoute.kt"))
    found |= {"route:" + n for n in re.findall(r"data (?:object|class) (\w+)\b[^\n]*:\s*EmivitaRoute", route)}
    settings = read(os.path.join(JAVA, "feature", "settings", "SettingsScreen.kt"))
    body = re.search(r"enum class SettingsDestination\s*\{([^}]*)\}", settings).group(1)
    found |= {"setting:" + n for n in re.findall(r"\b([A-Z]\w*)\b", body)}
    manifest = read(MANIFEST)
    found |= {"activity:" + n for n in re.findall(r'android:name="\.[\w.]*?\.?(\w+Activity)"', manifest)}
    found |= {"widget:" + n for n in re.findall(r'android:name="\.[\w.]*?\.?(\w+WidgetReceiver)"', manifest)}
    channels = read(os.path.join(JAVA, "core", "notification", "ReminderNotificationChannels.kt"))
    found |= {"channel:" + v for v in re.findall(r'\bconst val CHANNEL_\w+\s*=\s*"([^"]+)"', channels)}
    for path in glob.glob(os.path.join(JAVA, "**", "*.kt"), recursive=True):
        for name, kind in re.findall(r"^(?:internal |private )?fun ([A-Z]\w*(Dialog|Sheet|Card))\(", read(path), re.M):
            found.add(kind.lower() + ":" + name)
    return found - HOST


def spec_refs():
    spec = read(SPEC)
    s9 = spec[spec.index("\n## 9."):spec.index("\n## 10.")]
    items = {"§9 #" + n for n in re.findall(r"^ ?(\d+)\. \*\*", s9, re.M)}
    rows = set(re.findall(r"^\| (R\d+) \|", spec, re.M))
    return items | rows


def checks(m):
    bad = []
    ids = {s["id"] for s in m["screens"]}
    purposes = {p["id"] for p in m["purposes"] + m.get("not_purposes", [])}
    placed = {c for s in m["screens"] for c in s["code"]}
    parts = {"card:" + p for p in m["parts"]}
    code = census()
    refs = spec_refs()
    for c in sorted(code - placed - parts):
        bad.append("in the code, not on the map: %s" % c)
    for c in sorted((placed | parts) - code):
        bad.append("on the map, not in the code: %s" % c)
    for s in m["screens"]:
        if s["purpose"] not in purposes:
            bad.append("%s: unknown purpose %s" % (s["id"], s["purpose"]))
        for o in s["overlaps"]:
            if o not in ids:
                bad.append("%s: overlaps an unknown screen %s" % (s["id"], o))
        for r in s["spec"]:
            if r not in refs:
                bad.append("%s: SPEC has no %s" % (s["id"], r))
    for g in m["overlap_groups"]:
        for o in g["screens"]:
            if o not in ids:
                bad.append("%s: unknown screen %s" % (g["id"], o))
    return bad


def render(m):
    groups = m["purposes"] + m.get("not_purposes", [])
    by = {p["id"]: [] for p in groups}
    for s in m["screens"]:
        by[s["purpose"]].append(s)
    name = {s["id"]: s["name"] for s in m["screens"]}
    out = ["# Purpose map", "", m["about"], "",
           "Generated from `purpose-map.json` by `tools/purpose_map.py`; edit the JSON, not this page.", "",
           "## Purposes", "", "| | Purpose | Its job | Screens |", "|---|---|---|---|"]
    out += ["| %s | **%s** | %s | %d |" % (p["id"], p["name"], p["job"], len(by[p["id"]])) for p in m["purposes"]]
    if m.get("not_purposes"):
        out += ["", "Screens that serve no purpose of their own (the owner, 2026-10-20): " +
                "; ".join("**%s** (%s)" % (p["name"], p["job"]) for p in m["not_purposes"]) + "."]
    for p in groups:
        out += ["", "## %s %s" % (p["id"], p["name"]), "", "_%s_" % p["job"], "",
                "| Screen | In the code | SPEC | Status | Overlaps |", "|---|---|---|---|---|"]
        for s in by[p["id"]]:
            out.append("| %s | %s | %s | %s | %s |" % (
                s["name"], ", ".join("`%s`" % c for c in s["code"]), ", ".join(s["spec"]), s["status"],
                ", ".join(name.get(o, o) for o in s["overlaps"])))
    out += ["", "Parts of a screen, not screens: " + ", ".join("`%s`" % p for p in m["parts"]) + ".", "",
            "## Screens whose purposes overlap", "", "| | Strength | Screens | Why |", "|---|---|---|---|"]
    out += ["| %s | %s | %s | %s |" % (g["id"], g["strength"], ", ".join(name.get(o, o) for o in g["screens"]), g["why"])
            for g in m["overlap_groups"]]
    out += ["", "## The owner's answers", ""] + ["- %s" % a for a in m["owner_answers"]]
    open_ = sum(1 for s in m["screens"] if "open" in s["status"])
    out += ["", "_%d screens in %d purposes; %d with an open item._" % (len(m["screens"]), len(m["purposes"]), open_), ""]
    return "\n".join(out)


def main():
    m = json.loads(read(SRC))
    bad = checks(m)
    page = render(m)
    if "--check" in sys.argv:
        if not os.path.exists(OUT) or read(OUT) != page:
            bad.append("docs/purpose-map.md is stale: run tools/purpose_map.py")
    else:
        with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(page)
        print(OUT)
    for b in bad:
        print("FAIL", b)
    if not bad:
        print("PASS  %d screens placed, every SPEC reference resolves" % len(m["screens"]))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
