package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType

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
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
            SheetButton(
                label = stringResource(
                    if (repeating) R.string.action_complete_repeating else R.string.action_complete,
                ),
                tag = "action-complete",
            ) { onAction(TaskAction.Complete) }

            SheetButton(
                label = stringResource(R.string.action_cancel_task),
                tag = "action-cancel",
            ) { onAction(TaskAction.Status(TaskType.CANCELLED)) }

            if (task.taskType != TaskType.TODO) {
                SheetButton(
                    label = stringResource(R.string.action_reopen),
                    tag = "action-reopen",
                ) { onAction(TaskAction.Status(TaskType.TODO)) }
            }

            SheetButton(
                label = stringResource(
                    if (task.priority == null) R.string.action_priority_set else R.string.action_priority_clear,
                ),
                tag = "action-priority",
            ) { onAction(TaskAction.Priority(if (task.priority == null) "A" else null)) }

            // Which planning line the task carries decides which one can move;
            // the extractor reports the kind it found.
            val keyword = when (task.timestampType) {
                "DEADLINE" -> PlanningKeyword.DEADLINE
                "SCHEDULED" -> PlanningKeyword.SCHEDULED
                else -> null
            }
            if (keyword != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
