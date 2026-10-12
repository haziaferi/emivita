#!/usr/bin/env python3
"""Score a proposed split of one oversized Kotlin file (SPEC §7 R82).

A split is an assignment of each top-level declaration to a part. `--score`
reads one assignment and prints its cost, so an optimizer can drive it;
`--greedy` prints a deterministic baseline to beat; `--explain` shows the cost
of the current single-file arrangement.

Cost is lines of real consequence, not taste: an edge crossing a part boundary
is a reader jumping files, a private symbol pulled from its callers is a
visibility widening, and a part over the cap has not solved the problem.
"""

from __future__ import annotations

import argparse
import json
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

CAP = 450          # a part above this is still an oversized file
CROSS = 3.0        # per weighted reference crossing a boundary
WIDEN = 25.0       # per private declaration that would need `internal`
OVER = 1.0         # per line above the cap
PART = 40.0        # per part, so the split stops multiplying files
SPREAD = 0.25      # per line of deviation from the mean part size


def load(path: str):
    with open(path, "rb") as fh:
        return json.loads(fh.read().decode("utf-8"))


def cost(graph, assign: dict[str, int], detail: bool = False):
    sizes: dict[int, int] = {}
    for node in graph["nodes"]:
        part = assign[node["symbol"]]
        sizes[part] = sizes.get(part, 0) + node["lines"]
    if not sizes:
        return 0.0, {}

    private = {n["symbol"] for n in graph["nodes"] if n.get("private")}
    crossing = 0.0
    widened = set()
    for edge in graph["edges"]:
        a, b = assign[edge["from"]], assign[edge["to"]]
        if a != b:
            crossing += edge["weight"]
            if edge["to"] in private:
                widened.add(edge["to"])

    mean = sum(sizes.values()) / len(sizes)
    over = sum(max(0, s - CAP) for s in sizes.values())
    spread = sum(abs(s - mean) for s in sizes.values())

    terms = {
        "cross_refs": CROSS * crossing,
        "visibility_widenings": WIDEN * len(widened),
        "lines_over_cap": OVER * over,
        "parts": PART * len(sizes),
        "size_spread": SPREAD * spread,
    }
    total = sum(terms.values())
    if detail:
        terms["_sizes"] = dict(sorted(sizes.items()))
        terms["_crossing_refs"] = crossing
        terms["_widened"] = sorted(widened)
    return total, terms


def greedy(graph, parts: int):
    """Largest declaration first into the part it is most attached to, else the emptiest."""
    nodes = sorted(graph["nodes"], key=lambda n: -n["lines"])
    weight: dict[tuple[str, str], float] = {}
    for e in graph["edges"]:
        weight[(e["from"], e["to"])] = weight.get((e["from"], e["to"]), 0) + e["weight"]
        weight[(e["to"], e["from"])] = weight.get((e["to"], e["from"]), 0) + e["weight"]

    assign: dict[str, int] = {}
    sizes = {p: 0 for p in range(parts)}
    for node in nodes:
        pull = {p: 0.0 for p in range(parts)}
        for other, part in assign.items():
            pull[part] += weight.get((node["symbol"], other), 0)
        best = max(pull, key=lambda p: (pull[p], -sizes[p]))
        if pull[best] == 0:
            best = min(sizes, key=lambda p: sizes[p])
        assign[node["symbol"]] = best
        sizes[best] += node["lines"]
    return assign


def refine(graph, assign: dict[str, int], parts: int):
    """Best-improvement local search: move one declaration, then swap two.

    Part labels are interchangeable, so the landscape is full of symmetric
    plateaus that a population search cannot cross. Moves and swaps walk
    straight down them, which is why this beats the metaheuristic here.
    """
    assign = dict(assign)
    best, _ = cost(graph, assign)
    symbols = [n["symbol"] for n in graph["nodes"]]
    improved = True
    while improved:
        improved = False
        for sym in symbols:
            home = assign[sym]
            for part in range(parts):
                if part == home:
                    continue
                assign[sym] = part
                trial, _ = cost(graph, assign)
                if trial < best - 1e-9:
                    best, home, improved = trial, part, True
                else:
                    assign[sym] = home
        for i, a in enumerate(symbols):
            for b in symbols[i + 1:]:
                if assign[a] == assign[b]:
                    continue
                assign[a], assign[b] = assign[b], assign[a]
                trial, _ = cost(graph, assign)
                if trial < best - 1e-9:
                    best, improved = trial, True
                else:
                    assign[a], assign[b] = assign[b], assign[a]
    return assign, best


def normalize(assign: dict[str, int]) -> dict[str, int]:
    """Renumber parts by first appearance, so equivalent splits compare equal."""
    order: dict[int, int] = {}
    for sym in assign:
        order.setdefault(assign[sym], len(order))
    return {s: order[p] for s, p in assign.items()}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("graph")
    ap.add_argument("--score", metavar="ASSIGN_JSON", help='{"Symbol": part, ...} or a file holding it')
    ap.add_argument("--greedy", type=int, metavar="PARTS")
    ap.add_argument("--explain", action="store_true", help="cost of the current one-file arrangement")
    ap.add_argument("--solve", action="store_true",
                    help="refine every seed (one file, greedy k=1..5, --seed) and print the winner")
    ap.add_argument("--seed", metavar="ASSIGN_JSON", help="an extra starting assignment to refine")
    args = ap.parse_args()

    graph = load(args.graph)

    if args.explain:
        single = {n["symbol"]: 0 for n in graph["nodes"]}
        total, terms = cost(graph, single, detail=True)
        print(f"one file: cost {total:.1f}")
        print(json.dumps(terms, indent=2))
        return 0

    if args.solve:
        seeds = {"one-file": {n["symbol"]: 0 for n in graph["nodes"]}}
        for k in range(2, 6):
            seeds[f"greedy-k{k}"] = greedy(graph, k)
        if args.seed:
            try:
                given = json.loads(args.seed)
            except json.JSONDecodeError:
                given = load(args.seed)
            # `optimizer_cli best --json` wraps the point and returns floats.
            given = given.get("vector", given)
            seeds["given"] = {k: int(round(float(v))) for k, v in given.items()}
        results = []
        for label, start in seeds.items():
            before, _ = cost(graph, start)
            tuned, after = refine(graph, start, 5)
            results.append((after, label, before, normalize(tuned)))
            print(f"  {label:12} {before:8.1f} -> {after:8.1f}")
        after, label, before, winner = min(results, key=lambda r: r[0])
        total, terms = cost(graph, winner, detail=True)
        print(json.dumps({"from_seed": label, "cost": round(total, 2),
                          "parts": len(terms["_sizes"]), "sizes": terms["_sizes"],
                          "crossing_refs": terms["_crossing_refs"],
                          "widenings": terms["_widened"], "assign": winner}, indent=2))
        return 0

    if args.greedy:
        assign = greedy(graph, args.greedy)
        total, terms = cost(graph, assign, detail=True)
        print(json.dumps({"assign": assign, "cost": round(total, 2),
                          "terms": {k: (round(v, 2) if isinstance(v, float) else v)
                                    for k, v in terms.items()}}, indent=2))
        return 0

    if args.score:
        raw = args.score
        try:
            assign = json.loads(raw)
        except json.JSONDecodeError:
            assign = load(raw)
        missing = [n["symbol"] for n in graph["nodes"] if n["symbol"] not in assign]
        if missing:
            print(f"unassigned: {missing}", file=sys.stderr)
            return 2
        total, _ = cost(graph, assign)
        print(f"{total:.4f}")
        return 0

    ap.error("one of --score, --greedy, --explain")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
