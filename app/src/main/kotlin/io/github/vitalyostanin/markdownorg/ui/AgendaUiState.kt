package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R
import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.Task
import java.time.LocalDate
import java.time.LocalTime

/**
 * What stands in the time column of a row.
 *
 * Kept as a time and a date rather than as the text of either, for the same
 * reason as [AgendaRow.daysOffset]: the order of day and month, the clock the
 * hour is written on and the separator between its parts all belong to the
 * locale of the reader, and this projection is built before the screen knows
 * which locale that is.
 */
sealed interface RowTime {
    /** The hour the note gives the task. */
    data class Clock(val at: LocalTime) : RowTime

    /** The date an overdue row slipped from, shown where its time would be. */
    data class Since(val date: LocalDate) : RowTime

    /**
     * A time the note states in a form that is not one.
     *
     * Nothing stops a note from writing `<2026-07-03 half past nine>`, and
     * dropping what cannot be parsed would take the only clue the row has
     * about when the task is due. It is shown as written.
     */
    data class Verbatim(val text: String) : RowTime

    /** Nothing to show: an all-day task still ahead. */
    data object None : RowTime
}

/** One line of the agenda: the task plus what the row shows around it. */
data class AgendaRow(
    val task: Task,
    /** Start time, or the date for an entry that has passed. */
    val time: RowTime,
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

/**
 * One thing the walk behind the agenda ran into.
 *
 * Kept as a resource id rather than a finished string for the same reason as
 * [SyncMessage]: the wording, and its plural forms, belong to the resources.
 */
sealed interface ScanNotice {
    /** Something that happened to a number of files, and to how many. */
    data class Counted(@param:PluralsRes val text: Int, val count: Int) : ScanNotice

    /** Something that either happened or did not, with nothing to count. */
    data class Flag(@param:StringRes val text: Int) : ScanNotice
}

/**
 * What is worth telling the user about the files the agenda was built from.
 *
 * Reasons are listed apart rather than summed into "N files skipped": a note
 * in another encoding is fixed by converting it, an unreadable one by looking
 * at permissions, and a truncated list by narrowing the scan.
 */
fun AgendaResult.notices(): List<ScanNotice> = buildList {
    if (stats.filesNotUtf8 > 0u) {
        add(ScanNotice.Counted(R.plurals.agenda_skipped_encoding, stats.filesNotUtf8.toInt()))
    }
    if (stats.filesFailed > 0u) {
        add(ScanNotice.Counted(R.plurals.agenda_unreadable, stats.filesFailed.toInt()))
    }
    if (stats.filesTooLarge > 0u) {
        add(ScanNotice.Counted(R.plurals.agenda_skipped_size, stats.filesTooLarge.toInt()))
    }
    if (stats.nonutf8Paths > 0u) {
        add(ScanNotice.Counted(R.plurals.agenda_unnamed_paths, stats.nonutf8Paths.toInt()))
    }
    if (stats.truncated) {
        add(ScanNotice.Flag(R.string.agenda_truncated))
    }
}

/**
 * Whether an edit aimed at this task can reach its file.
 *
 * A filename on Linux is an arbitrary byte sequence; one that is not UTF-8
 * arrives here with U+FFFD in place of the invalid bytes and no longer names
 * anything on disk, so every edit would come back as "file not found". The
 * count of such paths is reported separately — see [notices]. A file whose
 * name genuinely contains U+FFFD is treated the same way, which costs that
 * one file its actions and needs no second channel to detect.
 */
fun Task.isEditable(): Boolean = !file.contains('�')

sealed interface AgendaUiState {
    /** Before the first agenda of the session, and only then — see [Ready.refreshing]. */
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
        /** What the walk behind this agenda skipped, if anything. */
        val notices: List<ScanNotice> = emptyList(),
        /**
         * Another scan is under way over what is on screen.
         *
         * Every edit and every sync ends in one, and what comes back differs
         * from what is shown by a line or two. Going back through [Loading]
         * for that would take the header away — the layout switch with it —
         * and rebuild the list from its first row, losing the place the user
         * was at.
         */
        val refreshing: Boolean = false,
    ) : AgendaUiState

    /**
     * The scan failed and there is no agenda to show.
     *
     * A message the resources word rather than a finished string: the reason
     * is shown on a screen that is otherwise in the language of the reader.
     */
    data class Failed(val reason: SyncMessage) : AgendaUiState
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

private fun Task.rowTime(): RowTime {
    // Held in a local rather than read twice through `!!`: the generated
    // record declares its fields as `var`, so a smart cast is not available
    // and the check alone does not narrow the type.
    val stated = timestampTime

    return when {
        stated != null -> runCatching { LocalTime.parse(stated) }
            .fold({ RowTime.Clock(it) }, { RowTime.Verbatim(stated) })

        // A date that has passed replaces the empty time column: without it an
        // overdue row would say how many days late it is but not since when.
        (daysOffset ?: 0) < 0 -> slippedFrom() ?: RowTime.None

        else -> RowTime.None
    }
}

/** The date of the task, when it states one that reads as a date. */
private fun Task.slippedFrom(): RowTime.Since? = timestampDate
    ?.let { stated -> runCatching { LocalDate.parse(stated) }.getOrNull() }
    ?.let(RowTime::Since)
