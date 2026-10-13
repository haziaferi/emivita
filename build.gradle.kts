// Top-level build file. If you scaffold the project shell via Android Studio's
// "New Project -> Empty Activity (Compose)" wizard first, it will generate this
// file with its own current AGP/Kotlin plugin versions -- keep those and just
// make sure the two plugin ids below (ksp, compose-compiler) are present, since
// Room's annotation processor and the Kotlin 2.x Compose compiler both need them
// as separate plugins now (no longer a composeOptions block).

// Patched versions for libraries AGP's own tooling brings (§7 R86): the
// signer's Bouncy Castle, gRPC's Netty, sdk-common's commons-compress,
// bundletool's jose4j, jetifier's jdom2. Each had advisories; none of them is
// in the APK. app/build.gradle.kts applies the same table to the app's
// configurations (lint, tests, the device-test runner); keep the two equal.
buildscript {
    val patched = mapOf(
        "org.bouncycastle:bcprov-jdk18on" to "1.86",
        "org.bouncycastle:bcpkix-jdk18on" to "1.86",
        "org.bouncycastle:bcutil-jdk18on" to "1.86",
        "org.apache.commons:commons-compress" to "1.28.0",
        "org.bitbucket.b_c:jose4j" to "0.9.6",
        "org.jdom:jdom2" to "2.0.6.1",
    )
    configurations.classpath {
        resolutionStrategy.eachDependency {
            val key = "${requested.group}:${requested.name}"
            when {
                requested.group == "io.netty" && !requested.name.startsWith("netty-tcnative") ->
                    useVersion("4.1.138.Final")
                key.startsWith("com.google.protobuf:protobuf-") && requested.version.orEmpty().startsWith("3.") ->
                    useVersion("3.25.9")
                key in patched -> useVersion(patched.getValue(key))
            }
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