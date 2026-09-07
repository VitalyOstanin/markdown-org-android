package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.markdown_org_ffi.TimestampType
import java.time.LocalDate

/**
 * Which occurrence of a series the actions of the sheet are about.
 *
 * The row on screen and the occurrence it stands for are the same day only
 * until the occurrence moves: after that the row is drawn where the entry is
 * held, while the occurrence is still named by the day the series draws it on.
 * Everything written about it -- the `MOVED` line, the `EXDATE` -- is keyed by
 * that day, so this is the answer the whole sheet depends on.
 */
class TaskOccurrenceTest {

    @Test
    fun `a row of a series stands for the day it is drawn on`() {
        val task = task(date = "2026-08-20", repeater = "+1w")

        assertEquals(LocalDate.of(2026, 8, 20), task.occurrence())
    }

    @Test
    fun `a moved occurrence stands for the day of the series, not the day it is held`() {
        val task = task(date = "2026-08-22", repeater = "+1w", movedFrom = "2026-08-20")

        assertEquals(LocalDate.of(2026, 8, 20), task.occurrence())
    }

    @Test
    fun `an entry that does not repeat has no occurrence to act on`() {
        assertNull(task(date = "2026-08-20", repeater = null).occurrence())
    }

    @Test
    fun `a bare timestamp has no keyword an exception could be written against`() {
        assertNull(task(date = "2026-08-20", repeater = "+1w", timestampType = null).occurrence())
    }

    @Test
    fun `a deadline repeats like anything else, and its occurrences can be acted on`() {
        val task = task(
            date = "2026-08-22",
            repeater = "+1w",
            timestampType = TimestampType.DEADLINE,
            movedFrom = "2026-08-20",
        )

        assertEquals(LocalDate.of(2026, 8, 20), task.occurrence())
    }
}
