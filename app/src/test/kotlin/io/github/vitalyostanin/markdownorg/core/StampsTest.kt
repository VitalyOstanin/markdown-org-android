package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * What a note says in the date and the hour of a timestamp, read the way the
 * extractor writes it.
 *
 * The extractor captures both by shape and keeps them as the file has them:
 * `\d{4}-\d{2}-\d{2}` says nothing about a thirtieth of February, and
 * `\d{1,2}:\d{2}` allows an hour of one digit. Read strictly, the first throws
 * and the second is lost -- so both are read here, in one place, and answer
 * `null` for what is not a date or an hour rather than for what is merely
 * written the shorter way.
 */
class StampsTest {

    @Test
    fun `a date the note states reads as that date`() {
        assertEquals(LocalDate.of(2026, 8, 6), statedDate("2026-08-06"))
    }

    @Test
    fun `a day the calendar does not have is not a date`() {
        // The shape passes the extractor's capture; the meaning does not.
        assertNull(statedDate("2026-02-30"))
        assertNull(statedDate("2026-13-01"))
    }

    @Test
    fun `what is not a date at all is not one`() {
        assertNull(statedDate("завтра"))
        assertNull(statedDate(""))
        assertNull(statedDate(null))
    }

    @Test
    fun `an hour of two digits reads as that hour`() {
        assertEquals(LocalTime.of(15, 0), statedTime("15:00"))
        assertEquals(LocalTime.of(0, 5), statedTime("00:05"))
    }

    @Test
    fun `an hour written with one digit reads as the same hour`() {
        assertEquals(LocalTime.of(9, 0), statedTime("9:00"))
        assertEquals(LocalTime.of(0, 30), statedTime("0:30"))
    }

    @Test
    fun `an hour outside the clock is not one`() {
        assertNull(statedTime("24:00"))
        assertNull(statedTime("9:60"))
        assertNull(statedTime("9"))
        assertNull(statedTime("вечером"))
        assertNull(statedTime(null))
    }

    /**
     * A range is what the extractor captures the first half of, so the halves
     * never arrive here joined; asserted so that a reading widened later does
     * not start answering for a field it was not given.
     */
    @Test
    fun `a range is not an hour`() {
        assertNull(statedTime("15:00-16:00"))
    }
}
