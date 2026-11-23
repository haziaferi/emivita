# Identifier map

Where each identifier of the old `docs/SPEC.md` (commit `82dabce`) lives after the migration. Code, tests, tools and
CI keep their old citations (`§7 R143`, `§9 #34`, `§10`) until a file is next touched, when they move to
`SPEC:Rnn`; this table resolves them meanwhile. Rows are added by each migration pass.

## How to read an old citation

| Old citation | Where to look |
|---|---|
| `§7 Rn`, `Rn` (n up to 146), `Tn`, ledger rows 1 to 4 | `findings-ledger.md`, the row with the same id |
| `§7.1 rule n` | `SPEC.md` R(159 + n), from the table below |
| `Appendix B` | `appendix-b-ux-audit.md` |
| `Appendix C`, `T1` to `T6` reasoning | `appendix-c-theme-review.md` |
| `Appendix D` | git only: `git show 82dabce:docs/SPEC.md` |
| `Appendix E`, `E.1` to `E.5`, area codes `A-` to `F-` | `appendix-e-medtimer-diff.md` |
| `Appendix F`, `F.1` to `F.6`, app codes (`HM-`, `MT-`, `DS-`, `DP-`, `PT-`, ...) | `appendix-f-cross-app-diff.md` |
| a bare `§1` | `SPEC.md` R148, the platform constraints |
| a bare `§10`, "the §10 triad" | `SPEC.md` R150 to R154; the build triad is R153 |
| any other `§n` (2, 3, 4, 5, 6, 8, 9), `§9 #N`, `Appendix A` | `../SPEC-legacy.md`, same heading, until a pass moves it |

## Moved to SPEC.md, pass 1 (2026-11-22)

| Old identifier | New rule |
|---|---|
| Header: "This is the source of truth" | R149 |
| "Where everything went" (the retired files) | R149 (its Not) |
| "Where everything went": "The rule this pass establishes" (one file) | R150 |
| §1 first paragraph | `SPEC.md` 1. Purpose and jobs (background) |
| §1 constraint table | R148 |
| §1 identity row's rename narrative | R148 (Identity row); the rename itself is ledger rows R4, R5 and R9 |
| (no jobs anywhere) | R147, answered by the owner 2026-11-22 and 23 |
| §10 "spec-first checkpoint" | R151 |
| §10 "allowed to run ahead of shipped code" | R151 |
| §10 "XML comments never use" double hyphens | R154 |
| §10 "Every batch gets checked against §1's constraint table" | R152 |
| §10 "Verify a build against the build" | R153 |
| §10 "One file" | R150 |
| ledger row 58 (`CLAUDE.md` carries commands only) | R149 |
| ledger row 86 (public repository, pull requests, four checks) | R155, R156 |
| ledger row 82 and 83 (the owner's comment ruling) | R157 |
| ledger row 102 (2) (adopt the reference app as-is, scoring axes) | R159 |
| ledger row 102 (3) (release signing) | R148 (Release signing row), R182 |
| ledger row 131 (the four standing practices, the design axes) | R158, R159 |
| §7.1 rule 1 | R160 |
| §7.1 rule 2 | R161 |
| §7.1 rule 3 | R162 |
| §7.1 rule 4 | R163 |
| §7.1 rule 5 | R164 |
| §7.1 rule 6 | R165 |
| §7.1 rule 7 | R166 |
| §7.1 rule 8 | R167 |
| §7.1 rule 9 | R168 |
| §7.1 rule 10 | R169 |
| §7.1 rule 11 | R170 |
| §7.1 rule 12 | R171 |
| §7.1 rule 13 | R172 |
| §7.1 rule 14 | R173 |
| §7.1 rule 15 | R174 |
| §7.1 rule 16 | R175 |
| §7.1 rule 17 | R176 |
| §7.1 rule 18 | R177 |
| §7.1 rule 19 | R178 |
| §7.1 rule 20 | R179 |
| §7.1 rule 21 | R180 |
| §7.1 rule 22 | R181 |
