plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

// Pins the JDK used to actually RUN the Kotlin/Java compiler tasks for this
// module to 21 -- distinct from compileOptions and compilerOptions, which
// target compiled bytecode (deliberately kept at 17, see those comments).
// Without this, the project relies entirely on whichever JDK happens to be
// configured in Android Studio's own Gradle-JDK setting (README's manual
// setup step 4) -- invisible to anyone opening the project on a different
// machine or in CI, silently breaking there with no signal in the codebase
// pointing at the fix. This pins only the toolchain used for compilation,
// which Gradle can auto-detect from an already-installed JDK 21 without
// needing network access. The Gradle *daemon's* own JVM is pinned separately
// by gradle/gradle-daemon-jvm.properties (toolchainVersion=21), which also
// travels with the repo -- so neither half depends on a machine-local IDE
// setting any more.
kotlin {
    jvmToolchain(21)
    // Bytecode stays at 17, matching compileOptions below. The old
    // `android { kotlinOptions { jvmTarget } }` is an error from Kotlin 2.4 (§7 R87).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.hazia.emivita"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hazia.emivita"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Instrumentation runner for app/src/androidTest (spec §9 #20). These
        // tests run only on a physical ARM device -- see the abiFilters block
        // below, which deliberately ships no x86 native libs -- so they are
        // never part of CI's `verify` job. `./gradlew connectedDebugAndroidTest`
        // with the phone attached is the whole invocation.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only the two ABIs a real phone can load. The x86/x86_64 variants of
        // libsqlcipher.so are ~4.4MB of the release APK and are reachable only
        // from an emulator, which this app is never developed against (device
        // testing happens on physical hardware). Drop this block, do not add
        // an x86 filter back, if emulator testing ever becomes part of the loop.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Stubbed android.* calls return 0/null instead of throwing "not
        // mocked". Needed because the JVM test suite exercises classes that log
        // through android.util.Log on their failure paths --
        // OccurrenceProjectionCache's recompute guard is the one that matters:
        // it exists so a bad row costs one pass instead of the loop, and it
        // would itself have thrown, from inside the handler, in any test that
        // reached it. Only the migration and repository tests run under
        // Robolectric; the scheduling tests deliberately do not.
        unitTests.isReturnDefaultValues = true

        // Required by the Compose UI tests (spec §7 R47). Without it AGP hands
        // Robolectric a synthesised stub manifest with no resources, and
        // createComposeRule() fails to resolve the host activity that
        // ui-test-manifest contributes to the merged debug manifest
        // ("Unable to resolve activity for Intent ... ComponentActivity").
        // It also makes real resources readable from the pure-JVM tests, which
        // the migration and repository tests neither need nor are harmed by.
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
    }

    // §9 #28 (Appendix F F-31 / HM-35): AGP writes _generated_res_locale_config
    // from the values-* folders and points android:localeConfig at it, so
    // Android 13+'s per-app language page lists exactly the shipped languages.
    // The base locale comes from res/resources.properties.
    androidResources {
        generateLocaleConfig = true
    }
    // An in-app language choice needs every language in the installed
    // package (lint AppBundleLocaleChanges): no per-language bundle splits.
    // Moot for a sideloaded APK (§1: no Play Store), right for a bundle.
    bundle {
        language { enableSplit = false }
    }

    // EmivitaDatabase (@Database) declares exportSchema = true so future Room
    // migrations have a baseline schema to diff against. Without this KSP arg,
    // Room has nowhere to write that schema JSON and silently skips the export.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }


    // Shared DEBUG signing identity, checked in deliberately. Without this,
    // every machine (and every fresh ~/.android) generates its own throwaway
    // debug key, so a debug build from a second machine cannot install over
    // one from the first -- Android rejects the upgrade as a signature
    // mismatch and forces an uninstall, taking the app database with it.
    // The password below is the published Android debug default, not a secret.
    // A genuine RELEASE keystore is a separate, uncommitted thing (.gitignore).
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystores/my-shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            // Signed with the SHARED DEBUG identity on purpose, not with a
            // separate secret release key. This app is sideloaded onto the
            // owner's own devices and never goes to a store, and an unsigned
            // release APK cannot be installed at all -- which blocks both
            // on-device verification of the minified build and keeping an
            // archived APK to upgrade FROM when testing a Room migration
            // in place.
            //
            // READ THIS BEFORE GIVING THIS APP TO A SECOND PERSON.
            //
            // The trade is bigger than "anyone could build an APK that
            // installs over this one", which is how this comment used to put
            // it. AndroidKeyStore aliases are scoped to (package name,
            // signing identity) and survive an in-place upgrade. This
            // keystore is committed and its password is the published Android
            // default, so anyone holding this repository can build an APK
            // that Android accepts as an *upgrade* -- no signature-mismatch
            // prompt, because an identical signer is the entire point of
            // sharing this identity. That upgrade inherits the app's data
            // directory AND its Keystore aliases (emivita_db_wrap_key,
            // emivita_autosync_wrap_key), so it can call
            // PassphraseProvider.getOrCreatePassphrase() and
            // AutoSyncPassphraseStore.get() and read out both the SQLCipher
            // key and the user's auto-sync backup password in plaintext.
            //
            // So the whole at-rest design -- 256-bit SecureRandom key, a
            // non-exportable Keystore wrap, separate blast radii for the DB
            // key and the backup password -- is in practice only as strong as
            // control of keystores/my-shared-debug.keystore. It still holds
            // against the threat it was built for (a lost or stolen device
            // reading the raw .db file); it does not hold against installing
            // a build someone else produced. Note this is also why
            // res/xml/data_extraction_rules.xml's reasoning that a copied
            // database "arrives as ciphertext the new install can never open"
            // is true for a device-to-device transfer and NOT true for an
            // upgrade install under this identity.
            //
            // Acceptable while the only installs are the owner's own, on the
            // owner's own devices. Before this reaches anyone else, in
            // preference order:
            //  1. Give `release` a real keystore read from a gitignored
            //     keystore.properties, and keep this shared identity for
            //     `debug`, where cross-machine reinstalls without wiping the
            //     database are what it actually buys. Cut a fresh dist/
            //     baseline under the new identity; the archived vc1 stays
            //     valid for migration testing under the old one.
            //  2. If one identity has to stay, add
            //     setUserAuthenticationRequired(true) to the
            //     KeyGenParameterSpec in
            //     AutoSyncPassphraseStore.getOrCreateWrapKey(), so the backup
            //     password at least needs a biometric to unwrap. That has a
            //     real cost on the debounced auto-sync write path and is a
            //     design decision, not a one-line edit.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false

            // Pseudolocalization (spec §9 #19 Stage A). Builds `en-XA`, which
            // renders every string that actually came from a resource as
            // [Ácçéñtéd Ténnnxt ...] with ~30% padding, and `ar-XB`, which is
            // right-to-left. Both are generated from the default strings at
            // build time; nothing is translated and no locale is shipped.
            //
            // This is the instrument that makes the extraction checkable
            // *while* it happens rather than afterwards: switch the device to
            // en-XA and any string still hardcoded in Kotlin renders plain
            // English amid accented text, so an incomplete extraction is
            // visible on the screen instead of being a claim about the source.
            // The padding also surfaces layouts that only fit English, which
            // is the other half of what a translation breaks.
            //
            // Debug only. It costs two generated resource sets in the debug
            // APK and nothing in release.
            isPseudoLocalesEnabled = true
            // No applicationIdSuffix, deliberately. The usual reason for one is
            // to run debug and release side by side; here that is the thing to
            // avoid. A suffix makes them two unrelated apps to Android -- two
            // package names, two data directories, two sets of AndroidKeyStore
            // aliases, two sets of alarms -- and this project is one app.
            //
            // It also broke two things that were written down as true. The
            // archived baseline in dist/vc1/ exists, by its own MANIFEST, to be
            // "an installed predecessor to upgrade from" so that a real in-place
            // Room migration can be exercised; it is com.hazia.emivita, so a
            // suffixed debug build could never upgrade it and the artifact's
            // entire purpose was unreachable from the build anyone actually
            // develops with. README.md's claim that both variants "can install
            // over each other" was false for the same reason -- the shared
            // signing identity is necessary for that and was never sufficient.
            // Both are true now.
            //
            // The cost is that debug and release cannot be installed at once.
            // That is the intended trade: they are one app, and the release
            // variant is built for archiving and for verifying the minified
            // build, not for running alongside. See docs/SPEC.md §7 R41.
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // The lint gate (spec §7 R26). Until this block existed, lintDebug
    // reported and the build carried on regardless: R23 found a real
    // DataExtractionRules warning that had sat unread in the report through
    // an entire audit cycle. Errors already aborted by default; what was
    // missing was any consequence for a *warning*, which is the severity the
    // finding that got through actually had.
    lint {
        abortOnError = true
        warningsAsErrors = true

        // Records the findings that existed the day the gate went up, so the
        // gate is about NEW findings rather than a demand to clear the
        // backlog first. Regenerate deliberately with:
        //     ./gradlew lintDebug -Dlint.baselines.continue=true
        // after deleting the file -- never as a reflex to make a red build
        // green, which is the one habit that turns a baseline into a way of
        // never reading lint again.
        baseline = file("lint-baseline.xml")

        // Demoted rather than baselined, because these three fire on calendar
        // time instead of on the diff: they mean "something newer exists
        // upstream", so a month from now they would fail a build whose commit
        // touched nothing related. A gate that goes red for reasons outside
        // the change under test is a gate people switch off. Informational
        // keeps them visible in the report -- which is the whole R23 lesson --
        // without letting them block. OldTargetApi is here for the same
        // reason: targetSdk stays 36 by decision while compileSdk is 37 for
        // the libraries (§7 R94).
        // AndroidGradlePluginVersion is the same kind: "a newer AGP or Gradle
        // exists", which failed the AGP 9 build on Gradle 9.8.0 the week 9.8.1
        // shipped (§7 R94).
        informational += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi", "AndroidGradlePluginVersion")

        // CI uploads these as artifacts; the XML is also what any future
        // tooling would parse.
        xmlReport = true
        htmlReport = true
        textReport = true
    }
}

// The same patched versions as the root build.gradle.kts (§7 R86), for the
// app's own configurations: lint and the tests run AGP's tooling too, and the
// device-test runner (com.google.testing.platform) brings Netty 4.1.93 and
// protobuf 3.24.4. None of it is in the APK. Keep the two tables equal.
val patchedVersions = mapOf(
    "org.bouncycastle:bcprov-jdk18on" to "1.86",
    "org.bouncycastle:bcpkix-jdk18on" to "1.86",
    "org.bouncycastle:bcutil-jdk18on" to "1.86",
    "org.apache.commons:commons-compress" to "1.28.0",
    "org.bitbucket.b_c:jose4j" to "0.9.6",
    "org.jdom:jdom2" to "2.0.6.1",
)
configurations.configureEach {
    resolutionStrategy.eachDependency {
        val key = "${requested.group}:${requested.name}"
        when {
            requested.group == "io.netty" && !requested.name.startsWith("netty-tcnative") ->
                useVersion("4.1.138.Final")
            key.startsWith("com.google.protobuf:protobuf-") && requested.version.orEmpty().startsWith("3.") ->
                useVersion("3.25.9")
            key in patchedVersions -> useVersion(patchedVersions.getValue(key))
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.android.material:material:1.14.0")

    // Room + SQLCipher (encrypted local DB)
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    // IMPORTANT: net.zetetic:android-database-sqlcipher is the OLD, now-deprecated
    // artifact, and its classes live in the `net.sqlcipher.database` package.
    // EmivitaDatabase.kt imports
    // `net.zetetic.database.sqlcipher.SupportOpenHelperFactory`, which belongs
    // to the NEW artifact below (`sqlcipher-android`). The old artifactId plus
    // that import would not resolve at all. sqlcipher-android is also the only
    // one of the two still receiving updates, including 16KB-page-size-
    // compatible native libraries.
    //
    // Pinned to 4.17.0, not the current 4.18.0 latest: 4.18.0's AAR metadata
    // requires consumers to compile against API 37, and checkReleaseAarMetadata
    // failed outright on compileSdk 36 -- confirmed by trying it, not assumed.
    // The AGP 9 migration (§7 R94) lifted that gate; the bump itself is its own
    // change, with a phone check that the existing database still opens.
    //
    // On upgrading this at all: SQLCipher keeps its on-disk format stable across
    // a 4.x series, and this app hands SupportOpenHelperFactory a raw key
    // (PassphraseProvider's `x'...'` literal) rather than a passphrase, so it
    // isn't exposed to KDF parameter changes either -- an existing database
    // keeps opening. That is reasoning, not a test result: nothing in the unit
    // suite touches SQLCipher at all (the Room tests build an unencrypted
    // in-memory database precisely because this native library won't load under
    // Robolectric), so a bump here is only ever really verified by opening an
    // existing database on a real device.
    implementation("net.zetetic:sqlcipher-android:4.17.0")
    implementation("androidx.sqlite:sqlite:2.6.2")
    // Plain (non-SQLCipher) framework SQLite open helper -- migration tests
    // (spec §7 R11) build their "before" fixture with this rather than the
    // app's encrypted factory, since a migration test cares about
    // schema/data correctness, not the cipher wrapping.
    testImplementation("androidx.sqlite:sqlite-framework:2.6.2")

    // DataStore (preferences: theme choice, active profile, feature toggles)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // NOTE: no WorkManager dependency here on purpose (R5, spec section 9 item 0
    // housekeeping note). The auto-backup/stock-recompute work this was originally
    // added for is now specified as debounce + explicit-trigger (spec section 9 #4
    // and #9), not a periodic job -- adding WorkManager back would reintroduce a
    // second background-work paradigm the spec explicitly avoids.

    // Glance (home screen widgets)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Biometric app-lock (optional toggle) -- pinned to the current stable
    // release; an alpha build is best avoided for an authentication/security
    // feature even in a personal-use app.
    implementation("androidx.biometric:biometric:1.1.0")

    // SAF (sync-safe local backup export)
    implementation("androidx.documentfile:documentfile:1.1.0")

    // NOTE: no androidx.security:security-crypto dependency here on purpose.
    // EncryptedSharedPreferences/MasterKey would be the obvious way to wrap
    // the SQLCipher key, but that library is pinned at the 1.1.0-alpha07
    // release those classes were deprecated in, and pulls in Tink.
    // PassphraseProvider wraps the key directly with an AndroidKeyStore
    // AES/GCM Cipher instead -- same Keystore-backed guarantee, no extra
    // dependency, no deprecated-API liability.

    // NOTE: no Health Connect dependency here on purpose (R4, spec section 9 item 0
    // housekeeping note; also spec section 9 #13, dropped). Health Connect's
    // "built-in" path still names Google Play services as a dependency, a direct
    // conflict with section 1's de-googled constraint -- not adopted in any batch.

    // JSON backup/restore
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // AES-256 password-protected zip read/write (spec §9 #9) -- same shape as
    // Catima's (open-source) optional password-protected export/import, the
    // standard library for this on Android/Kotlin.
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    
    // Charts (History's adherence trend chart, §9 #6) -- a stacked daily
    // bar (taken/skipped/missed), not a pie: Vico 2.x's cartesian module
    // only has line/column/candlestick layers, the pie layer Vico 1.x had
    // was dropped, so pie was never actually available at this pinned
    // version regardless of what an earlier comment here implied.
    implementation("com.patrykandpatrick.vico:compose-m3:2.1.0")

    // History calendar view (spec §5 ADR-0001 shopping list, §9 #6) -- the
    // Android-only `compose` artifact, not `compose-multiplatform`: this app
    // targets Android exclusively (§1), so the plain artifact avoids pulling
    // in KMP-only surface the app would never use.
    // Held at 2.7.0 while the project's Kotlin was 2.1.0, which cannot read
    // the 2.3 metadata newer releases carry; Kotlin 2.4 reads it (§7 R93).
    implementation("com.kizitonwose.calendar:compose:2.10.1") {
        // ui-tooling is declared as an ordinary dependency here, not a
        // debugImplementation, so it lands on releaseRuntimeClasspath and
        // drags androidx.compose.ui.tooling.PreviewActivity -- exported, no
        // permission -- into the release manifest. The project's own
        // debugImplementation on ui-tooling (above) is correctly scoped and is
        // the one that should govern. AndroidManifest.xml removes the activity
        // from both variants; this keeps the artifact itself out of release.
        // Nothing in this app calls into ui-tooling at runtime -- it is IDE
        // tooling -- so excluding it is a no-op for behaviour.
        exclude(group = "androidx.compose.ui", module = "ui-tooling")
    }

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.core:core-splashscreen:1.2.0")

    // Testing
    testImplementation("junit:junit:4.13.2")

    // On-device tests (spec §9 #20). Versions pinned to what the Gradle cache
    // already holds, so `--offline` keeps working. Nothing here is a mock
    // framework: the point of this source set is the real SQLCipher, the real
    // AlarmManager and the real NotificationManager, which is exactly what the
    // JVM suite cannot reach (§7 R39 verified these by hand, once).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // ApplicationProvider.getApplicationContext() for Robolectric-backed Room
    // repository tests (spec §7 R3 -- resolve()/tag-dedupe race-prone logic).
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Compose UI tests live in the UNIT test source set, driven by Robolectric,
    // not in an androidTest source set. That is deliberate and is the opposite
    // of what this comment used to advise (spec §7 R47).
    //
    // An androidTest source set runs under `connectedAndroidTest`, and
    // .github/workflows/ci.yml runs assembleDebug, testDebugUnitTest and
    // lintDebug -- no device, no emulator, so nothing there would ever have
    // executed an instrumented test. The `ndk.abiFilters` block above says the
    // same thing from the other end: this project ships arm ABIs only and is
    // never developed against an emulator. A test suite that cannot run is
    // worse than none, because the ledger starts crediting it.
    //
    // Robolectric is already here for the Room tests, and
    // ui-test-junit4 + createComposeRule() work under it, so these run inside
    // the task CI already runs. The trade is that Robolectric draws to a
    // shadow canvas: assertions about semantics, click handling and text are
    // sound, and anything about real pixels, animation timing or fonts is not.
    // Every UI test in this project is written against semantics only.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Provides the ComposeView host activity createComposeRule() attaches to.
    // `debugImplementation` in an app module reaches the unit test classpath
    // for the debug variant, which is the only variant tests are compiled for.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    }
}