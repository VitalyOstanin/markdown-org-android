package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What holds the decision records and their index together.
 *
 * The index in `docs/adr/README.md` is how a record is found: nothing links to
 * the files directly, and a record left out of the table is a decision that
 * exists and cannot be reached. The other direction matters as much — a row
 * pointing at a file that was renamed reads as a decision and leads nowhere.
 */
class AdrIndexTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    private val directory = root.resolve("docs/adr")

    private val index = directory.resolve("README.md").readText()

    @Test
    fun everyRecordIsInTheIndex() {
        val records = records()

        assertTrue("docs/adr holds no records?", records.isNotEmpty())
        val missing = records.filter { !index.contains("($it)") }
        assertTrue("records the index does not link: $missing", missing.isEmpty())
    }

    @Test
    fun everyRowOfTheIndexPointsAtARecord() {
        val linked = LINK.findAll(index).map { it.groupValues[1] }.toSet()

        assertTrue("the index links nothing?", linked.isNotEmpty())
        val broken = linked.filter { !directory.resolve(it).isFile }
        assertTrue("rows pointing at a file that is not there: $broken", broken.isEmpty())
    }

    /**
     * The rules file is where work on this repository starts, and it lists the
     * decisions that bear on it. A decision left out of that list is one the
     * reader is never told about, and the file then contradicts the code
     * outright: the list stopped at ADR-0017 while six accepted records stood
     * after it, one of them the push the file said the application never does.
     */
    @Test
    fun everyRecordIsNamedInTheProjectRules() {
        val rules = root.resolve("CLAUDE.md").readText()
        val missing = records().filter { !rules.contains(it) }

        assertTrue("decisions the rules leave unmentioned: $missing", missing.isEmpty())
    }

    /**
     * Numbers are what the records refer to each other by, so two files
     * sharing one — or a gap where a number was skipped — makes a reference
     * ambiguous or dead.
     */
    @Test
    fun theNumbersRunFromOneWithoutRepeatingOrSkipping() {
        val numbers = records().map { it.take(4).toInt() }.sorted()

        assertEquals("the numbers are not 1..${numbers.size}", (1..numbers.size).toList(), numbers)
    }

    /** Every section a record is expected to carry, in the order they appear. */
    @Test
    fun everyRecordCarriesTheSectionsTheFormatAsksFor() {
        val incomplete = records().filter { name ->
            val text = directory.resolve(name).readText()
            SECTIONS.any { section -> !text.contains("## $section") }
        }

        assertTrue("records missing one of $SECTIONS: $incomplete", incomplete.isEmpty())
    }

    /** The record files, without the index itself, in a fixed order. */
    private fun records(): List<String> = directory
        .listFiles()
        .orEmpty()
        .map(File::getName)
        .filter { RECORD.matches(it) }
        .sorted()

    private companion object {
        val RECORD = Regex("""\d{4}-[a-z0-9-]+\.md""")
        val LINK = Regex("""]\((\d{4}-[a-z0-9-]+\.md)\)""")
        val SECTIONS = listOf("Status", "Context", "Decision", "Consequences")
    }
}
