# Emivita — operating notes

`docs/SPEC.md` is the source of truth for this project: §7 is the finding
ledger, §7.1 the rules distilled from it, §10 the working agreements. This
file exists only because §10 is 185 KB into that document and nothing loads
it. **It carries commands, not reasoning** — where the two ever disagree,
SPEC.md wins and this file is the one that is wrong.

## Verifying a build (§10, §7 R57)

Three consecutive green builds were reported here over a compile that was
failing. Both signals agreed because they shared a cause.

```bash
rm -rf app/build/test-results
./gradlew assembleDebug testDebugUnitTest lintDebug > build.log 2>&1; echo "EXIT=$?"
grep -E "^e: |BUILD " build.log
ls -la app/build/test-results/testDebugUnitTest/*.xml | tail -3
```

- **Never put the build on the left of a pipe** when its exit code is what
  you are checking. `./gradlew … | tail` reports *tail's* status, and so does
  the `$?` after it, and so does a background task's "exit code 0" summary.
- **Clear `app/build/test-results` before quoting test counts.** It persists
  across runs, so a failed compile writes no new XML and parsing the directory
  returns the *previous* run's numbers. Print the newest file's mtime beside
  the counts.
- `grep` the log explicitly. "No visible errors" is not an assertion of
  success.

R8 runs only on release, so the triad cannot see what it strips. Glance keys
placed widgets by class name and R8 merges the two widgets without the keep
in `proguard-rules.pro` (§7 R69); the gate reads the DEX, and CI runs it:

```bash
./gradlew :app:assembleRelease && python tools/r8_check.py
```

CI on this private repository has been billing-blocked since 2026-09-13
(every run dies in 3 s), so the workflows are verified locally instead
(§7 R74): every `uses:` pinned to a commit SHA that resolves to its `# vX`
comment, every `with:` key an input of the action's own `action.yml`, and
actionlint (a binary under `tools/bin/`, gitignored; fetch a release from
rhysd/actionlint if it is missing). `--resolve` needs the network.

```bash
python tools/workflow_check.py --resolve
```

The four palettes are derived, not typed (§7 R76): `tools/palette_derive.py`
prints `Color.kt`'s palette objects (`--kotlin`) and SPEC Appendix A
(`--tables`) from three agreed colours per register, and `--check` fails
when the appendix has drifted from the tool. Edit the seeds in the tool,
regenerate both, and let `PaletteSpecConsistencyTest` confirm the code.

```bash
python tools/palette_derive.py --check
```

Code health is computed, not eyeballed (§7 R82): `code_health.py --check` exits 1
on a comment block over three sentences, an import no longer used, or a
top-level declaration referenced only where it is declared. `--selftest` proves
each detector can still fire — run it before trusting a zero. `split_plan.py`
scores a proposed split of an oversized file (`--explain` the current one, then
`--solve` refines every seed); its cost counts boundary-crossing references,
`private` symbols that would need `internal`, lines over a 450-line cap, part
count and size spread.

```bash
python tools/code_health.py --selftest && python tools/code_health.py --check
python tools/code_health.py --graph app/src/main/java/.../Screen.kt /tmp/g.json
python tools/split_plan.py /tmp/g.json --solve
```

- `getValue`/`setValue` back every `var x by remember { … }` and are resolved by
  convention, never written out: `CONVENTION_IMPORTS` exempts them, and no
  textual census may call such an import unused. The compiler is the verifier.
- A `${…}` span inside a string is live Kotlin. Blanking it with the literal
  hides real references; `code_health.py`'s lexer reads comments and literals in
  one pass for that reason.

## On-device tests (§9 #20)

`app/src/androidTest/` runs against the real SQLCipher database, AlarmManager
and NotificationManager. It needs a **physical ARM device** attached — the
build ships no x86 libs, so no emulator and no CI job can run it.

```bash
adb devices
rm -rf app/build/outputs/androidTest-results
./gradlew connectedDebugAndroidTest > device.log 2>&1; echo "EXIT=$?"
```

Results land in `app/build/outputs/androidTest-results/connected/`. Same
rule as above: clear the directory first, read the real exit code. The suite
installs a debug build over whatever is on the phone and writes (then deletes)
its own rows in the real database — check what is installed before running it
on a device that is not the test device.

`connectedDebugAndroidTest` **uninstalls the app when it finishes, and the
uninstall deletes its data** — every suite run from R59 to R75 wiped the
owner's phone (§7 R75). On a phone whose data matters, keep the APKs:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true > device.log 2>&1; echo "EXIT=$?"
```

To seed or clean a device pass without the suite's uninstall, install both
APKs and run the gated class directly (§7 R75). The test APK borrows the
Kotlin runtime from the app, which R8 renames in a release build, so this
only runs against a **debug** install — `adb install -r` the release back
afterwards; the data survives both (§7 R76).

```bash
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.hazia.emivita.PassSeedTest -e seedPass true com.hazia.emivita.test/androidx.test.runner.AndroidJUnitRunner
```

Pseudolocale sweep (§7 R59) — every resource string renders accented, every
leftover literal renders plain English. The debug build has
`isPseudoLocalesEnabled = true`; the locale is set per app, not system-wide:

```bash
adb shell cmd locale set-app-locales com.hazia.emivita --locales en-XA   # or ar-XB, or it (§9 #28); "" to reset
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml      # the real accessibility tree
adb exec-out screencap -p > screen.png
```

en-XA glyph traps when matching text in a dump: b→ɓ, f→ƒ, v→V, x→Cyrillic х.
A dump does not surface `onClickLabel`; that half stays Robolectric-only.

The walk above is scripted (§7 R81): a route of string *keys* resolved through
`values-<lang>`, one screenshot and one dump per stop under `build/ui_walk/`,
and `--a11y` lists the nodes TalkBack has nothing to say about (labels are
matched by bounds, since uiautomator flattens Compose differently from
TalkBack; a control at the bottom edge is clipped and reads as unlabelled).
Needs the seed for the detail stops.

```bash
python tools/ui_walk.py --route all-screens --a11y            # --locale it|en-XA|"" first if needed
python tools/ui_walk.py --a11y build/ui_walk                  # the report alone, on saved dumps
```

## User-visible text (§9 #19)

Every string a user sees lives in `app/src/main/res/values/strings.xml`,
and every translation in `values-xx/strings.xml` is **complete or it does not
ship**: `TranslationCompletenessTest` fails on a missing or extra key, a
changed format argument, a plurals without one/other, or a value left in
English (its `identical` list is the record of deliberate same-word cases).
Italian needs `many` as well as `one`/`other` (CLDR 42). A new language is one
folder plus one entry in `AppLocale.SHIPPED`.
`HardcodedUiTextGateTest` fails the build on a literal handed to `Text()`,
`contentDescription`, a notification builder, or a `title`/`label` argument
in the UI layers. Its blind spot is a literal assigned to a `val` first;
en-XA on the device (`isPseudoLocalesEnabled`) is the complement.

- Composable: `stringResource(R.string.x)`. Callback or coroutine:
  `LocalResources.current.getString(...)` captured at composition — not
  `LocalContext`, which Compose lint rejects. Top-level `val` or enum:
  `@StringRes Int`, resolved at the render site.
- Deliberately not resources, do not "fix": `csvStatusLabel` (CSV is data),
  `MedPalette.displayName()` (Italian names, pinned to SPEC Appendix A), `CardLabels.ENGLISH`
  (test-pinned), `"Me"` (a stored sentinel), `MedicineIconOption.key`
  (persisted). Each says why at its declaration.
- Lint will demand real dashes, ellipses and `<plurals>` the moment text
  lands in XML. Fix them; never baseline them.
- `LiveControlBesideLabelGateTest` fails the build on a `Switch`/`Checkbox`
  that is its own tap target beside a label: make the row `toggleable` and
  the control `onCheckedChange = null` (Stage B's one-node pattern).

A translation's *wording* is reviewed with `tools/strings_review.py` (§7 R81):
`--check` is deterministic (placeholders the test's regex misses, glyph
consistency, stale `identical` entries, `--` in comments) and exits 1;
`--sheet` writes every key by screen with EN beside IT and flags that aim the
reading (length ratio, Title Case, gendered forms, R67 glossary). The sheet
belongs in a scratch directory, not the repository.

```bash
python tools/strings_review.py --check
python tools/strings_review.py --sheet /tmp/it_sheet.md
```

## The reference app (§5, §7 R60, Appendix E)

medTimer v1.24.6 (MIT) is the reference. `docs/SPEC.md` Appendix E is the
285-row code-level diff, with the ranked adoption list in E.4 and the
`import-as-is` candidates in E.1 — a copy needs `THIRD_PARTY_NOTICES.md`
with the MIT notice, created at the first copy. Thirty of medTimer's
scheduler tests run against Emivita's engine in
`app/src/test/.../core/scheduling/medtimer/`; when scheduling changes,
they say whether parity or a documented divergence moved.

Appendix F is the same exercise against the rest of the field (HomeMedkit,
MediTrak, Did I Take My Meds?, Daily Pill, Pill Time, Dosage, the Medisafe /
MyTherapy listings) — **all GPL: ideas only, never code**. F.5 is the
re-ranked adoption list; §9 #24–#26 are the owner's picks from it. The
trees are under `Downloads/xapps/` beside `Downloads/medTimer-main/`.

## Editing files

- Line endings are enforced by `.gitattributes`; do not fight it. If a patch
  script writes files, open them in binary mode or pass `newline=""` — Python's
  text mode on Windows turns a three-line edit into a 990-line diff (§7 R57).
- XML comments never use `--` in the body (§10). Kotlin comments are fine.
- Prefer editing a file to regenerating it. Rewriting `Color.kt` wholesale is
  what silently dropped `NeutralMedicineAccent` and its 14 call sites; the
  palette objects are the one part of it the tool regenerates, and
  `NeutralMedicineAccent` sits below them for exactly that reason.

## Before saying something is done

- A claim about behaviour is either enforced by a test or labelled unverified
  (§7.1 rule 5). `PaletteSpecConsistencyTest` is the pattern: it asserts the
  spec's own tables against what the app resolves.
- Findings, audits and rationale go in `docs/SPEC.md` — §7's ledger for the
  finding, an appendix for the reasoning. Not in a new markdown file (§10).
- The §7 ledger is append-only. Mark a superseded row via its
  `Superseded by` column; never edit what a row originally said.
