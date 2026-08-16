package io.github.vitalyostanin.markdownorg.ui

import uniffi.markdown_org_ffi.TimestampType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

// What a month is laid out as, apart from what draws it. The arithmetic of a
// calendar — where the month starts in its first week, how many days the
// neighbours lend it — is the same on any screen and answers without a device,
// which is what keeps it out of the composable and in a test on the JVM.

/**
 * How much work a day carries, as a cell says it.
 *
 * A count and whether it has slipped, rather than the rows themselves: a cell
 * has room for one number, and the rows behind it are what the day view is
 * for.
 *
 * [total] counts what is dated to that day and nothing else. The overdue and
 * the upcoming buckets are deliberately left out of it: the core files a task
 * under the day it is dated to *and* repeats it under today as arrears or as a
 * deadline coming up, so counting those buckets counted the same task twice —
 * once in its own cell and once in whichever cell today happened to be.
 */
data class MonthLoad(val total: Int, val overdue: Boolean)

/**
 * One cell of the month grid, in the order the grid lays them out.
 *
 * [otherMonth] marks the days the neighbouring months lend to fill the first
 * and last weeks. They carry a real date and open like any other cell — a
 * calendar that refuses the 31st of the previous month because of where it
 * falls would be answering a question nobody asked.
 */
data class MonthCell(
    val date: LocalDate,
    val otherMonth: Boolean,
    val weekend: Boolean,
    val today: Boolean,
)

/**
 * The month [anchor] falls in: the days its first week borrows from the month
 * before, the month itself, then enough of the next one to finish the last
 * week. That is what gives the grid its 4, 5 or 6 rows.
 *
 * [firstDay] is the weekday a week starts on. The core groups a week from
 * Monday (`num_days_from_monday` in its agenda), so a grid that started on
 * Sunday would cut the month into different weeks than the week span shows,
 * and stepping between the two spans would not line up.
 */
internal fun buildMonthGrid(
    anchor: LocalDate,
    today: LocalDate,
    firstDay: DayOfWeek = DayOfWeek.MONDAY,
): List<MonthCell> {
    val columns = DayOfWeek.entries.size
    val month = YearMonth.from(anchor)
    val first = month.atDay(1)
    val leading = (first.dayOfWeek.value - firstDay.value + columns) % columns
    val cells = leading + month.lengthOfMonth()
    val trailing = (columns - cells % columns) % columns

    return (0 until cells + trailing).map { index ->
        val date = first.plusDays((index - leading).toLong())
        MonthCell(
            date = date,
            otherMonth = YearMonth.from(date) != month,
            weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY,
            today = date == today,
        )
    }
}

/**
 * What each day of the payload carries, by date, as of [today].
 *
 * Days with nothing on them are left out, so a cell that finds no entry is an
 * empty day and draws no chip. The span whose entries have no date at all
 * ([AgendaSpan.TASKS]) has no place on a calendar and contributes nothing.
 *
 * A day is marked as overdue from its own rows rather than from the arrears
 * bucket: the date has gone by and something planned still sits on it. Read
 * off the bucket instead, the whole month's arrears landed in one cell — the
 * core gathers them under today and nowhere else — and moved from cell to cell
 * as the reader paged the calendar.
 */
internal fun List<AgendaDay>.monthLoad(today: LocalDate): Map<LocalDate, MonthLoad> = buildMap {
    for (day in this@monthLoad) {
        val date = day.date ?: continue
        val sections = day.sections
        // Dated to this day: the rows of the day itself. What the core adds
        // relative to today — arrears and deadlines coming up — is already
        // counted in the cells those tasks belong to.
        val rows = (sections.timed + sections.untimed).filter { it.daysOffset <= 0L }
        if (rows.isNotEmpty()) {
            val owed = date < today && rows.any { it.owes() }

            put(date, MonthLoad(total = rows.size, overdue = owed))
        }
    }
}

/**
 * Whether a row left something behind once its date went by.
 *
 * Only the planning keywords do, as the core has it (`keeps_a_missed_date`): a
 * meeting that has been and gone is not a debt, a SCHEDULED or DEADLINE that
 * has not been closed is.
 */
private fun AgendaRow.owes(): Boolean =
    task.timestampType == TimestampType.SCHEDULED || task.timestampType == TimestampType.DEADLINE
