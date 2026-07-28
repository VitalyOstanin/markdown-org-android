package io.github.vitalyostanin.markdownorg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vitalyostanin.markdownorg.ui.AgendaScreen
import io.github.vitalyostanin.markdownorg.ui.AgendaViewModel
import io.github.vitalyostanin.markdownorg.ui.SyncSettingsScreen
import io.github.vitalyostanin.markdownorg.ui.TaskActionsSheet
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MarkdownOrgTheme {
                val model: AgendaViewModel = viewModel(factory = AgendaViewModel.Factory)
                val state by model.state.collectAsStateWithLifecycle()
                val layout by model.layout.collectAsStateWithLifecycle()
                val sync by model.syncState.collectAsStateWithLifecycle()
                val selected by model.selected.collectAsStateWithLifecycle()

                // Two screens and no navigation library: settings is the only
                // place to go, and it comes back to the agenda.
                var settingsOpen by rememberSaveable { mutableStateOf(false) }

                val insets = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .consumeWindowInsets(WindowInsets.safeDrawing)

                if (settingsOpen) {
                    // Read once per opening, so a save followed by a reopen
                    // shows what was stored.
                    val (url, branch, hasToken) = remember { model.currentSettings() }

                    BackHandler { settingsOpen = false }
                    SyncSettingsScreen(
                        initialUrl = url,
                        initialBranch = branch,
                        hasToken = hasToken,
                        onSave = { newUrl, newBranch, token ->
                            model.saveSettings(newUrl, newBranch, token)
                            settingsOpen = false
                        },
                        onDismiss = { settingsOpen = false },
                        modifier = insets,
                    )
                } else {
                    AgendaScreen(
                        state = state,
                        layout = layout,
                        onLayoutChange = model::setLayout,
                        modifier = insets,
                        sync = sync,
                        onSync = model::syncNow,
                        onOpenSettings = { settingsOpen = true },
                        onTaskClick = model::select,
                    )

                    // Over the agenda rather than instead of it: the list is
                    // the context for what was tapped.
                    selected?.let { task ->
                        TaskActionsSheet(
                            task = task,
                            onAction = { action -> model.apply(task, action) },
                            onDismiss = { model.select(null) },
                        )
                    }
                }
            }
        }
    }
}
