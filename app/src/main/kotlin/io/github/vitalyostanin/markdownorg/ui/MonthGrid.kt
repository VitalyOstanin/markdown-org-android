package io.github.vitalyostanin.markdownorg.ui

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
 * Two numbers rather than the rows themselves: a cell has room for a count and
 * for the mark that some of that count has slipped, and the rows behind it are
 * what the day view is for.
 */
data class MonthLoad(val total: Int, val overdue: Int)

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
 * What each day of the payload carries, by date.
 *
 * Days with nothing on them are left out, so a cell that finds no entry is an
 * empty day and draws no chip. The span whose entries have no date at all
 * ([AgendaSpan.TASKS]) has no place on a calendar and contributes nothing.
 */
internal fun List<AgendaDay>.monthLoad(): Map<LocalDate, MonthLoad> = buildMap {
    for (day in this@monthLoad) {
        val date = day.date ?: continue
        val sections = day.sections
        val total = sections.overdue.size + sections.timed.size + sections.untimed.size
        if (total > 0) {
            put(date, MonthLoad(total = total, overdue = sections.overdue.size))
        }
    }
}
