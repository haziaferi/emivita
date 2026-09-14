#!/usr/bin/env python3
"""Reads the release DEX and asserts that every GlanceAppWidget subclass survived R8 by name.

    python tools/r8_check.py [path/to/app-release.apk]

Why this exists (docs/SPEC.md §7 R69). Glance maps a placed widget to its provider by the
provider's class name: `GlanceAppWidgetManager` records receiver -> `glanceAppWidget.javaClass.name`,
and `updateAll()` looks placed instances up by that name. Two widgets that share a runtime class
share an identity. With `proguard-android-optimize.txt` and no keep rule, R8 horizontally merges
`NextRemindersWidget` and `StockLevelsWidget` into one class with an int class-id constructor
(`new-instance Lq9/d;` / `<init>:(I)V` in both receivers, verified 2026-09-14), after which
updating one widget redraws every widget on the launcher. `app/proguard-rules.pro` keeps
`* extends androidx.glance.appwidget.GlanceAppWidget`, and that rule is the only thing in the way.

Why the DEX and not the keep rule. A dropped, mistyped or shadowed keep does not fail the build;
it ships a merged widget. So this checks the *outcome* (each descriptor is in the DEX) rather than
the *mechanism* (a rule that happens to match today). `mapping.txt` cannot answer the question:
R8 omits identity mappings, so a kept name is simply absent from it.

What must survive is read from the sources, not repeated here: every `class X : GlanceAppWidget()`
under app/src/main/java. A widget added elsewhere is found; a list copied into this file would not
be updated. Modelled on Chronicle's tools/r8_check.py, which caught the same merge the same day.

Needs `dexdump` from an SDK build-tools directory (ANDROID_HOME / ANDROID_SDK_ROOT, or the
default %LOCALAPPDATA%\\Android\\Sdk on Windows).
"""

import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
DEFAULT_APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
APP_SRC = os.path.join(ROOT, "app", "src", "main", "java")

PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.M)
WIDGET_RE = re.compile(r"^class (\w+)\s*:\s*GlanceAppWidget\s*\(", re.M)


def widgets_from_source():
    out = []
    for path in glob.glob(os.path.join(APP_SRC, "**", "*.kt"), recursive=True):
        text = open(path, encoding="utf-8").read()
        text = re.sub(r"//[^\n]*", "", text)
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        names = WIDGET_RE.findall(text)
        if not names:
            continue
        pkg = PACKAGE_RE.search(text).group(1)
        out.extend("%s.%s" % (pkg, name) for name in names)
    return out


def find_dexdump():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(var)
        if sdk:
            break
    else:
        sdk = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    hits = glob.glob(os.path.join(sdk, "build-tools", "*", "dexdump*"))
    hits = [h for h in hits if os.path.isfile(h) and not h.endswith(".pdb")]
    if not hits:
        sys.exit("no dexdump under %s/build-tools; set ANDROID_HOME" % sdk)
    return sorted(hits, key=lambda h: [int(x) for x in re.findall(r"\d+", os.path.basename(os.path.dirname(h)))])[-1]


def descriptor(fqn):
    return "L%s;" % fqn.replace(".", "/")


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
    if not os.path.isfile(apk):
        sys.exit("no release APK at %s - run :app:assembleRelease first" % apk)
    widgets = widgets_from_source()
    if not widgets:
        sys.exit("found no GlanceAppWidget subclasses under %s; the source scan is broken" % APP_SRC)

    dexdump = find_dexdump()
    with tempfile.TemporaryDirectory() as work:
        with zipfile.ZipFile(apk) as z:
            dexes = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            for n in dexes:
                z.extract(n, work)
        dump = ""
        for n in dexes:
            dump += subprocess.run([dexdump, os.path.join(work, n)], capture_output=True, text=True,
                                   errors="replace").stdout

    failures = []
    for fqn in sorted(widgets):
        if "Class descriptor  : '%s'" % descriptor(fqn) in dump:
            print("  ok  %s" % fqn)
        else:
            failures.append("R8 dropped, renamed or merged widget %s" % fqn)

    if failures:
        print("\nFAIL")
        for f in failures:
            print("  " + f)
        print("  the -keep for GlanceAppWidget in app/proguard-rules.pro is missing or no longer matches")
        return 1
    print("\nPASS  %d widgets survived R8 in %s" % (len(widgets), os.path.relpath(apk, ROOT)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
