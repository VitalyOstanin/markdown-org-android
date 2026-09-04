package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That an hour shown to the reader is written by one rule.
 *
 * The rule is in `ui/TimeLabels.kt`: the 24-hour setting of the device
 * overrides what the locale would choose on its own, so a reader on `en-US`
 * who turned it on is shown `13:05` rather than `1:05 PM`. A formatter asked
 * for a localised time knows nothing of that setting, and every place that
 * reaches for one drifts away from the screen without a word — the reminder
 * in the drawer said `1:05 PM` beside an agenda saying `13:05`, and nothing
 * failed.
 *
 * A date is another matter and is left alone: `ofLocalizedDate` carries no
 * clock, and the screens use it where a date is written out.
 */
class ReaderClockTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    @Test
    fun noHourIsFormattedPastTheRuleTheScreensFollow() {
        val offenders = sources()
            .flatMap { file ->
                file.readLines()
                    .mapIndexed { index, line -> index + 1 to line }
                    .filter { (_, line) -> LOCALISED_TIME.containsMatchIn(line) }
                    .map { (line, text) -> "  ${file.name}:$line: ${text.trim()}" }
            }

        assertTrue(
            "these write an hour without the 24-hour setting of the device — " +
                "call timeLabel or momentLabel of ui/TimeLabels.kt instead:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** Every Kotlin source of the application, wherever in the tree it stands. */
    private fun sources(): List<File> = root
        .resolve("app/src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .sortedBy(File::getPath)
        .toList()

    private companion object {
        /** A formatter that writes a time of day the locale's way, and no other way. */
        val LOCALISED_TIME = Regex("""ofLocalized(Time|DateTime)\(""")
    }
}
