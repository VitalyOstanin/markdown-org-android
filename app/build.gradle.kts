plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The versions the build image installs and CI sets up, read from the one
// file that states them. Written out here as well, they would drift: a
// compileSdk the image has no platform for fails inside the container, and a
// build-tools version it does not ship is downloaded again on every run.
val toolVersions: Map<String, String> = rootProject.file("tools/versions.env")
    .readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim() }

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
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    buildTypes {
        release {
            isMinifyEnabled = false
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
