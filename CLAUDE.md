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

## Editing files

- Line endings are enforced by `.gitattributes`; do not fight it. If a patch
  script writes files, open them in binary mode or pass `newline=""` — Python's
  text mode on Windows turns a three-line edit into a 990-line diff (§7 R57).
- XML comments never use `--` in the body (§10). Kotlin comments are fine.
- Prefer editing a file to regenerating it. Rewriting `Color.kt` wholesale is
  what silently dropped `NeutralMedicineAccent` and its 14 call sites.

## Before saying something is done

- A claim about behaviour is either enforced by a test or labelled unverified
  (§7.1 rule 5). `PaletteSpecConsistencyTest` is the pattern: it asserts the
  spec's own tables against what the app resolves.
- Findings, audits and rationale go in `docs/SPEC.md` — §7's ledger for the
  finding, an appendix for the reasoning. Not in a new markdown file (§10).
- The §7 ledger is append-only. Mark a superseded row via its
  `Superseded by` column; never edit what a row originally said.
