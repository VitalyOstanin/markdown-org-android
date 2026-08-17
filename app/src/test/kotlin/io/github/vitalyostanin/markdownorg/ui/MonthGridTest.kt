package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.TimestampType
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
    fun `a day carries the rows dated to it`() {
        val load = listOf(
            AgendaDay(
                TODAY,
                agenda(
                    day(
                        date = "2026-08-16",
                        scheduledTimed = listOf(task(heading = "Daily standup", time = "09:30")),
                        scheduledNoTime = listOf(task(heading = "Order the parts")),
                    ),
                ).toSections(),
            ),
        ).monthLoad(TODAY)

        assertEquals(MonthLoad(total = 2, overdue = false), load[TODAY])
    }

    @Test
    fun `what today is owed is not counted a second time under today`() {
        // The core files a task under the day it is dated to and repeats it
        // under today as arrears — so counting the arrears bucket counted the
        // same task twice, once in its own cell and once in today's. Which
        // cell that was moved with the reader, because the whole month's
        // arrears are gathered in one place.
        val slipped = task(heading = "Renew the certificate", date = "2026-08-10", daysOffset = -6)
        val load = listOf(
            AgendaDay(
                LocalDate.of(2026, 8, 10),
                agenda(day(date = "2026-08-10", scheduledNoTime = listOf(slipped))).toSections(),
            ),
            AgendaDay(
                TODAY,
                agenda(
                    day(
                        date = "2026-08-16",
                        overdue = listOf(slipped),
                        scheduledTimed = listOf(task(heading = "Daily standup", time = "09:30")),
                    ),
                ).toSections(),
            ),
        ).monthLoad(TODAY)

        assertEquals(MonthLoad(total = 1, overdue = true), load[LocalDate.of(2026, 8, 10)])
        assertEquals(MonthLoad(total = 1, overdue = false), load[TODAY])
    }

    @Test
    fun `a deadline still ahead is counted in its own day and not in today`() {
        // The upcoming bucket is the same repetition the other way round: a
        // deadline within the warning window is reported under today as well
        // as on the day it falls. Counted once, in its own cell — and that
        // cell is the one the warning marks.
        val ahead = task(
            heading = "File the return",
            line = 12u,
            timestampType = TimestampType.DEADLINE,
            date = "2026-08-20",
            daysOffset = 4,
        )
        val load = listOf(
            AgendaDay(
                TODAY,
                agenda(day(date = "2026-08-16", upcoming = listOf(ahead))).toSections(),
            ),
            AgendaDay(
                LocalDate.of(2026, 8, 20),
                agenda(
                    day(
                        date = "2026-08-20",
                        scheduledNoTime = listOf(ahead.copy(daysOffset = 0)),
                    ),
                ).toSections(),
            ),
        ).monthLoad(TODAY)

        assertNull(load[TODAY])
        assertEquals(
            MonthLoad(total = 1, overdue = false, dueSoon = true),
            load[LocalDate.of(2026, 8, 20)],
        )
    }

    @Test
    fun `a deadline the core is not warning about yet is left unmarked`() {
        // The window belongs to the core: it applies Org's rule, including the
        // `-Xd` a timestamp may carry. What reaches the client is the answer —
        // a copy under today — and a date without one is simply a date ahead.
        val load = listOf(
            AgendaDay(TODAY, agenda(day(date = "2026-08-16")).toSections()),
            AgendaDay(
                LocalDate.of(2026, 9, 30),
                agenda(
                    day(
                        date = "2026-09-30",
                        scheduledNoTime = listOf(
                            task(
                                heading = "Renew the licence",
                                line = 12u,
                                timestampType = TimestampType.DEADLINE,
                                date = "2026-09-30",
                            ),
                        ),
                    ),
                ).toSections(),
            ),
        ).monthLoad(TODAY)

        assertEquals(
            MonthLoad(total = 1, overdue = false, dueSoon = false),
            load[LocalDate.of(2026, 9, 30)],
        )
    }

    @Test
    fun `a deadline already missed reads as owed rather than as due`() {
        // One chip, one state: once the date has gone by, what it owes is what
        // the reader needs, not that it was due.
        val missed = task(
            heading = "Send the report",
            line = 12u,
            timestampType = TimestampType.DEADLINE,
            date = "2026-08-10",
            daysOffset = -6,
        )
        val load = listOf(
            AgendaDay(
                LocalDate.of(2026, 8, 10),
                agenda(day(date = "2026-08-10", scheduledNoTime = listOf(missed))).toSections(),
            ),
            AgendaDay(
                TODAY,
                agenda(
                    day(date = "2026-08-16", upcoming = listOf(missed.copy(daysOffset = 6))),
                ).toSections(),
            ),
        ).monthLoad(TODAY)

        assertEquals(
            MonthLoad(total = 1, overdue = true, dueSoon = false),
            load[LocalDate.of(2026, 8, 10)],
        )
    }

    @Test
    fun `a date gone by owes nothing unless something planned sits on it`() {
        // Only the planning keywords leave a debt behind, as the core has it:
        // a meeting that has been and gone is not one.
        val load = listOf(
            AgendaDay(
                LocalDate.of(2026, 8, 12),
                agenda(
                    day(
                        date = "2026-08-12",
                        scheduledTimed = listOf(
                            task(
                                heading = "The lecture",
                                date = "2026-08-12",
                                time = "18:00",
                                timestampType = TimestampType.PLAIN,
                            ),
                        ),
                    ),
                ).toSections(),
            ),
        ).monthLoad(TODAY)

        assertEquals(MonthLoad(total = 1, overdue = false), load[LocalDate.of(2026, 8, 12)])
    }

    @Test
    fun `an empty day is left out, so a cell finding nothing draws no chip`() {
        val load = listOf(
            AgendaDay(TODAY, agenda(day(date = "2026-08-16")).toSections()),
            // The span whose entries have no date has no place on a calendar,
            // however much it holds.
            AgendaDay(null, flatAgenda(task(heading = "No date at all")).toSections()),
        ).monthLoad(TODAY)

        assertTrue(load.isEmpty())
        assertNull(load[TODAY])
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 16)
    }
}
