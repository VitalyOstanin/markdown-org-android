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
import io.github.vitalyostanin.markdownorg.core.NotesArea
import io.github.vitalyostanin.markdownorg.core.NotesEditor
import io.github.vitalyostanin.markdownorg.core.NotesLocation
import io.github.vitalyostanin.markdownorg.core.NotesLocationPreferences
import io.github.vitalyostanin.markdownorg.core.NotesStore
import io.github.vitalyostanin.markdownorg.core.NotesSync
import io.github.vitalyostanin.markdownorg.core.NotesSyncer
import io.github.vitalyostanin.markdownorg.core.NotesWriter
import io.github.vitalyostanin.markdownorg.core.RemoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.StorageAccess
import io.github.vitalyostanin.markdownorg.core.SyncPreferences
import io.github.vitalyostanin.markdownorg.core.SyncRun
import io.github.vitalyostanin.markdownorg.core.SyncSettings
import io.github.vitalyostanin.markdownorg.core.UiPreferences
import io.github.vitalyostanin.markdownorg.core.UiSettings
import io.github.vitalyostanin.markdownorg.core.notesPathProblem
import io.github.vitalyostanin.markdownorg.core.ownNotesRoot
import io.github.vitalyostanin.markdownorg.core.remoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.splitCredentials
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class AgendaViewModel(
    private val notes: NotesArea,
    private val agenda: AgendaLoader,
    private val sync: NotesSyncer,
    private val settings: SyncPreferences,
    private val ui: UiPreferences,
    private val editor: NotesWriter,
    private val location: NotesLocationPreferences,
    /** The directory an empty choice falls back to. */
    private val ownNotes: File,
    /** Whether a directory outside that one may be read, asked of the platform. */
    private val storageGranted: () -> Boolean,
    /** The wall clock, taken as a parameter so a test can move it by hand. */
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    private val _state = MutableStateFlow<AgendaUiState>(AgendaUiState.Loading)
    val state: StateFlow<AgendaUiState> = _state.asStateFlow()

    /**
     * Kept apart from [state] so switching the layout redraws without going
     * back through Loading — the data is the same, only its shape changes.
     * Read from the stored preference, so the agenda opens the way it was
     * left rather than always on the hour axis.
     */
    private val _layout = MutableStateFlow(ui.layout)
    val layout: StateFlow<AgendaLayout> = _layout.asStateFlow()

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
        refresh()
        readCheckout()
    }

    fun setLayout(layout: AgendaLayout) {
        _layout.value = layout
        ui.layout = layout
    }

    fun select(task: Task?) {
        _selected.value = task
    }

    /** The edit failure has been shown, so it is not shown again. */
    fun editIssueShown() {
        _editIssue.value = null
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

        viewModelScope.launch {
            // The other half of what a tap costs, alongside the scan timed in
            // refresh(): the write is one file, but the commit that follows it
            // reads the whole working copy, so this grows with the notes too.
            val started = System.nanoTime()
            val outcome = when (action) {
                TaskAction.Complete -> editor.complete(task, clock().toLocalDate())
                is TaskAction.Status -> editor.setStatus(task, action.status)
                is TaskAction.Priority -> editor.setPriority(task, action.value)
                is TaskAction.Shift -> editor.shift(task, action.keyword, action.days)
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
                    agenda.reread(task.file).onFailure { failure ->
                        Log.w(TAG, "the edited note could not be re-read", failure)
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

        viewModelScope.launch {
            val started = System.nanoTime()
            val outcome = editor.applyToGroup(tasks, action, clock().toLocalDate())
            Log.i(TAG, "the group of ${tasks.size} took ${millisSince(started)} ms")

            outcome.fold(
                onSuccess = { group ->
                    group.report.commitFailure?.let { failure ->
                        Log.w(TAG, "the group was written but not committed", failure)
                    }
                    _groupResult.value = GroupResult(
                        action = action,
                        changed = group.outcome.changed.toInt(),
                        refused = group.outcome.refused.size,
                        rollback = group.outcome.rollback,
                    )
                    // Which files changed is known, but a band spans several
                    // and the held notes for each would have to be dropped one
                    // by one; the walk that follows is the same one an edit
                    // ends in.
                    agenda.invalidate()
                    refresh()
                },
                onFailure = { error ->
                    Log.w(TAG, "the group could not be applied", error)
                    _editIssue.value = error.toEditMessage()
                },
            )
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
            editor.undoGroup(rollback).fold(
                onSuccess = { undone ->
                    undone.report.commitFailure?.let { failure ->
                        Log.w(TAG, "the undo was written but not committed", failure)
                    }
                    if (undone.outcome.skipped.isNotEmpty() || undone.outcome.failed.isNotEmpty()) {
                        // Some of it went back and some did not, which is a
                        // state the user has to be told about rather than left
                        // to notice: the agenda below will show both.
                        _editIssue.value = SyncMessage(R.string.agenda_group_undo_partial)
                    }
                    agenda.invalidate()
                    refresh()
                },
                onFailure = { error ->
                    Log.w(TAG, "the group could not be undone", error)
                    _editIssue.value = error.toEditMessage()
                },
            )
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
            // What the walk cost, for the one question the screen cannot
            // answer: whether a directory of this size is still usable. The
            // scan is the only part of a refresh that grows with the notes,
            // and there is no console on a phone to time it from.
            val started = System.nanoTime()
            // Seeding is a write to the same directory the scan reads, and it
            // fails the same ways: no space, a directory that cannot be
            // written to. Its failure goes on the screen rather than out of
            // the coroutine, which used to take the process with it.
            val seeded = notes.ensureSeeded(today) { settings.isConfigured }

            _state.value = seeded
                // One day, and nothing else asks for another: the wider scopes
                // want a header per day before their entries can be told
                // apart, and a parameter nobody passes would say the screen
                // can already show them.
                .mapCatching { agenda.load(Scope.DAY, today).getOrThrow() }
                .fold(
                    onSuccess = { result ->
                        val sections = result.toSections()
                        val rows = sections.overdue.size + sections.timed.size +
                            sections.untimed.size
                        Log.i(
                            TAG,
                            "the agenda was built in ${millisSince(started)} ms, $rows rows",
                        )
                        AgendaUiState.Ready(
                            date = today,
                            sections = sections,
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

    /** Fetch and fast-forward, then rebuild the agenda over what arrived. */
    fun syncNow() {
        if (syncJob?.isActive == true) {
            return
        }
        startSync()
    }

    /**
     * Stores the remote and gets the directory into a state it can be synced
     * from.
     *
     * Nothing here empties anything. A directory that already holds notes and
     * no git is taken in as it stands — `adopt` makes what is in it the first
     * commit and only then adds the remote — and a directory holding neither
     * is cloned into. Replacing what is on disk is a separate, stated action
     * ([replaceNotes]) rather than a side effect of saving a form.
     *
     * A checkout of another remote is the one case saving cannot resolve: it
     * says so and leaves both alone, because the commits in it may exist
     * nowhere else.
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
        notesPath: String = location.path.orEmpty(),
        sshKey: String = "",
        sshPassphrase: String = "",
        dropKey: Boolean = false,
    ) {
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

        viewModelScope.launch {
            // A sync in flight owns the directory that is about to be emptied,
            // so it is stopped rather than raced with.
            //
            // Cancelling asks; it does not interrupt. The sync is inside a
            // call into the core, and that call returns when it returns — a
            // fetch on a stalled connection, at the outside, when the core's
            // own network timeouts expire. This waits for that, and it is why
            // the core has those timeouts rather than the operating system's.
            syncJob?.cancelAndJoin()
            _syncState.update { it.copy(running = false) }

            // Before the remote is looked at: everything below reads the
            // checkout, and after a move that has to be the checkout in the
            // new directory. A move that fails leaves the rest untouched —
            // storing a remote against a directory the notes are not in would
            // clone into the old one.
            val moved = moveNotes(notesPath)
            if (moved.isFailure) {
                return@launch
            }

            // The rest is about a remote, and there is none in the form. What
            // was stored before stays: clearing it here would be a way to lose
            // a repository by saving a directory.
            if (address.isEmpty()) {
                return@launch
            }

            // Which host the stored token was issued for: the settings, not
            // the checkout. A directory holding no repository yet says nothing
            // about where the token came from.
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
                return@launch
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
                // Somebody else's checkout, or this one pointed elsewhere.
                // Emptying it here is what used to happen, and it took every
                // commit that had not been pushed with it.
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
    }

    /**
     * Empty the notes directory and clone the configured remote into it.
     *
     * The one thing that deletes notes, and it exists so that saving a form
     * never does: the user asks for it, having been told the directory holds a
     * checkout of somewhere else.
     */
    fun replaceNotes() {
        if (!settings.isConfigured) {
            return
        }

        val running = syncJob
        syncJob = viewModelScope.launch {
            // A sync in flight owns the directory that is about to be emptied,
            // so it is stopped rather than raced with — the same wait, and for
            // the same reason, as a change of settings makes.
            running?.cancelAndJoin()
            _syncState.update { it.copy(running = false) }

            val wiped = notes.reset()
            if (wiped.isFailure) {
                // A directory emptied only in part cannot be cloned into, and
                // the clone would report it as a repository failure — a
                // sentence about git, not about the directory it is about.
                Log.w(TAG, "the notes directory could not be emptied", wiped.exceptionOrNull())
                _syncState.update {
                    it.copy(message = SyncMessage(R.string.notes_reset_failed, failed = true))
                }
                return@launch
            }

            // The directory has just been emptied, so what is held about it
            // describes files that no longer exist.
            agenda.invalidate()
            _syncState.update { it.copy(repository = null) }
            startSync()
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
        val chosen = path.trim().ifEmpty { null }
        if (chosen == location.path) {
            return Result.success(Unit)
        }

        val used = notes.useDirectory(chosen?.let(::File) ?: ownNotes)
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

        location.path = chosen
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

    /** Current settings, for filling the form. */
    fun currentSettings(): SyncForm = SyncForm(
        url = settings.remoteUrl.orEmpty(),
        branch = settings.branch.orEmpty(),
        hasToken = !settings.token.isNullOrBlank(),
        notesPath = location.path.orEmpty(),
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

    private fun startSync() {
        if (!settings.isConfigured) {
            return
        }

        syncJob = viewModelScope.launch {
            _syncState.update { it.copy(running = true, message = null) }

            // An edit whose commit did not happen leaves the checkout dirty,
            // and the core refuses to fast-forward a dirty checkout. The
            // core's commit is idempotent, so this costs nothing when there
            // is nothing to commit.
            editor.commitPending().onFailure { failure ->
                Log.w(TAG, "the uncommitted edits could not be committed", failure)
            }

            val outcome = sync.sync(settings)
            // A sync that went through hands back the state of the checkout it
            // wrote. Asking again walks every file in the working copy,
            // untracked ones included, for an answer already in hand; only a
            // failed sync has nothing to report and has to read.
            val status = outcome.getOrNull()?.head
                ?: sync.status()
                    .onFailure { failure -> Log.w(TAG, "the checkout could not be read", failure) }
                    .getOrNull()
            val message = outcome.fold(
                onSuccess = SyncRun::toMessage,
                onFailure = Throwable::toSyncMessage,
            )

            val host = outcome.hostInQuestion()
            _syncState.update { current ->
                current.copy(
                    configured = settings.isConfigured,
                    running = false,
                    repository = status,
                    lastSyncedAt = settings.lastSyncedAt,
                    message = message,
                    pendingHost = host?.first,
                    pendingHostReplaces = host?.second,
                )
            }

            if (outcome.isSuccess) {
                // A fetch rewrites whatever it fast-forwarded over, and which
                // files those were is not something this side is told. The held
                // notes are stale as a whole, so the agenda that follows walks
                // the directory again.
                agenda.invalidate()
                refresh()
            }
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

    /** How long ago [started] was, in whole milliseconds. */
    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    /**
     * Rebuilds the agenda when [moment] has crossed into a day the one on
     * screen does not cover. Does nothing while the date still matches, which
     * is every minute but one.
     */
    private fun refreshOnNewDay(moment: LocalDateTime) {
        val shown = (_state.value as? AgendaUiState.Ready)?.date ?: return
        if (shown != moment.toLocalDate()) {
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
                val notes = NotesStore(application)
                val settings = SyncSettings(application)
                AgendaViewModel(
                    notes = notes,
                    agenda = AgendaSource(notes),
                    sync = NotesSync(application, notes),
                    settings = settings,
                    ui = UiSettings(application),
                    editor = NotesEditor(notes, settings),
                    location = NotesLocation(application),
                    ownNotes = ownNotesRoot(application),
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
