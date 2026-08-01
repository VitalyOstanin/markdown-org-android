package io.github.vitalyostanin.markdownorg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vitalyostanin.markdownorg.core.CrashLog
import io.github.vitalyostanin.markdownorg.core.LicenceGroup
import io.github.vitalyostanin.markdownorg.core.StorageAccess
import io.github.vitalyostanin.markdownorg.core.licenceCatalog
import io.github.vitalyostanin.markdownorg.core.ownNotesRoot
import io.github.vitalyostanin.markdownorg.ui.AgendaScreen
import io.github.vitalyostanin.markdownorg.ui.AgendaViewModel
import io.github.vitalyostanin.markdownorg.ui.LicensesScreen
import io.github.vitalyostanin.markdownorg.ui.SyncSettingsScreen
import io.github.vitalyostanin.markdownorg.ui.TaskActionsSheet
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                val editIssue by model.editIssue.collectAsStateWithLifecycle()
                // With the lifecycle, so the ticker behind it stops with the
                // screen and reads the clock again when the screen comes back.
                val now by model.now.collectAsStateWithLifecycle()

                // Three screens and no navigation library: the agenda, the
                // settings it opens, and the notices behind those. Each comes
                // back to the one it was opened from.
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                var licencesOpen by rememberSaveable { mutableStateOf(false) }

                // Whether the notes may be kept outside the application's own
                // storage. Held here because it is granted elsewhere — in a
                // screen of the platform, or in a dialog — and the form has to
                // stop complaining as soon as the answer changes.
                var storageGranted by remember {
                    mutableStateOf(StorageAccess.granted(applicationContext))
                }
                val grantResult: (Any?) -> Unit = {
                    storageGranted = StorageAccess.granted(applicationContext)
                }
                // Two ways to ask, one per platform: the settings screen of
                // all-files access from Android 11, the permission dialog
                // before it. Both answer by way of the state above rather than
                // by their result — the settings screen returns "cancelled"
                // however it went.
                val allFiles = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                    grantResult,
                )
                val dialog = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                    grantResult,
                )

                // A directory pointed at in the system's picker, on its way to
                // the field. Saved rather than remembered: the picker is
                // another activity, and this one may be rebuilt while it is up.
                var picked by rememberSaveable { mutableStateOf<String?>(null) }
                val pickTree = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { tree ->
                    // Nothing on a cancelled pick, and nothing for a tree that
                    // names no directory on the shared storage — the form is
                    // left as it was rather than filled with a path that is
                    // not there.
                    picked = tree?.let(StorageAccess::directoryOf)
                }

                val insets = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .consumeWindowInsets(WindowInsets.safeDrawing)

                if (licencesOpen) {
                    // Read off the assets once per opening, on a background
                    // thread: the texts come to a few hundred kilobytes and
                    // the main thread is drawing.
                    val catalog by produceState(emptyList<LicenceGroup>()) {
                        value = withContext(Dispatchers.IO) { licenceCatalog(applicationContext) }
                    }

                    BackHandler { licencesOpen = false }
                    LicensesScreen(
                        catalog = catalog,
                        onDismiss = { licencesOpen = false },
                        modifier = insets,
                    )
                } else if (settingsOpen) {
                    // Read once per opening, so a save followed by a reopen
                    // shows what was stored.
                    val form = remember { model.currentSettings() }

                    // What the run that crashed left behind, off the main
                    // thread like the notices: a file read is a file read.
                    val log = remember { CrashLog(applicationContext) }
                    var forgotten by remember { mutableStateOf(false) }
                    val kept by produceState<String?>(null, log) {
                        value = withContext(Dispatchers.IO) { log.read() }
                    }
                    val crash = kept?.takeUnless { forgotten }

                    BackHandler { settingsOpen = false }
                    SyncSettingsScreen(
                        initialUrl = form.url,
                        initialBranch = form.branch,
                        hasToken = form.hasToken,
                        onSave = { newUrl, newBranch, token, dropToken, notesPath ->
                            model.saveSettings(newUrl, newBranch, token, dropToken, notesPath)
                            settingsOpen = false
                        },
                        onDismiss = { settingsOpen = false },
                        onOpenLicences = { licencesOpen = true },
                        modifier = insets,
                        initialNotesPath = form.notesPath,
                        ownNotesPath = remember { ownNotesRoot(applicationContext).absolutePath },
                        storageGranted = storageGranted,
                        onRequestStorage = {
                            val screen = StorageAccess.settings(applicationContext)
                            if (screen == null) {
                                dialog.launch(StorageAccess.permissions())
                            } else {
                                allFiles.launch(screen)
                            }
                        },
                        pickedNotesPath = picked,
                        // No initial directory: the picker opens where it was
                        // left, which is closer to what someone picking a
                        // second time wants than the root every time.
                        onPickNotesDirectory = { pickTree.launch(null) },
                        onPickedNotesTaken = { picked = null },
                        crash = crash,
                        onForgetCrash = {
                            forgotten = true
                            log.clear()
                        },
                    )
                } else {
                    AgendaScreen(
                        state = state,
                        layout = layout,
                        onLayoutChange = model::setLayout,
                        modifier = insets,
                        now = now,
                        sync = sync,
                        editIssue = editIssue,
                        onEditIssueShown = model::editIssueShown,
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
