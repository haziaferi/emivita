# Emivita v0.1.0 (versionCode 1) — archived release baseline

**Why this exists:** an installed predecessor to upgrade *from*. Room
migration tests (`MigrationFrom1To2Test` and friends) build their "before"
state from hand-written fixtures, which proves the SQL is correct but never
exercises a real in-place upgrade of a database written by a real previous
build. `docs/SPEC.md` §9 #1 / §7 R11 record that gap as unclosable because
no v1 APK was ever kept. This is that APK. Keep it.

**Read the "v1" in that paragraph as `versionCode` 1, not Room schema 1.**
The two are unrelated numbers and this APK ships schema **4** (see the table
below), so installing it and upgrading exercises the *next* migration, 4→5,
and never `MIGRATION_1_2` — which stays fixture-tested only, because no
build of this source tree has ever written a v1, v2 or v3 database.
`docs/SPEC.md` §9 #1 spells the same caveat out; it is repeated here so this
file cannot be read on its own and mistaken for a way to validate the three
migrations that already exist.

**Do not rebuild and overwrite this file.** Its value is entirely that it is
the artifact a real database was created by. A regenerated APK from a later
tree is a different thing wearing the same name.

## Provenance

| | |
|---|---|
| Built from commit | `72010105ada188a7a9e51518a665e835b4b30c48` |
| Built on | 2026-09-01 |
| applicationId | `com.hazia.emivita` (no suffix — release, not the `.debug` variant) |
| versionName / versionCode | 0.1.0 / 1 |
| Room schema version | 4 (`app/schemas/com.hazia.emivita.core.data.db.EmivitaDatabase/4.json`) |
| minSdk / targetSdk / compileSdk | 30 / 36 / 36 |
| Build type | release — R8 minify + resource shrinking on, `isDebuggable = false` |
| ABIs | arm64-v8a, armeabi-v7a |
| Size | 10,665,093 bytes |
| SHA-256 | `5e4ed549bc1445c720cfad35f2344e982a1b411af9867c0ee08ce9ae223e9a15` |
| Signer (V2) SHA-1 | `d746d7497112959fbf944816e74b091aba06997b` |
| Signing identity | shared debug keystore, `keystores/my-shared-debug.keystore` |

The signer matters as much as the APK: an upgrade install only succeeds if the
new APK is signed by this same identity. If that keystore is ever replaced with
a real release key, this baseline stops being upgradable-from and a fresh
baseline has to be cut under the new identity.

## mapping-vc1.txt.gz

R8's obfuscation map for this exact APK (62MB uncompressed). Without it a stack
trace from this build is unreadable. Regenerating it is impossible once the
build directory is cleaned — R8 output is not reproducible across AGP versions.

    gunzip -c mapping-vc1.txt.gz > mapping.txt

## Using it as a migration baseline

1. `adb install dist/vc1/emivita-0.1.0-vc1-release.apk`
2. Create real data — a medicine, a reminder, a few logged doses. The point is
   a database written by this build's own code paths, not an empty one.
3. Build the new version and `adb install -r` it. A bumped `versionCode` is
   good practice but not required — `-r` reinstalls over an equal one, which is
   how the run recorded below was done.
4. A `SQLiteException` or a wiped database means the migration is wrong. An
   in-place upgrade that keeps the data means it is right — which is the thing
   fixture-based tests cannot tell you.

**Any build upgrades this one now.** This section used to end with a warning
that the release build "installs alongside any `com.hazia.emivita.debug` build
(separate applicationId, separate database)", and that step 2's data had to be
created in *this* app rather than the debug one. That warning described a real
split and quietly made this artifact useless for its own stated purpose: the
build anyone actually develops with was suffixed, so it could never upgrade
this APK, and the only thing that could was a fresh minified release. The
suffix is gone (see `app/build.gradle.kts`'s `debug` block and docs/SPEC.md §7
R41) — one applicationId, so an ordinary debug build upgrades this in place,
which is what steps 1–4 always assumed.

**Exercised 2026-09-04** (OnePlus 9 Pro, OxygenOS / Android 14), the first time
this was possible: this APK installed and launched, then a debug build carrying
schema 5 installed over it with `-r`. No signature prompt, `libsqlcipher.so`
loaded from `arm64-v8a`, no `IllegalStateException` from Room's schema
validation, app alive afterwards. That is `MIGRATION_4_5` run against a
database written by this build's own code — the check the fixture tests
structurally cannot perform.
