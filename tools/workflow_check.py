#!/usr/bin/env python3
"""Checks the GitHub workflows nobody can watch run (docs/SPEC.md §7 R74).

    python tools/workflow_check.py [--resolve]

Runs on this repository have died in three seconds on a billing block since 2026-09-13, so a
workflow file can be wrong for months without a red run to say so. This is the gate those files
get instead:

  * every `uses:` is pinned to a 40-character commit SHA and carries a `# vX.Y.Z` comment -- a tag
    can be moved, a SHA cannot (the F-18 note in Appendix E);
  * `permissions:` is declared at the top level of every workflow (least privilege, then the job
    adds what it needs);
  * with --resolve, each version comment is checked against GitHub: the tag must exist and
    dereference (annotated tags included) to exactly that SHA, so the comment cannot lie about
    what the pin is;
  * with --resolve, every `with:` key is checked against the `inputs:` of the action's own
    action.yml at that tag -- the misspelt-input class of error a run would report. actionlint
    does this only for actions it knows by version tag; a SHA-pinned `uses:` skips it, which a
    first falsification of this gate showed (`java-versoin` passed), so it is done here;
  * actionlint (https://github.com/rhysd/actionlint), when present on PATH or under tools/bin/,
    checks syntax, expressions, runner labels and the shape of every key.

Exits non-zero on any failure, and says plainly when actionlint could not be run.
"""

import glob
import os
import re
import shutil
import subprocess
import sys

import yaml

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
WORKFLOWS = os.path.join(ROOT, ".github", "workflows")

USES_LINE = re.compile(r"^\s*(?:-\s+)?uses:\s*(\S+)\s*(#\s*(\S+))?\s*$")
PINNED = re.compile(r"^([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(/[^@\s]+)?@([0-9a-f]{40})$")
VERSION = re.compile(r"^v\d+(\.\d+)*$")


def uses_lines(path):
    """(line number, ref, comment) for every `uses:` in the file, read as text so the comment survives."""
    out = []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            m = USES_LINE.match(line)
            if m:
                out.append((n, m.group(1), m.group(3)))
    return out


def gh_json(path):
    r = subprocess.run(["gh", "api", path], capture_output=True, text=True)
    if r.returncode != 0:
        return None
    import json
    return json.loads(r.stdout)


def resolve(repo, tag):
    """The commit SHA a tag points at, dereferencing an annotated tag object."""
    ref = gh_json("repos/%s/git/ref/tags/%s" % (repo, tag))
    if not ref:
        return None
    obj = ref["object"]
    if obj["type"] == "tag":
        obj = gh_json("repos/%s/git/tags/%s" % (repo, obj["sha"]))["object"]
    return obj["sha"]


def steps_with_inputs(doc):
    """(uses ref, [with keys]) for every step of every job in a parsed workflow."""
    out = []
    for job in (doc.get("jobs") or {}).values():
        for step in (job or {}).get("steps") or []:
            if isinstance(step, dict) and "uses" in step:
                out.append((step["uses"], list((step.get("with") or {}).keys())))
    return out


def action_inputs(repo, subpath, tag):
    """The declared inputs of owner/repo(/subpath)'s action.yml at [tag], or None if unreadable."""
    import base64
    for name in ("action.yml", "action.yaml"):
        path = "%s/%s" % (subpath.strip("/"), name) if subpath else name
        meta = gh_json("repos/%s/contents/%s?ref=%s" % (repo, path, tag))
        if meta and meta.get("content"):
            spec = yaml.safe_load(base64.b64decode(meta["content"]).decode("utf-8"))
            return set((spec.get("inputs") or {}).keys())
    return None


def find_actionlint():
    for cand in [shutil.which("actionlint"), os.path.join(ROOT, "tools", "bin", "actionlint.exe"), os.path.join(ROOT, "tools", "bin", "actionlint")]:
        if cand and os.path.isfile(cand):
            return cand
    return None


def main():
    do_resolve = "--resolve" in sys.argv[1:]
    files = sorted(glob.glob(os.path.join(WORKFLOWS, "*.yml")) + glob.glob(os.path.join(WORKFLOWS, "*.yaml")))
    if not files:
        sys.exit("no workflows under %s" % WORKFLOWS)
    failures = []
    resolved = {}

    for path in files:
        rel = os.path.relpath(path, ROOT)
        with open(path, encoding="utf-8") as f:
            doc = yaml.safe_load(f)
        if not isinstance(doc, dict):
            failures.append("%s: not a mapping" % rel)
            continue
        if "permissions" not in doc:
            failures.append("%s: no top-level `permissions:`" % rel)
        for n, ref, comment in uses_lines(path):
            if ref.startswith("./"):
                continue  # a local action needs no pin
            m = PINNED.match(ref)
            if not m:
                failures.append("%s:%d: `uses: %s` is not pinned to a 40-char commit SHA" % (rel, n, ref))
                continue
            if not comment or not VERSION.match(comment):
                failures.append("%s:%d: `uses: %s` has no `# vX.Y.Z` comment naming the pinned version" % (rel, n, ref))
                continue
            repo, sha = m.group(1), m.group(3)
            print("  pin  %s@%s %s" % (repo, sha[:12], comment))
            if do_resolve:
                key = (repo, comment)
                if key not in resolved:
                    resolved[key] = resolve(repo, comment)
                actual = resolved[key]
                if actual is None:
                    failures.append("%s:%d: %s has no tag %s on GitHub" % (rel, n, repo, comment))
                elif actual != sha:
                    failures.append("%s:%d: %s %s is %s on GitHub, not %s" % (rel, n, repo, comment, actual, sha))
                else:
                    print("       resolved %s %s -> %s ok" % (repo, comment, actual[:12]))
        if do_resolve:
            inputs_cache = {}
            for ref, keys in steps_with_inputs(doc):
                m = PINNED.match(ref)
                if not m or not keys:
                    continue
                comment = next((c for _, r, c in uses_lines(path) if r == ref), None)
                key = (m.group(1), m.group(2) or "", comment)
                if key not in inputs_cache:
                    inputs_cache[key] = action_inputs(m.group(1), m.group(2) or "", comment)
                declared = inputs_cache[key]
                if declared is None:
                    failures.append("%s: could not read action.yml for %s@%s to check its inputs" % (rel, key[0] + key[1], comment))
                    continue
                for k in keys:
                    if k not in declared:
                        failures.append("%s: `with: %s` is not an input of %s@%s (declared: %s)" % (rel, k, key[0] + key[1], comment, ", ".join(sorted(declared))))
                print("       inputs %s%s %s ok (%s)" % (key[0], key[1], comment, ", ".join(keys)))

    actionlint = find_actionlint()
    if actionlint:
        r = subprocess.run([actionlint, "-no-color"] + files, capture_output=True, text=True, cwd=ROOT)
        if r.returncode != 0:
            failures.append("actionlint:\n" + (r.stdout + r.stderr).strip())
        else:
            print("  actionlint ok (%s)" % subprocess.run([actionlint, "-version"], capture_output=True, text=True).stdout.split("\n")[0])
    else:
        print("  actionlint NOT RUN: not on PATH and not under tools/bin/ -- fetch a release from https://github.com/rhysd/actionlint/releases")

    if failures:
        print("\nFAIL")
        for f in failures:
            print("  " + f)
        return 1
    print("\nPASS  %d workflows, every action pinned%s%s" % (len(files), ", every pin resolved" if do_resolve else "", ", actionlint clean" if actionlint else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
