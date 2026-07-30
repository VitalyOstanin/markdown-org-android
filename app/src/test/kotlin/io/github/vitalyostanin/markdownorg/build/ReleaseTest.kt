package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What has to hold for one published APK to be told from another.
 *
 * Android decides whether a package may replace an installed one by its
 * version code alone, and a reader deciding whether to update has nothing but
 * the version name and the notes. Both are produced by the build rather than
 * observed by it, so nothing at runtime fails when they are wrong: an APK
 * carrying last month's version installs and runs exactly as well as one
 * carrying today's.
 */
class ReleaseTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    private val build = root.resolve("app/build.gradle.kts").readText()

    private val workflow = root.resolve(".github/workflows/build.yml").readText()

    private val changelog = root.resolve("CHANGELOG.md")

    /**
     * A constant version code means the second APK is refused by every store
     * and installs over the first without either being the newer one.
     */
    @Test
    fun theVersionIsDecidedByTheBuildAndNotFrozenIntoTheSources() {
        assertTrue(
            "versionCode is a literal, so every build claims to be the same one",
            !Regex("""versionCode = \d+\s*$""", RegexOption.MULTILINE).containsMatchIn(build),
        )
        for (property in listOf(VERSION_CODE, VERSION_NAME)) {
            assertTrue("$property is not read by the build", build.contains(property))
        }
    }

    /** The number that orders the builds has to differ between them. */
    @Test
    fun everyPublishedBuildIsGivenItsOwnVersionCode() {
        assertTrue(
            "the APK is built without a version code of its own",
            workflow.contains("-P$VERSION_CODE="),
        )
        assertTrue(
            "the version code is not taken from the run that produced the APK",
            workflow.contains("github.run_number"),
        )
    }

    /** A tag is read by tools that only accept the three fields of semver. */
    @Test
    fun theTagOfAPrereleaseStaysWithinSemver() {
        val tags = Regex("""tag="([^"]*)"""").findAll(workflow).map { it.groupValues[1] }.toList()

        assertTrue("nothing in the workflow builds a tag", tags.isNotEmpty())
        val fourth = tags.filter { Regex("""\}\.\$\{|\)\.\$\{""").containsMatchIn(it) }
        assertTrue("a fourth numeric field is not a version: $fourth", fourth.isEmpty())
        assertTrue(
            "a prerelease is not marked as one in its tag",
            tags.any { it.contains("-build.") || it.contains("-rc.") },
        )
    }

    /**
     * A lightweight tag has no author, no date and no message: it says which
     * commit was released but not when, by whom, or as what.
     */
    @Test
    fun aReleaseIsMarkedWithAnAnnotatedTag() {
        assertTrue(
            "the tag is left for gh to create, which creates a lightweight one",
            Regex("""^\s*(git .*)?tag (-a|--annotate) """, RegexOption.MULTILINE)
                .containsMatchIn(workflow),
        )
    }

    /** The one file that says what changed, in the form the sibling projects use. */
    @Test
    fun theChangelogFollowsKeepAChangelog() {
        assertTrue("there is no CHANGELOG.md", changelog.isFile)
        val text = changelog.readText()

        assertTrue("the format is not stated", text.contains("Keep a Changelog"))
        assertTrue(
            "there is nowhere to write the change being made now",
            text.contains("## [Unreleased]"),
        )
        assertTrue("the versioning scheme is not stated", text.contains("Semantic Versioning"))
    }

    /** A version nobody wrote a line about is a release with no notes. */
    @Test
    fun theChangelogDescribesTheVersionBeingBuilt() {
        val version = root.resolve("gradle.properties")
            .readLines()
            .firstOrNull { it.startsWith("$VERSION_NAME=") }
            ?.substringAfter('=')
            ?.trim()

        assertTrue("$VERSION_NAME is not stated in gradle.properties", version != null)
        val text = changelog.readText()
        // Either the version has been cut, and it has a section of its own, or
        // it is still being worked towards and what will go into it is under
        // Unreleased. Nothing in both places, and nothing in neither.
        val released = text.contains("## [$version]")
        val pending = text.substringAfter("## [Unreleased]").substringBefore("\n## ").isNotBlank()

        assertTrue("nothing is written about $version anywhere", released || pending)
    }

    /** Two lines of metadata are not a description of what changed. */
    @Test
    fun theNotesOfAReleaseSayMoreThanTheCommitItWasBuiltFrom() {
        assertTrue(
            "the notes are written by hand and carry no list of changes",
            workflow.contains("--generate-notes") || workflow.contains("--notes-file"),
        )
    }

    /** The input offers a tag of one's choosing; a prerelease is not that. */
    @Test
    fun aTagGivenByHandIsNotForcedIntoAPrerelease() {
        assertTrue(
            "workflow_dispatch cannot publish anything but a prerelease",
            workflow.contains("prerelease:"),
        )
    }

    /** The version an installed build reports, without reaching for adb. */
    @Test
    fun theApplicationCanNameTheBuildItIs() {
        assertTrue(
            "BuildConfig is not generated, so the version is not in the APK's code",
            build.contains("buildConfig = true"),
        )
        val sources = root.resolve("app/src/main/kotlin").walkTopDown().filter { it.isFile }

        assertTrue(
            "no screen shows the version",
            sources.any { it.readText().contains("BuildConfig.VERSION_NAME") },
        )
    }

    /** A build that turned out to be broken has to be leavable. */
    @Test
    fun theReadmeStatesHowToGoBackToTheBuildBefore() {
        val readme = root.resolve("README.md").readText()

        assertTrue("the readme does not name CHANGELOG.md", readme.contains("CHANGELOG.md"))
        assertTrue(
            "there is no way back from a bad build in the readme",
            readme.contains("adb install -r -d") || readme.contains("Rolling back"),
        )
    }

    private companion object {
        const val VERSION_CODE = "appVersionCode"
        const val VERSION_NAME = "appVersionName"
    }
}
