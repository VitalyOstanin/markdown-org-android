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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
     * Put an hour on the planning date, or take it off with `null`.
     *
     * Only offered where the task carries a date of that kind: an hour is a
     * token inside a timestamp, and there is none to put it in otherwise.
     */
    data class PlanTime(val keyword: PlanningKeyword, val time: LocalTime?) : TaskAction

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

    /**
     * Change several fields at once by saying what to change.
     *
     * [said] is the sentence as it was typed or heard; the rules that read it
     * live in the core, and what they make of it is applied to this entry in
     * one write.
     */
    data class Phrase(val said: String) : TaskAction

    /**
     * Carry the whole entry into another file of the same collection.
     *
     * [file] is relative to that collection's directory, which is how every
     * file this application names is spelled. One action for both ways of
     * asking — the file the collection calls its main one, and one picked from
     * the list — because what happens afterwards is the same.
     */
    data class MoveToFile(val file: String) : TaskAction
}

/**
 * Where the entry on screen can be carried, as the sheet needs it.
 *
 * Both halves come from the collection the task belongs to: [mainFile] is what
 * that collection calls its main file, and [files] is every markdown file in
 * it. Empty in both when the collection is gone, and the sheet then offers no
 * move at all — which is also what a sheet driven by a test or a preview gets.
 */
data class MoveTargets(val mainFile: String? = null, val files: List<String> = emptyList())

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
    /** Where this entry can be carried; see [MoveTargets]. */
    move: MoveTargets = MoveTargets(),
    onEdit: (() -> Unit)? = null,
    onOpenExternally: (() -> Unit)? = null,
    dictation: Dictation = rememberSystemDictation(),
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
            SheetHeader(task)

            // Ahead of the buttons because it can say what several of them
            // say at once: "перенеси на пятницу в 16:00 и сделай срочной" is
            // three of the actions below, in one sentence and one write.
            SpokenEdit(onAction, dictation)

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

            // After everything that writes a line of this entry, because this
            // one writes the entry somewhere else: what the actions above did
            // travels with it.
            TaskMove(task, move, onAction)

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
 * What the sheet says the entry is: its heading, the day it stands on and
 * where in the notes it lives.
 *
 * The date is there because the row a tap comes from counts days -- "in 1
 * day" -- and never names the day itself; a reader who wanted it had to put
 * the sheet away and press the row again, long. The wording is the one that
 * long press uses, so the two agree.
 */
@Composable
private fun SheetHeader(task: Task) {
    Text(
        text = task.heading,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("action-heading"),
    )
    taskDateLine(task)?.let { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("action-date"),
        )
    }
    HintTooltip(stringResource(R.string.hint_action_where)) {
        Text(
            text = "${task.file}:${task.line}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * The sentence that changes the entry, typed or spoken.
 *
 * The same shape the creation screen uses for a phrase: a field, a button that
 * listens, and a button that hands what is in the field to the rules. What was
 * heard joins what is already there rather than replacing it, so a sentence
 * said in two goes is one sentence.
 */
@Composable
private fun SpokenEdit(onAction: (TaskAction) -> Unit, dictation: Dictation) {
    var phrase by rememberSaveable { mutableStateOf("") }
    // Said once the phone has answered that it cannot listen, and kept until
    // the next attempt: a line under the field rather than a message that goes
    // away on its own, because what to do instead is to type here.
    var unheard by rememberSaveable { mutableStateOf(false) }
    val prompt = stringResource(R.string.action_phrase_prompt)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            label = { Text(stringResource(R.string.action_phrase)) },
            supportingText = if (unheard) {
                { Text(stringResource(R.string.create_phrase_unheard)) }
            } else {
                null
            },
            isError = unheard,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .withoutAutofill()
                .testTag("action-phrase"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HintTooltip(stringResource(R.string.hint_action_phrase_speak)) {
                TextButton(
                    onClick = {
                        unheard = !dictation.listen(prompt) { heard ->
                            phrase = listOf(phrase.trim(), heard.trim())
                                .filter { it.isNotEmpty() }
                                .joinToString(" ")
                        }
                    },
                    modifier = Modifier.testTag("action-phrase-speak"),
                ) {
                    Text(stringResource(R.string.action_phrase_speak))
                }
            }
            HintTooltip(stringResource(R.string.hint_action_phrase)) {
                TextButton(
                    onClick = {
                        onAction(TaskAction.Phrase(phrase))
                        phrase = ""
                        unheard = false
                    },
                    enabled = phrase.isNotBlank(),
                    modifier = Modifier.testTag("action-phrase-apply"),
                ) {
                    Text(stringResource(R.string.action_phrase_apply))
                }
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
