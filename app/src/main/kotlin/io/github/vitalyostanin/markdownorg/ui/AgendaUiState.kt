package io.github.vitalyostanin.markdownorg.ui

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

sealed interface AgendaUiState {
    data object Loading : AgendaUiState

    data class Ready(val title: String, val rows: List<AgendaRow>) : AgendaUiState

    data class Failed(val message: String) : AgendaUiState
}

/**
 * Flattens the day buckets the core returns into rows.
 *
 * The buckets stay in their order — overdue first, then the timed entries,
 * then the all-day ones, then what is coming up — because that order is the
 * agenda's, not a rendering detail.
 */
fun AgendaResult.toRows(): List<AgendaRow> =
    days.flatMap { day ->
        day.overdue + day.scheduledTimed + day.scheduledNoTime + day.upcoming
    }.plus(tasks).map { task ->
        AgendaRow(task = task, time = task.rowTime(), daysOffset = task.daysOffset ?: 0)
    }

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
