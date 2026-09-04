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
import io.github.vitalyostanin.markdownorg.core.CollectionsInUse
import io.github.vitalyostanin.markdownorg.core.CorePhraseRules
import io.github.vitalyostanin.markdownorg.core.DeviceCollections
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.NotesCollectionsPreferences
import io.github.vitalyostanin.markdownorg.core.NotesCollectionsStore
import io.github.vitalyostanin.markdownorg.core.NotesLocation
import io.github.vitalyostanin.markdownorg.core.PhraseRules
import io.github.vitalyostanin.markdownorg.core.ReminderAlarms
import io.github.vitalyostanin.markdownorg.core.ReminderScheduler
import io.github.vitalyostanin.markdownorg.core.ReminderSettings
import io.github.vitalyostanin.markdownorg.core.Replanning
import io.github.vitalyostanin.markdownorg.core.SampleWording
import io.github.vitalyostanin.markdownorg.core.StorageAccess
import io.github.vitalyostanin.markdownorg.core.UiPreferences
import io.github.vitalyostanin.markdownorg.core.UiSettings
import io.github.vitalyostanin.markdownorg.core.collectionProblem
import io.github.vitalyostanin.markdownorg.core.mergeTagDictionaries
import io.github.vitalyostanin.markdownorg.core.migratedCollections
import io.github.vitalyostanin.markdownorg.core.nextCollectionId
import io.github.vitalyostanin.markdownorg.core.ownNotesRoot
import io.github.vitalyostanin.markdownorg.core.readDeclaredTags
import io.github.vitalyostanin.markdownorg.core.single
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.Task
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * What the screens read from and what they ask for.
 *
 * One model per process, holding the agenda itself: the scan of the notes, the
 * state it is drawn from, and the clock that moves it over midnight. The rest
 * is held by four classes it composes, each answering a question of its own:
 *
 *  * [PlanView] -- how much of the plan is on screen and how it is drawn;
 *  * [RowFilters] -- which of the scanned rows are shown;
 *  * [EntryEdits] -- what a tap writes into the notes and what it answers;
 *  * [NotesSettings] -- where the notes live and what they are synced with.
 *
 * They are separate classes rather than sections of one because each of them
 * is a different kind of work: two redraw what is in hand, one writes files,
 * one talks to a remote. Each stands as a field of its own rather than behind
 * a row of forwarding methods: a screen that steps the plan says so where it
 * is read, and this class is not the place a member is added to whenever one
 * of the four gains a method.
 *
 * What this class keeps for itself is what the four have in common: the scan,
 * the collections in use, and the rule that a write is followed by a read.
 */
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
    /**
     * Told when the notes may have moved under whatever else reads them.
     *
     * The reminders are what this is for, and they are named nowhere here: a
     * plan of what to announce is not the agenda's business, and the agenda
     * would otherwise have to be built with the alarms of the platform behind
     * it to be exercised at all. What is passed in shares this walk's index,
     * so the reading it does costs the days it asks for rather than the
     * collection.
     *
     * Returns at once and takes a coroutine of its own where it is built: the
     * screen that reported an edit is not what the walk that follows should
     * belong to. What it is handed is what to do when the walk fails — the
     * log is written wherever the plan is made, and this is for the screen
     * that asked and is still there to be told.
     */
    private val notesChanged: (onFailure: (Throwable) -> Unit) -> Unit = {},
    /** The wall clock, taken as a parameter so a test can move it by hand. */
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    /**
     * The rules a spoken phrase is read by.
     *
     * A parameter for the reason the clock is one, and a stronger one: the
     * rules live in the core, the core is a native library loaded on a device,
     * and a test on the JVM that called it would not get as far as the
     * answer — so every way a phrase can be refused would go unexercised.
     */
    private val phrases: PhraseRules = CorePhraseRules,
    /**
     * Where the walks over the notes directory run.
     *
     * A parameter rather than `Dispatchers.IO` written into the call, so a test
     * can put the walk on the scheduler that drives it. Left on the real pool,
     * the walk outlives the test that started it and resumes onto a main
     * dispatcher that has already been reset — an exception with no test left
     * to attribute it to, which then fails whichever test runs next.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
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

    /**
     * Whether whatever reads the notes behind the screen has been told yet.
     *
     * Once per launch and then on each occasion that moves a note: the first
     * agenda of a run is also the first chance to say that this device is
     * awake and its notes are as they are.
     */
    private var announced = false

    /** An entry a notification named, waiting for the agenda that holds it. */
    private var awaited: EntryAddress? = null

    /**
     * Which of the rows a scan produced are on screen: the collection chips
     * and the tag.
     */
    val filters = RowFilters()

    /**
     * How much of the plan is on screen and how it is drawn: the layout, the
     * span, the sections, the calendar, the first weekday and the date the
     * plan is asked around.
     */
    val view = PlanView(ui, clock, ::refresh)

    /**
     * The settings screen: where a collection's notes live and what they are
     * synced with. Every operation of that screen is there; the agenda reads
     * its state and asks it for a sync.
     */
    val settings = NotesSettings(
        collections = collections,
        stored = stored,
        agenda = agenda,
        ownNotes = ownNotes,
        storageGranted = storageGranted,
        scope = viewModelScope,
        io = io,
        editingId = { _editingId.value },
        onCollectionSet = { set -> _collectionSet.value = set },
        onCollections = ::useCollections,
        rescan = ::refresh,
        onNotesMoved = ::notesMayHaveMoved,
    )

    /**
     * What a tap on a row writes into the notes, and what it answers with:
     * one entry or a whole band of them, and the way back from either.
     */
    val edits = EntryEdits(
        collections = collections,
        agenda = agenda,
        editing = { editing },
        phrases = phrases,
        clock = clock,
        scope = viewModelScope,
        io = io,
        rescan = ::refresh,
        onNotesMoved = ::notesMayHaveMoved,
    )

    /**
     * The work in flight, so a new request can supersede it.
     *
     * Both are read and written from the main thread, where every entry point
     * of this class is called. Holding the jobs rather than a flag also keeps
     * the decision to start atomic: a boolean checked before `launch` and set
     * inside it leaves a window between the two.
     */
    private var refreshJob: Job? = null

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
        settings.readCheckout()
    }

    /**
     * Open the day an entry falls on, with the entry itself picked out.
     *
     * What a notification asks for. The entry is named by where it is written
     * rather than by what it says: two headings can read alike, and the copy
     * the agenda puts in a day carries the line of the original. The address
     * is held until a day holding it has been read, because the scan is
     * asynchronous -- there is no sheet to open over an agenda still being
     * built, and a day the entry has since left simply leaves it unopened.
     */
    fun showEntry(date: LocalDate, root: String?, file: String, line: UInt) {
        awaited = EntryAddress(root = root, file = file, line = line)
        view.showDay(date)
    }

    /**
     * Show or hide the rows of one collection.
     *
     * Answers on the spot, without a scan: the notes have not changed, only
     * how much of them is on screen.
     */
    fun setCollectionShown(id: String, shown: Boolean) {
        filters.setCollectionShown(id, shown)
        reshow()
    }

    /**
     * Narrow the agenda to one tag, or to none when [tag] is null.
     *
     * Costs no scan, like the collection chips: the tag reads the name of the
     * file a row came from, which the rows already carry.
     */
    fun setTag(tag: String?) {
        filters.setTag(tag)
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
        val shown = filters.apply(full)
        _state.update { current ->
            when (current) {
                is AgendaUiState.Ready -> current.copy(days = shown)
                else -> current
            }
        }
    }

    /**
     * Pick out the entry a notification named, once the agenda holds it.
     *
     * Cleared whether or not the entry was there: a notification names the day
     * the plan was made for, and an entry the reader has since moved or
     * finished is one the sheet has nothing to say about. Holding the address
     * for later would open a sheet over some other day, whenever that day
     * happened to be read.
     */
    private fun pickAwaited() {
        val address = awaited ?: return
        val ready = _state.value as? AgendaUiState.Ready ?: return

        awaited = null
        edits.select(
            ready.days
                .asSequence()
                .flatMap { day -> day.sections.run { overdue + timed + untimed }.asSequence() }
                .map(AgendaRow::task)
                .firstOrNull(address::names),
        )
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
            settings.say(problem.toMessage())
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
            notesMayHaveMoved()
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
            settings.say(SyncMessage(R.string.settings_notes_failed, failed = true))
        }
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
            val shown = view.anchor.value ?: today
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
            val span = view.span.value
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
            val scope = if (span == AgendaSpan.MONTH && view.monthAsGrid.value) {
                Scope.MONTH_GRID
            } else {
                span.scope
            }
            val built = seeded.mapCatching {
                agenda.load(
                    scope,
                    today = today,
                    shown = shown,
                    weekStart = view.firstDayOfWeek(),
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
                            days = filters.apply(days),
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

            if (!announced && _state.value is AgendaUiState.Ready) {
                announced = true
                notesMayHaveMoved()
            }
            pickAwaited()
        }
    }

    /**
     * Say that the notes are as they now are, without waiting for the answer.
     *
     * What listens walks the notes, and it does so away from here: a screen
     * that waited for that would hold the edit it has just finished
     * reporting, and a walk belonging to this model would stop when the model
     * does — with the edit written and nothing announcing it.
     */
    private fun notesMayHaveMoved() = notesChanged {}

    /**
     * Plan the reminders again, over the index this model already holds.
     *
     * For the settings screen: a choice about the reminders is not a change
     * to the notes, but it is a change to what is announced out of them. It
     * comes through here so that the walk uses the index of the agenda rather
     * than opening every collection a second time.
     *
     * Unlike an edit, this one is answered on screen when it fails: the
     * reader has just moved a switch, and a switch that planned nothing is
     * otherwise indistinguishable from one that worked — until the reminders
     * fail to arrive, days later.
     */
    fun replanReminders() = notesChanged { failure ->
        settings.say(
            SyncMessage(
                R.string.reminders_plan_failed,
                detail = failure.message?.let(Detail::Verbatim),
                failed = true,
            ),
        )
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
    private fun offerFilter(labels: List<CollectionLabel>) = filters.offerCollections(labels)

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
        val merged = mergeTagDictionaries(
            collections.entries.mapNotNull { entry ->
                // Under the collection's own lock, which is where the step off
                // the main thread lives: this used to be the one read of
                // storage on the frame loop. The lock is also what keeps the
                // file from being read halfway through the fetch rewriting it.
                entry.area.exclusive { readDeclaredTags(entry.collection.name, File(entry.root)) }
            },
        )
        filters.offerTags(merged)
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
                val agenda = AgendaSource(inUse)
                // Over the same index the screen reads: a plan of its own
                // would walk every collection a second time on every edit.
                val reminders = ReminderScheduler(
                    agenda = agenda,
                    preferences = ReminderSettings(application),
                    alarms = ReminderAlarms(application),
                )
                AgendaViewModel(
                    collections = inUse,
                    stored = stored,
                    agenda = agenda,
                    ui = UiSettings(application),
                    ownNotes = ownNotesRoot(application),
                    sample = sampleWording(application),
                    // Asked at every check rather than read once: it is
                    // granted in a settings screen of the platform, and the
                    // application is still running when the user comes back
                    // from it.
                    storageGranted = { StorageAccess.granted(application) },
                    // One walk at a time and the newest request winning, so
                    // that an edit and a choice made a moment apart do not
                    // race to replace the same alarms.
                    notesChanged = { onFailure -> Replanning.request(reminders, onFailure) },
                )
            }
        }
    }
}

/**
 * Where an entry is written, which is what names it between screens.
 *
 * A heading is what the reader sees and not what tells two entries apart: the
 * same words can stand in two notes, and a repeating entry puts the same words
 * in every day it falls on. The file and the line are what the core hands out
 * and what an edit is aimed at, and the collection is what keeps the same
 * relative path in two of them apart.
 */
private data class EntryAddress(val root: String?, val file: String, val line: UInt) {

    fun names(task: Task): Boolean = task.line == line && task.file == file && task.root == root
}
