# Emivita — setup

This is now a buildable Gradle project and a git repository, not a loose source
drop. The Gradle wrapper (`gradlew`, `gradle/wrapper/*`) and `local.properties`
are present, and both `./gradlew assembleDebug` and `./gradlew assembleRelease`
run to completion from this directory with no Android Studio involvement.

**Build it from here:**

    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
    ./gradlew testDebugUnitTest    # JVM unit, Room migration and Compose UI tests
    ./gradlew lintDebug            # gated: fails on any finding not in the baseline

The Compose UI tests run in that same task, under Robolectric, rather than in
an `androidTest` source set: `.github/workflows/ci.yml` runs the three tasks
above and has no device attached, so an instrumented suite would never have
executed there (docs/SPEC.md §7 R47). Two settings make that work and are
easy to mistake for noise -- `testOptions.unitTests.isIncludeAndroidResources`
in `app/build.gradle.kts`, without which Robolectric gets a stub manifest and
cannot resolve the Compose host activity, and `app/src/test/resources/
robolectric.properties`, which pins the Robolectric SDK image (4.14.1 ships
none for this project's targetSdk 36) and the stock `Application` (the real
one loads a SQLCipher native library that cannot load on the JVM). Each file
says the same in its own comments.

`lintDebug` is a gate, not a report: `app/build.gradle.kts`'s `lint` block sets
`warningsAsErrors` against `app/lint-baseline.xml`, so a new warning fails the
build rather than being written to a file nobody opens (spec §7 R23 lost a real
finding that way; R26 put the gate up). The baseline holds what existed the day
it went up. Version-currency checks are demoted to informational instead of
baselined, because they fire on calendar time rather than on the change under
test. `.github/workflows/ci.yml` runs these same three tasks on push and pull
request.

Both variants share one `applicationId` (`com.hazia.emivita`; `debug` sets no
`applicationIdSuffix`) and are signed with the shared debug keystore at
`keystores/my-shared-debug.keystore`, checked in deliberately so builds from any
machine share one signing identity. Together those two facts are what let any
build install over any other — this sentence used to credit the shared signer
alone, which was necessary and not sufficient: while `debug` carried a suffix
the two variants were separate apps and could not replace each other at all
(docs/SPEC.md §7 R41). The consequence to know is that they cannot be installed
side by side; that is intended, since this is one app. Its password
is the published Android default, so that identity is not secret — see the
comment on `buildTypes.release` in `app/build.gradle.kts` for the trade that
accepts and when to replace it.

## One-time setup (only if starting from the zip on a fresh machine)

Skip this entirely in a clone of the repo — it describes rebuilding the project
shell from scratch, which is already done here.


1. **Android Studio → New Project → Empty Activity (Compose)**
   - Name: Emivita (or whatever you rename it to)
   - Package name: `com.hazia.emivita`
   - Minimum SDK: **API 30 (Android 11)**
   - Build configuration language: **Kotlin DSL**
2. Close the project once it finishes generating.
3. Copy everything from this zip **into** that generated project folder, overwriting
   files at the same path (`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
   `app/src/main/AndroidManifest.xml`, everything under `app/src/main/java` and
   `app/src/main/res`).
4. **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK →
   set to JDK 21.** Android Studio bundles JDK 21 itself, so this is usually just
   picking "Embedded JDK" from the dropdown rather than installing anything.
   (This is the JDK that *runs the Gradle daemon*, and it is no longer only an
   IDE-local setting: `gradle/gradle-daemon-jvm.properties` is tracked in this
   repo and pins `toolchainVersion=21` for the daemon, which the tracked Gradle
   8.13 wrapper honours. Setting it in Studio is the belt-and-braces half, for
   when the IDE supplies its own daemon criteria. `app/build.gradle.kts`
   separately pins `kotlin { jvmToolchain(21) }`, which
   covers the JDK that runs the *compiler tasks* specifically — so a mismatch
   here now fails loudly and specifically rather than surfacing as a vague
   "AGP requires Java 17/21" error with nothing in the repo pointing at the
   fix. Independent of the app's own `sourceCompatibility`/`jvmTarget`, which
   stay at 17 — see the comments in `app/build.gradle.kts` if you're curious
   why those aren't the same number.)
5. Reopen in Android Studio → **Sync Now** when prompted.
6. If Android Studio offers an AGP upgrade, accept it as long as it stays on the
   8.x line (8.13.2 or a newer 8.x patch) — this project intentionally avoids AGP
   9.x, which removes compatibility with the `org.jetbrains.kotlin.android`
   plugin this build uses and would force a much bigger migration than a
   personal single-module app needs.
7. First build will pull the dependencies listed in `app/build.gradle.kts`
   (Room, SQLCipher, DataStore, Glance, biometric, Vico, zip4j,
   kotlinx-serialization, etc.) from Google's Maven — needs network access
   once, same as any new Android project. **No direct WorkManager dependency,
   no Health Connect, no `google-services`/GMS** — all three were considered
   or briefly present in earlier batches and have since been dropped for
   good on the app's own side (see `docs/SPEC.md` §1, §7 findings R4/R5/R14,
   and §9 #13): Health Connect conflicted with the de-googled/no-Play-services
   constraint, and every
   background-work need (projection-cache recompute, auto-sync writes) is
   handled by a debounce + explicit-trigger design instead of a job
   scheduler. (`androidx.work` itself still ends up in the final APK as a
   transitive dependency of `androidx.glance:glance-appwidget`, which uses it
   internally for widget-session management — outside the app's control and
   unrelated to the periodic-job concern R5 was actually about; the app's own
   code never calls into it. `docs/SPEC.md` §7 R14.)
8. First build also creates an `app/schemas/` folder — that's Room's exported
   schema JSON (used for migration tests, §7 finding R11), not something to
   delete.

## Project status

Not tracked here, on purpose. `docs/SPEC.md` is the source of truth: §9's
checkmarks answer "is X built?", §7 is the audit-findings ledger, and §8
carries the few notes §9's one-line entries can't.

This file used to carry a ~110-line status narrative alongside those. It was
a second copy of state `docs/SPEC.md` already owned, and it drifted the way
second copies do: it claimed the backup-password path was kept out of
un-scrubbable `String`s "end-to-end" when §7 R14 had already recorded that
Compose's String-backed text fields make that unachievable, and it listed
two Room migration tests after a third existed. §8 hit the same failure
earlier and removed its own copy for the same reason — the note it left
behind ("two places to update and only one of them ever updated") applies
here identically.

What belongs in this file is everything above it: how to build this, how it
is signed, and which setup steps bite on a fresh machine.
