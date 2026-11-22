# Emivita spec

Scope: Emivita, a personal medication reminder and adherence app for one Android phone; what it does, how it is built, and how work on it is done.

History: `SPEC-history.md`, one line per change. It is never a source of current rules.

Kinds: O owner rule · D decided · I inherited · A assumed · Q open.

Migration: this file is being migrated topic by topic from `SPEC-legacy.md` (R150). A section still in `SPEC-legacy.md` stays authoritative there until it moves; `spec-evidence/identifier-map.md` maps every old identifier to its new place.

## Rule index

| ID | Name | Section |
|---|---|---|
| Q-R147 | Jobs and how often | 1. Purpose and jobs |
| O-R148 | Platform constraints | 1. Purpose and jobs |
| D-R149 | Source of truth | 2. Working agreements |
| D-R150 | Findings and evidence | 2. Working agreements |
| D-R151 | Spec first | 2. Working agreements |
| D-R152 | Constraint check before shipping | 2. Working agreements |
| D-R153 | Verifying a build | 2. Working agreements |
| D-R154 | Double hyphens in XML comments | 2. Working agreements |
| D-R155 | How changes land | 2. Working agreements |
| D-R156 | Public repository | 2. Working agreements |
| D-R157 | Comment length | 2. Working agreements |
| D-R158 | Standing practices | 2. Working agreements |
| D-R159 | Adopting from the reference app | 2. Working agreements |
| D-R160 | A permission needs its request path | 3. Engineering rules |
| D-R161 | Permissions against the merged manifest | 3. Engineering rules |
| D-R162 | One write per edit | 3. Engineering rules |
| D-R163 | Fix the defect class | 3. Engineering rules |
| D-R164 | Assert the property | 3. Engineering rules |
| D-R165 | Tests run off UTC | 3. Engineering rules |
| D-R166 | Agreement is not evidence | 3. Engineering rules |
| D-R167 | Resolved means the call sites moved | 3. Engineering rules |
| D-R168 | Derived signals that share a cause | 3. Engineering rules |
| D-R169 | Harmony cannot carry meaning | 3. Engineering rules |
| D-R170 | Red is for emergencies | 3. Engineering rules |
| D-R171 | Where a colour-alone floor applies | 3. Engineering rules |
| D-R172 | Separate on lightness | 3. Engineering rules |
| D-R173 | Lint is a gate | 3. Engineering rules |
| D-R174 | One project, one build | 3. Engineering rules |
| D-R175 | designkit's silent fallback | 3. Engineering rules |
| D-R176 | Silent safe degradation is a defect | 3. Engineering rules |
| D-R177 | What a walk is for | 3. Engineering rules |
| D-R178 | Port the reference app's tests first | 3. Engineering rules |
| D-R179 | A false or null return is a failure | 3. Engineering rules |
| D-R180 | A gate proves it scanned | 3. Engineering rules |
| D-R181 | A test crosses the defect's boundary | 3. Engineering rules |
| D-R182 | Release key and distribution | Out of scope |

## Glossary

| Term | Meaning |
|---|---|
| Batch | One planned unit of work that lands as one pull request. |
| Builder | Whoever writes the code: a Claude session, or the owner. |
| Findings ledger | `spec-evidence/findings-ledger.md`: the audit findings of 2026-08 to 2026-11-02, rows 1 to 146 and T1 to T6, frozen as evidence. |
| Gate | A check that fails the build or CI, as opposed to one that only reports. |
| Owner | The one person who uses the app and decides what it does. |
| Reference app | medTimer (`Futsch1/medTimer`, MIT), the app whose logic Emivita ports and compares against. |
| Walk | A pass through the app on the owner's phone, as opposed to a test run. |

## 1. Purpose and jobs

Emivita reminds one person to take their medicines and keeps the record of what was taken, skipped or missed, so the record can be trusted and shown to a clinician. It is a structural revisit of the reference app: its scheduling, notification and backup logic is a source to port from, its Fragment and XML interface is not.

### Q-R147 · Jobs and how often

**Rule:** Which jobs does the app serve, and how often is each done?

**Default:** none proposed: `SPEC-legacy.md` names the four tabs (Today, Medicines, History, Settings) but never states the jobs behind them or their frequency, and the order of every default should follow that frequency.

**Why:** The jobs and their frequency decide what opens first and what each screen puts at the top; no rule records them.

**Since:** 2026-11-22 · builder · own words

### O-R148 · Platform constraints

**Rule:** Every build meets each constraint below. A batch that breaks one does not ship (R152).

**Why:** Each row's reason is in its last column; together they keep the app private, local and installable on the owner's phone.

**Values:**

| Constraint | Value | Why |
|---|---|---|
| File input and output | Storage Access Framework only | no legacy file paths, no scoped-storage workarounds |
| Network | no `INTERNET` permission, no HTTP client of any kind | local-first |
| Google services | no Firebase, no GMS or Play Services, no `google-services` plugin | de-googled |
| minSdk | 30 (Android 11) | the floor for the target device |
| targetSdk | 36 or later; `compileSdk` may run ahead of it | the ceiling tracks current behaviour, not legacy |
| Root | none assumed: no Shizuku, no `su` | the phone is not rooted |
| Distribution | sideloaded APK, no Google Play, no Play-specific APIs | personal use only |
| Release signing | the committed shared debug key, `keystores/my-shared-debug.keystore` | no private release key (R182) |
| Toolchain | Windows 11, Android Studio, JDK 21 runs Gradle; `sourceCompatibility` and `jvmTarget` 17 | 17 is deliberate, not a mismatch |
| Language | Kotlin and Compose only, no JavaScript or TypeScript | one language across the tree |
| Identity | `applicationId` and root package `com.hazia.emivita`, label "Emivita" | fixed project identity; a change is a fresh install with a new Keystore alias |

**Evidence:** `findings-ledger.md` row 35 verified the network and Google rows against the merged manifest and the whole `releaseRuntimeClasspath`. `app/build.gradle.kts` sets `minSdk = 30`, `targetSdk = 36`, `compileSdk = 37`, `jvmToolchain(21)` and `JvmTarget.JVM_17`; checked 2026-11-22. Rows 94 (targetSdk) and 102 (signing) of the findings ledger set the values above.

**Acceptance:** The merged release manifest declares no `INTERNET` permission; `releaseRuntimeClasspath` holds no `com.google.android.gms` or `com.google.firebase` artifact; `app/build.gradle.kts` has the minSdk, targetSdk, toolchain and identity of the table.

**Since:** 2026-10-20 · owner · migrated

## 2. Working agreements

### D-R149 · Source of truth

**Rule:** The files below hold Emivita's decisions, each in the role its row gives; anything decided in conversation is folded into `SPEC.md` before it affects the code. Where any other file, a code comment or a chat disagrees with `SPEC.md`, `SPEC.md` wins until it is edited.

**Values:**

| File | Role |
|---|---|
| `docs/SPEC.md` | the current rules |
| `docs/SPEC-history.md` | one line per change to a rule; never a source of rules |
| `docs/SPEC-legacy.md` | the sections not yet migrated, authoritative for them until they move (R150) |
| `docs/spec-evidence/*.md` | evidence that rules cite: the findings ledger, the reviews and the reference-app diffs |
| `docs/purpose-map.md` | a view generated from `docs/purpose-map.json`, checked in CI; loses to `SPEC.md` |
| `CLAUDE.md` | commands only, for agents; loses to `SPEC.md` |
| `README.md` | setup and orientation; loses to `SPEC.md` |
| `dist/vc1/MANIFEST.md` | one archived binary, not the design |

**Acceptance:** none: a working practice, applied when a decision is made.

**Not:** Keeping decisions in separate Emivita documents: five such files once drifted apart, two lived outside the repository and one was cited by name after it was deleted (`git show 4536736:docs/retired/` holds them).

**Since:** 2026-11-22 · owner · migrated

### D-R150 · Findings and evidence

**Rule:** A finding lands as a change to the rule it concerns plus one line in `SPEC-history.md`; reasoning too long for the rule's Evidence goes in its own file under `docs/spec-evidence/`, named from that Evidence. The findings ledger is closed at its row 146 and is never edited. `SPEC-legacy.md` is migrated one topic per pull request, each leaving a line where its sections were, and is deleted once empty.

**Why:** At 1 MB the old spec was the thing nobody read before the work that would break it, and its append-only ledger held most of the current rules inside table cells.

**Values:**

| Kind of material | Where it goes |
|---|---|
| A decision or a corrected one | the rule's own fields, rewritten |
| The change itself | one `SPEC-history.md` line, with the commit |
| Long evidence: an audit, a measurement, a diff of another app | a file in `docs/spec-evidence/` |
| Harvest catalogues, comparisons of other apps, scores | outside the repository, under `_work/` |
| Retired wording | git |

**Acceptance:** `speccheck` on `docs/SPEC.md` reports no BLOCK or ASK finding, and `findings-ledger.md` is unchanged since the commit that moved it.

**Not:** Appending each finding as a ledger row in the spec: replaced 2026-11-22 by the owner's choice. A separate Emivita document for a finding: the drift R149 records.

**Since:** 2026-11-22 · owner · agreed rewording

### D-R151 · Spec first

**Rule:** Small changes discussed in chat are folded into the spec; a large batch does not start until the rules it builds on are confirmed. The spec may run several batches ahead of the code, and the sync always goes from spec to code, never the reverse.

**Why:** A batch's bundled copy of the spec lagging the real one is drift, not a contradiction, but it is corrected before the next handoff so it cannot compound.

**Acceptance:** none: a working practice, applied before a batch starts.

**Since:** 2026-09-01 · unrecorded · migrated

### D-R152 · Constraint check before shipping

**Rule:** Every batch is checked against the constraints of R148 before it ships.

**Acceptance:** The checks in R148's Acceptance pass on the batch's merge commit.

**Since:** 2026-09-01 · unrecorded · migrated

### D-R153 · Verifying a build

**Rule:** A build is verified against the build, never against an artefact of it: the build is never on the left of a pipe when its exit code is read, `app/build/test-results` is cleared before a test count is quoted, and the newest result file's time is printed beside the count. The log is searched for `^e:` and `BUILD ` explicitly.

**Why:** A build piped to `tail` and stale test-result files reported three consecutive greens over a failing compile (`findings-ledger.md` row 57); R168 is the general form.

**Acceptance:** none: a working practice; `CLAUDE.md` carries the commands.

**Since:** 2026-09-06 · unrecorded · migrated

### D-R154 · Double hyphens in XML comments

**Rule:** An XML comment never holds two hyphens in a row in its body; single hyphens, `=` or commas separate instead. Kotlin comments are unaffected.

**Why:** The XML parser refuses them: the manifest merger failed on one (`findings-ledger.md` row 64).

**Acceptance:** `python tools/strings_review.py --check` passes, and the merged manifest builds.

**Since:** 2026-09-01 · unrecorded · migrated

### D-R155 · How changes land

**Rule:** `master` takes changes only by pull request, merged when the four checks below are green. No force-push, no deletion, no bypass.

**Values:**

| Check | Workflow job |
|---|---|
| Build, test, lint | `ci.yml` verify |
| Release build, R8 gate | `ci.yml` release-gate |
| Analyze (java-kotlin) | `codeql.yml` |
| Dependency review | `dependency-review.yml` |

**Evidence:** Ruleset 24953901 ("master: PRs with green CI") requires the four checks and blocks deletion and non-fast-forward pushes, with no bypass actors; checked 2026-11-22.

**Acceptance:** `gh api repos/haziaferi/emivita/rulesets/24953901` lists the four required checks, `deletion` and `non_fast_forward`, and an empty `bypass_actors`.

**Since:** 2026-10-13 · owner · migrated

### D-R156 · Public repository

**Rule:** The repository is public under GPL-3.0, and security reports go through GitHub's private vulnerability reporting, so no contact is published. No personal information appears in any file or in git metadata: commits carry `haziaferi` and the GitHub no-reply address.

**Evidence:** `LICENSE` and `SECURITY.md` exist; the history was rewritten with `git filter-repo` on 2026-10-13 and the pre-rewrite history kept outside the repository (`findings-ledger.md` row 86).

**Acceptance:** `git log --format='%an %ae %cn %ce'` lists only `haziaferi` and the no-reply address, and a scan of the tracked files finds no personal name, email or contact.

**Since:** 2026-10-13 · owner · migrated

### D-R157 · Comment length

**Rule:** A comment block is at most three sentences. A comment that is wrong or redundant is removed rather than shortened.

**Why:** The owner's ruling of 2026-10-12, which made comment length computable instead of a matter of taste (`findings-ledger.md` rows 82 and 83).

**Acceptance:** `python tools/code_health.py --selftest` and `python tools/code_health.py --check` exit 0.

**Since:** 2026-10-12 · owner · migrated

### D-R158 · Standing practices

**Rule:** The four practices below apply to every batch, in the owner's words.

**Values:**

| Practice | What it means |
|---|---|
| Render sheets before every ask | a choice about layout, wording or colour is shown as rendered options before the owner is asked |
| Gate 2.0 + 360 dp in CI | `ScreenSweepTest` walks every screen at 360 dp, font 1.0 and 2.0, and fails on cut or broken text |
| One walk per batch | each batch gets one walk on the phone, not one per change |
| Incremental harvests | ideas are harvested from the owner's projects that resemble Emivita most, reading only what changed since the last index |

**Acceptance:** `ScreenSweepTest` runs in the "Build, test, lint" check; the other three rows are practices with nothing to run.

**Since:** 2026-10-30 · owner · own words

### D-R159 · Adopting from the reference app

**Rule:** A feature the reference app has is adopted as it is, unless scoring on axes the owner picks shows a variant is better. The axes are asked before anything is scored, and the axes below are the owner's picks.

**Values:**

| Group | Axes |
|---|---|
| Effort | harmony and alignment, fewest large empty areas, taps to finish, screen density, time to answer a reminder, learn once and it works everywhere |
| Safety | missed-dose risk, mistakes and undo, record fidelity, privacy on the lock screen |
| Fit | text follows the font settings, contrast, touch targets in at most three sizes by rank (48, 42, 38 dp), calm tone, fits the purposes, Italian reads naturally |
| Design | visual-function hierarchy, harmony, least taps, glance, findability, familiar navigation, consistency across screens, no stock look |
| Cost (informational, never decisive) | build size, test-enforceable, battery, permissions |

**Evidence:** `findings-ledger.md` rows 102 (the rule and the first three groups) and 131 (the design group).

**Acceptance:** none: a working practice, applied before a feature is scored.

**Since:** 2026-10-30 · owner · migrated

## 3. Engineering rules

Each rule below was paid for by a defect; its Evidence names the findings ledger rows.

### D-R160 · A permission needs its request path

**Rule:** A permission that is declared and checked must also be requested somewhere; check that the request path exists, not just the check.

**Why:** `POST_NOTIFICATIONS` and `SCHEDULE_EXACT_ALARM` each shipped read correctly at every call site and requested nowhere, so on a fresh install reminders silently failed.

**Evidence:** `findings-ledger.md` rows 14 and 21.

**Acceptance:** none: a review practice; each permission's own rule carries its check.

**Since:** 2026-09-06 · builder · migrated

### D-R161 · Permissions against the merged manifest

**Rule:** A claim about permissions is checked against the merged manifest, never the source one.

**Evidence:** `findings-ledger.md` row 21.

**Acceptance:** none: a review practice, applied to every permission claim.

**Since:** 2026-09-06 · builder · migrated

### D-R162 · One write per edit

**Rule:** One edit is one write. Two full-row writes describing one edit lose an update, and the fix is one write, not a reordering.

**Why:** Editing a reminder did nothing, because `@Update` writes every column and the second write restored what the first had changed.

**Evidence:** `findings-ledger.md` row 23.

**Acceptance:** none: a review practice for data-layer code.

**Since:** 2026-09-06 · builder · migrated

### D-R163 · Fix the defect class

**Rule:** A defect class is fixed with a test that enumerates its cases, not one instance at a time.

**Why:** Fixing one instance left its siblings, and only the enumerating test found them.

**Evidence:** `findings-ledger.md` row 24.

**Acceptance:** none: a review practice for every fix.

**Since:** 2026-09-06 · builder · migrated

### D-R164 · Assert the property

**Rule:** A test asserts the property itself, not a proxy for it. A green suite that never exercised the code under test is worse than none, because it cancels the gate that made green mean something.

**Why:** A hatch defect would have passed an assertion that the three series had different colours.

**Evidence:** `findings-ledger.md` rows 26, 28 and 55.

**Acceptance:** none: a review practice for every test.

**Since:** 2026-09-06 · builder · migrated

### D-R165 · Tests run off UTC

**Rule:** Scheduling tests run in a zone with an offset, `Europe/Rome`, never in UTC alone.

**Why:** A suite that runs only in UTC cannot catch an offset bug.

**Evidence:** `findings-ledger.md` row 24. Not checked: of the 21 test files under `core/scheduling`, 4 set `Europe/Rome`; whether the rest run in the zone some other way was not read.

**Acceptance:** Every scheduling test either sets `Europe/Rome` or a non-UTC zone of its own.

**Since:** 2026-09-06 · builder · migrated

### D-R166 · Agreement is not evidence

**Rule:** Documentation that agrees across every call site is not evidence: check the shared premise against the shipped code.

**Why:** Three times, every site agreed with every other and all rested on a premise that did not survive one check against the code; consistency is what a wrong shared assumption looks like.

**Evidence:** `findings-ledger.md` rows 25, 27 and 28.

**Acceptance:** none: a review practice for every claim.

**Since:** 2026-09-06 · builder · migrated

### D-R167 · Resolved means the call sites moved

**Rule:** A finding is resolved when its call sites have migrated, not when its mechanism has shipped.

**Why:** Two rows were marked resolved on the mechanism alone; one fix had reached one of History's three surfaces.

**Evidence:** `findings-ledger.md` rows 46 and 47.

**Acceptance:** none: a review practice before a finding is closed.

**Since:** 2026-09-05 · builder · migrated

### D-R168 · Derived signals that share a cause

**Rule:** Two derived signals that agree are not corroboration when they share a cause.

**Why:** A build piped to `tail` and stale `test-results` files together reported a passing build three times over a failing compile.

**Evidence:** `findings-ledger.md` rows 57 and 47.

**Acceptance:** none: a review practice; R153 applies it to builds.

**Since:** 2026-09-06 · builder · migrated

### D-R169 · Harmony cannot carry meaning

**Rule:** A colour chosen for harmony with the accent cannot also mean that something is wrong.

**Why:** A hue rotated to stay harmonious produced teal, cyan, magenta and green for the role that paints missed doses; two failures of one rule is the rule being wrong, not the palettes.

**Evidence:** `findings-ledger.md` rows 40 and 42.

**Acceptance:** none: a design rule; the palette rules in `SPEC-legacy.md` carry the checks until the palette pass.

**Since:** 2026-09-06 · builder · migrated

### D-R170 · Red is for emergencies

**Rule:** Red is reserved for genuine emergencies: never an accent, never a brand colour, never the marker of an ordinary event.

**Why:** It is the governing rule of the app's calm, patient-facing register, and the reason the `attention` and `neutralFill` roles exist.

**Evidence:** `findings-ledger.md` row 56.

**Acceptance:** none: a design rule; the palette rules in `SPEC-legacy.md` carry the checks until the palette pass.

**Since:** 2026-09-06 · builder · migrated

### D-R171 · Where a colour-alone floor applies

**Rule:** A colour-alone distance floor governs only where colour is the sole channel. Any surface that shows state with no icon or label beside it is such a place.

**Why:** `AdherenceChart` turned out to show state by colour alone, which overturned a judgement that colour was only its second channel.

**Evidence:** `findings-ledger.md` rows 53, 54 and 55.

**Acceptance:** none: a design rule; the palette rules in `SPEC-legacy.md` carry the checks until the palette pass.

**Since:** 2026-09-06 · builder · migrated

### D-R172 · Separate on lightness

**Rule:** In the app's non-alarming register, colours are separated on lightness, not chroma.

**Why:** Lightness is the axis colour-vision deficiency spares; chroma is the axis that reads as alarm.

**Evidence:** `findings-ledger.md` row 56.

**Acceptance:** none: a design rule; the palette rules in `SPEC-legacy.md` carry the checks until the palette pass.

**Since:** 2026-09-06 · builder · migrated

### D-R173 · Lint is a gate

**Rule:** `lintDebug` fails the build on any warning, and CI runs it on every pull request.

**Why:** Every workflow run once failed at its first step with `Permission denied` while the README claimed the gates ran remotely.

**Evidence:** `findings-ledger.md` rows 26 and 38. `app/build.gradle.kts` sets `abortOnError = true` and `warningsAsErrors = true`, and `ci.yml` runs `lintDebug` in the verify job; checked 2026-11-22.

**Acceptance:** `app/build.gradle.kts` keeps `abortOnError` and `warningsAsErrors` true, and the "Build, test, lint" check runs `lintDebug`.

**Since:** 2026-09-06 · builder · migrated

### D-R174 · One project, one build

**Rule:** The committed build has one application id: no `applicationIdSuffix`.

**Why:** A `.debug` suffix let a migration check run against a leftover database and read as a pass either way.

**Evidence:** `findings-ledger.md` row 41. `app/build.gradle.kts` declares none and says why; checked 2026-11-22.

**Acceptance:** `app/build.gradle.kts` sets no `applicationIdSuffix`.

**Since:** 2026-09-06 · builder · migrated

### D-R175 · designkit's silent fallback

**Rule:** Before trusting a `designkit` fitness number, set `OPTIMIZATION_ENGINES_HOME` and check that `dk status` says `portfolio`.

**Why:** When `optimization-engines` is unreachable, `designkit` falls back to a local search that never evaluates `constraints`, so every number quoted before the check was computed with the constraints off.

**Evidence:** `findings-ledger.md` row 50.

**Acceptance:** none: a practice for design scoring runs.

**Since:** 2026-09-06 · builder · migrated

### D-R176 · Silent safe degradation is a defect

**Rule:** A guard that degrades safely must also be visible: the test is whether anyone can tell that it did, and a log line does not count.

**Why:** `postIfPermitted` refused to post without permission and logged one line; the projection cache published a partial pass as a whole one; weekend mode turned defer into skip on a sub-daily reminder. Each left the app looking healthy while doing less than it claims.

**Evidence:** `findings-ledger.md` row 58.

**Acceptance:** none: a review practice for every guard.

**Since:** 2026-09-07 · builder · migrated

### D-R177 · What a walk is for

**Rule:** A walk covers what the suite cannot construct, never what a screenshot can show. The owner finds a different class of defect than the builder, so neither replaces the other.

**Why:** In a few minutes of ordinary use the owner found a column no screen could set, a header no test compared, a row that only squeezes at phone width and a channel with the wrong defaults, after the automated half had passed.

**Evidence:** `findings-ledger.md` row 59.

**Acceptance:** none: a working practice for planning walks.

**Since:** 2026-09-13 · builder · migrated

### D-R178 · Port the reference app's tests first

**Rule:** When a reference implementation exists, its tests are ported and run against Emivita before its code is read.

**Why:** Reading the reference app's scheduler beside Emivita's produced 48 rows of prose and no defect; running its tests produced four failures in a minute.

**Evidence:** `findings-ledger.md` row 60; the ported tests are under `app/src/test/java/com/hazia/emivita/core/scheduling/medtimer/`.

**Acceptance:** none: a working practice for comparisons.

**Since:** 2026-09-13 · builder · migrated

### D-R179 · A false or null return is a failure

**Rule:** A call that reports failure by returning `false` or `null` is branched on at its call site.

**Why:** Five silent instances in one audit: a discarded `renameTo` deleted the good backup, another reported a recovery that had not happened, a null output stream turned a write into "Backup exported.", `resolveActivity` returned a component the app may not start, and a null password downgraded an encrypted export to plaintext.

**Evidence:** `findings-ledger.md` rows 23, 81 and 82.

**Acceptance:** none: a review practice; the Kotlin type system does not flag a discarded `Boolean`.

**Since:** 2026-10-12 · builder · migrated

### D-R180 · A gate proves it scanned

**Rule:** Every source-level gate asserts a lower bound on the files it read and has a self-test that feeds each of its checks a sample it must catch.

**Why:** A walk over a renamed or missing directory yields nothing and the gate goes green over code it never opened; a check that reported zero and one that ran on nothing look the same.

**Evidence:** `findings-ledger.md` row 82. Checked 2026-11-22: `HardcodedUiTextGateTest` has both; `DialogPairGateTest` has the lower bound only; `LiveControlBesideLabelGateTest` has the self-test only; `AlphaTintGateTest` has neither.

**Acceptance:** Each `*GateTest` under `app/src/test` holds a lower-bound assertion on its source count and a self-test.

**Since:** 2026-10-12 · builder · migrated

### D-R181 · A test crosses the defect's boundary

**Rule:** A test for a fix runs against the old code first and must fail there; a test that passes on the old code is not testing the fix.

**Why:** The drag test passed against the broken key because Robolectric delivers a whole touch block in one frame and the defect lived between frames; rotation and a lost Keystore key fail only where the real save, restore or key loss happens.

**Evidence:** `findings-ledger.md` row 83.

**Acceptance:** none: a practice for every fix; the pull request says which test failed before the fix.

**Since:** 2026-10-12 · builder · migrated

## Acceptance list

- R148 · Platform constraints: The merged release manifest declares no `INTERNET` permission; `releaseRuntimeClasspath` holds no `com.google.android.gms` or `com.google.firebase` artifact; `app/build.gradle.kts` has the minSdk, targetSdk, toolchain and identity of the table.
- R150 · Findings and evidence: `speccheck` on `docs/SPEC.md` reports no BLOCK or ASK finding, and `findings-ledger.md` is unchanged since the commit that moved it.
- R152 · Constraint check before shipping: The checks in R148's Acceptance pass on the batch's merge commit.
- R154 · Double hyphens in XML comments: `python tools/strings_review.py --check` passes, and the merged manifest builds.
- R155 · How changes land: `gh api repos/haziaferi/emivita/rulesets/24953901` lists the four required checks, `deletion` and `non_fast_forward`, and an empty `bypass_actors`.
- R156 · Public repository: `git log --format='%an %ae %cn %ce'` lists only `haziaferi` and the no-reply address, and a scan of the tracked files finds no personal name, email or contact.
- R157 · Comment length: `python tools/code_health.py --selftest` and `python tools/code_health.py --check` exit 0.
- R158 · Standing practices: `ScreenSweepTest` runs in the "Build, test, lint" check; the other three rows are practices with nothing to run.
- R165 · Tests run off UTC: Every scheduling test either sets `Europe/Rome` or a non-UTC zone of its own.
- R173 · Lint is a gate: `app/build.gradle.kts` keeps `abortOnError` and `warningsAsErrors` true, and the "Build, test, lint" check runs `lintDebug`.
- R174 · One project, one build: `app/build.gradle.kts` sets no `applicationIdSuffix`.
- R180 · A gate proves it scanned: Each `*GateTest` under `app/src/test` holds a lower-bound assertion on its source count and a self-test.

## Out of scope

### D-R182 · Release key and distribution

**Rule:** No private release key and no distribution beyond the owner's phone.

**Why:** There is no interest in distribution; release builds keep the shared debug key (R148).

**Acceptance:** none: a scope boundary.

**Not:** A private release key: the committed debug key is public, so anyone can sign an update that installs over the app and keeps its database; the owner accepted that on 2026-10-13 and kept it on 2026-10-20.

**Since:** 2026-10-20 · owner · migrated

