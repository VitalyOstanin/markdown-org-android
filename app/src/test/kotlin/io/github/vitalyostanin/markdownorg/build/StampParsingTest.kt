package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the date and the hour of a note are read in one place.
 *
 * `LocalDate.parse` and `LocalTime.parse` are the strict ISO readings, and
 * what the extractor hands over is neither strict nor guaranteed: a date is
 * kept as the file writes it, and an hour may be written with one digit.
 * Called directly, they throw where the screen expects a value and are
 * swallowed where the reminder expects an hour. `statedDate` and `statedTime`
 * answer for both, and this holds the callers to them.
 *
 * Read off the source: what is being kept is which reading a caller reaches
 * for, and a value that parsed cannot say which one read it.
 */
class StampParsingTest {

    private val root = File(System.getProperty("repo.root") ?: "..")

    private val main = root.resolve("app/src/main/kotlin/io/github/vitalyostanin/markdownorg")

    @Test
    fun theStrictReadingsAreCalledOnlyWhereTheyAreDefined() {
        val direct = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Stamps.kt" }
            .filter { file ->
                file.readText().let { text ->
                    text.contains("LocalDate.parse(") || text.contains("LocalTime.parse(")
                }
            }
            .map { it.name }
            .toList()

        assertTrue(
            "these files read a stated date or hour strictly — a date the calendar does not " +
                "have throws under the screen that shows it, and an hour of one digit is lost " +
                "with it; statedDate and statedTime answer for both:\n" +
                direct.joinToString("\n") { "  $it" },
            direct.isEmpty(),
        )
    }
}
