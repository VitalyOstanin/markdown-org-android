package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.TaskDraft
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.canonicalRepeater
import java.time.LocalDate
import java.time.LocalTime

/**
 * What is being typed into the creation screen.
 *
 * One object rather than seven pieces of remembered state, for the reason
 * [SyncFormState] gives: the fields belong to one another, and a section of
 * the screen takes the whole draft rather than a value and a setter apiece.
 *
 * Saved rather than merely remembered: the activity declares no
 * `configChanges`, so turning the phone rebuilds the screen, and a task typed
 * into it would be gone.
 */
@Stable
class NewTaskState(collectionId: String) {

    /** Which collection receives the task; its own file is what it goes into. */
    var collectionId by mutableStateOf(collectionId)

    var title by mutableStateOf("")
    var body by mutableStateOf("")

    /** The keyword to write, `null` for a heading that carries none. */
    var status by mutableStateOf<TaskType?>(TaskType.TODO)

    /** The bare priority, `null` for a task without a cookie. */
    var priority by mutableStateOf<String?>(null)

    /** Which kind of date [day] is; answered whether or not one was chosen. */
    var keyword by mutableStateOf(PlanningKeyword.SCHEDULED)

    var day by mutableStateOf<LocalDate?>(null)

    /**
     * The hour the entry is held at, `null` for one that takes the whole day.
     *
     * Kept even while no day is chosen, so a day cleared and picked again does
     * not lose the hour that was set with it; what goes into the file is the
     * pair, and the core is handed neither without a date.
     */
    var time by mutableStateOf<LocalTime?>(null)

    /** The repeater to write (`++1w`), `null` for a task that happens once. */
    var repeater by mutableStateOf<String?>(null)

    /** The draft as the writer takes it. */
    fun draft(): TaskDraft = TaskDraft(
        title = title,
        body = body,
        status = status,
        priority = priority,
        keyword = keyword,
        date = day,
        time = time,
        repeater = repeater,
    )

    companion object {
        // The enums travel by name and the day and the hour by their numbers:
        // what a saved state holds goes into a Bundle, and a name is the one
        // form of an enum that survives the class being reloaded.
        val Saver: Saver<NewTaskState, Any> = mapSaver(
            save = { state ->
                mapOf(
                    "collection" to state.collectionId,
                    "title" to state.title,
                    "body" to state.body,
                    "status" to state.status?.name,
                    "priority" to state.priority,
                    "keyword" to state.keyword.name,
                    "day" to state.day?.toEpochDay(),
                    "time" to state.time?.toSecondOfDay(),
                    "repeater" to state.repeater,
                )
            },
            restore = { saved ->
                NewTaskState(saved["collection"] as? String ?: "").apply {
                    title = saved["title"] as? String ?: ""
                    body = saved["body"] as? String ?: ""
                    status = (saved["status"] as? String)?.let(TaskType::valueOf)
                    priority = saved["priority"] as? String
                    keyword = (saved["keyword"] as? String)
                        ?.let(PlanningKeyword::valueOf)
                        ?: PlanningKeyword.SCHEDULED
                    day = (saved["day"] as? Long)?.let(LocalDate::ofEpochDay)
                    time = (saved["time"] as? Int)?.toLong()?.let(LocalTime::ofSecondOfDay)
                    repeater = saved["repeater"] as? String
                }
            },
        )
    }
}

/**
 * The screen that writes a task the notes do not hold yet.
 *
 * A screen rather than the sheet the actions live in, for the reason the entry
 * editor is one: it is typed into, and a sheet with the keyboard over it
 * leaves the body a line high.
 *
 * Where the task goes is not asked as a path: it is the file the chosen
 * collection receives new tasks in, named once in the settings and stated at
 * the foot of the screen. The entry is appended to the end of that file.
 */
@Composable
fun TaskCreator(
    collections: List<NotesCollection>,
    onCreate: (String, TaskDraft) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    weekStart: WeekStart = WeekStart.AUTO,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // The platform's dialog width is meant for a question, not for a form.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val first = collections.firstOrNull()?.id.orEmpty()
        val state = rememberSaveable(first, saver = NewTaskState.Saver) { NewTaskState(first) }

        Surface(modifier = modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    CreatorBar(
                        // A heading with no title is not a heading, and the
                        // core refuses to write one; the button says so by
                        // being unavailable rather than by failing afterwards.
                        creatable = state.title.isNotBlank() && state.collectionId.isNotEmpty(),
                        onCreate = { onCreate(state.collectionId, state.draft()) },
                        onDismiss = onDismiss,
                    )
                },
            ) { padding ->
                CreatorFields(
                    state = state,
                    collections = collections,
                    weekStart = weekStart,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/** What the screen does: leave it, or write what is in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatorBar(creatable: Boolean, onCreate: () -> Unit, onDismiss: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.create_title)) },
        navigationIcon = {
            HintTooltip(stringResource(R.string.hint_create_cancel)) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("create-cancel")) {
                    Text(stringResource(R.string.entry_cancel))
                }
            }
        },
        actions = {
            HintTooltip(stringResource(R.string.hint_create_save)) {
                TextButton(
                    onClick = onCreate,
                    enabled = creatable,
                    modifier = Modifier.testTag("create-save"),
                ) {
                    Text(stringResource(R.string.create_save))
                }
            }
        },
    )
}

/**
 * Everything the task is made of, in the order it is decided in: where it
 * goes, what it says, what it is, and when it is for.
 */
@Composable
private fun CreatorFields(
    state: NewTaskState,
    collections: List<NotesCollection>,
    weekStart: WeekStart,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ReceivingCollection(state, collections)

        // A line under the field rather than a tooltip on it: inside a text
        // field a long press belongs to selecting text.
        OutlinedTextField(
            value = state.title,
            onValueChange = { state.title = it },
            label = { Text(stringResource(R.string.entry_heading)) },
            supportingText = { Text(stringResource(R.string.create_heading_support)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create-title"),
        )
        OutlinedTextField(
            value = state.body,
            onValueChange = { state.body = it },
            label = { Text(stringResource(R.string.entry_body)) },
            supportingText = { Text(stringResource(R.string.create_body_support)) },
            modifier = Modifier
                .fillMaxWidth()
                .withoutAutofill()
                .testTag("create-body"),
        )

        NewTaskKeyword(state)
        NewTaskPriority(state)
        NewTaskDate(state, weekStart)

        // Where the task is about to go, named rather than implied: the file
        // is a setting of the collection, and a task written into a note the
        // user did not expect is a task they will look for.
        collections.firstOrNull { it.id == state.collectionId }?.let { collection ->
            Text(
                text = stringResource(R.string.create_goes_to, collection.inbox),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag("create-target"),
            )
        }
    }
}

/**
 * Which collection receives the task.
 *
 * Nothing is drawn while there is one of them: the answer is not a choice, and
 * the file it goes into is stated at the foot of the screen either way.
 */
@Composable
private fun ReceivingCollection(state: NewTaskState, collections: List<NotesCollection>) {
    if (collections.size < 2) {
        return
    }

    ChoiceHeading(
        text = stringResource(R.string.create_collection),
        hint = stringResource(R.string.hint_create_collection),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("create-collections"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        collections.forEach { collection ->
            FilterChip(
                selected = collection.id == state.collectionId,
                onClick = { state.collectionId = collection.id },
                label = {
                    Text(
                        text = collection.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = Sizes.collectionName),
                    )
                },
                modifier = Modifier.testTag("create-collection-${collection.id}"),
            )
        }
    }
}

/**
 * Which keyword the heading is written with.
 *
 * The three the extractor reads, and none at all — a heading without a keyword
 * is a note rather than a task, and the agenda shows it where its date falls.
 * The keywords are not translated: they are what goes into the file.
 */
@Composable
private fun NewTaskKeyword(state: NewTaskState) {
    ChoiceHeading(
        text = stringResource(R.string.create_keyword),
        hint = stringResource(R.string.hint_create_keyword),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        KEYWORDS.forEach { status ->
            FilterChip(
                selected = status == state.status,
                onClick = { state.status = status },
                label = { Text(status?.name ?: stringResource(R.string.action_priority_none)) },
                modifier = Modifier.testTag("create-keyword-${status?.name ?: "none"}"),
            )
        }
    }
}

/** The keywords a task is written with, and the heading that carries none. */
private val KEYWORDS = listOf(TaskType.TODO, TaskType.DONE, TaskType.CANCELLED, null)

/**
 * Which priority the task is written with.
 *
 * The three org-mode uses out of the box and no priority at all, which is what
 * a new task has unless it is said otherwise — the sheet of the task offers
 * the rest of the scale once it is in the notes.
 */
@Composable
private fun NewTaskPriority(state: NewTaskState) {
    ChoiceHeading(
        text = stringResource(R.string.action_priority),
        hint = stringResource(R.string.hint_create_priority),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PRIORITIES.forEach { level ->
            FilterChip(
                selected = level == state.priority,
                onClick = { state.priority = level },
                label = { Text(level ?: stringResource(R.string.action_priority_none)) },
                modifier = Modifier.testTag("create-priority-${level ?: "none"}"),
            )
        }
    }
}

private val PRIORITIES = listOf("A", "B", "C", null)

/**
 * When the task is for, and which kind of date that is.
 *
 * The kind is asked whether or not a day has been chosen, because the two are
 * not interchangeable: one says when work starts, the other when the task is
 * due. A task written without a day carries no planning line at all and shows
 * up in the flat list of tasks rather than on a date.
 *
 * The hour and the repeater belong to the chosen day and are only offered once
 * there is one: a timestamp is what carries them, and a task with no date has
 * no timestamp to write them into.
 */
@Composable
private fun NewTaskDate(state: NewTaskState, weekStart: WeekStart) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }

    ChoiceHeading(
        text = stringResource(R.string.create_date),
        hint = stringResource(R.string.hint_create_date),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        KINDS.forEach { kind ->
            FilterChip(
                selected = kind == state.keyword,
                onClick = { state.keyword = kind },
                label = { Text(stringResource(kind.label())) },
                modifier = Modifier.testTag("create-kind-${kind.name.lowercase()}"),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SheetAction(
            // The day itself once there is one: the chosen date is the answer,
            // and a button that keeps offering to choose says nothing about
            // what was chosen. Spelled as the notes spell it.
            label = state.day?.toString() ?: stringResource(R.string.action_pick_date),
            tag = "create-pick-date",
            modifier = Modifier.weight(1f),
        ) { picking = true }
        if (state.day != null) {
            SheetAction(
                label = stringResource(R.string.create_date_none),
                tag = "create-clear-date",
                modifier = Modifier.weight(1f),
            ) { state.day = null }
        }
    }

    if (state.day != null) {
        ChoiceHeading(
            text = stringResource(R.string.create_time),
            hint = stringResource(R.string.hint_create_time),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SheetAction(
                // The hour as the phone writes it, which is what the picker
                // showed and what the reader recognises; the file spells it
                // its own way.
                label = state.time
                    ?.let { timeLabel(it, LocalLocale.current.platformLocale, use24Hour()) }
                    ?: stringResource(R.string.create_pick_time),
                tag = "create-pick-time",
                modifier = Modifier.weight(1f),
            ) { pickingTime = true }
            if (state.time != null) {
                SheetAction(
                    label = stringResource(R.string.create_time_none),
                    tag = "create-clear-time",
                    modifier = Modifier.weight(1f),
                ) { state.time = null }
            }
        }

        NewTaskRepeat(state)
    }

    if (picking) {
        DateChoice(
            initial = state.day,
            weekStart = weekStart,
            onDismiss = { picking = false },
            onPicked = { date ->
                picking = false
                state.day = date
            },
        )
    }
    if (pickingTime) {
        TimeChoice(
            // Nine in the morning for a task that has no hour yet: the picker
            // opens somewhere, and the start of a working day is a shorter way
            // from most answers than midnight is.
            initial = state.time ?: DEFAULT_HOUR,
            onDismiss = { pickingTime = false },
            onPicked = { time ->
                pickingTime = false
                state.time = time
            },
        )
    }
}

/** Where the clock opens for a task that names no hour yet. */
private val DEFAULT_HOUR: LocalTime = LocalTime.of(9, 0)

/**
 * Whether the date repeats, and how often.
 *
 * Four intervals and none at all, with a field behind Every… for the rest of
 * what the format writes. The four are catch-up repeaters (`++1w`): completing
 * a task that was missed moves it to the next occurrence ahead of today rather
 * than one step from the date in the file, which for a task done every week is
 * what the next week means. A repeater that has to step exactly once, or count
 * from the day it was done, is typed into the field.
 */
@Composable
private fun NewTaskRepeat(state: NewTaskState) {
    var typing by rememberSaveable { mutableStateOf(false) }
    val ready = REPEATS.any { it.written == state.repeater }

    ChoiceHeading(
        text = stringResource(R.string.create_repeat),
        hint = stringResource(R.string.hint_create_repeat),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        REPEATS.forEach { repeat ->
            FilterChip(
                selected = repeat.written == state.repeater,
                onClick = { state.repeater = repeat.written },
                label = { Text(stringResource(repeat.label)) },
                modifier = Modifier.testTag("create-repeat-${repeat.tag}"),
            )
        }
        FilterChip(
            // The repeater itself once it is one of its own: what was typed is
            // the answer, and a chip that keeps saying Every… says nothing
            // about what it was set to.
            selected = !ready,
            onClick = { typing = true },
            label = {
                Text(
                    text = state.repeater
                        ?.takeUnless { ready }
                        ?: stringResource(R.string.create_repeat_custom),
                )
            },
            modifier = Modifier.testTag("create-repeat-custom"),
        )
    }

    if (typing) {
        RepeaterChoice(
            initial = state.repeater.orEmpty(),
            onDismiss = { typing = false },
            onPicked = { written ->
                typing = false
                state.repeater = written
            },
        )
    }
}

/**
 * A repeater typed by hand, answered while it is being typed.
 *
 * The core is asked what the field spells, and what it answers is what would
 * go into the file — so a repeater written +007d comes back as +7d rather than
 * being written the long way. A field that spells no repeater cannot be
 * confirmed: the alternative is a task refused after it has been composed.
 */
@Composable
private fun RepeaterChoice(initial: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    var typed by rememberSaveable { mutableStateOf(initial) }
    val written = canonicalRepeater(typed.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_repeat_title)) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                isError = typed.isNotBlank() && written == null,
                supportingText = {
                    Text(
                        stringResource(
                            if (typed.isNotBlank() && written == null) {
                                R.string.create_repeat_invalid
                            } else {
                                R.string.create_repeat_support
                            },
                        ),
                    )
                },
                // A repeater is written in lower case and starts with a sign;
                // the keyboard's own capitalising and correcting would both be
                // answered by the field refusing what they produced.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create-repeat-field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { written?.let(onPicked) },
                enabled = written != null,
                modifier = Modifier.testTag("create-repeat-set"),
            ) {
                Text(stringResource(R.string.date_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("create-repeat-cancel")) {
                Text(stringResource(R.string.date_cancel))
            }
        },
        modifier = Modifier.testTag("create-repeat-dialog"),
    )
}

/** One of the intervals the chips offer, and what it writes. */
private class Repeat(@param:StringRes val label: Int, val tag: String, val written: String?)

/**
 * The intervals offered, in the order they are: none, and then from the
 * shortest to the longest.
 */
private val REPEATS = listOf(
    Repeat(R.string.create_repeat_none, "none", null),
    Repeat(R.string.create_repeat_daily, "daily", "++1d"),
    Repeat(R.string.create_repeat_weekly, "weekly", "++1w"),
    Repeat(R.string.create_repeat_monthly, "monthly", "++1m"),
    Repeat(R.string.create_repeat_yearly, "yearly", "++1y"),
)

/** The two kinds of planning date, in the order the sheet offers them. */
private val KINDS = listOf(PlanningKeyword.SCHEDULED, PlanningKeyword.DEADLINE)

private fun PlanningKeyword.label() = when (this) {
    PlanningKeyword.SCHEDULED -> R.string.create_scheduled
    PlanningKeyword.DEADLINE -> R.string.create_deadline
}

/**
 * What a row of chips is about, in the size the sheet uses for the same.
 *
 * The heading carries the row's tooltip, as it does in the sheet: the chips
 * are the answers and the heading is the question, so what a choice writes
 * into the file is explained once rather than on each chip.
 */
@Composable
private fun ChoiceHeading(text: String, hint: String) {
    HintTooltip(hint) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
