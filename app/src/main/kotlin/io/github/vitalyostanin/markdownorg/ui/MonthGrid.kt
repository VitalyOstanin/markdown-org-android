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
 *
 * [dueSoon] marks a date still ahead that a deadline's warning window has
 * reached. The window is the core's to decide — it applies Org's
 * `org-deadline-warning-days`, and the `-Xd` a timestamp may carry, when it
 * repeats the deadline under today — so the client reads the answer rather
 * than the rule.
 */
data class MonthLoad(val total: Int, val overdue: Boolean, val dueSoon: Boolean = false)

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
 * The days of [days] laid out as cells, in the order they arrived.
 *
 * Which dates those are is the core's answer, not this function's: asked for
 * [uniffi.markdown_org_ffi.Scope.MONTH_GRID] it returns the whole weeks the
 * anchor month touches, beginning on the weekday it was given (its ADR-0028
 * and ADR-0030). Cutting the month into weeks a second time here would be a
 * second implementation of that rule, free to disagree with the one that
 * produced the rows — and it did: the borrowed days at either end stood for
 * dates the answer said nothing about, so a task on the 30th of the previous
 * month was missing from the cell that shows it.
 *
 * What is still read off the date is what a day does not carry: whether it
 * falls outside the month [anchor] names, whether it is a weekend, and whether
 * it is [today].
 */
internal fun buildMonthGrid(
    days: List<AgendaDay>,
    anchor: LocalDate,
    today: LocalDate,
): List<MonthCell> {
    val month = YearMonth.from(anchor)

    return days.mapNotNull { day ->
        val date = day.date ?: return@mapNotNull null
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
    val warned = warnedDeadlines(today)

    for (day in this@monthLoad) {
        val date = day.date ?: continue
        val sections = day.sections
        // Dated to this day: the rows of the day itself. What the core adds
        // relative to today — arrears and deadlines coming up — is already
        // counted in the cells those tasks belong to.
        val rows = (sections.timed + sections.untimed).filter { it.daysOffset <= 0L }
        if (rows.isNotEmpty()) {
            val owed = date < today && rows.any { it.owes() }
            val due = date >= today && rows.any { it.key in warned }

            put(date, MonthLoad(total = rows.size, overdue = owed, dueSoon = due))
        }
    }
}

/**
 * The deadlines the core is already warning about, by row key.
 *
 * They are the copies it files under today: a deadline enters that bucket once
 * its warning window opens, which is the same rule Org applies in the agenda of
 * the day being lived through. The mark itself goes on the date the deadline
 * falls on — the grid already shows the reader where that date is.
 */
private fun List<AgendaDay>.warnedDeadlines(today: LocalDate): Set<String> =
    firstOrNull { it.date == today }
        ?.sections
        ?.untimed
        .orEmpty()
        .filter { it.daysOffset > 0L && it.task.timestampType == TimestampType.DEADLINE }
        .map { it.key }
        .toSet()

/**
 * Whether a row left something behind once its date went by.
 *
 * Only the planning keywords do, as the core has it (`keeps_a_missed_date`): a
 * meeting that has been and gone is not a debt, a SCHEDULED or DEADLINE that
 * has not been closed is.
 */
private fun AgendaRow.owes(): Boolean =
    task.timestampType == TimestampType.SCHEDULED || task.timestampType == TimestampType.DEADLINE
