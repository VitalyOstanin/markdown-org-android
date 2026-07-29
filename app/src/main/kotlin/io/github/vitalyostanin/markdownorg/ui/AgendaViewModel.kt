package io.github.vitalyostanin.markdownorg.ui

import android.app.Application
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
import io.github.vitalyostanin.markdownorg.core.remoteUrlProblem
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.Task

class AgendaViewModel(
    private val notes: NotesArea,
    private val agenda: AgendaLoader,
    private val sync: NotesSyncer,
    private val settings: SyncPreferences,
    private val editor: NotesWriter,
) : ViewModel() {

    private val _state = MutableStateFlow<AgendaUiState>(AgendaUiState.Loading)
    val state: StateFlow<AgendaUiState> = _state.asStateFlow()

    /**
     * Kept apart from [state] so switching the layout redraws without going
     * back through Loading — the data is the same, only its shape changes.
     */
    private val _layout = MutableStateFlow(AgendaLayout.TIME)
    val layout: StateFlow<AgendaLayout> = _layout.asStateFlow()

    private val _sync = MutableStateFlow(SyncUiState())
    val syncState: StateFlow<SyncUiState> = _sync.asStateFlow()

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
    }

    fun select(task: Task?) {
        _selected.value = task
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
            _sync.update {
                it.copy(
                    message = SyncMessage(
                        R.string.edit_failed_unnamed,
                        failed = true,
                        source = MessageSource.EDIT,
                    ),
                )
            }
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
                onSuccess = {
                    // Clears the failure this edit answers, and leaves a
                    // message about the checkout standing: it is about
                    // something else.
                    _sync.update { state ->
                        state.copy(
                            message = state.message?.takeIf { it.source != MessageSource.EDIT },
                        )
                    }
                    refresh()
                },
                onFailure = { error ->
                    _sync.update { it.copy(message = error.toEditMessage()) }
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
    fun refresh(scope: Scope = Scope.DAY) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = AgendaUiState.Loading
            val today = LocalDate.now()
            notes.ensureSeeded(today) { settings.isConfigured }

            _state.value = agenda.load(scope, today).fold(
                onSuccess = { result ->
                    val sections = result.toSections()
                    AgendaUiState.Ready(
                        date = today,
                        sections = sections,
                        // The agenda is always for today so far; once a date
                        // can be picked, another day passes null and loses
                        // the marker line.
                        timeline = sections.toTimeline(now = LocalTime.now()),
                        notices = result.notices(),
                    )
                },
                onFailure = { error ->
                    AgendaUiState.Failed(error.message ?: error::class.java.simpleName)
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
     */
    fun saveSettings(url: String, branch: String, token: String, dropToken: Boolean = false) {
        val problem = remoteUrlProblem(url)
        if (problem != null) {
            // Nothing is stored and nothing is deleted: the address is checked
            // before the destructive part, not after the clone fails.
            _sync.update { it.copy(message = problem.toMessage()) }
            return
        }

        viewModelScope.launch {
            // A sync in flight owns the directory that is about to be emptied,
            // so it is stopped rather than raced with.
            syncJob?.cancelAndJoin()
            _sync.update { it.copy(running = false) }

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
                settings.remoteUrl = url
                settings.branch = branch
                settings.token = tokenFor(token, dropToken, changedHost = configuredUrl != url)
                _sync.update {
                    it.copy(
                        configured = settings.isConfigured,
                        message = SyncMessage(R.string.sync_status_unreadable, failed = true),
                    )
                }
                return@launch
            }

            val before = previous.getOrNull()?.url
            settings.remoteUrl = url
            settings.branch = branch
            settings.token = tokenFor(token, dropToken, changedHost = configuredUrl != url)

            if (before != settings.remoteUrl) {
                notes.reset()
                _sync.update { it.copy(repository = null) }
            }

            _sync.update { it.copy(configured = settings.isConfigured) }
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
    fun currentSettings(): Triple<String, String, Boolean> = Triple(
        settings.remoteUrl.orEmpty(),
        settings.branch.orEmpty(),
        !settings.token.isNullOrBlank(),
    )

    private fun startSync() {
        if (!settings.isConfigured) {
            return
        }

        syncJob = viewModelScope.launch {
            // Clears the previous sync result, and only that: an edit that
            // failed is still unanswered and stays up.
            _sync.update { state ->
                state.copy(
                    running = true,
                    message = state.message?.takeIf { it.failed && it.source == MessageSource.EDIT },
                )
            }

            val outcome = sync.sync(settings)
            // A sync that went through hands back the state of the checkout it
            // wrote. Asking again walks every file in the working copy,
            // untracked ones included, for an answer already in hand; only a
            // failed sync has nothing to report and has to read.
            val status = outcome.getOrNull()?.head ?: sync.status().getOrNull()
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

            _sync.update { current ->
                current.copy(
                    configured = settings.isConfigured,
                    running = false,
                    repository = status,
                    lastSyncedAt = settings.lastSyncedAt,
                    // A failed edit stays up: the sync result does not answer
                    // it, and read-modify-write here rather than a fresh
                    // object keeps whatever else arrived meanwhile.
                    message = current.message.notDisplacedBy(message),
                )
            }

            if (outcome.isSuccess) {
                refresh()
            }
        }
    }

    private fun readCheckout() {
        viewModelScope.launch {
            val status = sync.status().getOrNull()
            _sync.update {
                it.copy(
                    configured = settings.isConfigured,
                    repository = status,
                    lastSyncedAt = settings.lastSyncedAt,
                )
            }
        }
    }

    companion object {
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
                    editor = NotesEditor(notes, settings),
                )
            }
        }
    }
}
