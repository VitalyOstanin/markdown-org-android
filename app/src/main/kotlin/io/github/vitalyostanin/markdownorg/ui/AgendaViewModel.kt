package io.github.vitalyostanin.markdownorg.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vitalyostanin.markdownorg.core.AgendaSource
import io.github.vitalyostanin.markdownorg.core.NotesStore
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.markdown_org_ffi.Scope

class AgendaViewModel(private val store: NotesStore) : ViewModel() {

    private val _state = MutableStateFlow<AgendaUiState>(AgendaUiState.Loading)
    val state: StateFlow<AgendaUiState> = _state.asStateFlow()

    /**
     * Kept apart from [state] so switching the layout redraws without going
     * back through Loading — the data is the same, only its shape changes.
     */
    private val _layout = MutableStateFlow(AgendaLayout.TIME)
    val layout: StateFlow<AgendaLayout> = _layout.asStateFlow()

    init {
        refresh()
    }

    fun setLayout(layout: AgendaLayout) {
        _layout.value = layout
    }

    fun refresh(scope: Scope = Scope.DAY) {
        viewModelScope.launch {
            _state.value = AgendaUiState.Loading
            val today = LocalDate.now()
            withContext(Dispatchers.IO) { store.ensureSeeded(today) }

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

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                AgendaViewModel(NotesStore(application))
            }
        }
    }
}
