package io.github.vitalyostanin.markdownorg.build

import io.github.vitalyostanin.markdownorg.core.LicenceGroup
import io.github.vitalyostanin.markdownorg.core.licenceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What has to hold for the APK to be distributable.
 *
 * The APK is published on every push, which makes each build a distribution of
 * everything compiled into it. Apache-2.0 asks for its text to travel with the
 * binary, MPL-2.0 asks that the recipient be told where the source is, and a
 * statically linked libgit2 is worth naming. None of that has a runtime to
 * fail in — a build missing the notices runs exactly as well as one carrying
 * them, which is why it is checked here.
 */
class LicensingTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    private val notice = root.resolve("NOTICE")

    private val collected = root.resolve("app/src/main/assets/licenses-core.json")

    /** Everything statically linked into the native library, named in one place. */
    @Test
    fun theVendoredComponentsAreInTheNotices() {
        assertTrue("there is no NOTICE", notice.isFile)
        val text = notice.readText()

        for (component in listOf("libgit2", "openssl", "Mozilla CA certificate store")) {
            assertTrue("$component is not in NOTICE", text.contains(component))
        }
        assertTrue(
            "the linking exception libgit2 is taken under is not stated",
            text.contains("LINKING EXCEPTION") || text.contains("linking exception"),
        )
    }

    /** A licence listed without its text is an attribution nobody can read. */
    @Test
    fun everyCollectedLicenceCarriesItsText() {
        val catalog = licenceCatalog(collected.readText(), "[]")

        assertTrue("nothing was collected", catalog.isNotEmpty())
        val silent = catalog.filter { it.text.isBlank() }.map(LicenceGroup::id)
        assertTrue("collected with no text: $silent", silent.isEmpty())
    }

    /**
     * The crate graph moves with every dependency bump, and the file is
     * committed rather than generated during the build — so the two drift
     * apart silently unless something compares them.
     */
    @Test
    fun ciChecksThatTheNoticesAreCurrent() {
        val workflows = root.resolve(".github/workflows").listFiles().orEmpty()

        assertTrue(
            "nothing in CI runs tools/licenses.sh --check",
            workflows.any { it.readText().contains("licenses.sh --check") },
        )
    }

    /** A dependency under terms nobody looked at should fail the build, not ship. */
    @Test
    fun theGradleGraphIsHeldToAnAllowList() {
        val build = root.resolve("app/build.gradle.kts").readText()

        assertTrue("the licensee plugin is not applied", build.contains("licensee"))
        assertTrue("no licence is allowed, which allows all of them", build.contains("allow(\""))
        assertTrue(
            "the list is not bundled into the APK, so the screen has nothing to show",
            build.contains("bundleAndroidAsset = true"),
        )
    }

    /** One copyright line, in the one place a reader looks for it. */
    @Test
    fun theCopyrightYearIsStatedTheSameWayInBothFiles() {
        val stated = COPYRIGHT.find(root.resolve("LICENSE").readText())?.value

        assertTrue("LICENSE states no copyright", stated != null)
        assertEquals(
            "NOTICE and LICENSE disagree about the copyright",
            stated,
            COPYRIGHT.find(notice.readText())?.value,
        )
    }

    private companion object {
        val COPYRIGHT = Regex("""Copyright \(c\) [0-9-]+ .+""")
    }
}
