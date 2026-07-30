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
import io.github.vitalyostanin.markdownorg.core.NotesStore
import io.github.vitalyostanin.markdownorg.core.NotesSync
import io.github.vitalyostanin.markdownorg.core.NotesSyncer
import io.github.vitalyostanin.markdownorg.core.NotesWriter
import io.github.vitalyostanin.markdownorg.core.SyncPreferences
import io.github.vitalyostanin.markdownorg.core.SyncSettings
import io.github.vitalyostanin.markdownorg.core.UiPreferences
import io.github.vitalyostanin.markdownorg.core.UiSettings
import io.github.vitalyostanin.markdownorg.core.remoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.splitCredentials
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.Task
import java.time.LocalDate
import java.time.LocalTime

class AgendaViewModel(
    private val notes: NotesArea,
    private val agenda: AgendaLoader,
    private val sync: NotesSyncer,
    private val settings: SyncPreferences,
    private val ui: UiPreferences,
    private val editor: NotesWriter,
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
            val outcome = when (action) {
                TaskAction.Complete -> editor.complete(task, LocalDate.now())
                is TaskAction.Status -> editor.setStatus(task, action.status)
                is TaskAction.Priority -> editor.setPriority(task, action.value)
                is TaskAction.Shift -> editor.shift(task, action.keyword, action.days)
            }

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
            val today = LocalDate.now()
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
                        AgendaUiState.Ready(
                            date = today,
                            sections = sections,
                            // The agenda is always for today so far; once a
                            // date can be picked, another day passes null and
                            // loses the marker line.
                            timeline = sections.toTimeline(now = LocalTime.now()),
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
     * Stores the remote and empties the directory when the clone cannot land
     * in what is already there.
     *
     * The core clones into an empty directory, so both cases have to start
     * from one: the first setup, where the directory holds the sample notes,
     * and a change of remote, where it holds someone else's checkout. Only an
     * existing checkout of the same URL is kept — that one is fetched into,
     * including when the branch changed: the core moves the checkout onto the
     * new branch, and wiping would take commits made here with it.
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
     */
    fun saveSettings(url: String, branch: String, token: String, dropToken: Boolean = false) {
        val split = splitCredentials(url)
        val address = split.url
        val secret = token.ifBlank { split.token.orEmpty() }

        val problem = remoteUrlProblem(address)
        if (problem != null) {
            // Nothing is stored and nothing is deleted: the address is checked
            // before the destructive part, not after the clone fails.
            _syncState.update { it.copy(message = problem.toMessage()) }
            return
        }

        viewModelScope.launch {
            // A sync in flight owns the directory that is about to be emptied,
            // so it is stopped rather than raced with.
            syncJob?.cancelAndJoin()
            _syncState.update { it.copy(running = false) }

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
                settings.remoteUrl = address
                settings.branch = branch
                settings.token = tokenFor(secret, dropToken, changedHost = configuredUrl != address)
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
            // repository, and treating it as another one would empty the
            // directory and take local commits with it.
            val before = previous.getOrNull()?.url?.let { splitCredentials(it).url }
            settings.remoteUrl = address
            settings.branch = branch
            settings.token = tokenFor(secret, dropToken, changedHost = configuredUrl != address)

            if (before != settings.remoteUrl) {
                val wiped = notes.reset()
                if (wiped.isFailure) {
                    // A directory emptied only in part cannot be cloned into,
                    // and the clone would report it as a repository failure —
                    // a sentence about git, not about the directory it is
                    // actually about.
                    Log.w(TAG, "the notes directory could not be emptied", wiped.exceptionOrNull())
                    _syncState.update {
                        it.copy(
                            configured = settings.isConfigured,
                            message = SyncMessage(R.string.notes_reset_failed, failed = true),
                        )
                    }
                    return@launch
                }
                _syncState.update { it.copy(repository = null) }
            }

            _syncState.update { it.copy(configured = settings.isConfigured) }
            // Unconditionally, not through syncNow(): the sync this replaced
            // has just been cancelled, and skipping the new one would leave an
            // emptied directory and a remote nobody fetched from.
            startSync()
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

    /** Current settings, for filling the form. */
    fun currentSettings(): SyncForm = SyncForm(
        url = settings.remoteUrl.orEmpty(),
        branch = settings.branch.orEmpty(),
        hasToken = !settings.token.isNullOrBlank(),
    )

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
                onSuccess = { result ->
                    when {
                        result.cloned -> SyncMessage(R.string.sync_cloned)
                        result.commitsApplied > 0u -> SyncMessage(R.string.sync_updated)
                        else -> SyncMessage(R.string.sync_already_current)
                    }
                },
                onFailure = Throwable::toSyncMessage,
            )

            _syncState.update { current ->
                current.copy(
                    configured = settings.isConfigured,
                    running = false,
                    repository = status,
                    lastSyncedAt = settings.lastSyncedAt,
                    message = message,
                )
            }

            if (outcome.isSuccess) {
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
                    repository = status,
                    lastSyncedAt = settings.lastSyncedAt,
                )
            }
        }
    }

    companion object {
        /** Where the failures the screen does not spell out are written. */
        private const val TAG = "Agenda"

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
                )
            }
        }
    }
}
