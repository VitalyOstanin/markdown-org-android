package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import java.time.LocalDate
import java.time.LocalTime

/** What the user asked to do with a task. */
sealed interface TaskAction {
    /** Mark done, or move a repeating task on. */
    data object Complete : TaskAction

    /** Set the keyword outright, or clear it with `null`. */
    data class Status(val status: TaskType?) : TaskAction

    /** Set the priority cookie, or clear it with `null`. */
    data class Priority(val value: String?) : TaskAction

    /** Move a planning date by whole days. */
    data class Shift(val keyword: PlanningKeyword, val days: Int) : TaskAction

    /** Put a planning date on the day chosen, or take it off with `null`. */
    data class Plan(val keyword: PlanningKeyword, val date: LocalDate?) : TaskAction

    /**
     * Take one occurrence out of a repeating entry, leaving the series as it
     * is.
     *
     * [date] is the day the row was drawn on, which is the occurrence being
     * cancelled -- not the anchor the file spells the series with.
     */
    data class CancelOccurrence(val date: LocalDate) : TaskAction

    /**
     * Move one occurrence to another day, another time, or both.
     *
     * [occurrence] is the day it stands on now, and is what the entry written
     * in its place names: a replacement has to say which occurrence it is
     * standing in for, and the new date cannot say that. [time] is `null`
     * where the series is not held at an hour.
     */
    data class MoveOccurrence(
        val occurrence: LocalDate,
        val date: LocalDate,
        val time: LocalTime?,
    ) : TaskAction
}

/**
 * The actions a task offers, as a sheet over the agenda.
 *
 * A sheet rather than a screen: every action is one tap and the agenda behind
 * it is the context — what was tapped, and what is around it. Only actions the
 * task can actually take are shown, so a task without a deadline offers no way
 * to move one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskActionsSheet(
    task: Task,
    onAction: (TaskAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    weekStart: WeekStart = WeekStart.AUTO,
    onEdit: (() -> Unit)? = null,
    onOpenExternally: (() -> Unit)? = null,
) {
    // Opened whole rather than half way up. What the sheet holds depends on the
    // task -- a dated one carries two rows of date actions on top of the rest --
    // and the half-open state cut the last actions off below the edge, with the
    // scroll of the column inside it going to the column rather than to the
    // sheet.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, modifier = modifier) {
        Column(
            // Scrolled, because how tall the sheet is depends on the task: a
            // dated task carries two rows of date actions the others do not,
            // and on a short screen the last actions -- editing the text,
            // handing the note over -- were drawn past the bottom edge with
            // no way to reach them.
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = task.heading,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("action-heading"),
            )
            HintTooltip(stringResource(R.string.hint_action_where)) {
                Text(
                    text = "${task.file}:${task.line}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            CompletionAction(task, onAction)

            if (task.taskType != TaskType.CANCELLED) {
                SheetButton(
                    label = stringResource(R.string.action_cancel_task),
                    tag = "action-cancel",
                    hint = stringResource(R.string.hint_action_cancel),
                ) { onAction(TaskAction.Status(TaskType.CANCELLED)) }
            }

            if (task.taskType != TaskType.TODO) {
                SheetButton(
                    label = stringResource(R.string.action_reopen),
                    tag = "action-reopen",
                    hint = stringResource(R.string.hint_action_reopen),
                ) { onAction(TaskAction.Status(TaskType.TODO)) }
            }

            PriorityChoice(task.priority, onAction)

            TaskDates(task, weekStart, onAction)

            // Under the date actions, because they are the same question
            // narrowed: those move the series, these move one of it.
            TaskOccurrence(task, weekStart, onAction)

            // The one action that opens a screen rather than writing a line:
            // the heading's own text and the lines under it. Absent when the
            // caller does not offer it -- a preview, or a test that drives the
            // sheet alone.
            if (onEdit != null) {
                SheetButton(
                    label = stringResource(R.string.action_edit_entry),
                    tag = "action-edit-entry",
                    hint = stringResource(R.string.hint_action_edit_entry),
                    onClick = onEdit,
                )
            }

            // Last, and on its own: every action above writes one line and
            // stays here, while this one hands the whole note to another
            // application and leaves. Absent when the caller has nowhere to
            // send it -- a preview, or a test that drives the sheet alone.
            if (onOpenExternally != null) {
                SheetButton(
                    label = stringResource(R.string.action_open_externally),
                    tag = "action-open-externally",
                    hint = stringResource(R.string.hint_action_open_externally),
                    onClick = onOpenExternally,
                )
            }
        }
    }
}

/**
 * Finishing the task, worded by what finishing it does.
 *
 * A task that is already done has nothing to complete — writing the keyword
 * back would commit a change that changes nothing. A repeating one is the
 * exception: there "done" means the next occurrence, whatever the keyword
 * says now.
 */
@Composable
private fun CompletionAction(task: Task, onAction: (TaskAction) -> Unit) {
    val repeating = task.timestampRepeater != null
    if (!repeating && task.taskType == TaskType.DONE) {
        return
    }

    SheetButton(
        label = stringResource(
            if (repeating) R.string.action_complete_repeating else R.string.action_complete,
        ),
        tag = "action-complete",
        hint = stringResource(
            if (repeating) {
                R.string.hint_action_complete_repeating
            } else {
                R.string.hint_action_complete
            },
        ),
    ) { onAction(TaskAction.Complete) }
}

/**
 * Which priority the task carries, as the whole scale rather than one switch.
 *
 * The badge in the agenda tells A, B and C apart, and a task that arrived from
 * the notes with B could otherwise only be cleared and set back to A. The
 * level already in force is drawn as the selection and does nothing when
 * tapped: writing the same cookie back would be a commit with no change in it.
 */
@Composable
private fun PriorityChoice(current: String?, onAction: (TaskAction) -> Unit) {
    // On the heading of the row rather than on every chip: the line is about
    // the cookie the whole row writes, and repeating it four times would put a
    // tooltip under every finger that reaches for a level.
    HintTooltip(stringResource(R.string.hint_action_priority)) {
        Text(
            text = stringResource(R.string.action_priority),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (level in levels(current)) {
            val selected = level == current
            FilterChip(
                selected = selected,
                onClick = { if (!selected) onAction(TaskAction.Priority(level)) },
                label = { Text(level ?: stringResource(R.string.action_priority_none)) },
                modifier = Modifier.testTag("priority-${level ?: "none"}"),
            )
        }
    }
}

/**
 * What to offer: org-mode's default range, whatever this task already carries,
 * and no priority at all.
 *
 * The core accepts any uppercase letter and any number in `0..=64`, which is
 * 91 values and no row of chips. Three of them are what org-mode uses out of
 * the box and what the badge in the agenda gives its own colour; the rest
 * appear one at a time, when a task arrives from the notes carrying one — so
 * a task with `[#D]` can be set back to `D` after being changed.
 */
private fun levels(current: String?): List<String?> =
    (DEFAULT_RANGE + listOfNotNull(current)).distinct() + null

private val DEFAULT_RANGE = listOf("A", "B", "C")

/**
 * One action across the sheet, with a line behind a long press saying what it
 * writes into the note.
 *
 * The tooltip wraps the button rather than the other way round, so the press
 * that opens it is a press on the button itself. A button builds its own
 * semantics node, so the tag stays where it is put.
 */
@Composable
internal fun SheetButton(label: String, tag: String, hint: String, onClick: () -> Unit) {
    HintTooltip(hint) {
        SheetAction(label = label, tag = tag, onClick = onClick)
    }
}

/**
 * A plain button of the sheet, with no line of its own.
 *
 * Separate from [SheetButton] because of what the two do with a width: this
 * one takes a [modifier] the caller's row measures — a weight given to a
 * tooltip is not a weight the row can see, and the button beside it ended up
 * off the screen.
 */
@Composable
internal fun SheetAction(
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Text(label)
    }
}
