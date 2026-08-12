package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The agenda states hours and dates in the reader's own conventions.
 *
 * Both used to be assembled from literals — `"%02d:00"` on the axis and
 * `"${day}.${month}"` on an overdue row — which reads as intended in Russian
 * and misleads in English: `03.07` is the third of July here and the seventh
 * of March there, and no part of the screen says which.
 */
class TimeLabelsTest {

    @Test
    fun `an hour on a twelve hour clock carries its period`() {
        assertEquals("1:00 PM", hourLabel(13, Locale.US, use24Hour = false).plainSpaces())
    }

    @Test
    fun `an hour on a twenty four hour clock is padded to line up`() {
        assertEquals("09:00", hourLabel(9, Locale.US, use24Hour = true))
    }

    @Test
    fun `the separator of the hour comes from the locale`() {
        // Finnish writes a full stop where English writes a colon; a literal
        // colon in the format would be wrong there whatever the hour is.
        assertEquals("13.00", hourLabel(13, Locale.forLanguageTag("fi"), use24Hour = true))
    }

    @Test
    fun `midnight and noon keep their twelve on a twelve hour clock`() {
        assertEquals("12:00 AM", hourLabel(0, Locale.US, use24Hour = false).plainSpaces())
        assertEquals("12:00 PM", hourLabel(12, Locale.US, use24Hour = false).plainSpaces())
    }

    @Test
    fun `the day and month of an overdue row follow the locale order`() {
        val date = LocalDate.of(2026, 7, 3)

        assertEquals("7/3", dayMonthLabel(date, Locale.US))
        assertEquals("03.07", dayMonthLabel(date, Locale.forLanguageTag("ru")))
    }

    @Test
    fun `the year is left out of the day and month label`() {
        val label = dayMonthLabel(LocalDate.of(2026, 7, 3), Locale.US)

        assertEquals("no year in $label", false, label.contains("26"))
    }

    @Test
    fun `a row that slipped within the year states no year`() {
        val slipped = LocalDate.of(2026, 7, 3)
        val agenda = LocalDate.of(2026, 8, 12)

        assertEquals("03.07", slippedDateLabel(slipped, agenda, RU))
        assertEquals("7/3", slippedDateLabel(slipped, agenda, Locale.US))
    }

    @Test
    fun `a row that slipped from another year states which one`() {
        // The band below a year holds dates whose year is the whole of what
        // they say: without it the first of May of 2021 reads as the first of
        // May just gone, and the row asks to be redone rather than closed.
        val slipped = LocalDate.of(2021, 5, 1)
        val agenda = LocalDate.of(2026, 8, 12)

        assertEquals("01.05.2021", slippedDateLabel(slipped, agenda, RU))
        assertEquals(true, slippedDateLabel(slipped, agenda, Locale.US).contains("21"))
    }

    @Test
    fun `the year of a row is read against its agenda, not against the last of December`() {
        // A day of January is looked at, and the entry slipped in December: one
        // month apart, two years apart, and the year is what says so.
        val slipped = LocalDate.of(2025, 12, 30)
        val agenda = LocalDate.of(2026, 1, 3)

        assertEquals("30.12.2025", slippedDateLabel(slipped, agenda, RU))
    }

    @Test
    fun `the moment of the last sync carries its date as well as its time`() {
        // A date and not only a clock: the app is left open across a night,
        // and "14:05" alone would be read as this afternoon.
        val moment = LocalDateTime.of(2026, 7, 30, 14, 5)

        assertEquals("30.07.2026, 14:05", momentLabel(moment, RU, use24Hour = true))
    }

    @Test
    fun `the moment of the last sync follows the locale order and the clock`() {
        val moment = LocalDateTime.of(2026, 7, 30, 14, 5)

        assertEquals("7/30/26, 2:05 PM", momentLabel(moment, Locale.US, false).plainSpaces())
    }

    @Test
    fun `the twenty four hour setting overrides what the locale would write`() {
        // The setting stands apart from the language: a reader on en-US can
        // turn it on, and then every time on screen follows, this one too.
        val moment = LocalDateTime.of(2026, 7, 30, 14, 5)

        assertEquals("7/30/26, 14:05", momentLabel(moment, Locale.US, use24Hour = true))
    }

    /**
     * Every kind of space written as a plain one.
     *
     * Which space stands before AM or PM is the formatter's business and not
     * this test's: JDK 20 took CLDR 42, where it became a narrow no-break
     * space, and a test spelling out one of them says nothing about the label
     * and breaks on the next JDK. The device formats through Android's own
     * ICU and may well write a third.
     */
    private fun String.plainSpaces(): String = replace(Regex("""\p{Zs}"""), " ")

    private companion object {
        val RU: Locale = Locale.forLanguageTag("ru")
    }
}
