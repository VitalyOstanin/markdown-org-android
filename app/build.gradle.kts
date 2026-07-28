plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.vitalyostanin.markdownorg"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.vitalyostanin.markdownorg"
        // 26 is where java.time is available without desugaring, and the
        // agenda is date arithmetic from end to end.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // JNA ships libjnidispatch.so for ABIs Android dropped years ago
            // — mips, mips64, armeabi. Without this filter they ride along at
            // around 0.4 MB of dead weight, and the core is only built for
            // the ABIs listed here anyway.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
}
