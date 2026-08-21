package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.vitalyostanin.markdownorg.R
import uniffi.markdown_org_ffi.Task
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the sheet offers about one occurrence of a repeating entry.
 *
 * A repeating date is a series, and every action above these moves the series:
 * a class at three every Thursday, shifted a day, is at three every Friday.
 * These two act on the day the row was drawn on and leave the series where it
 * is — this Thursday at six, or no class this Thursday at all.
 *
 * Which day that is comes from the task itself: the core rewrites the date of
 * the copy it puts in a day, so a row drawn under the twentieth carries the
 * twentieth however the file spells the anchor.
 *
 * Moving asks for the day first and then, where the series is held at an
 * hour, for the hour: a class moved to another week is usually held at the
 * same time, and a class moved within its day is the case the whole operation
 * exists for.
 */
@Composable
internal fun TaskOccurrence(task: Task, weekStart: WeekStart, onAction: (TaskAction) -> Unit) {
    val occurrence = task.occurrence() ?: return
    val time = task.startTime()

    var pickingDay by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    // The day the move is going to, held between the two dialogs. As the epoch
    // day rather than as a date, which is what a saved state can carry across
    // a rotation with the time picker open.
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }

    Text(
        text = stringResource(R.string.action_occurrence),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SheetButton(
        label = stringResource(R.string.action_move_occurrence),
        tag = "action-move-occurrence",
        hint = stringResource(R.string.hint_action_move_occurrence),
    ) { pickingDay = true }
    SheetButton(
        label = stringResource(R.string.action_cancel_occurrence),
        tag = "action-cancel-occurrence",
        hint = stringResource(R.string.hint_action_cancel_occurrence),
    ) { onAction(TaskAction.CancelOccurrence(occurrence)) }

    if (pickingDay) {
        DateChoice(
            initial = occurrence,
            weekStart = weekStart,
            onDismiss = { pickingDay = false },
            onPicked = { date ->
                pickingDay = false
                if (time == null) {
                    onAction(TaskAction.MoveOccurrence(occurrence, date, null))
                } else {
                    chosen = date.toEpochDay()
                    pickingTime = true
                }
            },
        )
    }

    if (pickingTime && time != null) {
        TimeChoice(
            initial = time,
            onDismiss = { pickingTime = false },
            onPicked = { picked ->
                pickingTime = false
                chosen?.let { day ->
                    onAction(
                        TaskAction.MoveOccurrence(occurrence, LocalDate.ofEpochDay(day), picked),
                    )
                }
            },
        )
    }
}

/**
 * The day this row stands for, when it is one occurrence of a series.
 *
 * A repeating planning line, and nothing else: a bare timestamp carries no
 * keyword for an exception to be written against, and an entry that does not
 * repeat has no occurrences — what the sheet offers for those is the row of
 * date actions above.
 */
private fun Task.occurrence(): LocalDate? {
    if (timestampRepeater == null || planningKeyword() == null) {
        return null
    }

    return timestampDate?.let { date -> runCatching { LocalDate.parse(date) }.getOrNull() }
}

/** The hour the entry is held at, for a series that names one. */
private fun Task.startTime(): LocalTime? =
    timestampTime?.let { time -> runCatching { LocalTime.parse(time) }.getOrNull() }

/**
 * The clock, as a dialog over the sheet.
 *
 * Wrapped in a dialog by hand, which is what Material's own guide for time
 * pickers gives: the picker is a composable, and the dialog around it is the
 * caller's. Whether it is drawn as a twelve- or a twenty-four-hour clock is
 * the phone's setting, which is what the picker's state reads when it is not
 * told otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChoice(initial: LocalTime, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onPicked(LocalTime.of(state.hour, state.minute)) },
                modifier = Modifier.testTag("time-set"),
            ) {
                Text(stringResource(R.string.date_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("time-cancel")) {
                Text(stringResource(R.string.date_cancel))
            }
        },
        text = { TimePicker(state = state) },
        modifier = Modifier.testTag("time-picker"),
    )
}
