// Top-level build file. If you scaffold the project shell via Android Studio's
// "New Project -> Empty Activity (Compose)" wizard first, it will generate this
// file with its own current AGP/Kotlin plugin versions -- keep those and just
// make sure the two plugin ids below (ksp, compose-compiler) are present, since
// Room's annotation processor and the Kotlin 2.x Compose compiler both need them
// as separate plugins now (no longer a composeOptions block).

// AGP 8.13.2 signs and packages with Bouncy Castle 1.79, which has four
// advisories fixed by 1.85 (§7 R86). Build-only, never in the APK; raised so
// the dependency review stays green and the signer runs patched code.
buildscript {
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcutil-jdk18on:1.86")
        }
    }
}

plugins {
    // AGP 8.7.2 only supports compileSdk up to ~35; compileSdk 36 needs AGP 8.9.1+.
    // Pinned to 8.13.2 (the last AGP 8.x release) rather than jumping to AGP 9.x:
    // AGP 9 makes Kotlin support built-in and drops compatibility with the
    // org.jetbrains.kotlin.android plugin below, which would force a much larger
    // migration (new DSL, KMP-style module split) than a personal, single-module
    // app needs. 8.13.2 already supports compileSdk/targetSdk 36.1.
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}