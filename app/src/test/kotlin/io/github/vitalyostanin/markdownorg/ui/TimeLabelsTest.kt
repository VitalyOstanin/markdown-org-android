package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
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
        assertEquals("1:00 PM", hourLabel(13, Locale.US, use24Hour = false))
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
        assertEquals("12:00 AM", hourLabel(0, Locale.US, use24Hour = false))
        assertEquals("12:00 PM", hourLabel(12, Locale.US, use24Hour = false))
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
}
