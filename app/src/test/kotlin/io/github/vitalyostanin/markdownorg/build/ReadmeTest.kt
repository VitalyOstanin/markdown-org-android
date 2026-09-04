package io.github.vitalyostanin.markdownorg.build

import io.github.vitalyostanin.markdownorg.ui.settingHelp
import io.github.vitalyostanin.markdownorg.ui.settingsCatalogue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the README has to keep saying while the repository moves.
 *
 * It is the only documentation of this project — there are no API docs and no
 * manual — so a reader who follows it and finds nothing there has no second
 * place to look. These tests hold it to the parts that go stale without a
 * word: an entry point that was renamed, a function that was added, an ABI
 * the APK stopped carrying.
 */
class ReadmeTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    private val readme = root.resolve("README.md").readText()

    /** Every way into the project is a script; a script nobody named is unreachable. */
    @Test
    fun everyScriptIsNamedInTheReadme() {
        val missing = scripts().map(File::getName).filter { !readme.contains(it) }

        assertTrue("tools/ scripts the README never names: $missing", missing.isEmpty())
    }

    /**
     * The exported surface of the core is what a caller may use, and the
     * README is where it is written down. A function that is exported and
     * unmentioned is a feature nobody can find.
     */
    @Test
    fun everyExportedFunctionIsNamedInTheReadme() {
        val exported = exportedFunctions()

        assertTrue("nothing is exported?", exported.isNotEmpty())
        val missing = exported.filter { !readme.contains(it) }
        assertTrue("exported but not in the README: $missing", missing.isEmpty())
    }

    /** Every module of the core stands in the layout, or the layout reads as complete and is not. */
    @Test
    fun everyModuleOfTheCoreIsInTheLayout() {
        val modules =
            root
                .resolve("rust/markdown-org-ffi/src")
                .listFiles()
                .orEmpty()
                .map(File::getName)
                .filter { it.endsWith(".rs") }

        val missing = modules.filter { !readme.contains(it) }
        assertTrue("modules of the core the layout omits: $missing", missing.isEmpty())
    }

    /**
     * A source set Gradle knows on its own — `main`, `test`, `androidTest` —
     * is where an Android project keeps those files and needs no map. One
     * wired up by hand is not: nothing about the name says which suites read
     * it or why it is not simply a package of the tests.
     */
    @Test
    fun everySourceSetWiredUpByHandIsInTheLayout() {
        val gradle = root.resolve("app/build.gradle.kts").readText()
        val wired =
            ADDED_DIRECTORY
                .findAll(gradle)
                .map { it.groupValues[1] }
                .filter { it.startsWith("src/") }
                // The set itself, not the language directory under it: what
                // the layout names is `src/sharedTest`, not its `kotlin/`.
                .map { it.split("/").take(2).joinToString("/") }
                .toSet()

        val missing = wired.filter { !readme.contains(it) }
        assertTrue("source sets added by hand and left out: $missing", missing.isEmpty())
    }

    /**
     * An ABI the APK does not package is a build nobody needed: the core
     * takes a while per architecture, and the result is dropped silently at
     * packaging time.
     */
    @Test
    fun noAbiIsProposedThatTheApkDoesNotCarry() {
        val packaged = packagedAbis()

        assertTrue("abiFilters names nothing?", packaged.isNotEmpty())
        val proposed =
            ABIS
                .findAll(readme)
                .flatMap { it.groupValues[1].trim('"').split(" ") }
                .filter(String::isNotBlank)
                .toSet()

        assertTrue("the README builds no ABI?", proposed.isNotEmpty())
        assertTrue(
            "proposed but not packaged: ${proposed - packaged}",
            packaged.containsAll(proposed),
        )
    }

    /**
     * The APK is published, so its terms have to be readable before it is
     * installed rather than after the repository is cloned.
     */
    @Test
    fun theLicenceIsStatedAndLinked() {
        val licence = root.resolve("LICENSE").readLines().first().trim()

        assertTrue("the README does not name the licence ($licence)", readme.contains("MIT"))
        assertTrue("the README does not link LICENSE", readme.contains("](LICENSE)"))
    }

    /**
     * The bundle replaces the device's trust store for git traffic. Where it
     * came from and when it is refreshed has to be written down, or it is
     * carried until a sync fails against a certificate nobody can explain.
     */
    @Test
    fun theCertificateBundleIsDocumented() {
        val bundle = root.resolve("app/src/main/assets/cacert.pem")
        val source =
            bundle
                .useLines { lines -> lines.take(20).firstOrNull { it.contains("https://curl.se") } }
                ?.substringAfter("here: ")
                ?.trim()

        assertTrue("the bundle names no source", source != null)
        assertTrue("the README never names ${bundle.name}", readme.contains(bundle.name))
        assertTrue("the README does not say where the bundle comes from", readme.contains(source!!))
    }

    /**
     * The settings screen is described by how many of its items say what they
     * are for, and both counts are written out in words.
     *
     * They are the first thing about that section to go stale: an item added
     * to the catalogue moves the total, and whether it was given an
     * explanation moves the other two numbers. Nothing about adding one says
     * that a paragraph elsewhere counts them.
     */
    @Test
    fun theSettingsScreenIsCountedAsItStands() {
        val explained = settingHelp.size
        val plain = settingsCatalogue.size - explained

        val counted = listOf(
            "${inWords(explained)} of the ${inWords(settingsCatalogue.size)} items",
            "The ${inWords(plain)} without a mark",
            "What those ${inWords(plain)} kept",
        )

        val wrong = counted.filter { !readme.contains(it, ignoreCase = true) }
        assertTrue("the README counts the settings screen otherwise: $wrong", wrong.isEmpty())
    }

    /**
     * That the section on distribution says the same as the note behind it.
     *
     * The README states why the application is installed from a release page
     * rather than from a store, and `TODO.md` holds the same reasons at
     * length. The one that is quoted verbatim is the policy ruling the
     * lightest of the stores out; a quotation that drifts from the note it
     * summarises is worse than none, since neither reader can tell which was
     * checked against the policy itself.
     */
    @Test
    fun theStoresRuledOutAreNamedTheSameWayTwice() {
        // Wrapped where each file wraps it, so the quotation is compared as
        // it reads rather than as it is typed.
        val todo = root.resolve("TODO.md").readText().unwrapped()
        val stated = readme.unwrapped()
        val elsewhere = listOf(QUOTED_POLICY, "F-Droid", "RuStore", "AppGallery")

        val missing = elsewhere.filter { !stated.contains(it) || !todo.contains(it) }

        assertTrue(
            "the README and TODO.md disagree on why no store carries this: $missing",
            missing.isEmpty(),
        )
    }

    /** Prose as one line, whatever column the file was wrapped at. */
    private fun String.unwrapped(): String = replace(Regex("""\s+"""), " ")

    /** The scripts of `tools/`, in a fixed order so a failure names the same one twice. */
    private fun scripts(): List<File> = root
        .resolve("tools")
        .listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".sh") }
        .sortedBy(File::getName)

    /** The surface UniFFI exports, under the names the generated Kotlin gives it. */
    private fun exportedFunctions(): List<String> = root
        .resolve("rust/markdown-org-ffi/src")
        .listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".rs") }
        .sortedBy(File::getName)
        .flatMap { file -> EXPORTED.findAll(file.readText()).map { it.groupValues[1].camelCase() } }

    /** The ABIs the APK packages, whatever the core was built for. */
    private fun packagedAbis(): Set<String> = ABI_FILTERS
        .find(root.resolve("app/build.gradle.kts").readText())
        ?.groupValues
        ?.get(1)
        ?.split(",")
        ?.map { it.trim().trim('"') }
        ?.filter(String::isNotBlank)
        .orEmpty()
        .toSet()

    /** A count as the README writes it, which is in words up to ninety-nine. */
    private fun inWords(count: Int): String = when {
        count < 20 -> UNITS[count]
        count % 10 == 0 -> TENS[count / 10]
        else -> "${TENS[count / 10]}-${UNITS[count % 10]}"
    }

    private fun String.camelCase(): String = split("_")
        .mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar(Char::uppercase)
        }.joinToString("")

    private companion object {
        val UNITS = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen",
        )

        val TENS = listOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
            "eighty", "ninety",
        )

        /** The rule that rules out the store the metadata was first written for. */
        const val QUOTED_POLICY =
            "strongly opposed to apps which are fully or in part created by " +
                "generative AI tools"

        /** A function UniFFI carries across, which the attribute marks and nothing else. */
        val EXPORTED = Regex("""#\[uniffi::export]\s*\n\s*pub fn (\w+)""")

        /** What `ABIS=` is set to in an example, quoted or bare. */
        val ABIS = Regex("""ABIS=("[^"]*"|\S+)""")

        /** A directory the Gradle build adds to a source set of its own accord. */
        val ADDED_DIRECTORY = Regex("""directories\.add\("([^"]+)"\)""")

        val ABI_FILTERS = Regex("""abiFilters \+= listOf\(([^)]*)\)""")
    }
}
