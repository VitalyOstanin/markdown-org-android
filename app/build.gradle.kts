import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.licensee)
}

// The versions the build image installs and CI sets up, read from the one
// file that states them. Written out here as well, they would drift: a
// compileSdk the image has no platform for fails inside the container, and a
// build-tools version it does not ship is downloaded again on every run.
val toolVersions: Map<String, String> = rootProject.file("tools/versions.env")
    .readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim() }

// What the APK says it is. The name comes from gradle.properties, which is
// what CHANGELOG.md is written against; the code and the commit come from
// whoever ran the build. Android orders packages by the code alone — a
// constant one makes every build look like a reinstall of the same one, and
// every store refuses the second upload — so CI passes the number of the run
// that produced the APK. A build from a working copy keeps the 1 below and is
// not meant to be distributed.
val appVersionName: String = providers.gradleProperty("appVersionName").get()
val appVersionCode: Int = providers.gradleProperty("appVersionCode").map(String::toInt).getOrElse(1)
val appCommit: String = providers.gradleProperty("appCommit").getOrElse("working copy")

android {
    namespace = "io.github.vitalyostanin.markdownorg"
    compileSdk = toolVersions.getValue("ANDROID_COMPILE_SDK").toInt()
    buildToolsVersion = toolVersions.getValue("ANDROID_BUILD_TOOLS")

    defaultConfig {
        applicationId = "io.github.vitalyostanin.markdownorg"
        // 26 is where java.time is available without desugaring, and the
        // agenda is date arithmetic from end to end.
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Which commit an installed build came from. The version name alone
        // does not say: every prerelease between two versions carries the
        // same one.
        buildConfigField("String", "COMMIT", "\"$appCommit\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The instrumented half of the same bound the unit tests get below.
        // A Compose test that waits for an idle state that never arrives
        // would otherwise hold the device until the whole run is killed.
        testInstrumentationRunnerArguments["timeout_msec"] = "120000"

        ndk {
            // JNA ships libjnidispatch.so for ABIs Android dropped years ago
            // — mips, mips64, armeabi. Without this filter they ride along at
            // around 0.4 MB of dead weight.
            //
            // The list matches what tools/build-core.sh actually builds:
            // arm64-v8a for devices, x86_64 for the emulator. Listing an ABI
            // the core is not built for is worse than leaving it out — the
            // APK installs, JNA finds its own library, and the app dies on
            // the first call into the core. 32-bit ARM would need the core
            // built for it first.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        // Off by default since AGP 9; the application is Compose-only.
        compose = true
        // The version the settings screen reads back to whoever is looking at
        // an installed build.
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // Both directories are build output of tools/build-core.sh and are
            // not committed: the native libraries and the Kotlin surface
            // UniFFI generates from them.
            jniLibs.directories.add("../rust/jniLibs")
            kotlin.directories.add("../generated")
        }
        // The task factories are shared: the JVM tests exercise the
        // projections, the instrumented ones feed the same shapes to the
        // screen, and duplicating the builders would let the two drift.
        getByName("test") { kotlin.directories.add("src/sharedTest/kotlin") }
        getByName("androidTest") { kotlin.directories.add("src/sharedTest/kotlin") }
    }

    packaging {
        jniLibs {
            // JNA loads the library from the APK directly, so it must stay
            // uncompressed and unstripped by the packager.
            useLegacyPackaging = false
        }
    }

    // The release key, when there is one. Read from the environment rather
    // than from `-Pandroid.injected.signing.*`: a property on the command line
    // stands in the process arguments, where every build script, Gradle plugin
    // and `build.rs` running in the same job can read it out of /proc. A
    // build without these variables produces an unsigned release APK, which
    // is what a local `assembleRelease` does.
    val keystore = System.getenv("APP_KEYSTORE_FILE")
    if (!keystore.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("APP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("APP_KEYSTORE_ALIAS")
                keyPassword = System.getenv("APP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Adds en-XA and ar-XB, which lengthen every string and mirror the
            // layout. Russian runs about a third longer than English here —
            // `settings_branch_default` is 44 characters against 18 — and a
            // label that is cut off in that direction shows up on en-XA
            // before it shows up in a translation.
            isPseudoLocalesEnabled = true
        }

        release {
            // Off until there is something checking a shrunk build still
            // works. The core is reached through JNA, which finds classes and
            // fields by name, so neither it nor the generated
            // `uniffi.markdown_org_ffi` layer is visible to R8's reachability
            // analysis; keep rules for them would be written once and never
            // verified, since the instrumented tests run against debug and
            // running them against release needs a signing key. A mistake
            // would surface on an installed release, at the first call into
            // the core.
            isMinifyEnabled = false
            if (!keystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        // The view model writes failures to logcat, and the JVM stub of
        // android.util.Log throws "not mocked" instead of doing nothing.
        // Returning defaults lets the tests exercise the same path the device
        // takes; nothing here asserts on what was logged.
        unitTests.isReturnDefaultValues = true

        unitTests.all {
            // JUnit 4 interrupts nothing on its own: a loop that does not
            // end, or a coroutine waiting on something that never arrives,
            // holds the Gradle task for as long as the build may run. Two
            // minutes is far above the whole suite, which runs in seconds.
            it.timeout.set(Duration.ofMinutes(2))
            // The tests that read the workflow, the ignore list and the build
            // images need to find them; a JVM test otherwise knows nothing
            // about where it is being run from.
            it.systemProperty("repo.root", rootDir.absolutePath)
        }
    }
}

// The JDK the build image and CI run, stated so that ./gradlew on a machine
// with a different one compiles against the same Java and fails loudly
// instead of producing subtly different output.
kotlin {
    jvmToolchain(toolVersions.getValue("JDK_VERSION").toInt())
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Reads the two licence lists the build collects into the assets.
    implementation(libs.kotlinx.serialization.json)

    // The @aar classifier is what carries the Android native libraries.
    implementation(variantOf(libs.jna) { artifactType("aar") })

    debugImplementation(libs.compose.ui.tooling)

    // The agenda projections are plain Kotlin over UniFFI records, which are
    // data classes — no native library is loaded, so they run on the JVM.
    testImplementation(libs.junit)
    // The view model is exercised against stand-ins for the core, so what it
    // does with concurrent requests can be pinned down without a device.
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Supplies the empty activity ComposeTestRule launches into.
    debugImplementation(libs.compose.ui.test.manifest)
}

// The list of what the APK carries besides this project, read out of the
// dependency graph rather than written by hand: a list kept by hand is right
// on the day it is written and wrong at the next dependency update.
licensee {
    // Every licence the graph is allowed to bring in. An artifact under
    // anything else fails the build, which is the point — a dependency whose
    // terms nobody looked at should not reach a published APK.
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("EPL-1.0")

    // JNA offers LGPL-2.1-or-later or Apache-2.0 and leaves the choice to
    // whoever receives it. Apache-2.0 is the choice made here: it asks for
    // attribution rather than for the recipient to be able to relink.
    allowDependency("net.java.dev.jna", "jna", "5.19.1") {
        because("dual-licensed; taken under Apache-2.0")
    }

    // Bundled into the APK as assets/licenses.json, which is what the
    // licences screen reads at runtime.
    bundleAndroidAsset = true
    androidAssetReportPath = "licenses.json"
}
