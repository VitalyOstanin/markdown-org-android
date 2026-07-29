plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint)
}

// Stated rather than left to the plugin's default: the rules change between
// ktlint releases, and the check has to fail the same way on a laptop and in
// CI. Read out here because inside the blocks below `libs` would be looked
// up on the project being configured, which has no version catalogue.
val ktlintVersion = libs.versions.ktlint.get()

// The formatting of the Kotlin half, the counterpart of `cargo fmt --check`
// for the core. Applied to the root project as well as the application: the
// build scripts are Kotlin too.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

allprojects {
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        // generated/ is the Kotlin UniFFI writes out of the built core. It is
        // build output, it is not committed, and how it is laid out is the
        // binding generator's business.
        filter { exclude { it.file.path.contains("${File.separator}generated${File.separator}") } }
    }
}
