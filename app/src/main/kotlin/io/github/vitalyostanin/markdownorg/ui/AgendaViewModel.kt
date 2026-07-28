package io.github.vitalyostanin.markdownorg.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.AgendaSource
import io.github.vitalyostanin.markdownorg.core.NotesStore
import io.github.vitalyostanin.markdownorg.core.NotesSync
import io.github.vitalyostanin.markdownorg.core.SyncSettings
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.markdown_org_ffi.Scope

class AgendaViewModel(
    private val store: NotesStore,
    private val sync: NotesSync,
    private val settings: SyncSettings,
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

    init {
        refresh()
        readCheckout()
    }

    fun setLayout(layout: AgendaLayout) {
        _layout.value = layout
    }

    fun refresh(scope: Scope = Scope.DAY) {
        viewModelScope.launch {
            _state.value = AgendaUiState.Loading
            val today = LocalDate.now()
            withContext(Dispatchers.IO) { store.ensureSeeded(today, settings.isConfigured) }

            _state.value = AgendaSource(store.root).load(scope, today).fold(
                onSuccess = { result ->
                    val sections = result.toSections()
                    AgendaUiState.Ready(
                        date = today,
                        sections = sections,
                        // The agenda is always for today so far; once a date
                        // can be picked, another day passes null and loses
                        // the marker line.
                        timeline = sections.toTimeline(now = LocalTime.now()),
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
        if (_sync.value.running || !settings.isConfigured) {
            return
        }

        viewModelScope.launch {
            _sync.update { it.copy(running = true, message = null) }

            val outcome = sync.sync(settings)
            val status = sync.status()

            _sync.value = SyncUiState(
                configured = settings.isConfigured,
                running = false,
                repository = status,
                lastSyncedAt = settings.lastSyncedAt,
                message = outcome.fold(
                    onSuccess = { result ->
                        when {
                            result.cloned -> SyncMessage(R.string.sync_cloned)
                            result.commitsApplied > 0u -> SyncMessage(R.string.sync_updated)
                            else -> SyncMessage(R.string.sync_already_current)
                        }
                    },
                    onFailure = Throwable::toSyncMessage,
                ),
            )

            if (outcome.isSuccess) {
                refresh()
            }
        }
    }

    /**
     * Stores the remote and empties the directory when the clone cannot land
     * in what is already there.
     *
     * The core clones into an empty directory, so both cases have to start
     * from one: the first setup, where the directory holds the sample notes,
     * and a change of remote, where it holds someone else's checkout. Only an
     * existing checkout of the same URL is kept — that one is fetched into.
     */
    fun saveSettings(url: String, branch: String, token: String) {
        viewModelScope.launch {
            // Read off disk rather than from the state: the state is filled in
            // asynchronously after launch, and saving before it arrives would
            // throw away a checkout that did not need to go.
            val previous = sync.status()?.url
            settings.remoteUrl = url
            settings.branch = branch
            if (token.isNotBlank()) {
                settings.token = token
            }

            if (previous != settings.remoteUrl) {
                withContext(Dispatchers.IO) { store.reset() }
                _sync.update { it.copy(repository = null) }
            }

            _sync.update { it.copy(configured = settings.isConfigured) }
            syncNow()
        }
    }

    /** Current settings, for filling the form. */
    fun currentSettings(): Triple<String, String, Boolean> = Triple(
        settings.remoteUrl.orEmpty(),
        settings.branch.orEmpty(),
        !settings.token.isNullOrBlank(),
    )

    private fun readCheckout() {
        viewModelScope.launch {
            val status = sync.status()
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
                val store = NotesStore(application)
                AgendaViewModel(
                    store = store,
                    sync = NotesSync(application, store.root),
                    settings = SyncSettings(application),
                )
            }
        }
    }
}
