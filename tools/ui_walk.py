#!/usr/bin/env python3
"""Walk the app on an attached phone by string key, saving a screenshot and the accessibility tree per stop.

    python tools/ui_walk.py --route all-screens                  # png + ui.xml per stop under build/ui_walk/
    python tools/ui_walk.py --route all-screens --a11y           # also list TalkBack-blind nodes per stop
    python tools/ui_walk.py --a11y build/ui_walk                 # re-run the a11y report on saved dumps
    python tools/ui_walk.py --locale it|en-XA|ar-XB|""           # set (or clear) the app's locale first
    python tools/ui_walk.py --route my.txt                       # a route file of your own

Why this exists (docs/SPEC.md §7 R81). Every device pass from R59 to R80 took the same three adb
commands per screen by hand (`uiautomator dump`, `pull`, `screencap`), fifteen screens at a time,
and the Italian wording review was deferred for three batches largely because "screenshot each
screen" was the expensive half. A route is a list of stops written in string *keys*, resolved
through `values-<lang>/strings.xml` at run time, so the same route walks the app in English,
Italian or a pseudolocale; a node is found by its text or content-description in the live
accessibility tree and tapped at the centre of its bounds. The dumps are the real tree TalkBack
reads, so `--a11y` doubles as the desk half of a TalkBack pass: a clickable node with no label
anywhere in its subtree, an image with no description, a text field with neither hint nor label.
What a dump cannot show stays with Robolectric (`onClickLabel`, CLAUDE.md) or with ears.

Route syntax, one step per line (`#` comments):
    launch                     force-stop and start MainActivity
    tap <key>                  tap the node whose text or content-desc equals the resolved string
    tap "literal text"         tap by literal (seeded medicine names, digits)
    tap_desc <key>             match content-desc only (icon buttons)
    tap_id <resource-id>       match the resource-id suffix
    scroll <up|down>           swipe the main content
    back / home                key events
    wait <seconds>
    shot <name>                save <name>.png and <name>.xml

adb is invoked directly (not through a shell), so `/sdcard` paths are not mangled by Git Bash.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import time
try:
    import defusedxml.ElementTree as ET  # the dump is the phone's own, but entity expansion costs nothing to refuse
except ImportError:
    import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
PACKAGE = "com.hazia.emivita"
ACTIVITY = f"{PACKAGE}/.MainActivity"
DEVICE_DUMP = "/sdcard/ui_walk.xml"

ROUTES = {
    # The owner's walking order (§9 #34): tabs, then each Settings page, then the medicine detail
    # and its pages, then the reminder form. Needs the seed (`PassSeedTest`) for the detail stops.
    "all-screens": """
        launch
        wait 2
        shot today
        tap nav_medicines
        shot medicines
        tap "androidTest Ibuprofen"
        shot medicine_detail
        tap medicine_detail_more
        shot medicine_detail_menu
        tap common_stock_expiration
        shot stock_expiration
        back
        tap medicine_detail_edit_medicine
        shot edit_medicine
        back
        tap common_add_reminder
        shot add_reminder_picker
        tap reminder_fixed_time
        shot add_reminder_fixed_time
        back
        back
        back
        tap nav_history
        shot history_list
        tap history_view_calendar
        shot history_calendar
        tap history_view_table
        shot history_table
        tap nav_settings
        shot settings
        tap settings_row_notifications
        shot settings_notifications
        back
        tap settings_row_alarms
        shot settings_alarms
        back
        tap settings_row_weekend
        shot settings_weekend
        back
        scroll down
        tap settings_row_appearance
        shot settings_appearance
        back
        scroll down
        tap settings_row_data_backup
        shot settings_data_backup
        back
        scroll down
        tap settings_row_medical_id
        shot settings_medical_id
        back
    """,
}


# ---------------------------------------------------------------- adb

def adb(serial, *args, binary=False):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    out = subprocess.run(cmd, capture_output=True, check=False)
    if out.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {out.stderr.decode('utf-8', 'replace').strip()}")
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def dump(serial):
    adb(serial, "shell", "uiautomator", "dump", DEVICE_DUMP)
    xml = adb(serial, "exec-out", "cat", DEVICE_DUMP)
    return xml[xml.index("<?xml"):] if "<?xml" in xml else xml


def screencap(serial, path):
    with open(path, "wb") as f:
        f.write(adb(serial, "exec-out", "screencap", "-p", binary=True))


# ---------------------------------------------------------------- strings

def load_strings(lang):
    """key -> value as the user sees it, from values-<lang> (or values/ for English)."""
    folder = "values" if lang in ("", "en") else f"values-{lang}"
    path = os.path.join(RES, folder, "strings.xml")
    with open(path, "rb") as f:
        text = f.read().decode("utf-8")
    out = {}
    for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', text, re.S):
        v = m.group(2).strip()
        if len(v) >= 2 and v[0] == v[-1] == '"':
            v = v[1:-1]
        v = v.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
        v = v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        out[m.group(1)] = v
    return out


# ---------------------------------------------------------------- tree

def bounds_of(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(int(x) for x in m.groups()) if m else None


def parse_tree(xml):
    root = ET.fromstring(xml)
    parents = {c: p for p in root.iter() for c in p}
    return root, parents


def label_of(node):
    return (node.get("text") or "").strip(), (node.get("content-desc") or "").strip()


def find_node(root, parents, needle, mode="any"):
    """The tappable node for a label: the match itself if clickable, else its nearest clickable
    ancestor, else the match. Exact match first, then prefix, then case-insensitive contains."""
    needle_n = " ".join(needle.split())
    candidates = []
    for node in root.iter("node"):
        text, desc = label_of(node)
        pool = {"any": (text, desc), "desc": (desc,), "text": (text,)}[mode]
        for value in pool:
            v = " ".join(value.split())
            if not v:
                continue
            if v == needle_n:
                rank = 0
            elif v.startswith(needle_n):
                rank = 1
            elif needle_n.lower() in v.lower():
                rank = 2
            else:
                continue
            candidates.append((rank, node))
            break
    if not candidates:
        return None
    candidates.sort(key=lambda c: c[0])
    node = candidates[0][1]
    cur = node
    while cur is not None:
        if cur.get("clickable") == "true":
            return cur
        cur = parents.get(cur)
    return node


def tap(serial, node):
    b = bounds_of(node)
    if not b:
        sys.exit("node has no bounds")
    x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    adb(serial, "shell", "input", "tap", str(x), str(y))


# ---------------------------------------------------------------- a11y report

def a11y_findings(xml):
    """TalkBack-blind nodes: what a screen reader has nothing to say about."""
    root, parents = parse_tree(xml)
    findings = []
    # uiautomator flattens Compose differently from TalkBack: a text field's label and a
    # row's caption are separate nodes laid over the control, not its children. So "labelled"
    # means a text or description anywhere inside the node's own bounds, not only its subtree.
    labelled = [(bounds_of(n), n) for n in root.iter("node") if any(label_of(n)) and bounds_of(n)]

    def has_label_within(node):
        b = bounds_of(node)
        if not b:
            return False
        if any(any(label_of(n)) for n in node.iter("node")):
            return True
        return any(lb[0] >= b[0] and lb[1] >= b[1] and lb[2] <= b[2] and lb[3] <= b[3] for lb, _ in labelled)

    for node in root.iter("node"):
        if node.get("package") != PACKAGE:
            continue
        cls = node.get("class", "")
        b = node.get("bounds", "")
        if node.get("clickable") == "true" and not has_label_within(node):
            findings.append(("clickable, no label within its bounds", cls, b))
        elif cls.endswith(("ImageView", "ImageButton")) and node.get("clickable") == "true" and not label_of(node)[1]:
            findings.append(("image tap target without description", cls, b))
        elif cls.endswith("EditText") and not has_label_within(node):
            findings.append(("text field with neither value nor label", cls, b))
        elif node.get("checkable") == "true" and not has_label_within(node) and not (parents.get(node) is not None and has_label_within(parents[node])):
            findings.append(("checkable without a label beside it", cls, b))
    return findings


def a11y_report(out_dir):
    total = 0
    for path in sorted(glob.glob(os.path.join(out_dir, "*.xml"))):
        with open(path, "rb") as f:
            xml = f.read().decode("utf-8", "replace")
        findings = a11y_findings(xml)
        total += len(findings)
        print(f"{os.path.basename(path)}: {len(findings)} finding(s)")
        for what, cls, b in findings:
            print(f"  - {what}: {cls} {b}")
    print(f"{total} a11y finding(s) across {len(glob.glob(os.path.join(out_dir, '*.xml')))} dump(s)")
    return total


# ---------------------------------------------------------------- walk

def walk(serial, route_text, strings, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    stops = 0
    for raw in route_text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        op, _, arg = line.partition(" ")
        arg = arg.strip()
        if op == "launch":
            adb(serial, "shell", "am", "force-stop", PACKAGE)
            adb(serial, "shell", "am", "start", "-n", ACTIVITY)
            time.sleep(2.0)
        elif op in ("tap", "tap_desc"):
            if arg.startswith('"') and arg.endswith('"'):
                needle = arg[1:-1]
            else:
                if arg not in strings:
                    sys.exit(f"unknown string key: {arg}")
                needle = strings[arg]
            root, parents = parse_tree(dump(serial))
            node = find_node(root, parents, needle, "desc" if op == "tap_desc" else "any")
            if node is None:
                sys.exit(f"not on screen: {arg} ({needle!r}) -- last dump saved as {os.path.join(out_dir, '_last.xml')}\n"
                         + _save_last(serial, out_dir))
            tap(serial, node)
            time.sleep(1.2)
        elif op == "tap_id":
            root, parents = parse_tree(dump(serial))
            node = next((n for n in root.iter("node") if n.get("resource-id", "").endswith(arg)), None)
            if node is None:
                sys.exit(f"no resource-id ending in {arg}")
            tap(serial, node)
            time.sleep(1.2)
        elif op == "scroll":
            size = adb(serial, "shell", "wm", "size")
            w, h = (int(v) for v in re.search(r"(\d+)x(\d+)", size).groups())
            y1, y2 = (int(h * 0.7), int(h * 0.3)) if arg == "down" else (int(h * 0.3), int(h * 0.7))
            adb(serial, "shell", "input", "swipe", str(w // 2), str(y1), str(w // 2), str(y2), "400")
            time.sleep(0.8)
        elif op == "back":
            adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)
        elif op == "home":
            adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(0.8)
        elif op == "wait":
            time.sleep(float(arg))
        elif op == "shot":
            xml = dump(serial)
            with open(os.path.join(out_dir, f"{arg}.xml"), "w", encoding="utf-8", newline="\n") as f:
                f.write(xml)
            screencap(serial, os.path.join(out_dir, f"{arg}.png"))
            stops += 1
            print(f"shot {arg}")
        else:
            sys.exit(f"unknown step: {line}")
    print(f"{stops} stop(s) saved under {out_dir}")


def _save_last(serial, out_dir):
    try:
        xml = dump(serial)
        with open(os.path.join(out_dir, "_last.xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write(xml)
        labels = sorted({v for n in ET.fromstring(xml).iter("node") for v in label_of(n) if v})
        return "on screen: " + " | ".join(labels[:40])
    except Exception as e:  # noqa: BLE001 - diagnostics only
        return f"(could not dump: {e})"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--lang", default="it", help="strings folder used to resolve keys (default it)")
    ap.add_argument("--locale", default=None, help='set the app locale first: it, en-XA, ar-XB, or "" to clear')
    ap.add_argument("--route", help="a built-in route name or a route file")
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "ui_walk"))
    ap.add_argument("--a11y", nargs="?", const=True, default=False, metavar="DIR",
                    help="report TalkBack-blind nodes (after the walk, or on the dumps in DIR)")
    args = ap.parse_args()

    if args.locale is not None:
        adb(args.serial, "shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", args.locale)
        print(f"app locale set to {args.locale!r}")

    if args.route:
        route_text = ROUTES.get(args.route)
        if route_text is None:
            with open(args.route, encoding="utf-8") as f:
                route_text = f.read()
        walk(args.serial, route_text, load_strings(args.lang), args.out)

    if args.a11y:
        return 1 if a11y_report(args.a11y if isinstance(args.a11y, str) else args.out) else 0
    if not args.route and args.locale is None:
        ap.error("nothing to do: pass --route, --a11y DIR, or --locale")
    return 0


if __name__ == "__main__":
    sys.exit(main())
