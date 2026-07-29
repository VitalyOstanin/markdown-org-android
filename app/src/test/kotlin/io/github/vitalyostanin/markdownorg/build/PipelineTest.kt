package io.github.vitalyostanin.markdownorg.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the build itself has to keep to.
 *
 * These read files rather than code, which is unusual for a unit test and is
 * the point: the failures they guard against — a secret on a command line, a
 * downloaded compiler nobody checked, a signing key the working copy would
 * offer to commit — have no runtime to show up in. Nothing else in the
 * project would notice them until they had already happened.
 */
class PipelineTest {

    private val root = File(System.getProperty("repo.root") ?: "..")

    private val workflow = root.resolve(".github/workflows/build.yml").readText()

    /**
     * `${{ }}` is substituted into the script before the shell sees it: a
     * secret ends up in the process arguments, where anything running in the
     * same job reads it out of `/proc`, and a tag holding shell syntax runs as
     * part of the script. Both are what GitHub's own guidance says to pass
     * through `env:` instead.
     */
    @Test
    fun noExpressionIsSubstitutedIntoAScript() {
        val offenders = shellScripts(workflow).filter { it.contains("\${{") }

        assertTrue(
            "these lines are substituted into a shell script:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    /**
     * The job that builds runs the sources of whatever is being checked —
     * Gradle plugins, `build.rs` of every crate — so it holds no token that
     * can write to the repository. Publishing is a job of its own.
     */
    @Test
    fun theJobThatBuildsCannotWriteToTheRepository() {
        val build = jobBody(workflow, "build")

        assertFalse(
            "the build job may not carry contents: write",
            build.contains("contents: write"),
        )
    }

    /** A downloaded toolchain is checked against a published digest. */
    @Test
    fun everyDownloadInTheBuildImagesIsVerified() {
        for (name in listOf("tools/Containerfile.ndk", "tools/Containerfile.sdk")) {
            val text = root.resolve(name).readText()
            // `curl -`, not `curl`: the latter also matches the apt line that
            // installs it.
            val downloads = text.split("curl -").size - 1
            val verified = text.split("sha256sum -c").size - 1

            assertTrue("$name downloads nothing?", downloads > 0)
            assertTrue(
                "$name: $downloads download(s), $verified checked against a digest",
                verified >= downloads,
            )
            assertTrue(
                "$name pulls a base image by a moving tag",
                text.lines().filter { it.startsWith("FROM ") }.all { it.contains("@sha256:") },
            )
        }
    }

    /** The wrapper downloads a Gradle distribution; TLS is not the only check. */
    @Test
    fun theGradleDistributionIsCheckedAgainstItsDigest() {
        val properties = root.resolve("gradle/wrapper/gradle-wrapper.properties").readText()

        assertTrue(
            "distributionSha256Sum is not set",
            properties.contains("distributionSha256Sum="),
        )
    }

    /** A key generated locally must never be a candidate for `git add`. */
    @Test
    fun theIgnoreListCoversEveryShapeOfSigningKey() {
        val ignored = root.resolve(".gitignore").readLines().map(String::trim).toSet()

        for (mask in listOf("*.keystore", "*.jks", "*.p12", "*.pfx")) {
            assertTrue("$mask is not ignored", mask in ignored)
        }
    }

    /**
     * The TLS stack is compiled into the application, so an advisory against
     * OpenSSL or libgit2 reaches users through a release of this project and
     * nothing else. Something has to notice the advisory.
     */
    @Test
    fun dependenciesAreWatchedForAdvisories() {
        val dependabot = root.resolve(".github/dependabot.yml")

        assertTrue("there is no dependabot configuration", dependabot.isFile)
        val text = dependabot.readText()
        for (ecosystem in listOf("cargo", "gradle", "github-actions")) {
            assertTrue("$ecosystem is not watched", text.contains("\"$ecosystem\""))
        }

        assertTrue(
            "no workflow audits the Rust dependencies",
            root.resolve(".github/workflows").listFiles().orEmpty().any {
                it.readText().contains("cargo audit") || it.readText().contains("audit-check")
            },
        )
    }

    /**
     * The bundle replaces the device's trust store for git traffic, so a root
     * withdrawn by Mozilla stays trusted here until the file is replaced.
     */
    @Test
    fun theCertificateBundleHasAKeeper() {
        assertTrue(
            "nothing checks how old app/src/main/assets/cacert.pem is",
            root.resolve(".github/workflows").listFiles().orEmpty().any {
                it.readText().contains("cacert.pem")
            },
        )
    }

    /**
     * Every `run:` script in the workflow, as a flat list of lines.
     *
     * A block scalar (`run: |`) runs until the indentation falls back to the
     * key's own level; a one-line `run:` is the rest of that line.
     */
    private fun shellScripts(text: String): List<String> {
        val lines = text.lines()
        val script = mutableListOf<String>()
        var indent = -1

        for (line in lines) {
            if (indent >= 0) {
                val blank = line.isBlank()
                val deeper = line.length > indent && line.takeWhile(Char::isWhitespace).length > indent

                if (blank || deeper) {
                    if (!blank) script += line
                    continue
                }
                indent = -1
            }

            val key = line.substringBefore("run:", missingDelimiterValue = "")
            if (!line.contains("run:") || key.isNotBlank() && key.trim() != "-") {
                continue
            }

            val rest = line.substringAfter("run:").trim()
            if (rest == "|" || rest == ">" || rest == "|-" || rest == ">-") {
                indent = line.takeWhile(Char::isWhitespace).length
            } else {
                script += line
            }
        }

        return script
    }

    /** The body of one job, up to the next job at the same indentation. */
    private fun jobBody(text: String, name: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimEnd() == "  $name:" }
        assertTrue("there is no job named $name", start >= 0)

        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.isNotBlank() && !it.startsWith("    ") && !it.startsWith("  #") }

        return rest.take(if (end < 0) rest.size else end).joinToString("\n")
    }
}
