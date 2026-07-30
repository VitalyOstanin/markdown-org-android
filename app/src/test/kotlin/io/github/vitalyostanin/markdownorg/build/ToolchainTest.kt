package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the pinned toolchain has to keep to.
 *
 * A pin is written once and then read for years, and none of what can go
 * wrong with it has a runtime: a TLS stack whose line stopped receiving
 * fixes, a version promised in a manifest that nothing ever compiles, a
 * version named in three files that drift apart. The application would ship
 * and run either way.
 */
class ToolchainTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    private val versions = root.resolve("tools/versions.env").readText()
    private val workflow = root.resolve(".github/workflows/build.yml").readText()

    /**
     * OpenSSL is built into the APK, so a fix for it reaches a phone through
     * a release of this project and through nothing else. On a line that has
     * reached its end there are no fixes to carry: the 3.6 line stops on
     * 2026-11-01, while 3.5 is supported until 2030-04-08.
     *
     * The lock file alone would not hold it — `openssl-src` arrives through
     * `openssl-sys`, whose requirement admits the newer line, so the choice
     * has to be stated as a requirement of this crate.
     */
    @Test
    fun theVendoredTlsStackStaysOnALineWithLongTermSupport() {
        val manifest = root.resolve("rust/markdown-org-ffi/Cargo.toml").readText()

        assertTrue(
            "nothing states which OpenSSL line is vendored",
            Regex("""^openssl-src = "~300\.5""", RegexOption.MULTILINE).containsMatchIn(manifest),
        )

        val locked = Regex("""name = "openssl-src"\nversion = "([^"]+)"""")
            .find(root.resolve("rust/Cargo.lock").readText())
            ?.groupValues
            ?.get(1)

        assertTrue(
            "the lock file resolves openssl-src to $locked, off the 3.5 line",
            locked != null && locked.startsWith("300.5."),
        )
    }

    /**
     * `rust-version` is a promise to whoever builds the core, and a promise
     * nothing compiles is true by accident at best. The toolchain the images
     * carry is years ahead of it, so a build there proves nothing about it.
     */
    @Test
    fun theOldestRustThatIsPromisedIsCompiledByTheBuild() {
        val promised = Regex("""^rust-version = "([^"]+)"""", RegexOption.MULTILINE)
            .find(root.resolve("rust/Cargo.toml").readText())
            ?.groupValues
            ?.get(1)

        assertTrue("the workspace promises no rust-version", promised != null)
        assertTrue(
            "nothing in the workflow reads rust-version out of the manifest",
            workflow.contains("rust-version"),
        )
        assertTrue(
            "nothing compiles the core with the oldest toolchain promised",
            Regex("""cargo \+"?\$\{?[A-Za-z_]+\}?"? (check|build)""").containsMatchIn(workflow),
        )
    }

    /**
     * A version repeated in a second file is a version that will be raised in
     * one of them. Every pin is named in versions.env, and the workflow, the
     * images and Gradle read it from there.
     */
    @Test
    fun everyPinnedVersionIsNamedInOnePlace() {
        assertTrue(
            "the workflow spells out a Java version of its own",
            Regex("""java-version: (?!\$\{\{ env\.JDK_VERSION)""").findAll(workflow).none(),
        )
        assertTrue(
            "the SDK image does not take the JDK version as an argument",
            root.resolve("tools/Containerfile.sdk").readText().contains("\${JDK_VERSION}"),
        )
        assertTrue(
            "Gradle spells out a Java version of its own",
            root
                .resolve("app/build.gradle.kts")
                .readText()
                .contains("""jvmToolchain(toolVersions.getValue("JDK_VERSION").toInt())"""),
        )
    }

    /**
     * The NDK is deliberately behind its latest release: r27 is the long-term
     * line, r29 is not, and a pin left behind without a reason is read as one
     * nobody has got round to raising.
     */
    @Test
    fun aPinLeftBehindItsLatestReleaseSaysWhy() {
        val reason = versions
            .lines()
            .takeWhile { !it.startsWith("NDK_RELEASE=") }
            .takeLastWhile { it.startsWith("#") }
            .joinToString(" ")

        assertTrue(
            "nothing beside NDK_RELEASE says why this release rather than the newest",
            reason.contains("LTS"),
        )
    }

    /** The JDK the whole build runs on is one of the long-term releases. */
    @Test
    fun theJavaTheBuildRunsOnIsALongTermRelease() {
        val jdk = Regex("""^JDK_VERSION=(\d+)$""", RegexOption.MULTILINE)
            .find(versions)
            ?.groupValues
            ?.get(1)
            ?.toInt()

        assertTrue("JDK_VERSION is not a number: $jdk", jdk != null)
        assertTrue("JDK $jdk is not a long-term release", jdk in LONG_TERM_JAVA)
    }

    /** The image the SDK is built on is the JDK the pins name, not another one. */
    @Test
    fun theSdkImageIsBuiltOnThePinnedJdk() {
        val image = root.resolve("tools/Containerfile.sdk").readText()
        val from = image.lines().first { it.startsWith("FROM ") }

        assertTrue(
            "the SDK image is not built on a temurin JDK: $from",
            from.contains("eclipse-temurin:\${JDK_VERSION}-jdk@sha256:"),
        )
        assertEquals(
            "the JDK version reaches the image through more than one argument",
            1,
            image.lines().count { it.trim() == "ARG JDK_VERSION" },
        )
    }

    private companion object {
        /**
         * The Temurin long-term lines still receiving updates. 8 and 11 are
         * long-term as well and too old for the Android plugin.
         */
        val LONG_TERM_JAVA = setOf(17, 21, 25)
    }
}
