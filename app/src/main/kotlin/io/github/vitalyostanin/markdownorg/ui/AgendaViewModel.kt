package io.github.vitalyostanin.markdownorg.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.AgendaLoader
import io.github.vitalyostanin.markdownorg.core.AgendaSource
import io.github.vitalyostanin.markdownorg.core.CollectionInUse
import io.github.vitalyostanin.markdownorg.core.CollectionProblem
import io.github.vitalyostanin.markdownorg.core.CollectionsInUse
import io.github.vitalyostanin.markdownorg.core.DeviceCollections
import io.github.vitalyostanin.markdownorg.core.MergedTag
import io.github.vitalyostanin.markdownorg.core.NotesArea
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.NotesCollectionsPreferences
import io.github.vitalyostanin.markdownorg.core.NotesCollectionsStore
import io.github.vitalyostanin.markdownorg.core.NotesLocation
import io.github.vitalyostanin.markdownorg.core.NotesSyncer
import io.github.vitalyostanin.markdownorg.core.NotesWriter
import io.github.vitalyostanin.markdownorg.core.RemoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.SampleWording
import io.github.vitalyostanin.markdownorg.core.StorageAccess
import io.github.vitalyostanin.markdownorg.core.SyncPreferences
import io.github.vitalyostanin.markdownorg.core.SyncRun
import io.github.vitalyostanin.markdownorg.core.UiPreferences
import io.github.vitalyostanin.markdownorg.core.UiSettings
import io.github.vitalyostanin.markdownorg.core.UndoReport
import io.github.vitalyostanin.markdownorg.core.byRoot
import io.github.vitalyostanin.markdownorg.core.collectionProblem
import io.github.vitalyostanin.markdownorg.core.mergeTagDictionaries
import io.github.vitalyostanin.markdownorg.core.migratedCollections
import io.github.vitalyostanin.markdownorg.core.nextCollectionId
import io.github.vitalyostanin.markdownorg.core.notesPathProblem
import io.github.vitalyostanin.markdownorg.core.ownNotesRoot
import io.github.vitalyostanin.markdownorg.core.readDeclaredTags
import io.github.vitalyostanin.markdownorg.core.remoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.single
import io.github.vitalyostanin.markdownorg.core.splitCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.markdown_org_ffi.Adoption
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.FileRollback
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.SyncException
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.generateSshKey
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

class AgendaViewModel(
    /** The collections in use, each with the working copy that belongs to it. */
    private val collections: CollectionsInUse,
    /** Where the set of collections is kept between launches. */
    private val stored: NotesCollectionsPreferences,
    private val agenda: AgendaLoader,
    private val ui: UiPreferences,
    /** The directory an empty choice falls back to. */
    private val ownNotes: File,
    /**
     * The words the sample notes are written in, read from the resources so
     * the first run speaks the language of the device.
     */
    private val sample: SampleWording,
    /** Whether a directory outside that one may be read, asked of the platform. */
    private val storageGranted: () -> Boolean,
    /** The wall clock, taken as a parameter so a test can move it by hand. */
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    /**
     * Which collection the settings screen is about.
     *
     * The agenda is over all of them, but a remote, a token and a directory
     * belong to one: the form edits the collection named here, and a device
     * that has never been past the first one edits that one.
     */
    private val _editingId = MutableStateFlow(collections.entries.first().collection.id)
    val editingId: StateFlow<String> = _editingId.asStateFlow()

    /**
     * The whole set, as the settings screen lists it.
     *
     * Beside [collectionFilter], which is about the agenda and is empty while
     * there is one collection: this one always holds every collection, because
     * the screen that adds the second one has to show the first.
     */
    private val _collectionSet = MutableStateFlow(
        collections.entries.map(CollectionInUse::collection),
    )
    val collectionSet: StateFlow<List<NotesCollection>> = _collectionSet.asStateFlow()

    /** The collection the settings screen is about, and what acts on it. */
    private val editing: CollectionInUse
        get() = collections.entries.firstOrNull { it.collection.id == _editingId.value }
            ?: collections.single

    private val notes: NotesArea get() = editing.area
    private val settings: SyncPreferences get() = editing.settings
    private val editor: NotesWriter get() = editing.editor
    private val sync: NotesSyncer get() = editing.syncer

    private val _state = MutableStateFlow<AgendaUiState>(AgendaUiState.Loading)
    val state: StateFlow<AgendaUiState> = _state.asStateFlow()

    /**
     * The days as the scan produced them, before the filter.
     *
     * Held so that hiding a collection is a regroup of what is already in
     * hand: [state] carries what is on screen, and rebuilding the full agenda
     * out of it after a chip has been turned off is not possible.
     */
    private var scanned: List<AgendaDay>? = null

    /** The collections whose rows the filter is keeping off the screen. */
    private var hidden: Set<String> = emptySet()

    /**
     * The tags the collections declare, merged, as of the last scan.
     *
     * Read with the notes rather than watched: the file arrives with a sync
     * like the notes around it, and a scan is what follows a sync.
     */
    private val _tags = MutableStateFlow<List<MergedTag>>(emptyList())
    val tags: StateFlow<List<MergedTag>> = _tags.asStateFlow()

    /**
     * The tag the agenda is narrowed to, or null while it is not narrowed.
     *
     * Not stored between launches, for the reason the collection filter is not:
     * an agenda missing half its tasks with no memory of why is worse than one
     * that starts whole.
     */
    private val _currentTag = MutableStateFlow<String?>(null)
    val currentTag: StateFlow<String?> = _currentTag.asStateFlow()

    /**
     * The filter over the agenda, empty while there is one collection.
     *
     * Not stored between launches: it hides tasks, and a device that opens on
     * an agenda missing half of them with no memory of why is worse off than
     * one that starts with everything shown.
     */
    private val _collectionFilter = MutableStateFlow<List<CollectionChoice>>(emptyList())
    val collectionFilter: StateFlow<List<CollectionChoice>> = _collectionFilter.asStateFlow()

    /**
     * Kept apart from [state] so switching the layout redraws without going
     * back through Loading — the data is the same, only its shape changes.
     * Read from the stored preference, so the agenda opens the way it was
     * left rather than always on the hour axis.
     */
    private val _layout = MutableStateFlow(ui.layout)
    val layout: StateFlow<AgendaLayout> = _layout.asStateFlow()

    /**
     * How much of the plan is asked for, read from the stored preference for
     * the same reason as the layout: a span chosen yesterday is the one the
     * screen opens on today.
     *
     * Unlike the layout, changing it costs a scan — the core groups the tasks
     * against the span, and a week is not something the day agenda on screen
     * can be regrouped into.
     */
    private val _span = MutableStateFlow(ui.span)
    val span: StateFlow<AgendaSpan> = _span.asStateFlow()

    /**
     * Whether the day is drawn under its section headings.
     *
     * Costs no scan, like the layout: the sections are already in hand and the
     * setting decides whether they announce themselves.
     */
    private val _grouped = MutableStateFlow(ui.grouped)
    val grouped: StateFlow<Boolean> = _grouped.asStateFlow()

    /**
     * Whether the month is read as a calendar or as the list of its days.
     *
     * Costs no scan either: the same month is in hand, and this decides which
     * of the two readings of it is drawn.
     */
    private val _monthAsGrid = MutableStateFlow(ui.monthAsGrid)
    val monthAsGrid: StateFlow<Boolean> = _monthAsGrid.asStateFlow()

    /**
     * Which weekday a week is read as beginning on.
     *
     * Unlike the two above it, this one costs a scan: where a week starts is
     * the core's to apply — it groups the week span and cuts the calendar into
     * rows — so a changed answer is a different agenda, not a different
     * drawing of the same one.
     */
    private val _weekStart = MutableStateFlow(ui.weekStart)
    val weekStart: StateFlow<WeekStart> = _weekStart.asStateFlow()

    /**
     * Which date the plan is asked around, or `null` for whatever day it is.
     *
     * `null` rather than today's date written down at launch: a phone is left
     * running over midnight, and a date taken once would keep showing yesterday
     * until something reset it. Not stored either — a step away from today is
     * an act of looking something up, and an application reopened tomorrow
     * opens on tomorrow.
     */
    private val _anchor = MutableStateFlow<LocalDate?>(null)
    val anchor: StateFlow<LocalDate?> = _anchor.asStateFlow()

    private val _syncState = MutableStateFlow(SyncUiState())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    /**
     * The last edit that could not be made, until it has been shown.
     *
     * A channel of its own rather than the sync banner: that line reports the
     * state of the checkout, and "the task could not be changed" is about a
     * tap the user just made. Sharing one slot meant an edit failure sat under
     * the header until the next sync, and displaced the checkout it describes.
     */
    private val _editIssue = MutableStateFlow<SyncMessage?>(null)
    val editIssue: StateFlow<SyncMessage?> = _editIssue.asStateFlow()

    /**
     * What the last group action did, until it has been shown.
     *
     * Apart from [editIssue] because it is not an issue: it carries what to
     * undo, and the undo is what makes acting on twenty notes at once
     * something a user can risk.
     */
    private val _groupResult = MutableStateFlow<GroupResult?>(null)
    val groupResult: StateFlow<GroupResult?> = _groupResult.asStateFlow()

    /**
     * The task whose actions are open, if any.
     *
     * Held here rather than in the composition so it survives a rebuild of
     * the agenda: an edit refreshes the list underneath the sheet.
     */
    private val _selected = MutableStateFlow<Task?>(null)
    val selected: StateFlow<Task?> = _selected.asStateFlow()

    /**
     * The work in flight, so a new request can supersede it.
     *
     * Both are read and written from the main thread, where every entry point
     * of this class is called. Holding the jobs rather than a flag also keeps
     * the decision to start atomic: a boolean checked before `launch` and set
     * inside it leaves a window between the two.
     */
    private var refreshJob: Job? = null
    private var syncJob: Job? = null

    /**
     * The date the agenda on screen was grouped against — what "overdue" was
     * decided by, whichever day is being looked at.
     *
     * The shown date cannot answer that once it can be stepped away from: an
     * agenda for next Friday differs from today's date every minute, and the
     * check for the day turning over would then fire on every tick.
     */
    private var groupedAgainst: LocalDate = clock().toLocalDate()

    /**
     * The wall clock, ticked once a minute while the screen is watching.
     *
     * The agenda used to take the time once, when it was built, and keep it:
     * the marker line then stood where the last scan had left it, which after
     * an hour in the background was an hour behind the phone's own clock. The
     * ticker is not part of [state] because it must not put a directory walk
     * behind the passing of a minute — the axis is projected from sections
     * already in hand.
     *
     * It runs only while collected, so a screen in the background costs
     * nothing; coming back to it resubscribes and reads the clock at once,
     * which is what makes a return from the background correct without a
     * lifecycle callback of its own. The delay is measured to the top of the
     * next minute rather than a flat 60 seconds, so the line turns over with
     * the clock instead of drifting away from it.
     */
    val now: StateFlow<LocalDateTime> = flow {
        while (true) {
            val moment = clock()
            emit(moment)
            delay(millisUntilNextMinute(moment))
        }
    }
        // A day that turns over needs the whole agenda again: what was due
        // today is overdue now, and the sections were grouped against the old
        // date. Checked here rather than by a broadcast receiver: this flow is
        // already awake for the minute in which it happens.
        .onEach(::refreshOnNewDay)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(TICKER_LINGER_MS), clock())

    init {
        viewModelScope.launch {
            // Before the first scan, not only when the set changes: a launch
            // reads a set that was stored earlier, and a directory of it may
            // be gone — removed by hand, or on storage that is not mounted.
            useDirectories()
            refresh()
        }
        readCheckout()
    }

    fun setLayout(layout: AgendaLayout) {
        _layout.value = layout
        ui.layout = layout
    }

    /** Draw the day under its section headings, or as one list. */
    fun setGrouped(grouped: Boolean) {
        _grouped.value = grouped
        ui.grouped = grouped
    }

    /**
     * Draw the month as a calendar, or as the list the week uses.
     *
     * No scan follows, unlike [setSpan]: both readings are of the same month
     * the core has already answered with, and which of them is drawn is the
     * screen's decision alone.
     */
    fun setMonthAsGrid(asGrid: Boolean) {
        if (asGrid == _monthAsGrid.value) return

        _monthAsGrid.value = asGrid
        ui.monthAsGrid = asGrid
        // The two readings of a month are no longer the same answer read two
        // ways: the calendar is asked for the whole weeks it draws, the list
        // for the month alone. Only the month is affected, and only while it
        // is the span on screen -- switching this from the day view changes
        // what the next month will ask for and nothing that is drawn now.
        if (_span.value == AgendaSpan.MONTH) {
            refresh()
        }
    }

    /**
     * Read a week as beginning on another weekday.
     *
     * A scan follows wherever the choice is visible: the calendar is cut into
     * weeks by the core, and so is the week span itself, so neither can be
     * redrawn from the answer already in hand.
     */
    fun setWeekStart(start: WeekStart) {
        if (start == _weekStart.value) return

        _weekStart.value = start
        ui.weekStart = start
        if (_span.value == AgendaSpan.WEEK ||
            (_span.value == AgendaSpan.MONTH && _monthAsGrid.value)
        ) {
            refresh()
        }
    }

    /**
     * The weekday a week begins on, for the spans drawn in weeks.
     *
     * The core takes a fixed Monday when it is told nothing, and reads no
     * locale to do better: it is a library with one answer per input, and the
     * phone is what knows how its owner reads a calendar. The setting answers,
     * and its own default is the system locale.
     */
    private fun weekStart(): DayOfWeek = _weekStart.value.resolve()

    /**
     * Ask the core for another span of the plan.
     *
     * A scan follows, because the grouping is the core's: a week is the same
     * notes read against seven dates, and the day already on screen carries
     * neither the other six nor the tasks that have no date at all. The one on
     * screen stays up while it runs — see [refresh].
     */
    fun setSpan(span: AgendaSpan) {
        if (span == _span.value) {
            return
        }

        _span.value = span
        ui.span = span
        refresh()
    }

    /**
     * Move the plan [steps] spans away from where it stands: a day at a time
     * under the day span, a week under the week, a month under the month.
     *
     * The step follows the span rather than always being a day, because the
     * span is what is on screen: stepping a week agenda by one day would answer
     * with six of the same seven days and read as nothing having happened. The
     * flat list of tasks has no dates to step through and stays where it is.
     *
     * Costs a scan, like [setSpan]: the grouping into overdue, timed and
     * untimed is the core's, and it is made against the date asked for.
     */
    fun stepBy(steps: Int) {
        if (steps == 0 || !_span.value.hasDays) {
            return
        }

        val from = _anchor.value ?: clock().toLocalDate()
        val moved = when (_span.value) {
            AgendaSpan.WEEK -> from.plusWeeks(steps.toLong())
            AgendaSpan.MONTH -> from.plusMonths(steps.toLong())
            else -> from.plusDays(steps.toLong())
        }
        // Back to following the clock rather than pinned to today's date: a
        // step back to today should leave the screen as it was before the first
        // step forward, midnight included.
        _anchor.value = moved.takeIf { it != clock().toLocalDate() }
        refresh()
    }

    /**
     * Show one day, whatever span was on screen.
     *
     * What a cell of the month calendar asks for. Both the anchor and the span
     * move, and a scan follows for the reason [setSpan] takes one: a day is a
     * different grouping of the notes, not a slice of the month already in
     * hand. The anchor is cleared when the day asked for is today, so the plan
     * goes back to following the clock — the same rule [stepBy] keeps.
     */
    fun showDay(date: LocalDate) {
        _anchor.value = date.takeIf { it != clock().toLocalDate() }
        if (_span.value != AgendaSpan.DAY) {
            _span.value = AgendaSpan.DAY
            ui.span = AgendaSpan.DAY
        }
        refresh()
    }

    /** Back to the day being lived through, from wherever the plan was moved to. */
    fun showToday() {
        if (_anchor.value == null) {
            return
        }

        _anchor.value = null
        refresh()
    }

    /**
     * Show or hide the rows of one collection.
     *
     * Answers on the spot, without a scan: the notes have not changed, only
     * how much of them is on screen. Turning the last collection off is
     * allowed and leaves the agenda empty — the chips stay up, and the way
     * back is the same tap.
     */
    fun setCollectionShown(id: String, shown: Boolean) {
        hidden = if (shown) hidden - id else hidden + id
        _collectionFilter.update { choices ->
            choices.map { choice ->
                if (choice.label.id == id) choice.copy(shown = shown) else choice
            }
        }
        reshow()
    }

    /**
     * Narrow the agenda to one tag, or to none when [tag] is null.
     *
     * Costs no scan, like the collection chips: the tag reads the name of the
     * file a row came from, which the rows already carry.
     */
    fun setTag(tag: String?) {
        _currentTag.value = tag
        reshow()
    }

    /**
     * Redraw what is on screen from the scan already in hand.
     *
     * Both filters run here and in this order: the collections decide which
     * rows exist at all, the tag selects among them. Order matters for what
     * the reader sees, not for the outcome -- neither can bring back a row the
     * other removed -- but keeping it in one place is what stops the two from
     * drifting apart.
     */
    private fun reshow() {
        val full = scanned ?: return
        val shown = full.showing(hidden).tagged(_currentTag.value, _tags.value)
        _state.update { current ->
            when (current) {
                is AgendaUiState.Ready -> current.copy(days = shown)
                else -> current
            }
        }
    }

    fun select(task: Task?) {
        _selected.value = task
    }

    /** Which collection the settings screen is about from now on. */
    fun editCollection(id: String) {
        if (collections.entries.any { it.collection.id == id }) {
            _editingId.value = id
        }
    }

    /**
     * Add a collection and make it the one the settings screen is about.
     *
     * The directory is one inside the application's own storage, which is
     * always there to be written to and clashes with nothing; pointing the
     * collection at a repository somewhere else is the next thing the form
     * does, and the one thing it cannot do is start from a path that does not
     * work.
     */
    fun addCollection(name: String) {
        val existing = stored.collections
        val id = nextCollectionId(existing)
        val added = NotesCollection(
            id = id,
            name = name.trim(),
            path = File(ownNotes.parentFile ?: ownNotes, "notes-$id").absolutePath,
        )

        val problem = collectionProblem(added.name, added.path, existing)
        if (problem != null) {
            _syncState.update { it.copy(message = problem.toMessage()) }
            return
        }

        useCollections(existing + added)
        _editingId.value = id
    }

    /**
     * Stop reading a collection.
     *
     * The directory is left exactly as it is: it may be a repository with
     * commits in it, and a screen that offers to stop showing notes must not
     * be the screen that deletes them. What does go is the settings file of
     * that collection — the token in it reached a server this device no longer
     * talks to.
     *
     * The last collection stays: an agenda over nothing has no way back except
     * a reinstall.
     */
    fun removeCollection(id: String) {
        val rest = stored.collections.filterNot { it.id == id }
        if (rest.isEmpty() || rest.size == stored.collections.size) {
            return
        }

        val gone = collections.entries.firstOrNull { it.collection.id == id }
        useCollections(rest)
        // After it has been taken out of use, so nothing is left holding the
        // settings that are about to be erased.
        gone?.let(collections::forget)
        if (_editingId.value == id) {
            _editingId.value = rest.first().id
        }
    }

    /**
     * Work with [set] from now on: stored, put to use and walked again.
     *
     * The three go together on purpose — a set stored but not put to use is
     * one the next launch reads and this one does not, and an agenda built
     * before the change describes directories that are no longer in it.
     */
    private fun useCollections(set: List<NotesCollection>) {
        stored.collections = set
        collections.use(set)
        _collectionSet.value = set
        viewModelScope.launch {
            useDirectories()
            // What is held describes the directories of the previous set; the
            // index behind it is opened again over the new roots.
            agenda.invalidate()
            refresh()
        }
    }

    /**
     * Make sure every collection has the directory it names.
     *
     * A collection that has just been added names a directory nothing has
     * created yet, and the walk refuses a root it cannot open — taking the
     * whole agenda with it, the other collections included. Done here rather
     * than at the first write, which on a collection that is only ever read
     * would never come.
     */
    private suspend fun useDirectories() {
        val refused = collections.entries.filterNot { entry ->
            entry.area.prepareDirectory().isSuccess
        }
        if (refused.isNotEmpty()) {
            Log.w(TAG, "directories could not be used: ${refused.map { it.collection.path }}")
            _syncState.update {
                it.copy(message = SyncMessage(R.string.settings_notes_failed, failed = true))
            }
        }
    }

    /** The edit failure has been shown, so it is not shown again. */
    fun editIssueShown() {
        _editIssue.value = null
    }

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

        // A task read out of a file whose name is not UTF-8 names a path that
        // does not exist, so every edit would come back as "file not found" —
        // refused here with a reason instead.
        if (!task.isEditable()) {
            _editIssue.value = SyncMessage(R.string.edit_failed_unnamed, failed = true)
            return
        }

        // The collection the task came from rather than the one being edited
        // in the settings: the agenda shows several at once, and writing to
        // the wrong directory would edit whatever note happens to sit at the
        // same relative path there. A collection removed while its tasks were
        // still on screen has nothing to write to.
        val theirEditor = collections.byRoot(task.root)?.editor
        if (theirEditor == null) {
            _editIssue.value = SyncMessage(R.string.edit_failed_no_collection, failed = true)
            refresh()
            return
        }

        viewModelScope.launch {
            // The other half of what a tap costs, alongside the scan timed in
            // refresh(): the write is one file, but the commit that follows it
            // reads the whole working copy, so this grows with the notes too.
            val started = System.nanoTime()
            val outcome = when (action) {
                TaskAction.Complete -> theirEditor.complete(task, clock().toLocalDate())
                is TaskAction.Status -> theirEditor.setStatus(task, action.status)
                is TaskAction.Priority -> theirEditor.setPriority(task, action.value)
                is TaskAction.Shift -> theirEditor.shift(task, action.keyword, action.days)
            }
            Log.i(TAG, "the edit took ${millisSince(started)} ms")

            outcome.fold(
                onSuccess = { report ->
                    // The note has been written either way. A commit that did
                    // not happen is said so in its own words — reported as a
                    // failed edit, it would send the user to tap again over a
                    // file that has already changed, and that second attempt
                    // comes back as "the file has changed".
                    report.commitFailure?.let { failure ->
                        Log.w(TAG, "the edit was written but not committed", failure)
                    }
                    _editIssue.value = report.commitFailure
                        ?.let { SyncMessage(R.string.edit_not_committed, failed = true) }
                    // One file changed and it is known which. Saying so is what
                    // keeps the agenda that follows from re-reading every note
                    // in the collection; a failure to re-read is not worth a
                    // sentence on screen, because the next full scan fixes it.
                    // Named by both halves — the same relative path occurs in
                    // more than one collection.
                    task.root?.let { root ->
                        agenda.reread(root, task.file).onFailure { failure ->
                            Log.w(TAG, "the edited note could not be re-read", failure)
                        }
                    }
                    refresh()
                },
                onFailure = { error ->
                    // What the core wrote about it is an English sentence for
                    // a log, and that is where it goes; the sheet answers in
                    // the language of the interface.
                    Log.w(TAG, "the edit failed", error)
                    _editIssue.value = error.toEditMessage()
                },
            )
        }
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
            refresh()
            return
        }

        viewModelScope.launch {
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
                refresh()
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

        viewModelScope.launch {
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
                refresh()
            }
        }
    }

    /** The group result has been shown, so it is not shown again. */
    fun groupResultShown() {
        _groupResult.value = null
    }

    /**
     * Rebuild the agenda, dropping whatever rebuild was under way.
     *
     * Loads run on the IO pool and finish out of order, so without the cancel
     * a slow scan started before an edit could land on top of the fast one
     * started after it, and put the pre-edit agenda back on screen.
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // An agenda already on screen stays on screen: the scan that
            // follows an edit answers with almost the same list, and blanking
            // the screen for it costs the header and the scroll position.
            _state.update { current ->
                when (current) {
                    is AgendaUiState.Ready -> current.copy(refreshing = true)
                    else -> AgendaUiState.Loading
                }
            }
            val today = clock().toLocalDate()
            // Which date the plan is asked around: today unless the user has
            // stepped away from it. Held apart from `today`, which the seeding
            // below and the day-turned-over check still mean literally.
            val shown = _anchor.value ?: today
            groupedAgainst = today
            // What the walk cost, for the one question the screen cannot
            // answer: whether a directory of this size is still usable. The
            // scan is the only part of a refresh that grows with the notes,
            // and there is no console on a phone to time it from.
            val started = System.nanoTime()
            // Seeding is a write to the same directory the scan reads, and it
            // fails the same ways: no space, a directory that cannot be
            // written to. Its failure goes on the screen rather than out of
            // the coroutine, which used to take the process with it.
            //
            // Only while there is one collection: the sample is what a fresh
            // install has to show instead of an empty screen, and dropping a
            // file of ours into a directory somebody added on purpose — a work
            // repository, a shared one — is not that.
            val seeded = collections.entries.singleOrNull()
                ?.let { only ->
                    only.area.ensureSeeded(today, sample) { only.settings.isConfigured }
                }
                ?: Result.success(Unit)

            val labels = collectionLabels()
            offerFilter(labels.values.toList())
            offerTags()

            // Read once, so the agenda that comes back is grouped the way the
            // header above it says: the span can be changed while a scan is in
            // flight, and the answer of that scan describes the span it was
            // asked for.
            val span = _span.value
            // Both dates, and they are not the same one: `today` is what the
            // plan is late against, `shown` is what is drawn. Asked around
            // `shown` alone, the arrears of the whole collection moved with
            // the reader — a month paged forward reported them under the day
            // being looked at, and the same task was counted both there and
            // in its own day.
            // Which reading of the month is on screen decides what is asked
            // for: the calendar needs the whole weeks it draws, borrowed days
            // and all, while the list of a month is that month and nothing
            // either side of it.
            val scope = if (span == AgendaSpan.MONTH && _monthAsGrid.value) {
                Scope.MONTH_GRID
            } else {
                span.scope
            }
            val built = seeded.mapCatching {
                agenda.load(
                    scope,
                    today = today,
                    shown = shown,
                    weekStart = weekStart(),
                ).getOrThrow()
            }
            // `mapCatching` catches every throwable, and the cancellation this
            // scan is dropped by is one of them: folded like any other failure
            // it put "the agenda could not be built" on screen, over a scan
            // nobody was waiting for any more. Rethrown, it ends this
            // coroutine and leaves the screen to the scan that replaced it.
            (built.exceptionOrNull() as? CancellationException)?.let { throw it }
            // And once more for the cancellation that arrived after the load
            // answered: there is no exception to rethrow then, and writing the
            // result would be this scan overtaking the one it was dropped for.
            ensureActive()

            _state.value = built
                .fold(
                    onSuccess = { result ->
                        val days = result.toDays(labels)
                        scanned = days
                        val sections = days.merged()
                        val rows = sections.overdue.size + sections.timed.size +
                            sections.untimed.size
                        Log.i(
                            TAG,
                            "the agenda was built in ${millisSince(started)} ms, $rows rows",
                        )
                        AgendaUiState.Ready(
                            date = shown,
                            days = days.showing(hidden).tagged(_currentTag.value, _tags.value),
                            span = span,
                            notices = result.notices(),
                        )
                    },
                    onFailure = { error ->
                        // The class and the stack go to the log, where they
                        // are of use; the screen gets wording it can
                        // translate.
                        Log.w(TAG, "the agenda could not be built", error)
                        AgendaUiState.Failed(error.toAgendaMessage())
                    },
                )
        }
    }

    /**
     * Fetch and fast-forward every collection that has a remote, then rebuild
     * the agenda over what arrived.
     *
     * One after another rather than together: each holds its own working copy
     * while it runs, and a phone syncing three repositories at once spends the
     * radio on all of them and finishes none of them sooner. A collection that
     * fails does not stop the ones after it — the notes of the others did come
     * forward, and saying otherwise would send the user looking for a fetch
     * that worked.
     */
    fun syncNow() {
        if (syncJob?.isActive == true) {
            return
        }

        val configured = collections.entries.filter { it.settings.isConfigured }
        if (configured.isEmpty()) {
            return
        }

        // What the previous run answered goes before this one starts: a line
        // per collection that is left over from an hour ago describes a
        // checkout nobody has looked at since.
        _syncState.update { it.copy(runs = emptyList()) }

        syncJob = viewModelScope.launch {
            configured.forEach { collection -> runSync(collection) }
        }
    }

    /**
     * Stores the remote and gets the directory into a state it can be synced
     * from.
     *
     * Nothing here empties anything, and nothing anywhere else does either. A
     * directory that already holds notes and no git is taken in as it stands —
     * `adopt` makes what is in it the first commit and only then adds the
     * remote — and a directory holding neither is cloned into.
     *
     * A checkout of another remote is the one case saving cannot resolve, and
     * it stays unresolved: it says so and leaves both alone. The files are the
     * user's, the commits in them may exist nowhere else, and the way on is
     * another directory or a hand emptying this one — not a button here.
     *
     * [token] empty means "leave the stored one alone", since the form never
     * shows it. That cannot hold across a change of host, though — a token is
     * issued by one server and has no business reaching another — so the
     * stored one is dropped along with the URL it belonged to. [dropToken]
     * clears it outright, which is the only way to go back to a remote that
     * needs no credentials.
     *
     * An address that carries credentials of its own — which is how a clone
     * command copied from a repository page reads — is split before anything
     * else: the secret belongs in the token, not in the field the screen shows
     * in the clear.
     *
     * [sshKey] follows the token's rule, with one difference: it is not
     * dropped when the address changes. A token is issued by one server; a key
     * belongs to the device and is added to as many servers as its owner
     * likes. What is dropped with the address is the server key it was known
     * by — that one is about the host and about nothing else.
     */
    @Suppress("LongParameterList")
    fun saveSettings(
        url: String,
        branch: String,
        token: String,
        dropToken: Boolean = false,
        notesPath: String = editing.collection.path,
        name: String = editing.collection.name,
        sshKey: String = "",
        sshPassphrase: String = "",
        dropKey: Boolean = false,
    ) {
        // Before anything is stored, and against the same rule the set itself
        // is checked by: a collection with no name is one the filter offers as
        // a blank chip and the rows carry a blank mark.
        val named = name.trim()
        if (named.isEmpty()) {
            _syncState.update { it.copy(message = CollectionProblem.NAME_EMPTY.toMessage()) }
            return
        }

        val split = splitCredentials(url)
        val address = split.url
        val secret = token.ifBlank { split.token.orEmpty() }

        // An empty address is not a failure but a form that is only about the
        // directory: notes already on the device need no remote, and the one
        // configured earlier — if any — is left exactly as it was.
        val problem = remoteUrlProblem(address).takeUnless { it == RemoteUrlProblem.EMPTY }
        if (problem != null) {
            // Nothing is stored and nothing is deleted: the address is checked
            // before the destructive part, not after the clone fails.
            _syncState.update { it.copy(message = problem.toMessage()) }
            return
        }

        // Checked here as well as on the form, and before anything is stored:
        // the form is one caller, and a directory that cannot hold the notes
        // must not become the one the next scan walks.
        val directoryProblem = notesPathProblem(notesPath, ownNotes, storageGranted())
            ?.toMessage()
        if (directoryProblem != null) {
            _syncState.update { it.copy(message = directoryProblem) }
            return
        }

        // Saving is work on the working copy, so it becomes the job that
        // stands for one: everything that asks "is a sync under way" — the
        // sync icon, the answers beside the banner — has to be told yes while
        // the directory is being moved and the address stored. Held in a local
        // first, because the job this is about to become cannot cancel itself.
        val running = syncJob
        syncJob = viewModelScope.launch {
            // A sync in flight owns the directory this is about to point
            // somewhere else, so it is stopped rather than raced with.
            //
            // Cancelling asks; it does not interrupt. The sync is inside a
            // call into the core, and that call returns when it returns — a
            // fetch on a stalled connection, at the outside, when the core's
            // own network timeouts expire. This waits for that, and it is why
            // the core has those timeouts rather than the operating system's.
            running?.cancelAndJoin()
            _syncState.update { it.copy(running = false) }

            if (!saveDirectory(notesPath, named)) {
                return@launch
            }

            // The rest is about a remote, and there is none in the form. What
            // was stored before stays: clearing it here would be a way to lose
            // a repository by saving a directory.
            if (address.isEmpty()) {
                return@launch
            }

            saveRemote(address, branch, secret, dropToken, sshKey, sshPassphrase, dropKey)
        }
    }

    /**
     * Put the notes where the form says, under the name it gives them.
     *
     * Runs before the remote half of the same save: everything there reads the
     * checkout, and after a move that has to be the checkout in the new
     * directory. Returns whether the save may go on — a move that failed leaves
     * the rest untouched, because storing a remote against a directory the
     * notes are not in would clone into the old one.
     */
    private suspend fun saveDirectory(notesPath: String, named: String): Boolean {
        if (moveNotes(notesPath).isFailure) {
            return false
        }

        // After the move, and over what the move stored: the two are edits to
        // the same set, and renaming against the set as it was would put the
        // old directory back.
        renameEditing(named)

        return true
    }

    /**
     * Store the address and the credentials, then take up the directory.
     *
     * The second half of a save, reached only with an address in the form. What
     * happens to the directory afterwards is decided from the checkout that is
     * already in it: a fetch into a checkout of this same remote, an adoption
     * of notes that are not in git yet, or a refusal to touch a checkout of
     * somewhere else.
     */
    @Suppress("LongParameterList")
    private suspend fun saveRemote(
        address: String,
        branch: String,
        secret: String,
        dropToken: Boolean,
        sshKey: String,
        sshPassphrase: String,
        dropKey: Boolean,
    ) {
        // Which host the stored token was issued for: the settings, not the
        // checkout. A directory holding no repository yet says nothing about
        // where the token came from.
        val configuredUrl = settings.remoteUrl

        // Read off disk rather than from the state: the state is filled in
        // asynchronously after launch, and saving before it arrives would
        // throw away a checkout that did not need to go.
        val previous = sync.status()
        if (previous.isFailure) {
            // The checkout is there but could not be read. Emptying it now
            // would delete commits that exist nowhere else, so the address
            // is stored and the directory left for a human to look at.
            storeRemote(address, branch, secret, dropToken, configuredUrl)
            storeKey(sshKey, sshPassphrase, dropKey)
            _syncState.update {
                it.copy(
                    configured = settings.isConfigured,
                    message = SyncMessage(R.string.sync_status_unreadable, failed = true),
                )
            }
            return
        }

        // Compared without the credentials the checkout's own `origin` may
        // carry: a clone made before those were split off names the same
        // repository, and treating it as another one would send the user
        // to a decision there is nothing to decide.
        val before = previous.getOrNull()?.url?.let { splitCredentials(it).url }
        val checkout = previous.getOrNull() != null
        storeRemote(address, branch, secret, dropToken, configuredUrl)
        storeKey(sshKey, sshPassphrase, dropKey)
        // An address was named, so this is no longer the store the user
        // said was local — whatever happens to the directory below.
        settings.storesLocally = false
        _syncState.update { it.copy(configured = settings.isConfigured) }

        when {
            // Somebody else's checkout, or this one pointed elsewhere. The
            // address is kept and the directory is not touched: emptying
            // it is what used to happen, and it took every commit that had
            // not been pushed with it. The message says the way on.
            checkout && before != settings.remoteUrl -> _syncState.update {
                it.copy(
                    message = SyncMessage(R.string.settings_other_checkout, failed = true),
                )
            }

            // Already a checkout of this remote: fetch into it, branch
            // change included — the core moves the checkout onto the new
            // branch without touching what is committed here.
            checkout -> startSync()

            // A directory with notes and no git: taken in as it stands.
            else -> startAdoption()
        }
    }

    /**
     * Keep the notes on this device and stop asking for a remote.
     *
     * The state was reachable by accident before — a directory with no address
     * is a plain directory — and read as "not set up yet" on every launch.
     * Said outright it is a way to use the application: no banner, no retry,
     * and a remote can still be added later without the notes going anywhere.
     */
    fun keepNotesLocal() {
        settings.storesLocally = true
        _syncState.update {
            it.copy(local = true, message = SyncMessage(R.string.settings_local_chosen))
        }
    }

    /**
     * Take the notes already in the directory into git and point them at the
     * configured remote.
     *
     * Runs in place of the clone when the directory holds notes: the files
     * stay where they are, and what happens next depends on what the remote
     * turns out to hold — see [Adoption].
     */
    private fun startAdoption() {
        syncJob = viewModelScope.launch {
            _syncState.update { it.copy(running = true, message = null) }

            val adopted = sync.adopt(settings)
            val status = sync.status()
                .onFailure { failure -> Log.w(TAG, "the checkout could not be read", failure) }
                .getOrNull()
            val host = adopted.hostInQuestion()

            _syncState.update { current ->
                current.copy(
                    configured = settings.isConfigured,
                    running = false,
                    repository = status,
                    lastSyncedAt = settings.lastSyncedAt,
                    message = adopted.fold(
                        onSuccess = Adoption::toMessage,
                        onFailure = Throwable::toSyncMessage,
                    ),
                    // The one outcome that needs an answer rather than a
                    // reading: both sides hold notes, and joining them is not
                    // something this application does by itself.
                    unrelated = (adopted.getOrNull() as? Adoption.Unrelated)?.branch,
                    pendingHost = host?.first,
                    pendingHostReplaces = host?.second,
                )
            }

            if (adopted.isSuccess) {
                agenda.invalidate()
                refresh()
            }
        }
    }

    /**
     * Answer the unrelated-histories question with "take what the server has".
     *
     * What was in the directory is not deleted: the core leaves it as a commit
     * on a branch of its own, readable by any git client.
     */
    fun takeRemoteNotes() {
        if (syncJob?.isActive == true) {
            return
        }

        syncJob = viewModelScope.launch {
            _syncState.update { it.copy(running = true, message = null) }

            val taken = sync.takeRemote(settings)
            _syncState.update { current ->
                current.copy(
                    running = false,
                    repository = taken.getOrNull()?.head ?: current.repository,
                    unrelated = if (taken.isSuccess) null else current.unrelated,
                    message = taken.fold(
                        onSuccess = { SyncMessage(R.string.sync_took_remote) },
                        onFailure = Throwable::toSyncMessage,
                    ),
                )
            }

            if (taken.isSuccess) {
                agenda.invalidate()
                refresh()
            }
        }
    }

    /**
     * Vouch for the server key the last attempt stopped on, and try again.
     *
     * The key is stored only here, on a press: an application that recorded
     * whatever answered the first time would pin nothing, since the first time
     * is exactly when a wrong server would be believed. What follows is the
     * attempt that was interrupted — a directory that is already a checkout
     * fetches, one that is not is taken in as it stands.
     */
    fun trustHost() {
        val fingerprint = _syncState.value.pendingHost ?: return
        if (syncJob?.isActive == true) {
            return
        }

        settings.knownHost = fingerprint
        _syncState.update {
            it.copy(pendingHost = null, pendingHostReplaces = null, message = null)
        }

        syncJob = viewModelScope.launch {
            if (sync.holdsRepository()) {
                startSync()
            } else {
                startAdoption()
            }
        }
    }

    /**
     * The server key an attempt is waiting on, and the one it would replace.
     *
     * Both failures mean the same thing to the screen — a question about who
     * answered — and differ only in how grave it is, which is what the second
     * half of the pair says.
     */
    private fun Result<*>.hostInQuestion(): Pair<String, String?>? =
        when (val failure = exceptionOrNull()) {
            is SyncException.UnknownHost -> failure.fingerprint to null
            is SyncException.HostChanged -> failure.fingerprint to failure.known
            else -> null
        }

    /**
     * Store the address and what is sent to it, dropping what belonged to the
     * address it replaces.
     *
     * The server key goes with the address rather than surviving it: `origin`
     * pointing somewhere else is a different server, and a key remembered for
     * the old one would vouch for whatever answers at the new.
     */
    private fun storeRemote(
        address: String,
        branch: String,
        secret: String,
        dropToken: Boolean,
        configuredUrl: String?,
    ) {
        val moved = configuredUrl != address
        settings.remoteUrl = address
        settings.branch = branch
        settings.token = tokenFor(secret, dropToken, changedHost = moved)
        if (moved) {
            settings.knownHost = null
        }
    }

    /**
     * Store the key an `ssh://` remote is reached with.
     *
     * Blank means "leave the stored one alone", the way a blank token does:
     * the form does not show a key it holds, so an empty field is silence
     * rather than an instruction. [dropKey] takes both halves — a passphrase
     * outliving the key it opens is worth nothing.
     */
    private fun storeKey(typed: String, passphrase: String, dropKey: Boolean) {
        when {
            dropKey -> {
                settings.sshKey = null
                settings.sshPassphrase = null
            }

            typed.isNotBlank() -> {
                settings.sshKey = typed
                settings.sshPassphrase = passphrase.ifEmpty { null }
            }

            // A passphrase typed against a key already stored: the key stays,
            // and this is how a wrong one gets corrected.
            passphrase.isNotEmpty() -> settings.sshPassphrase = passphrase
        }
    }

    /**
     * Which token to store: the one just typed, none, or the one already
     * there.
     *
     * Kept out of [saveSettings] because it is the whole of the rule and both
     * branches of that function apply it.
     */
    private fun tokenFor(typed: String, dropped: Boolean, changedHost: Boolean): String? = when {
        dropped -> null
        typed.isNotBlank() -> typed
        changedHost -> null
        else -> settings.token
    }

    /**
     * Points the working copy at the chosen directory, when the choice changed.
     *
     * Answers with a failure the caller stops on, having already put the
     * reason on screen: the whole of what saving does afterwards is about a
     * directory the notes are in, and there is nothing sensible to do with a
     * remote when the move did not happen.
     *
     * The directory is stored only after the move went through, so a path
     * that cannot be used is not what the application opens on next time.
     */
    private suspend fun moveNotes(path: String): Result<Unit> {
        val collection = editing.collection
        val chosen = path.trim().ifEmpty { null }?.let(::File) ?: ownNotes
        if (chosen.absolutePath == collection.path) {
            return Result.success(Unit)
        }

        // Against the other collections as well as against the filesystem: a
        // directory that is one of them, or sits inside one, would have every
        // note under it read twice — once per collection — and an edit would
        // then act on one of the two copies on screen.
        val others = collections.entries.map { it.collection }.filter { it.id != collection.id }
        val clash = collectionProblem(collection.name, chosen.absolutePath, others)
        if (clash != null) {
            _syncState.update { it.copy(message = clash.toMessage()) }
            return Result.failure(IllegalStateException("the directory clashes with a collection"))
        }

        val used = editing.area.useDirectory(chosen)
        if (used.isFailure) {
            // The sentence is about a directory on this device, and the
            // wording is in the resources; what the filesystem said about it
            // goes to the log.
            Log.w(TAG, "the notes directory could not be used", used.exceptionOrNull())
            _syncState.update {
                it.copy(message = SyncMessage(R.string.settings_notes_failed, failed = true))
            }
            return used
        }

        // Stored and put to work in one step: the set the walk reads and the
        // set the next launch reads are the same set, and a directory that
        // could not be used never becomes either.
        val moved = stored.collections.map { entry ->
            if (entry.id == collection.id) entry.copy(path = chosen.absolutePath) else entry
        }
        stored.collections = moved
        collections.use(moved)
        _collectionSet.value = moved
        // The notes held from the previous directory describe files this one
        // does not have. Dropped before anything reads them, for the same
        // reason the header below is cleared.
        agenda.invalidate()
        // What the header showed belongs to the directory that was left
        // behind; nothing is known about a checkout in the new one yet.
        _syncState.update { it.copy(repository = null) }
        // Ahead of the sync rather than after it: the agenda of what is
        // already in the new directory is on screen while the fetch runs, and
        // when no remote is configured it is all there is going to be.
        refresh()

        return used
    }

    /**
     * Give the collection being edited the name [named], if it is another one.
     *
     * Nothing but the name changes, so the working copy and the lock over it
     * are the ones already in use — [CollectionsInUse.use] keeps an entry whose
     * directory has not moved.
     */
    private fun renameEditing(named: String) {
        val collection = editing.collection
        if (named == collection.name) {
            return
        }

        useCollections(
            stored.collections.map { entry ->
                if (entry.id == collection.id) entry.copy(name = named) else entry
            },
        )
    }

    /** Current settings, for filling the form. */
    fun currentSettings(): SyncForm = SyncForm(
        url = settings.remoteUrl.orEmpty(),
        branch = settings.branch.orEmpty(),
        hasToken = !settings.token.isNullOrBlank(),
        notesPath = editing.collection.path,
        name = editing.collection.name,
        hasKey = !settings.sshKey.isNullOrBlank(),
        publicKey = settings.sshPublicKey.orEmpty(),
        knownHost = settings.knownHost.orEmpty(),
    )

    /**
     * Make a key for this device, keeping the private half here.
     *
     * Stored as it is made rather than when the form is saved: the public half
     * has to be taken to a server before anything can be synced, and a key
     * shown but not kept would let someone add a key to their account that
     * this device no longer holds.
     *
     * A key made this way replaces whatever was stored, passphrase included:
     * the new one has none, and an old passphrase against a new key is a
     * failure with nothing on screen to explain it.
     */
    fun createSshKey() {
        val made = runCatching { generateSshKey(KEY_COMMENT) }
        made.onFailure { failure ->
            Log.w(TAG, "the key could not be made", failure)
            _syncState.update {
                it.copy(message = SyncMessage(R.string.settings_key_failed, failed = true))
            }
        }

        val key = made.getOrNull() ?: return
        settings.sshKey = key.privateKey
        settings.sshPassphrase = null
        settings.sshPublicKey = key.publicKey
        _syncState.update { it.copy(publicKey = key.publicKey) }
    }

    /** Sync the collection the settings screen is about. */
    private fun startSync() {
        if (!settings.isConfigured) {
            return
        }

        val collection = editing
        syncJob = viewModelScope.launch { runSync(collection) }
    }

    /**
     * Fetch, fast-forward and push one collection, reporting what happened.
     *
     * Written as one suspending step rather than as a job of its own, so that
     * syncing every collection is that step repeated: the working copies are
     * held one at a time and the screen is told after each.
     */
    private suspend fun runSync(collection: CollectionInUse) {
        // Named apart from the properties of the same shape: those read the
        // collection the settings screen is about, and this run is over the one
        // it was handed.
        val theirSettings = collection.settings
        val theirSyncer = collection.syncer

        _syncState.update { it.copy(running = true, message = null) }

        // An edit whose commit did not happen leaves the checkout dirty,
        // and the core refuses to fast-forward a dirty checkout. The
        // core's commit is idempotent, so this costs nothing when there
        // is nothing to commit.
        collection.editor.commitPending().onFailure { failure ->
            Log.w(TAG, "the uncommitted edits could not be committed", failure)
        }

        val outcome = theirSyncer.sync(theirSettings)
            // The path with the most ways to fail — the network, the
            // credentials, a host key, a history that diverged, a checkout
            // left dirty — and the only failure that used to leave nothing
            // behind it. What reaches the screen is a phrase assembled from
            // the resources, so afterwards neither the class of the failure
            // nor what the core said with it was anywhere. Safe to write down:
            // the address is stored with the credentials already split off it
            // (`splitCredentials`, where the settings are saved).
            .onFailure { failure -> Log.w(TAG, "the sync failed", failure) }
        outcome.getOrNull()?.pushFailure?.let { failure ->
            // Beside a fetch that went through, so it never reaches the branch
            // above: the run is reported as a success with a note, and the
            // refusal itself is the half the screen says least about.
            Log.w(TAG, "the push was refused", failure)
        }
        // A sync that went through hands back the state of the checkout it
        // wrote. Asking again walks every file in the working copy, untracked
        // ones included, for an answer already in hand; only a failed sync has
        // nothing to report and has to read.
        val status = outcome.getOrNull()?.head
            ?: theirSyncer.status()
                .onFailure { failure -> Log.w(TAG, "the checkout could not be read", failure) }
                .getOrNull()
        val message = outcome.fold(
            onSuccess = SyncRun::toMessage,
            onFailure = Throwable::toSyncMessage,
        )

        val host = outcome.hostInQuestion()
        _syncState.update { current ->
            current.copy(
                configured = theirSettings.isConfigured,
                running = false,
                // The header is about the collection the settings screen is
                // about, so a run over the others leaves what it says alone.
                repository = status.takeIf { collection.collection.id == _editingId.value }
                    ?: current.repository,
                lastSyncedAt = theirSettings.lastSyncedAt,
                message = message,
                // The collection's own answer, kept apart from the last one of
                // the run: over several repositories the header alone cannot
                // say which of them failed.
                runs = current.runs.filterNot { it.id == collection.collection.id } +
                    CollectionRun(
                        id = collection.collection.id,
                        name = collection.collection.name,
                        message = message,
                    ),
                pendingHost = host?.first,
                pendingHostReplaces = host?.second,
            )
        }

        if (outcome.isSuccess) {
            // A fetch rewrites whatever it fast-forwarded over, and which
            // files those were is not something this side is told. The held
            // notes are stale as a whole, so the agenda that follows walks
            // the directories again.
            agenda.invalidate()
            refresh()
        }
    }

    private fun readCheckout() {
        viewModelScope.launch {
            // A checkout that cannot be read leaves the header saying nothing
            // about it, which is all the screen can do here — but the reason
            // has to end up somewhere, and this is the only place it exists.
            val status = sync.status()
                .onFailure { failure -> Log.w(TAG, "the checkout could not be read", failure) }
                .getOrNull()
            _syncState.update {
                it.copy(
                    configured = settings.isConfigured,
                    local = settings.storesLocally,
                    repository = status,
                    lastSyncedAt = settings.lastSyncedAt,
                )
            }
        }
    }

    /**
     * A label per collection, keyed by the root its tasks carry.
     *
     * Empty while there is one collection: a label on every row would be a
     * column of the same word, and the filter over it would offer a choice of
     * one. The tone is the position in the set, so a collection keeps its
     * colour for as long as the set is not reordered.
     *
     * Keyed by [CollectionInUse.root], which is the directory as the walk
     * reports it, so a path that goes through a symbolic link still matches
     * the rows that came out of it.
     */
    private fun collectionLabels(): Map<String, CollectionLabel> {
        val entries = collections.entries
        if (entries.size < 2) {
            return emptyMap()
        }

        return entries.withIndex().associate { (tone, entry) ->
            entry.root to CollectionLabel(
                id = entry.collection.id,
                name = entry.collection.name,
                tone = tone,
                root = entry.root,
            )
        }
    }

    /**
     * Rebuild the row of filter chips for the collections now in use.
     *
     * The hidden set is narrowed to what is still there: a collection that has
     * been removed must not go on hiding rows through an identifier its
     * successor could be given.
     */
    private fun offerFilter(labels: List<CollectionLabel>) {
        hidden = hidden.intersect(labels.map(CollectionLabel::id).toSet())
        _collectionFilter.value = labels.map { label ->
            CollectionChoice(label = label, shown = label.id !in hidden)
        }
    }

    /**
     * Reread the tags the collections declare and merge them into one
     * dictionary.
     *
     * Every collection is asked, not only the ones whose rows are on screen: a
     * tag is a word about the notes as a whole, and hiding a collection for a
     * moment should not take its vocabulary with it.
     *
     * The chosen tag survives only while the dictionary still holds it. A file
     * edited elsewhere and arriving with a sync can retire a tag, and going on
     * filtering by a name nothing declares would leave the agenda narrowed with
     * nothing on screen to say by what.
     *
     * Suspend because the declarations are files: one stat and one parse per
     * collection, and this runs on every rebuild of the agenda.
     */
    private suspend fun offerTags() {
        _tags.value = mergeTagDictionaries(
            collections.entries.mapNotNull { entry ->
                // Under the collection's own lock, which is where the step off
                // the main thread lives: this used to be the one read of
                // storage on the frame loop. The lock is also what keeps the
                // file from being read halfway through the fetch rewriting it.
                entry.area.exclusive { readDeclaredTags(entry.collection.name, File(entry.root)) }
            },
        )
        if (_currentTag.value !in _tags.value.map(MergedTag::name)) {
            _currentTag.value = null
        }
    }

    /** How long ago [started] was, in whole milliseconds. */
    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    /**
     * Rebuilds the agenda when [moment] has crossed into a day the one on
     * screen does not cover. Does nothing while the date still matches, which
     * is every minute but one.
     */
    private fun refreshOnNewDay(moment: LocalDateTime) {
        if (_state.value !is AgendaUiState.Ready) {
            return
        }
        if (groupedAgainst != moment.toLocalDate()) {
            // The notes themselves have not changed, so what is held for them
            // stands; it is the date they were grouped against that has moved,
            // and a scan against the new one is the whole of the fix.
            refresh()
        }
    }

    companion object {
        /** Where the failures the screen does not spell out are written. */
        private const val TAG = "Agenda"

        /**
         * How long the ticker keeps running after the screen stops watching.
         *
         * Long enough to carry a rotation, which tears the collector down and
         * puts it back; short enough that a screen left in the background does
         * not wake the process once a minute.
         */
        private const val TICKER_LINGER_MS = 5_000L

        /**
         * What a key made here is labelled with on the server it is added to.
         *
         * Not the device's own name: that is the user's to give, it says who
         * they are to whoever reads the list of keys, and asking a phone for
         * it needs a permission this application has no other use for.
         */
        private const val KEY_COMMENT = "markdown-org"

        /** Time from [moment] to the top of the next minute, in milliseconds. */
        private fun millisUntilNextMinute(moment: LocalDateTime): Long = Duration.between(
            moment,
            moment.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1),
        ).toMillis()

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                val stored = NotesCollectionsStore(application)
                // A device upgrading from the version that knew one directory
                // has that directory and no collections: it becomes the first
                // one, over the same path and with the same remote, and is
                // written back so the next launch reads it as an ordinary set.
                val collections = migratedCollections(
                    stored = stored.collections,
                    legacyPath = NotesLocation(application).path,
                    own = ownNotesRoot(application),
                    defaultName = application.getString(R.string.collection_default_name),
                )
                stored.collections = collections

                val inUse = DeviceCollections(application, collections)
                AgendaViewModel(
                    collections = inUse,
                    stored = stored,
                    agenda = AgendaSource(inUse),
                    ui = UiSettings(application),
                    ownNotes = ownNotesRoot(application),
                    sample = sampleWording(application),
                    // Asked at every check rather than read once: it is
                    // granted in a settings screen of the platform, and the
                    // application is still running when the user comes back
                    // from it.
                    storageGranted = { StorageAccess.granted(application) },
                )
            }
        }
    }
}
