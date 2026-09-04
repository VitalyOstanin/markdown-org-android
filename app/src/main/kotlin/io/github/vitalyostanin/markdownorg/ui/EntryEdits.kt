package io.github.vitalyostanin.markdownorg.ui

import android.util.Log
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.AgendaLoader
import io.github.vitalyostanin.markdownorg.core.AssumedDay
import io.github.vitalyostanin.markdownorg.core.CollectionInUse
import io.github.vitalyostanin.markdownorg.core.CollectionsInUse
import io.github.vitalyostanin.markdownorg.core.DEFAULT_WRITE_AT
import io.github.vitalyostanin.markdownorg.core.EditReport
import io.github.vitalyostanin.markdownorg.core.NotesWriter
import io.github.vitalyostanin.markdownorg.core.PhraseRules
import io.github.vitalyostanin.markdownorg.core.TaskDraft
import io.github.vitalyostanin.markdownorg.core.UndoReport
import io.github.vitalyostanin.markdownorg.core.assumedDay
import io.github.vitalyostanin.markdownorg.core.byRoot
import io.github.vitalyostanin.markdownorg.core.markdownFiles
import io.github.vitalyostanin.markdownorg.core.noteFileProblem
import io.github.vitalyostanin.markdownorg.core.onItsDay
import io.github.vitalyostanin.markdownorg.core.statedDate
import io.github.vitalyostanin.markdownorg.core.statedTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.FileRollback
import uniffi.markdown_org_ffi.PhraseDraft
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * What a tap on a row writes into the notes, and what it answers with.
 *
 * Every operation here ends in a file: a keyword, a priority, a date, a whole
 * entry, or the same over a band of rows at once. They are held apart from the
 * agenda because they are the other direction of it -- the agenda reads the
 * notes, this writes them -- and because they share a shape the agenda does
 * not: each ends in a rollback the reader can take back, and in a line saying
 * what was done.
 *
 * Nothing here rebuilds the agenda itself. A write that lands asks for one
 * through [rescan], so that the order stays the same wherever an edit came
 * from: write, answer, and only then read the notes again.
 *
 * @param collections the collections in use, to find the one a task belongs to
 * @param agenda the loader whose cache a written note invalidates
 * @param editing which collection a new task goes into by default
 * @param phrases the rules a spoken sentence is read by
 * @param clock what day it is, for the dates an action puts on a task
 * @param scope the model's scope, so an edit ends when the model does
 * @param io where the notes are read and written
 * @param rescan asks for the agenda again, once a write has landed
 * @param onNotesMoved says the notes are no longer what the reminders were
 *   planned against
 */
@Suppress("LongParameterList", "TooManyFunctions")
class EntryEdits(
    private val collections: CollectionsInUse,
    private val agenda: AgendaLoader,
    private val editing: () -> CollectionInUse,
    private val phrases: PhraseRules,
    private val clock: () -> LocalDateTime,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val rescan: () -> Unit,
    private val onNotesMoved: () -> Unit,
) {

    /**
     * The last edit that could not be made, until it has been shown.
     *
     * A channel of its own rather than the sync banner: that line reports the
     * state of the checkout, and "the task could not be changed" is about a
     * tap the user just made.
     */
    private val _editIssue = MutableStateFlow<SyncMessage?>(null)
    val editIssue: StateFlow<SyncMessage?> = _editIssue.asStateFlow()

    /** What a whole band of rows answered with, until it has been shown. */
    private val _groupResult = MutableStateFlow<GroupResult?>(null)
    val groupResult: StateFlow<GroupResult?> = _groupResult.asStateFlow()

    /** What one edit answered with, until it has been shown. */
    private val _editResult = MutableStateFlow<EditResult?>(null)
    val editResult: StateFlow<EditResult?> = _editResult.asStateFlow()

    /** The task the sheet of actions stands over, or `null` while there is none. */
    private val _selected = MutableStateFlow<Task?>(null)
    val selected: StateFlow<Task?> = _selected.asStateFlow()

    /** Where the selected task could be moved to. */
    private val _moveTargets = MutableStateFlow(MoveTargets())
    val moveTargets: StateFlow<MoveTargets> = _moveTargets.asStateFlow()

    /** The read of the files behind the move targets, dropped on a new selection. */
    private var targetsJob: Job? = null

    /** Whether the screen that composes a new task is up. */
    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    /** The entry being edited as text, or `null` while none is. */
    private val _editedEntry = MutableStateFlow<EntryDraft?>(null)
    val editedEntry: StateFlow<EntryDraft?> = _editedEntry.asStateFlow()

    /** The edit failure has been shown, so it is not shown again. */
    fun editIssueShown() {
        _editIssue.value = null
    }

    fun select(task: Task?) {
        _selected.value = task
        _moveTargets.value = MoveTargets()
        targetsJob?.cancel()

        // A task whose collection is gone is not moved anywhere, and the sheet
        // simply carries no move: the failure worth reporting is the one the
        // tap that writes produces, not one about a sheet being opened.
        val collection = task?.let { collections.byRoot(it.root) } ?: return

        targetsJob = scope.launch {
            val files = withContext(io) { markdownFiles(File(collection.root)) }

            _moveTargets.value = MoveTargets(
                mainFile = collection.collection.mainFile.takeUnless(String::isBlank),
                files = files,
            )
        }
    }

    /**
     * Where the note a task sits in actually is, or `null` when that cannot be
     * said.
     *
     * A task carries its file relative to the directory the walk covered --
     * `notes.md`, not `/home/.../notes.md` -- because that is what the agenda
     * shows and what an edit addresses, and every writer here joins the two
     * back together. Anything handing the file to another application has to
     * do the same: a relative path taken for an absolute one names a file in
     * the root of the filesystem, which exists nowhere.
     *
     * `null` where the collection is gone -- removed while its tasks were
     * still on screen -- since there is no directory left to join to.
     */
    fun noteFile(task: Task): File? =
        collections.byRoot(task.root)?.let { collection -> File(collection.root, task.file) }

    /**
     * Nothing on the device opened the note.
     *
     * Reported through the same channel as a failed edit, because to the
     * reader it is the same kind of event: the tap did not do what it said.
     */
    fun reportOpenFailure() {
        _editIssue.value = SyncMessage(R.string.open_externally_failed, failed = true)
    }

    /**
     * Apply an action to the selected task, then rebuild the agenda.
     *
     * The sheet closes first: every action writes to the file and commits,
     * and leaving the sheet up over a list that is being rebuilt reads as the
     * tap not having registered.
     */
    fun apply(task: Task, action: TaskAction) {
        _selected.value = null

        val theirEditor = editorFor(task) ?: return

        // Apart from the rest because it is the one action that writes two
        // notes: what it hands back is a pair to put back, where every other
        // action hands back one file.
        if (action is TaskAction.MoveToFile) {
            moveEntry(task, theirEditor, action.file)
            return
        }

        scope.launch {
            // The other half of what a tap costs, alongside the scan timed in
            // rescan(): the write is one file, but the commit that follows it
            // reads the whole working copy, so this grows with the notes too.
            val started = System.nanoTime()
            // What a phrase changed, worked out against the entry as it stands
            // now: after the write the note holds the new values, and a phrase
            // that named three fields would have nothing left to name.
            var changes: List<PhraseChange> = emptyList()
            val outcome = when (action) {
                TaskAction.Complete -> theirEditor.complete(task, clock().toLocalDate())

                is TaskAction.Status -> theirEditor.setStatus(task, action.status)

                is TaskAction.Priority -> theirEditor.setPriority(task, action.value)

                is TaskAction.Phrase -> {
                    // Read before anything is written, and refused here rather
                    // than in the core: what the phrase failed at is said in
                    // the language of the screen, which the core does not
                    // speak.
                    val draft = phraseFor(action.said) ?: return@launch
                    changes = phraseChanges(task, draft)
                    theirEditor.applyPhrase(task, draft)
                }

                is TaskAction.Shift -> theirEditor.shift(task, action.keyword, action.days)

                is TaskAction.Plan ->
                    theirEditor.setPlanning(task, action.keyword, action.date)

                is TaskAction.PlanTime ->
                    theirEditor.setPlanningTime(task, action.keyword, action.time)

                is TaskAction.CancelOccurrence ->
                    theirEditor.cancelOccurrence(task, action.date)

                is TaskAction.MoveOccurrence ->
                    theirEditor.moveOccurrence(task, action.occurrence, action.date, action.time)

                // Answered above, before the notes were touched.
                is TaskAction.MoveToFile -> return@launch
            }
            Log.i(TAG, "the edit took ${millisSince(started)} ms")

            settle(task, outcome, changes)
        }
    }

    /**
     * Carry the entry into another file of its own collection.
     *
     * Where in that file it lands is the collection's own setting, the one a
     * task written here is placed by: a reader who wants what is new at the
     * top of a file wants it there however the entry arrived.
     *
     * What is settled afterwards names both files: the agenda is rebuilt off a
     * re-read of the notes that changed, and a move changes two of them.
     */
    private fun moveEntry(task: Task, theirEditor: NotesWriter, file: String) {
        val at = collections.byRoot(task.root)?.collection?.writeAt ?: DEFAULT_WRITE_AT

        scope.launch {
            val started = System.nanoTime()
            val outcome = theirEditor.moveEntry(task, file, at)
            Log.i(TAG, "the move took ${millisSince(started)} ms")

            // Both notes are named: the entry has to appear in the file it
            // reached and stop appearing in the file it left, and the held
            // notes of an unread file would go on showing it in both.
            settle(
                Written(
                    root = task.root,
                    files = listOf(task.file, file),
                    heading = task.heading,
                ),
                outcome.map { move -> move.report.copy(rollback = move.outcome.rollback) },
            )
        }
    }

    /**
     * Open the text of a task's entry: its heading and the lines under it.
     *
     * Read before the screen is shown rather than by the screen itself, so a
     * file that has moved on says so where every other refusal is said, and
     * the editor never opens over text it could not write back.
     */
    fun edit(task: Task) {
        _selected.value = null

        val theirEditor = editorFor(task) ?: return

        scope.launch {
            theirEditor.readEntry(task).fold(
                onSuccess = { text ->
                    // A note whose whole content sits under one heading turns
                    // the body into a file, and a field of that size is not
                    // slow but unusable: a run measured six seconds for one
                    // keystroke at 676 KB. Past the threshold the note goes to
                    // an editor built for it.
                    if (text.body.length > BODY_LIMIT) {
                        _editIssue.value = SyncMessage(R.string.entry_too_long, failed = true)
                    } else {
                        _editedEntry.value = EntryDraft(task, text.title, text.body)
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "the entry could not be read", error)
                    _editIssue.value = error.toEditMessage()
                },
            )
        }
    }

    /** The editing screen was left without writing anything. */
    fun cancelEdit() {
        _editedEntry.value = null
    }

    /** Open the screen that writes a task the notes do not hold yet. */
    fun startCreating() {
        // The sheet of another task has nothing to do with a new one, and two
        // things over the agenda at once is one too many.
        _selected.value = null
        _creating.value = true
    }

    /** The creation screen was left without writing anything. */
    fun cancelCreating() {
        _creating.value = false
    }

    /**
     * Write a task out of one spoken sentence, without opening a screen.
     *
     * The sentence goes through the same rules the creation screen reads a
     * typed phrase with, so "позвонить врачу завтра в 15:00" becomes the same
     * entry either way. Where it lands is the first collection: the button is
     * for the task thought of while walking, and a question about which of the
     * collections it belongs to would be the screen this button exists to
     * avoid — a task written into the wrong one is moved from its own sheet.
     *
     * What the rules leave over becomes the heading; a sentence they consume
     * entirely is kept as the heading as it was said, because an entry without
     * one is not written at all and what was heard is better than nothing.
     */
    fun createFromPhrase(phrase: String) {
        val said = phrase.trim()
        if (said.isEmpty()) {
            return
        }

        val target = collections.entries.firstOrNull()
        if (target == null) {
            _editIssue.value = SyncMessage(R.string.edit_failed_no_collection, failed = true)
            return
        }

        val read = runCatching {
            phrases.refine(EMPTY_PHRASE, said, clock().toLocalDate())
        }.getOrElse { error ->
            Log.w(TAG, "the phrase could not be read", error)
            _editIssue.value = SyncMessage(R.string.agenda_dictate_failed, failed = true)
            return
        }

        createTask(
            target.collection.id,
            TaskDraft(
                title = read.heading.ifBlank { said },
                priority = read.priority,
                keyword = read.keyword ?: PlanningKeyword.SCHEDULED,
                date = statedDate(read.date),
                time = statedTime(read.time),
                repeater = read.repeater,
            ),
        )
    }

    /**
     * What the rules make of a phrase said about an entry that exists, or
     * `null` when there is nothing to apply.
     *
     * Three ways of coming back empty-handed, each said in its own words: the
     * rules refused the sentence, a word of it was not understood — and then
     * nothing is changed, because applying the half that was understood would
     * move a field nobody named — or the sentence named no field at all.
     */
    private fun phraseFor(said: String): PhraseDraft? {
        val phrase = said.trim()
        if (phrase.isEmpty()) {
            return null
        }

        val read = runCatching {
            phrases.refine(EMPTY_PHRASE, phrase, clock().toLocalDate())
        }.getOrElse { error ->
            Log.w(TAG, "the phrase could not be read", error)
            _editIssue.value = SyncMessage(R.string.agenda_dictate_failed, failed = true)
            return null
        }

        if (read.heading.isNotBlank()) {
            _editIssue.value = SyncMessage(
                R.string.agenda_phrase_leftover,
                detail = Detail.Verbatim(read.heading),
                failed = true,
            )
            return null
        }
        val named = read.status != null ||
            read.priority != null ||
            read.date != null ||
            read.time != null ||
            read.repeater != null ||
            read.cleared.isNotEmpty()
        if (!named) {
            _editIssue.value = SyncMessage(R.string.agenda_phrase_nothing, failed = true)
            return null
        }
        return read
    }

    /** Say that this phone has nothing to recognise speech with. */
    fun reportNoRecogniser() {
        _editIssue.value = SyncMessage(R.string.create_phrase_unheard, failed = true)
    }

    /**
     * Write a new task into the file the chosen collection receives them in.
     *
     * The screen closes first, for the reason the editing one does: what
     * follows is a write and a rebuilt agenda, and a screen left standing over
     * it reads as the task not having been created.
     */
    fun createTask(collectionId: String, draft: TaskDraft) {
        _creating.value = false

        // A collection removed while the screen stood over the agenda has
        // nothing to write to.
        val target = collections.entries.firstOrNull { it.collection.id == collectionId }
        if (target == null) {
            _editIssue.value = SyncMessage(R.string.edit_failed_no_collection, failed = true)
            rescan()
            return
        }

        scope.launch {
            val now = clock()
            // An hour or a repeat said without a day gets one, rather than
            // being dropped on the way to a planning line that cannot hold it.
            val assumed = draft.assumedDay(now)
            val outcome = target.editor.createTask(
                target.collection.inbox,
                target.collection.writeAt,
                draft.onItsDay(now),
                now,
            )

            settle(
                Written(
                    root = target.root,
                    files = listOf(target.collection.inbox),
                    heading = draft.title.trim(),
                    created = true,
                    assumedDay = assumed,
                ),
                outcome,
            )
        }
    }

    /**
     * Write the edited entry back, then rebuild the agenda.
     *
     * The screen closes first, for the reason the sheet does: what follows is
     * a write and a rebuilt list, and a screen left standing over it reads as
     * the save not having registered.
     */
    fun saveEntry(title: String, body: String) {
        val draft = _editedEntry.value ?: return
        _editedEntry.value = null

        val theirEditor = editorFor(draft.task) ?: return

        scope.launch {
            val started = System.nanoTime()
            val outcome = theirEditor.setEntry(draft.task, title, body)
            Log.i(TAG, "the entry took ${millisSince(started)} ms")

            settle(draft.task, outcome)
        }
    }

    /**
     * Which editor writes the collection a task came from, or nothing and a
     * reason on screen.
     *
     * The collection the task came from rather than the one being edited in
     * the settings: the agenda shows several at once, and writing to the wrong
     * directory would edit whatever note happens to sit at the same relative
     * path there. A task read out of a file whose name is not UTF-8 names a
     * path that does not exist, so every edit would come back as "file not
     * found" — refused here with a reason instead.
     */
    private fun editorFor(task: Task): NotesWriter? {
        if (!task.isEditable()) {
            _editIssue.value = SyncMessage(R.string.edit_failed_unnamed, failed = true)
            return null
        }

        // A collection removed while its tasks were still on screen has
        // nothing to write to.
        val theirEditor = collections.byRoot(task.root)?.editor
        if (theirEditor == null) {
            _editIssue.value = SyncMessage(R.string.edit_failed_no_collection, failed = true)
            rescan()
        }

        return theirEditor
    }

    /**
     * Which note a finished write changed, and what it was.
     *
     * One object rather than four arguments, and the reason there are four at
     * all: a write is settled the same way whether it edited a task the agenda
     * showed or wrote one that was not there — and the second has no task to
     * read the file and the heading off.
     */
    private data class Written(
        val root: String?,
        /**
         * The notes the write changed, which is one for every write but a
         * move: that one takes an entry out of a file and puts it in another,
         * and a file left unread would keep showing the entry it no longer
         * holds.
         */
        val files: List<String>,
        val heading: String,
        val created: Boolean = false,
        /**
         * The fields a phrase changed, for a write that was made by saying
         * one; empty for every other write, which says what it did in the
         * button that was pressed.
         */
        val changes: List<PhraseChange> = emptyList(),
        /**
         * The day a created task was given where what was said named none,
         * with the grounds for it; `null` for every other write.
         */
        val assumedDay: AssumedDay? = null,
    )

    /** What a finished write leaves on the screen, whichever write it was. */
    private suspend fun settle(
        task: Task,
        outcome: Result<EditReport>,
        changes: List<PhraseChange> = emptyList(),
    ) = settle(
        Written(
            root = task.root,
            files = listOf(task.file),
            heading = task.heading,
            changes = changes,
        ),
        outcome,
    )

    /** The same, for a write that changed a note no task on screen names. */
    private suspend fun settle(written: Written, outcome: Result<EditReport>) {
        outcome.fold(
            onSuccess = { report ->
                // The note has been written either way. A commit that did not
                // happen is said so in its own words — reported as a failed
                // edit, it would send the user to tap again over a file that
                // has already changed, and that second attempt comes back as
                // "the file has changed".
                report.commitFailure?.let { failure ->
                    Log.w(TAG, "the edit was written but not committed", failure)
                }
                _editIssue.value = report.commitFailure
                    ?.let { SyncMessage(R.string.edit_not_committed, failed = true) }
                // What it takes to put this one tap back, for as long as the
                // line offering it stands. An edit that wrote nothing brings
                // back no pair and clears the offer: the previous edit's
                // rollback would restore a note this tap never touched.
                _editResult.value = report.rollback.takeIf { it.isNotEmpty() }?.let { rollback ->
                    written.root?.let { root ->
                        EditResult(
                            root = root,
                            heading = written.heading,
                            rollback = rollback,
                            created = written.created,
                            changes = written.changes,
                            assumedDay = written.assumedDay,
                        )
                    }
                }
                // Which notes changed is known. Saying so is what keeps the
                // agenda that follows from re-reading every note in the
                // collection; a failure to re-read is not worth a sentence on
                // screen, because the next full scan fixes it. Named by both
                // halves — the same relative path occurs in more than one
                // collection.
                written.root?.let { root ->
                    written.files.forEach { file ->
                        agenda.reread(root, file).onFailure { failure ->
                            Log.w(TAG, "the edited note could not be re-read", failure)
                        }
                    }
                }
                rescan()
                onNotesMoved()
            },
            onFailure = { error ->
                // What the core wrote about it is an English sentence for a
                // log, and that is where it goes; the screen answers in the
                // language of the interface.
                Log.w(TAG, "the edit failed", error)
                _editIssue.value = error.toEditMessage()
            },
        )
    }

    /**
     * Apply one action to a whole band of overdue entries.
     *
     * A group of ten dates from three years ago is answered in one move rather
     * than read task by task, and the core does it as one rewrite per file and
     * one commit. Tasks whose file cannot be named are left out before the call
     * rather than refused by the core with a reason that would be about the
     * wrong thing — the same check a single tap makes.
     */
    fun applyToGroup(rows: List<AgendaRow>, action: BulkAction) {
        val tasks = rows.map(AgendaRow::task).filter(Task::isEditable)
        if (tasks.isEmpty()) {
            _editIssue.value = SyncMessage(R.string.edit_failed_unnamed, failed = true)
            return
        }

        // A band of overdue entries spans whatever collections have overdue
        // tasks, and a collection is one pass and one commit: split here, so
        // each directory is still rewritten once however many of them the band
        // covers. Tasks whose collection is gone are dropped rather than sent
        // to a directory that is no longer read.
        val byCollection = tasks.groupBy { collections.byRoot(it.root) }
            .mapNotNull { (collection, group) -> collection?.let { it to group } }
        if (byCollection.isEmpty()) {
            _editIssue.value = SyncMessage(R.string.edit_failed_no_collection, failed = true)
            rescan()
            return
        }

        scope.launch {
            val started = System.nanoTime()
            val outcomes = byCollection.map { (collection, group) ->
                collection to collection.editor.applyToGroup(group, action, clock().toLocalDate())
            }
            Log.i(TAG, "the group of ${tasks.size} took ${millisSince(started)} ms")

            // A collection that failed does not take the ones that went
            // through with it: what was changed is on disk, and the offer to
            // undo has to name it. The failure is still said, once, over the
            // whole group.
            outcomes.mapNotNull { (_, outcome) -> outcome.exceptionOrNull() }
                .firstOrNull()
                ?.let { error ->
                    Log.w(TAG, "the group could not be applied", error)
                    _editIssue.value = error.toEditMessage()
                }

            val applied = outcomes.mapNotNull { (collection, outcome) ->
                outcome.getOrNull()?.let { collection to it }
            }
            applied.forEach { (_, group) ->
                group.report.commitFailure?.let { failure ->
                    Log.w(TAG, "the group was written but not committed", failure)
                }
            }

            if (applied.isNotEmpty()) {
                _groupResult.value = GroupResult(
                    action = action,
                    changed = applied.sumOf { (_, group) -> group.outcome.changed.toInt() },
                    refused = applied.sumOf { (_, group) -> group.outcome.refused.size },
                    rollback = applied.map { (collection, group) ->
                        CollectionRollback(root = collection.root, files = group.outcome.rollback)
                    },
                )
                // Which files changed is known, but a band spans several
                // and the held notes for each would have to be dropped one
                // by one; the walk that follows is the same one an edit
                // ends in.
                agenda.invalidate()
                rescan()
            }
        }
    }

    /**
     * Put back what the last group action overwrote.
     *
     * Only the files that still hold what it wrote go back — a sync or an edit
     * that landed since is left alone by the core — so this can be pressed
     * without knowing what happened in between.
     */
    fun undoGroup() {
        val rollback = _groupResult.value?.rollback.orEmpty()
        _groupResult.value = null
        if (rollback.isEmpty()) {
            return
        }

        scope.launch {
            // Collection by collection, each one against its own directory:
            // the paths in a rollback are relative, and handing them to another
            // collection would restore whatever note sits at the same path
            // there. A collection removed since is simply not put back.
            val undone = rollback.mapNotNull { entry ->
                collections.byRoot(entry.root)?.editor?.undoGroup(entry.files)
            }

            undone.mapNotNull(Result<*>::exceptionOrNull).firstOrNull()?.let { error ->
                Log.w(TAG, "the group could not be undone", error)
                _editIssue.value = error.toEditMessage()
            }

            val restored = undone.mapNotNull(Result<UndoReport>::getOrNull)
            restored.forEach { report ->
                report.report.commitFailure?.let { failure ->
                    Log.w(TAG, "the undo was written but not committed", failure)
                }
            }
            val partial = restored.any {
                it.outcome.skipped.isNotEmpty() || it.outcome.failed.isNotEmpty()
            }
            if (partial) {
                // Some of it went back and some did not, which is a state the
                // user has to be told about rather than left to notice: the
                // agenda below will show both.
                _editIssue.value = SyncMessage(R.string.agenda_group_undo_partial)
            }

            if (restored.isNotEmpty()) {
                agenda.invalidate()
                rescan()
            }
        }
    }

    /** The group result has been shown, so it is not shown again. */
    fun groupResultShown() {
        _groupResult.value = null
    }

    /**
     * Put back what the last single edit overwrote.
     *
     * The note goes back only while it still holds what the edit wrote — a
     * sync landed since, the note was opened in another application — and the
     * screen says so rather than reporting an undo that did not happen. The
     * offer is dropped either way: it was made about a state the note has left.
     */
    fun undoEdit() {
        val result = _editResult.value ?: return
        _editResult.value = null

        // The collection the edit was made in, which may have been removed
        // while its line stood on screen.
        val theirEditor = collections.byRoot(result.root)?.editor ?: return

        scope.launch {
            // A creation is taken back by its own call: what it puts back is
            // the same file, and what it says in the history is not.
            val taken = when {
                result.created -> theirEditor.undoCreation(result.rollback, result.heading)
                else -> theirEditor.undoEdit(result.rollback, result.heading)
            }

            taken.fold(
                onSuccess = { undone ->
                    undone.report.commitFailure?.let { failure ->
                        Log.w(TAG, "the undo was written but not committed", failure)
                    }
                    if (undone.outcome.restored.isEmpty()) {
                        _editIssue.value = SyncMessage(R.string.agenda_edit_undo_skipped)
                        return@fold
                    }
                    val left = undone.outcome.skipped + undone.outcome.failed
                    if (left.isNotEmpty()) {
                        // An undone move puts two files back — the note the
                        // entry left and the one it arrived in. One of them
                        // returning and the other not leaves the entry in both
                        // notes or in neither, which is worth a line rather
                        // than the silence of a successful undo.
                        Log.w(TAG, "part of the edit was not put back: $left")
                        _editIssue.value = SyncMessage(R.string.agenda_edit_undo_partial)
                    }

                    // Which files went back is known, so the agenda is rebuilt
                    // off a re-read of those notes rather than a walk of the
                    // collection. Two of them for an undone move, one for
                    // everything else.
                    result.rollback.forEach { rollback ->
                        agenda.reread(result.root, rollback.file).onFailure { failure ->
                            Log.w(TAG, "the restored note could not be re-read", failure)
                        }
                    }
                    rescan()
                },
                onFailure = { error ->
                    Log.w(TAG, "the edit could not be undone", error)
                    _editIssue.value = error.toEditMessage()
                },
            )
        }
    }

    /** The edit result has been shown, so it is not shown again. */
    fun editResultShown() {
        _editResult.value = null
    }

    /** How long ago, in milliseconds, a step that was timed started. */
    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private companion object {
        /** Where the failures the screen does not spell out are written. */
        private const val TAG = "EntryEdits"

        /** A draft that has been told nothing, for a phrase read from scratch. */
        private val EMPTY_PHRASE = PhraseDraft(
            heading = "",
            priority = null,
            keyword = null,
            date = null,
            time = null,
            repeater = null,
            status = null,
            cleared = emptyList(),
        )

        /**
         * How much text an entry may hold and still be edited here.
         *
         * A field of this size answers a keystroke in about a third of a
         * second on an emulator; a run of the same measurement puts 134 KB at
         * a second and 676 KB at six. What sets the size is the user's file
         * rather than the interface, so the limit is stated rather than
         * assumed, and a longer entry is sent to an editor built for one.
         */
        private const val BODY_LIMIT = 20_000
    }
}
