package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /**
     * A tool installed from a registry names the version it wants.
     *
     * Without one, every run takes whatever was published last, and the job
     * that installs these goes on to decrypt the signing key and hand its
     * passwords to the next step. A release of the tool compromised an hour
     * ago would be in that process, beside the key, with no window in which
     * anybody could notice. Every other tool here is already pinned — the
     * digest-checked binaries, the actions by commit, the Gradle
     * distribution — so this is the one door left open.
     */
    @Test
    fun everyToolInstalledFromARegistryNamesItsVersion() {
        val installs = (workflow.lines() + root.resolve("tools/Containerfile.ndk").readLines())
            .map(String::trim)
            .filter { it.contains("cargo install ") || it.contains("cargo binstall ") }

        assertTrue("nothing installs a cargo tool?", installs.isNotEmpty())
        val loose = installs.filterNot { it.contains("@") }
        assertTrue(
            "these install whatever was published last:\n${loose.joinToString("\n")}",
            loose.isEmpty(),
        )
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
        val ignored =
            root
                .resolve(".gitignore")
                .readLines()
                .map(String::trim)
                .toSet()

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
     * A step that hangs — a Gradle daemon waiting on a lock, a download that
     * never answers — holds the runner for the six hours GitHub allows by
     * default. Every job states what it is worth waiting for.
     */
    @Test
    fun everyJobIsBounded() {
        val unbounded =
            jobNames(workflows()).filter { (file, name) ->
                !jobBody(file.readText(), name).contains("timeout-minutes:")
            }

        assertTrue(
            "no timeout: ${unbounded.joinToString { "${it.first.name}:${it.second}" }}",
            unbounded.isEmpty(),
        )
    }

    /**
     * The instrumented tests are the only ones that load the native library
     * and the only ones that see a screen, which makes them the ones a
     * release must not go out without.
     */
    @Test
    fun theInstrumentedTestsRunSomewhereInCi() {
        assertTrue(
            "nothing in .github/workflows runs the instrumented tests",
            workflows().any { it.readText().contains("connectedDebugAndroidTest") },
        )
    }

    /**
     * Rust is held to `cargo fmt --check` and clippy with warnings denied.
     * The Kotlin half is the larger one; it is checked as well, by a
     * formatter and by Android Lint.
     */
    @Test
    fun theKotlinSourcesAreCheckedToo() {
        val text = workflows().joinToString("\n") { it.readText() }

        assertTrue("no formatting check for Kotlin", text.contains("ktlintCheck"))
        assertTrue("Android Lint is never run", text.contains("lintDebug"))
        assertTrue(
            "the ktlint plugin is not applied",
            root.resolve("build.gradle.kts").readText().contains("ktlint"),
        )
    }

    /**
     * Cargo re-resolves the lock file without a word when a manifest and the
     * lock disagree, and the published APK then carries versions nothing in
     * the repository records.
     */
    @Test
    fun everyCargoCommandIsLocked() {
        val scripts =
            root
                .resolve("tools")
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".sh") }
                .map { it.name to commands(it.readLines()) } +
                listOf(".github/workflows/build.yml" to commands(shellScripts(workflow)))

        val loose =
            scripts.flatMap { (name, commands) ->
                commands
                    .map(String::trim)
                    .filter { !it.startsWith("#") && !it.startsWith("echo") }
                    .filter {
                        RESOLVES_DEPENDENCIES.containsMatchIn(
                            it,
                        ) && !it.contains("--locked")
                    }.map { "$name: $it" }
            }

        assertTrue(
            "these resolve dependencies freely:\n${loose.joinToString("\n")}",
            loose.isEmpty(),
        )
    }

    /**
     * A test that fails in CI is a test whose report is wanted; the step log
     * carries the assertion but not the trace, and an upload that runs only
     * after a green run is an upload of the case nobody needs.
     */
    @Test
    fun theTestReportsOutliveAFailedRun() {
        val steps = workflows().joinToString("\n") { it.readText() }.split("      - name:")

        assertTrue(
            "no step keeps the test reports of a failed run",
            steps.any {
                it.contains("upload-artifact") &&
                    it.contains("test-results") &&
                    (it.contains("if: always()") || it.contains("if: failure()"))
            },
        )
    }

    /**
     * The release variant is signed with a key CI holds and nothing else
     * reads. A build that silently fell back to the debug key would be
     * published all the same — the release step only checks that a file is
     * there.
     */
    @Test
    fun theReleaseApkIsCheckedBeforeItIsPublished() {
        assertTrue(
            "nothing verifies the signature of the APK that gets published",
            workflow.contains("apksigner") && workflow.contains("verify --print-certs"),
        )
    }

    /** The workflow files, in a fixed order so a failure names the same one twice. */
    private fun workflows(): List<File> = root
        .resolve(".github/workflows")
        .listFiles()
        .orEmpty()
        .sortedBy(File::getName)

    /** Every job of every workflow, as the file it stands in and its name. */
    private fun jobNames(files: List<File>): List<Pair<File, String>> = files.flatMap { file ->
        val lines = file.readLines()
        val start = lines.indexOfFirst { it.trimEnd() == "jobs:" }
        lines
            .drop(start + 1)
            .mapNotNull { JOB.matchEntire(it)?.groupValues?.get(1) }
            .map { file to it }
    }

    /**
     * Shell lines joined at their continuations, so that a command split over
     * several lines is examined as the one command it is.
     */
    private fun commands(lines: List<String>): List<String> {
        val joined = mutableListOf<String>()

        for (line in lines) {
            if (joined.isNotEmpty() && joined.last().endsWith("\\")) {
                joined[joined.size - 1] = joined.last().dropLast(1) + line.trim()
            } else {
                joined += line
            }
        }

        return joined
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
                val deeper =
                    line.length > indent && line.takeWhile(Char::isWhitespace).length > indent

                if (blank || deeper) {
                    if (!blank) script += line
                    continue
                }
                indent = -1
            }

            val key = line.substringBefore("run:", missingDelimiterValue = "")
            if (!line.contains("run:") || (key.isNotBlank() && key.trim() != "-")) {
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
        val end =
            rest.indexOfFirst {
                it.isNotBlank() && !it.startsWith("    ") &&
                    !it.startsWith("  #")
            }

        return rest.take(if (end < 0) rest.size else end).joinToString("\n")
    }

    private companion object {
        /** A key one level below `jobs:`, which is a job and nothing else. */
        val JOB = Regex("""^ {2}([a-z][a-z0-9-]*):\s*$""")

        /**
         * The cargo subcommands that read Cargo.lock and will rewrite it.
         * `cargo fmt` reads no manifest and takes no such flag.
         */
        val RESOLVES_DEPENDENCIES = Regex("""\bcargo\s+(ndk|build|test|run|clippy)\b""")
    }
}
