package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import java.time.LocalDate

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

    /** The draft as the writer takes it. */
    fun draft(): TaskDraft = TaskDraft(
        title = title,
        body = body,
        status = status,
        priority = priority,
        keyword = keyword,
        date = day,
    )

    companion object {
        // The enums travel by name and the day by its number: what a saved
        // state holds goes into a Bundle, and a name is the one form of an
        // enum that survives the class being reloaded.
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
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("create-cancel")) {
                Text(stringResource(R.string.entry_cancel))
            }
        },
        actions = {
            TextButton(
                onClick = onCreate,
                enabled = creatable,
                modifier = Modifier.testTag("create-save"),
            ) {
                Text(stringResource(R.string.create_save))
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

        OutlinedTextField(
            value = state.title,
            onValueChange = { state.title = it },
            label = { Text(stringResource(R.string.entry_heading)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create-title"),
        )
        OutlinedTextField(
            value = state.body,
            onValueChange = { state.body = it },
            label = { Text(stringResource(R.string.entry_body)) },
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

    ChoiceHeading(stringResource(R.string.create_collection))
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
    ChoiceHeading(stringResource(R.string.create_keyword))
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
    ChoiceHeading(stringResource(R.string.action_priority))
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
 */
@Composable
private fun NewTaskDate(state: NewTaskState, weekStart: WeekStart) {
    var picking by rememberSaveable { mutableStateOf(false) }

    ChoiceHeading(stringResource(R.string.create_date))
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
}

/** The two kinds of planning date, in the order the sheet offers them. */
private val KINDS = listOf(PlanningKeyword.SCHEDULED, PlanningKeyword.DEADLINE)

private fun PlanningKeyword.label() = when (this) {
    PlanningKeyword.SCHEDULED -> R.string.create_scheduled
    PlanningKeyword.DEADLINE -> R.string.create_deadline
}

/** What a row of chips is about, in the size the sheet uses for the same. */
@Composable
private fun ChoiceHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
