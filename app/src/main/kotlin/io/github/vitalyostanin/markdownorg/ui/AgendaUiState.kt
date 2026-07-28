package io.github.vitalyostanin.markdownorg.ui

import java.time.LocalDate
import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.Task

/** One line of the agenda: the task plus what the row shows around it. */
data class AgendaRow(
    val task: Task,
    /** Start time, or the date for an entry that has passed. */
    val time: String,
    /**
     * Days from the agenda date, negative when overdue. Kept as a number
     * rather than a formatted label: the wording is plural-sensitive and
     * belongs to the resources, not to this projection.
     */
    val daysOffset: Long,
)

/**
 * The agenda split the way both layouts read it.
 *
 * One projection feeds both: the list draws the three groups as sections, the
 * time layout draws [overdue] and [untimed] as bands and lays [timed] out on
 * the hour axis. Splitting once is what keeps the promise that the layouts
 * differ in visual language and not in how much they show.
 */
data class AgendaSections(
    /** Tasks whose date has passed. */
    val overdue: List<AgendaRow>,
    /** Tasks at a specific time, in the core's order. */
    val timed: List<AgendaRow>,
    /** All-day tasks and the deadlines coming up. */
    val untimed: List<AgendaRow>,
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && timed.isEmpty() && untimed.isEmpty()
}

sealed interface AgendaUiState {
    data object Loading : AgendaUiState

    /**
     * Both projections are built up front rather than on the layout switch:
     * they come from the same scan, and rebuilding one of them on a toggle
     * would put a directory walk behind a button press.
     */
    data class Ready(
        val date: LocalDate,
        val sections: AgendaSections,
        val timeline: Timeline,
    ) : AgendaUiState

    data class Failed(val message: String) : AgendaUiState
}

/**
 * Regroups the day buckets the core returns.
 *
 * Days are flattened together. That is right for a single day and stands in
 * for week and month, which will want a header per day before their entries
 * can be told apart.
 */
fun AgendaResult.toSections(): AgendaSections = AgendaSections(
    overdue = days.flatMap { it.overdue }.map(Task::toAgendaRow),
    timed = days.flatMap { it.scheduledTimed }.map(Task::toAgendaRow),
    // The `Tasks` scope fills `tasks` instead of `days`, and its entries have
    // no day to sit on; they join the untimed group rather than vanish.
    untimed = (days.flatMap { it.scheduledNoTime + it.upcoming } + tasks).map(Task::toAgendaRow),
)

internal fun Task.toAgendaRow(): AgendaRow =
    AgendaRow(task = this, time = rowTime(), daysOffset = daysOffset ?: 0)

private fun Task.rowTime(): String = when {
    timestampTime != null -> timestampTime!!
    // A date that has passed replaces the empty time column: without it an
    // overdue row would say how many days late it is but not since when.
    (daysOffset ?: 0) < 0 -> timestampDate?.let(::asDayMonth) ?: ""
    else -> ""
}

/** `YYYY-MM-DD` shown as `DD.MM`, the form the rest of the agenda uses. */
private fun asDayMonth(date: String): String {
    val parts = date.split('-')
    return if (parts.size == 3) "${parts[2]}.${parts[1]}" else date
}
