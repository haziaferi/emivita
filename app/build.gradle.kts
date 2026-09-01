plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

// Pins the JDK used to actually RUN the Kotlin/Java compiler tasks for this
// module to 21 -- distinct from compileOptions/kotlinOptions below, which
// target compiled bytecode (deliberately kept at 17, see those comments).
// Without this, the project relies entirely on whichever JDK happens to be
// configured in Android Studio's own Gradle-JDK setting (README's manual
// setup step 4) -- invisible to anyone opening the project on a different
// machine or in CI, silently breaking there with no signal in the codebase
// pointing at the fix. This does NOT pin the Gradle *daemon's* own JVM
// (Gradle has no supported way to do that from inside a build script --
// that's still JAVA_HOME/org.gradle.java.home/the IDE setting), only the
// toolchain used for compilation, which Gradle can auto-detect from an
// already-installed JDK 21 without needing network access.
kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.hazia.emivita"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hazia.emivita"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
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
            // in place. The trade this accepts: the signing password is the
            // published Android default, so this identity is not secret and
            // anyone could build an APK that installs over this one. Swap in
            // a real keystore (read from a gitignored keystore.properties)
            // the moment this app is distributed to anyone else.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.android.material:material:1.14.0")

    // Room + SQLCipher (encrypted local DB)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    // IMPORTANT: net.zetetic:android-database-sqlcipher is the OLD, now-deprecated
    // artifact -- its SupportFactory lives in the `net.sqlcipher.database` package.
    // EmivitaDatabase.kt imports `net.zetetic.database.sqlcipher.SupportFactory`,
    // which belongs to the NEW artifact below (`sqlcipher-android`). The old
    // artifactId + that import together would not compile/resolve at all.
    // sqlcipher-android is also the only one of the two still receiving updates,
    // including 16KB-page-size-compatible native libraries.
    implementation("net.zetetic:sqlcipher-android:4.16.0")
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
    implementation("androidx.documentfile:documentfile:1.0.1")

    // NOTE: no androidx.security:security-crypto dependency here on purpose
    // (efficiency pass, this batch). PassphraseProvider used to lean on
    // EncryptedSharedPreferences/MasterKey (that library, pinned to the
    // 1.1.0-alpha07 release those classes were deprecated in) to wrap the
    // SQLCipher key. It now wraps the key directly with an AndroidKeyStore
    // AES/GCM Cipher -- same Keystore-backed guarantee, no Tink dependency,
    // no deprecated-API liability to revisit later.

    // NOTE: no Health Connect dependency here on purpose (R4, spec section 9 item 0
    // housekeeping note; also spec section 9 #13, dropped). Health Connect's
    // "built-in" path still names Google Play services as a dependency, a direct
    // conflict with section 1's de-googled constraint -- not adopted in any batch.

    // JSON backup/restore
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // AES-256 password-protected zip read/write (spec §9 #9) -- same shape as
    // Catima's (open-source) optional password-protected export/import, the
    // standard library for this on Android/Kotlin.
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    
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
    // Pinned to 2.7.0, not the current 2.10.1 latest: newer releases are
    // compiled with a Kotlin compiler whose metadata this project's pinned
    // Kotlin 2.1.0 plugin (root build.gradle.kts) can't read ("Module was
    // compiled with an incompatible version of Kotlin... binary version...
    // 2.3.0, expected... 2.1.0") -- confirmed by testing 2.10.1 directly
    // against this build, not assumed. 2.7.0 predates that jump.
    implementation("com.kizitonwose.calendar:compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // ApplicationProvider.getApplicationContext() for Robolectric-backed Room
    // repository tests (spec §7 R3 -- resolve()/tag-dedupe race-prone logic).
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // No androidTestImplementation entries: there is no androidTest source
    // set. The only instrumented test was the New-Project wizard's
    // ExampleInstrumentedTest asserting the package name, and it had never
    // been run -- its dependencies were not even in the Gradle cache, which
    // is what made `./gradlew --offline lintDebug` fail. Add these three back
    // (compose-bom, androidx.test.ext:junit, ui-test-junit4) alongside the
    // first real instrumented test.
}
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    }
}