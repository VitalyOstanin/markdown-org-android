package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.TimestampType
import uniffi.markdown_org_ffi.TimestampType.DEADLINE

class OverdueBandsTest {

    @Test
    fun `each band takes the rows that fall in it`() {
        val rows = listOf(
            row(heading = "Missed English", repeater = "+7d", daysOffset = -5),
            row(heading = "Pay the tax", daysOffset = -1),
            row(heading = "Service the car", daysOffset = -152),
            row(heading = "Eye clinic", daysOffset = -1947),
        )

        assertEquals(
            listOf(
                OverdueBand.MISSED_REPEAT to listOf("Missed English"),
                OverdueBand.RECENT to listOf("Pay the tax"),
                OverdueBand.EARLIER to listOf("Service the car"),
                OverdueBand.LONG_AGO to listOf("Eye clinic"),
            ),
            rows.intoBands().map { it.band to it.rows.map { row -> row.task.heading } },
        )
    }

    @Test
    fun `a band nothing fell into is left out`() {
        val bands = listOf(row(daysOffset = -2)).intoBands()

        assertEquals(listOf(OverdueBand.RECENT), bands.map(OverdueGroup::band))
    }

    @Test
    fun `the order within a band is the order it came in`() {
        val rows = listOf(
            row(heading = "First", daysOffset = -40),
            row(heading = "Second", daysOffset = -300),
            row(heading = "Third", daysOffset = -41),
        )

        assertEquals(
            listOf("First", "Second", "Third"),
            rows.intoBands().single().rows.map { it.task.heading },
        )
    }

    @Test
    fun `a repeater lands among the missed repeats however old it is`() {
        val rows = listOf(
            row(heading = "Weekly", repeater = "+7d", daysOffset = -900),
            row(heading = "Monthly", repeater = "++1m", daysOffset = -2),
        )

        assertEquals(listOf(OverdueBand.MISSED_REPEAT), rows.intoBands().map(OverdueGroup::band))
    }

    @Test
    fun `a deadline is banded by its age like anything else`() {
        val rows = listOf(
            row(heading = "Renew", timestampType = DEADLINE, daysOffset = -3),
            row(heading = "File", timestampType = DEADLINE, daysOffset = -167),
        )

        assertEquals(
            listOf(OverdueBand.RECENT, OverdueBand.EARLIER),
            rows.intoBands().map(OverdueGroup::band),
        )
    }

    @Test
    fun `the boundaries fall on the day, not around it`() {
        assertEquals(OverdueBand.RECENT, row(daysOffset = -7).overdueBand())
        assertEquals(OverdueBand.EARLIER, row(daysOffset = -8).overdueBand())
        assertEquals(OverdueBand.EARLIER, row(daysOffset = -LONG_AGO_DAYS).overdueBand())
        assertEquals(OverdueBand.LONG_AGO, row(daysOffset = -LONG_AGO_DAYS - 1).overdueBand())
    }

    @Test
    fun `only a row of the recent band spells its age out`() {
        assertTrue(row(daysOffset = -7).statesAge())
        assertFalse(row(daysOffset = -8).statesAge())
        // Today and ahead: nothing has slipped, and the label reads "in N days".
        assertTrue(row(daysOffset = 0).statesAge())
        assertTrue(row(daysOffset = 5).statesAge())
    }

    @Test
    fun `sections hand the bands out ready split`() {
        val sections = agenda(
            day(
                overdue = listOf(
                    task(heading = "Old", daysOffset = -400),
                    task(heading = "Fresh", daysOffset = -1),
                ),
            ),
        ).toSections()

        assertEquals(
            listOf(OverdueBand.RECENT, OverdueBand.LONG_AGO),
            sections.overdueBands.map(OverdueGroup::band),
        )
    }
}

private fun row(
    heading: String = "Task",
    repeater: String? = null,
    timestampType: TimestampType? = TimestampType.SCHEDULED,
    daysOffset: Long,
): AgendaRow = task(
    heading = heading,
    repeater = repeater,
    timestampType = timestampType,
    daysOffset = daysOffset,
).toAgendaRow()
