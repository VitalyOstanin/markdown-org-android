package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.MergedTag
import io.github.vitalyostanin.markdownorg.core.fileMatchesTag
import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.Task
import java.io.File
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

/**
 * Which collection a row came from, as the agenda shows it.
 *
 * [tone] is a place in the palette rather than a colour: the palette belongs
 * to the theme, and the same collection has to keep its colour between a light
 * screen and a dark one.
 *
 * [root] is the directory the collection reads, as the walk reports it. The
 * chips carry names, and two collections may be named alike or named after
 * something other than where they live; the directory is what a long press on
 * the chip says, and it is the answer to which of them a row came from.
 */
data class CollectionLabel(val id: String, val name: String, val tone: Int, val root: String = "")

/**
 * One collection as the filter over the agenda offers it.
 *
 * The whole row of them is empty while there is a single collection: a filter
 * with one switch in it either shows everything or nothing, and neither is a
 * choice worth a row of the screen.
 */
data class CollectionChoice(val label: CollectionLabel, val shown: Boolean)

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
    /**
     * Which collection the row is from, or `null` while there is only one.
     *
     * Absent rather than always shown: a device with one collection has
     * nothing to tell apart, and a label on every row would be a column of the
     * same word.
     */
    val collection: CollectionLabel? = null,
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

    /** [overdue] split by what each row asks of the reader — see [OverdueBand]. */
    val overdueBands: List<OverdueGroup> get() = overdue.intoBands()
}

/**
 * One day of the agenda, with what falls on it.
 *
 * [date] is `null` for a span whose entries carry no day at all — see
 * [AgendaSpan.TASKS]. Everything else names the day it describes, which is what
 * a week and a month need before their entries can be told apart: without it
 * the two of them are one long list where Tuesday and Friday sit together.
 */
data class AgendaDay(val date: LocalDate?, val sections: AgendaSections)

/**
 * Everything on screen as one set of sections.
 *
 * What the hour axis is drawn from, and what a single day was before there were
 * several: the axis covers one day, and only [AgendaSpan.DAY] is ever drawn on
 * it. For the wider spans this is what the notices and the empty state are
 * counted against.
 */
internal fun List<AgendaDay>.merged(): AgendaSections = when (size) {
    1 -> single().sections

    else -> AgendaSections(
        overdue = flatMap { it.sections.overdue },
        timed = flatMap { it.sections.timed },
        untimed = flatMap { it.sections.untimed },
    )
}

/** The same days without the rows of the collections named in [hidden]. */
internal fun List<AgendaDay>.showing(hidden: Set<String>): List<AgendaDay> = if (hidden.isEmpty()) {
    this
} else {
    map { day -> day.copy(sections = day.sections.showing(hidden)) }
}

/**
 * The same days narrowed to one tag of [dictionary].
 *
 * Second of the two levels the agenda is filtered by, and applied after the
 * first: which collections are read is what put these rows here, and a tag
 * selects notes among them by their file name. A name the dictionary does not
 * hold narrows nothing — a tag left over from an edited file must not empty the
 * agenda.
 */
internal fun List<AgendaDay>.tagged(tag: String?, dictionary: List<MergedTag>): List<AgendaDay> {
    val selected = dictionary.firstOrNull { it.name == tag } ?: return this
    return map { day -> day.copy(sections = day.sections.tagged(selected, dictionary)) }
}

/**
 * The same sections narrowed to one tag, for the spans that carry no days.
 *
 * The pair of [showing] and this one exists on both shapes for the same reason:
 * `Tasks` is a flat list, and a filter that only knew how to walk days would
 * quietly leave that span unfiltered.
 */
internal fun AgendaSections.tagged(tag: String?, dictionary: List<MergedTag>): AgendaSections {
    val selected = dictionary.firstOrNull { it.name == tag } ?: return this
    return tagged(selected, dictionary)
}

private fun AgendaSections.tagged(tag: MergedTag, dictionary: List<MergedTag>): AgendaSections {
    fun keeps(row: AgendaRow): Boolean = fileMatchesTag(File(row.task.file).name, tag, dictionary)

    return AgendaSections(
        overdue = overdue.filter(::keeps),
        timed = timed.filter(::keeps),
        untimed = untimed.filter(::keeps),
    )
}

/**
 * The same agenda without the rows of the collections named in [hidden].
 *
 * Applied to the sections rather than to the scan: hiding a collection is a
 * change of view over notes already read, and walking the directories again
 * for it would put a filesystem walk behind a tap on a chip.
 *
 * A row with no label belongs to a device with a single collection and is
 * never hidden — there is nothing on that screen to tell it apart from.
 */
internal fun AgendaSections.showing(hidden: Set<String>): AgendaSections = if (hidden.isEmpty()) {
    this
} else {
    AgendaSections(
        overdue = overdue.filterNot { it.hiddenBy(hidden) },
        timed = timed.filterNot { it.hiddenBy(hidden) },
        untimed = untimed.filterNot { it.hiddenBy(hidden) },
    )
}

private fun AgendaRow.hiddenBy(hidden: Set<String>): Boolean = collection?.id in hidden

/**
 * What a row that has slipped asks of whoever reads it.
 *
 * A single "overdue" group answers the question "what is late" and no other.
 * On a file kept for years the answers differ: a repeat missed on Tuesday is
 * today's work, a date from May wants a new one, and a date from 2021 wants to
 * be closed or dropped. They are shown apart because they are acted on apart —
 * the same reason org-super-agenda splits `:scheduled past` from
 * `:deadline past`.
 */
enum class OverdueBand {
    /**
     * An occurrence of a repeating task that was not done on the day it came
     * round. Not a debt: the next occurrence is what there is to do, and the
     * dates behind it are gone whatever happens.
     */
    MISSED_REPEAT,

    /** Slipped within the last week — still the current plan, a day or two out. */
    RECENT,

    /** Slipped this year. A plan that no longer holds and wants a new date. */
    EARLIER,

    /** Older than a year. Kept, but not planning any more. */
    LONG_AGO,
}

/** One band of the overdue group, with the rows that fell into it. */
data class OverdueGroup(val band: OverdueBand, val rows: List<AgendaRow>)

/** Slipped by no more than this, and it is still the current plan. */
private const val RECENT_DAYS = 7L

/** Beyond this the date says less than the fact that it is long gone. */
internal const val LONG_AGO_DAYS = 365L

/**
 * Which band a row falls into.
 *
 * A repeater wins over the age on purpose: whether its missed occurrence was
 * yesterday or last spring, what to do with it is the same.
 */
internal fun AgendaRow.overdueBand(): OverdueBand = when {
    task.kind() == AgendaKind.REPEAT -> OverdueBand.MISSED_REPEAT
    daysOffset >= -RECENT_DAYS -> OverdueBand.RECENT
    daysOffset >= -LONG_AGO_DAYS -> OverdueBand.EARLIER
    else -> OverdueBand.LONG_AGO
}

/**
 * Splits the overdue rows into bands, keeping the order within each and
 * dropping the bands nothing fell into.
 *
 * The order of the bands is the order they are worth reading in — what is
 * actionable today first, the archive last — rather than the order of the
 * dates, which is what buries a missed repeat under entries from 2021.
 */
internal fun List<AgendaRow>.intoBands(): List<OverdueGroup> {
    val byBand = groupBy(AgendaRow::overdueBand)

    return OverdueBand.entries.mapNotNull { band ->
        byBand[band]?.takeIf(List<AgendaRow>::isNotEmpty)?.let { OverdueGroup(band, it) }
    }
}

/**
 * Whether the row states its age as a label of its own.
 *
 * "Overdue by 1947 days" is a sentence about the calendar, not about the task,
 * and it takes the width the heading needs — on the real notes it left three
 * characters of a heading and the whole of the number. Past the recent band
 * the date the row already carries says it, and the band says the rest.
 */
internal fun AgendaRow.statesAge(): Boolean = daysOffset >= -RECENT_DAYS

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
     * The sections come from the scan; the hour axis is projected from them
     * where it is drawn. It has to be, because it carries the marker line for
     * the current moment, and the moment moves while this state stands still.
     * The projection is cheap next to the walk that produced the sections, and
     * the layout memoises it.
     */
    data class Ready(
        val date: LocalDate,
        /**
         * The days the span covers, each with what falls on it.
         *
         * One entry for a day agenda, seven for a week, and one dateless entry
         * for the flat list of tasks. The core puts the overdue and the
         * upcoming entries into the bucket of the current date and nowhere
         * else, so a week carries them under today rather than repeated under
         * every day of it.
         */
        val days: List<AgendaDay>,
        /** How much of the plan this agenda was asked for. */
        val span: AgendaSpan = AgendaSpan.DAY,
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
    ) : AgendaUiState {

        /**
         * The single day this used to be.
         *
         * Kept for the callers that are about one day and only ever see one —
         * the hour axis, and the tests that hand a screen its sections
         * directly.
         */
        constructor(
            date: LocalDate,
            sections: AgendaSections,
            notices: List<ScanNotice> = emptyList(),
            refreshing: Boolean = false,
        ) : this(date, listOf(AgendaDay(date, sections)), AgendaSpan.DAY, notices, refreshing)

        /** Everything on screen as one set of sections — see [merged]. */
        val sections: AgendaSections get() = days.merged()
    }

    /**
     * The scan failed and there is no agenda to show.
     *
     * A message the resources word rather than a finished string: the reason
     * is shown on a screen that is otherwise in the language of the reader.
     */
    data class Failed(val reason: SyncMessage) : AgendaUiState
}

/**
 * The day buckets the core returns, kept apart.
 *
 * The bucket of the flat list ([AgendaSpan.TASKS]) fills `tasks` instead of
 * `days` and has no day to sit on; it becomes the one dateless day, so that
 * every span above this is a list of days whatever was asked for.
 */
fun AgendaResult.toDays(labels: Map<String, CollectionLabel> = emptyMap()): List<AgendaDay> = days
    .map { day ->
        AgendaDay(
            // A date the core wrote and this side could not read is not worth
            // dropping the day over: the entries are still the entries, and a
            // heading with no date above them says as much as it can.
            date = runCatching { LocalDate.parse(day.date) }.getOrNull(),
            sections = AgendaSections(
                overdue = day.overdue.map { it.toAgendaRow(labels) },
                timed = day.scheduledTimed.map { it.toAgendaRow(labels) },
                untimed = (day.scheduledNoTime + day.upcoming).map { it.toAgendaRow(labels) },
            ),
        )
    }
    .ifEmpty {
        listOf(
            AgendaDay(
                date = null,
                sections = AgendaSections(
                    overdue = emptyList(),
                    timed = emptyList(),
                    untimed = tasks.map { it.toAgendaRow(labels) },
                ),
            ),
        )
    }

/**
 * Regroups the day buckets the core returns, flattening them together.
 *
 * What a single day is read through, and what the hour axis is built from.
 * A span of several days keeps them apart instead — see [toDays].
 */
fun AgendaResult.toSections(labels: Map<String, CollectionLabel> = emptyMap()): AgendaSections =
    AgendaSections(
        overdue = days.flatMap { it.overdue }.map { it.toAgendaRow(labels) },
        timed = days.flatMap { it.scheduledTimed }.map { it.toAgendaRow(labels) },
        // The `Tasks` scope fills `tasks` instead of `days`, and its entries
        // have no day to sit on; they join the untimed group rather than
        // vanish.
        untimed = (days.flatMap { it.scheduledNoTime + it.upcoming } + tasks)
            .map { it.toAgendaRow(labels) },
    )

/**
 * [labels] is keyed by the root a task carries, and empty while there is one
 * collection — a row then carries no label, which is what keeps the screen of
 * a device that has never added a second one exactly as it was.
 */
internal fun Task.toAgendaRow(labels: Map<String, CollectionLabel> = emptyMap()): AgendaRow =
    AgendaRow(
        task = this,
        time = rowTime(),
        daysOffset = daysOffset ?: 0,
        collection = root?.let(labels::get),
    )

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
