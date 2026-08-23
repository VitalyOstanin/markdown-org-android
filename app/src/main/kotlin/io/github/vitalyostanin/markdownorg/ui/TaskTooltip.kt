package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import io.github.vitalyostanin.markdownorg.R
import uniffi.markdown_org_ffi.Task

// What a long press on a row says. The row itself shows one line and cuts the
// heading where it runs out of width, which on the real notes leaves half of a
// name; the press is where the rest of it is, together with the legend for the
// glyph and the priority badge beside it. The wording follows the VS Code
// extension (`src/utils/agendaTooltips.ts`), so a task reads the same in both
// clients.

/**
 * One line of the tooltip, as a resource and its arguments rather than as
 * finished text.
 *
 * The same split as [SyncMessage] and [ScanNotice] use: what a task is gets
 * decided where the task is, and the wording stays in the resources, which is
 * also what lets the decision be tested on the JVM.
 */
internal data class TooltipLine(
    @param:StringRes val text: Int,
    val args: List<String> = emptyList(),
)

/**
 * Which occurrence of a repeating entry a line names.
 *
 * A row on its own day already says when it is, so a tooltip beside it adds
 * the one after; a sheet covers the row it was opened from, and there the only
 * question left is which day that row stands on.
 */
internal enum class Occurrence(@param:StringRes val naming: Int) {
    NEXT(R.string.tooltip_repeating_next),
    THIS(R.string.tooltip_repeating_on),
}

/**
 * What the entry is, spelled out with the date it carries.
 *
 * [date] and [time] arrive as arguments for the reason the same helpers in the
 * extension take them: the reader's locale and clock live in the composition,
 * and this decision is made outside it. `null` where there is nothing to say —
 * a task with no timestamp at all states nothing beyond its heading.
 */
internal fun Task.tooltipKind(
    date: (String) -> String,
    time: (String) -> String,
    occurrence: Occurrence = Occurrence.NEXT,
): TooltipLine? {
    val repeater = timestampRepeater
    // The time of day stays the same across occurrences, so it is read once.
    val at = timestampTime?.let(time).orEmpty()
    val moment = { iso: String, withTime: Boolean ->
        val day = date(iso)
        if (withTime && at.isNotEmpty()) "$day $at" else day
    }
    val stated = timestampDate?.let { moment(it, true) }.orEmpty()

    return when (kind()) {
        AgendaKind.CANCELLED -> TooltipLine(R.string.tooltip_cancelled)

        AgendaKind.DONE -> TooltipLine(R.string.tooltip_done)

        // A row drawn under a day of its own names the occurrence after that
        // day; the copies the core borrows into today -- arrears, and
        // deadlines coming due -- carry only the one after today, which is
        // what they are there to say.
        //
        // Asked for this occurrence instead, the date on the copy is the
        // answer almost everywhere: the core rewrites it onto the day the copy
        // is drawn on, arrears included. The exception is a deadline coming
        // due, which keeps the date the file states -- the anchor of the
        // series, years back for a yearly repeat -- and is recognised by
        // counting forward, the only bucket whose offset is positive.
        AgendaKind.REPEAT -> repeatLine(
            repeater.orEmpty(),
            when {
                occurrence == Occurrence.NEXT -> timestampNextAfter ?: timestampNext
                (daysOffset ?: 0) > 0 -> timestampNext
                else -> timestampDate
            },
            occurrence.naming,
            stated,
            moment,
        )

        AgendaKind.DEADLINE -> if (stated.isEmpty()) {
            TooltipLine(R.string.tooltip_deadline)
        } else {
            TooltipLine(R.string.tooltip_deadline_at, listOf(stated))
        }

        // Anything the note dates without saying what for, and anything it
        // dates not at all: a heading with no timestamp lands here too, and
        // calling it scheduled would be inventing a plan it does not state.
        AgendaKind.SCHEDULED -> when {
            stated.isNotEmpty() -> TooltipLine(R.string.tooltip_scheduled_at, listOf(stated))
            timestampType != null -> TooltipLine(R.string.tooltip_scheduled)
            else -> null
        }
    }
}

/**
 * The repeating line, which names the occurrence the core resolved for this
 * row and the task's own date when it resolved none.
 *
 * An hour repeater is projected onto a whole-day grid with its interval
 * ignored (extract ADR-0023), so the stated clock time is not the time of that
 * occurrence and is left out of it; every other unit keeps the time of day.
 */
private fun repeatLine(
    repeater: String,
    next: String?,
    @StringRes naming: Int,
    stated: String,
    moment: (String, Boolean) -> String,
): TooltipLine {
    val brackets = if (repeater.isEmpty()) "" else " ($repeater)"

    return when {
        next != null -> TooltipLine(
            naming,
            listOf(brackets, moment(next, !repeater.trim().endsWith(HOUR_UNIT))),
        )

        stated.isNotEmpty() -> TooltipLine(
            R.string.tooltip_repeating_on,
            listOf(brackets, stated),
        )

        else -> TooltipLine(R.string.tooltip_repeating, listOf(brackets))
    }
}

/** The letter, with the ends of the A–C scale named; `null` for no cookie. */
internal fun Task.tooltipPriority(): TooltipLine? {
    val letter = priority?.uppercase()?.takeIf(String::isNotEmpty) ?: return null

    return when (letter) {
        "A" -> TooltipLine(R.string.tooltip_priority_highest, listOf(letter))
        "C" -> TooltipLine(R.string.tooltip_priority_lowest, listOf(letter))
        else -> TooltipLine(R.string.tooltip_priority, listOf(letter))
    }
}

/** The unit whose next occurrence carries no time of day. */
private const val HOUR_UNIT = "h"

/**
 * What the entry is and when, in the reader's own locale and clock.
 *
 * Split out because two places ask it: the tooltip a long press shows, and the
 * sheet a tap opens. The sheet used to name neither the date nor the
 * occurrence -- a row saying "in 1 day" opened onto a heading, a file and a
 * list of actions, and which day that was could only be had by dismissing the
 * sheet and pressing the row again, long this time.
 */
@Composable
internal fun taskKindLine(task: Task, occurrence: Occurrence = Occurrence.NEXT): TooltipLine? {
    val locale = LocalLocale.current.platformLocale
    val use24Hour = use24Hour()

    return task.tooltipKind(
        date = { statedDateLabel(it, locale) },
        time = { statedTimeLabel(it, locale, use24Hour) },
        occurrence = occurrence,
    )
}

/**
 * The same line as finished text, or `null` where the entry states no date.
 *
 * Names this occurrence rather than the next one: the sheet stands in place of
 * the row, so a repeating entry that answered with the occurrence after would
 * leave the tap on "in 1 day" as unanswered as it was before the line existed.
 */
@Composable
internal fun taskDateLine(task: Task): String? =
    taskKindLine(task, Occurrence.THIS)?.let { stringResource(it.text, *it.args.toTypedArray()) }

/** The whole tooltip: the heading in full, then what the row could not say. */
@Composable
internal fun taskTooltipText(task: Task, collection: CollectionLabel? = null): String {
    val lines = listOfNotNull(
        taskKindLine(task),
        task.tooltipPriority(),
        // The dot at the head of the row is six points across — too small to
        // aim a press at, and it is inside the row's own tooltip anyway. The
        // name it stands for is said here instead.
        collection?.let { TooltipLine(R.string.tooltip_collection, listOf(it.name)) },
    )

    return (listOf(task.heading) + lines.map { stringResource(it.text, *it.args.toTypedArray()) })
        .joinToString("\n")
}

/**
 * The row, with its tooltip behind a long press.
 *
 * The gesture was free: nothing in the agenda handles a long press, and a tap
 * still opens the sheet of actions. Wrapped around the row rather than around
 * the heading alone so the press lands anywhere on it — the heading is what is
 * cut off, and aiming at it is exactly what a reader cannot do when it is.
 * A tap on the shown tooltip takes it away rather than reaching the row under
 * it, so the sheet of actions is not opened by putting the text aside.
 *
 * No modifier is taken. What is given to the box here does not reach the node
 * the caller's own layout measures — a weight passed through it was dropped,
 * and the neighbour of the row that asked for it was left with no width. A
 * caller that has to size a row wraps it instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskTooltip(
    task: Task,
    collection: CollectionLabel? = null,
    content: @Composable () -> Unit,
) {
    val text = taskTooltipText(task, collection)
    val state = rememberTooltipState(isPersistent = true)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above,
        ),
        tooltip = {
            PlainTooltip(modifier = Modifier.dismissOnTap(state)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        state = state,
        content = content,
    )
}
