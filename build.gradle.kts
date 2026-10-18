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
    // AGP 9 (§7 R94, the owner's call, reversing the AGP-8 pin once Compose
    // 1.12, Navigation 2.10 and core-ktx 1.19 required AGP 9.1 and compileSdk
    // 37). AGP 9 compiles Kotlin itself and registers the `kotlin` extension,
    // so org.jetbrains.kotlin.android is gone: applying it on top fails.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}