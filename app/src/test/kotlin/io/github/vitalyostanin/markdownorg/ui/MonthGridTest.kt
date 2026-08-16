package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * How a month is laid out on a calendar, and how much each of its days is said
 * to carry. Both answer without a device, which is what keeps them out of the
 * composable that draws them.
 */
class MonthGridTest {

    @Test
    fun `a month is filled out to whole weeks`() {
        // August 2026 starts on a Saturday and ends on a Monday: five days are
        // borrowed at the front and six at the back, which is what makes the
        // grid rectangular.
        val cells = buildMonthGrid(anchor = LocalDate.of(2026, 8, 16), today = TODAY)

        assertEquals(0, cells.size % DayOfWeek.entries.size)
        assertEquals(LocalDate.of(2026, 7, 27), cells.first().date)
        assertEquals(LocalDate.of(2026, 9, 6), cells.last().date)
        assertEquals(DayOfWeek.MONDAY, cells.first().date.dayOfWeek)
    }

    @Test
    fun `the borrowed days are marked as another month and carry their own dates`() {
        val cells = buildMonthGrid(anchor = LocalDate.of(2026, 8, 16), today = TODAY)
        val borrowed = cells.filter { it.otherMonth }

        // Real dates rather than blanks: a cell of the previous month opens
        // like any other, and a hole in the first week would read as a day
        // that cannot be looked at.
        assertEquals(11, borrowed.size)
        assertTrue(borrowed.all { it.date.monthValue != 8 })
        assertTrue(cells.filterNot { it.otherMonth }.all { it.date.monthValue == 8 })
    }

    @Test
    fun `a month that starts on the first day of a week borrows nothing`() {
        // June 2026 starts on a Monday and has thirty days, which is four
        // weeks and two days: nothing at the front, five borrowed at the back.
        val cells = buildMonthGrid(anchor = LocalDate.of(2026, 6, 10), today = TODAY)

        assertEquals(LocalDate.of(2026, 6, 1), cells.first().date)
        assertEquals(35, cells.size)
    }

    @Test
    fun `today and the weekend are marked wherever the anchor sits`() {
        val cells = buildMonthGrid(anchor = LocalDate.of(2026, 8, 1), today = TODAY)

        assertEquals(TODAY, cells.single { it.today }.date)
        assertTrue(
            cells.filter { it.weekend }
                .all { it.date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) },
        )
    }

    @Test
    fun `a month the reader has stepped away from marks no day as today`() {
        val cells = buildMonthGrid(anchor = LocalDate.of(2026, 11, 3), today = TODAY)

        assertTrue(cells.none { it.today })
    }

    @Test
    fun `a week starting on Sunday shifts the grid without changing the month`() {
        val cells = buildMonthGrid(
            anchor = LocalDate.of(2026, 8, 16),
            today = TODAY,
            firstDay = DayOfWeek.SUNDAY,
        )

        assertEquals(DayOfWeek.SUNDAY, cells.first().date.dayOfWeek)
        assertEquals(LocalDate.of(2026, 8, 1), cells.first { !it.otherMonth }.date)
    }

    @Test
    fun `a day carries every row of it, and says how many have slipped`() {
        val load = listOf(
            AgendaDay(
                TODAY,
                agenda(
                    day(
                        date = "2026-08-16",
                        overdue = listOf(task(heading = "Renew the certificate")),
                        scheduledTimed = listOf(task(heading = "Daily standup", time = "09:30")),
                        scheduledNoTime = listOf(task(heading = "Order the parts")),
                    ),
                ).toSections(),
            ),
        ).monthLoad()

        assertEquals(MonthLoad(total = 3, overdue = 1), load[TODAY])
    }

    @Test
    fun `an empty day is left out, so a cell finding nothing draws no chip`() {
        val load = listOf(
            AgendaDay(TODAY, agenda(day(date = "2026-08-16")).toSections()),
            // The span whose entries have no date has no place on a calendar,
            // however much it holds.
            AgendaDay(null, flatAgenda(task(heading = "No date at all")).toSections()),
        ).monthLoad()

        assertTrue(load.isEmpty())
        assertNull(load[TODAY])
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 16)
    }
}
