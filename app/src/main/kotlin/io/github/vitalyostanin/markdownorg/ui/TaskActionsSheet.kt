package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import uniffi.markdown_org_ffi.TimestampType

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
) {
    val state = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = task.heading,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("action-heading"),
            )
            Text(
                text = "${task.file}:${task.line}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )

            val repeating = task.timestampRepeater != null
            // A task that is already done has nothing to complete — writing
            // the keyword back would commit a change that changes nothing. A
            // repeating one is the exception: there "done" means the next
            // occurrence, whatever the keyword says now.
            if (repeating || task.taskType != TaskType.DONE) {
                SheetButton(
                    label = stringResource(
                        if (repeating) {
                            R.string.action_complete_repeating
                        } else {
                            R.string.action_complete
                        },
                    ),
                    tag = "action-complete",
                ) { onAction(TaskAction.Complete) }
            }

            if (task.taskType != TaskType.CANCELLED) {
                SheetButton(
                    label = stringResource(R.string.action_cancel_task),
                    tag = "action-cancel",
                ) { onAction(TaskAction.Status(TaskType.CANCELLED)) }
            }

            if (task.taskType != TaskType.TODO) {
                SheetButton(
                    label = stringResource(R.string.action_reopen),
                    tag = "action-reopen",
                ) { onAction(TaskAction.Status(TaskType.TODO)) }
            }

            PriorityChoice(task.priority, onAction)

            // Which planning line the task carries decides which one can move;
            // the extractor reports the kind it found.
            val keyword = when (task.timestampType) {
                TimestampType.DEADLINE -> PlanningKeyword.DEADLINE

                TimestampType.SCHEDULED -> PlanningKeyword.SCHEDULED

                // Neither is a planning line: a closing date records when the
                // task was finished, and a bare timestamp carries no keyword
                // to move. Spelled out rather than left to `else`, so a kind
                // added to the core has to be answered for here.
                TimestampType.CLOSED, TimestampType.PLAIN, null -> null
            }
            if (keyword != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SheetButton(
                        label = stringResource(R.string.action_shift_back),
                        tag = "action-shift-back",
                        modifier = Modifier.weight(1f),
                    ) { onAction(TaskAction.Shift(keyword, -1)) }
                    SheetButton(
                        label = stringResource(R.string.action_shift_forward),
                        tag = "action-shift-forward",
                        modifier = Modifier.weight(1f),
                    ) { onAction(TaskAction.Shift(keyword, 1)) }
                }
            }
        }
    }
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
    Text(
        text = stringResource(R.string.action_priority),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

@Composable
private fun SheetButton(
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
