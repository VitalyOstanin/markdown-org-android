package io.github.vitalyostanin.markdownorg

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vitalyostanin.markdownorg.core.CrashLog
import io.github.vitalyostanin.markdownorg.core.LicenceGroup
import io.github.vitalyostanin.markdownorg.core.StorageAccess
import io.github.vitalyostanin.markdownorg.core.licenceCatalog
import io.github.vitalyostanin.markdownorg.core.ownNotesRoot
import io.github.vitalyostanin.markdownorg.ui.AgendaActions
import io.github.vitalyostanin.markdownorg.ui.AgendaFilters
import io.github.vitalyostanin.markdownorg.ui.AgendaScreen
import io.github.vitalyostanin.markdownorg.ui.AgendaUi
import io.github.vitalyostanin.markdownorg.ui.AgendaView
import io.github.vitalyostanin.markdownorg.ui.AgendaViewModel
import io.github.vitalyostanin.markdownorg.ui.CollectionsUi
import io.github.vitalyostanin.markdownorg.ui.DiagnosticsUi
import io.github.vitalyostanin.markdownorg.ui.EntryEditor
import io.github.vitalyostanin.markdownorg.ui.ExternalNote
import io.github.vitalyostanin.markdownorg.ui.LicensesScreen
import io.github.vitalyostanin.markdownorg.ui.SettingsInitial
import io.github.vitalyostanin.markdownorg.ui.StorageUi
import io.github.vitalyostanin.markdownorg.ui.SyncSettingsScreen
import io.github.vitalyostanin.markdownorg.ui.TaskActionsSheet
import io.github.vitalyostanin.markdownorg.ui.TaskCreator
import io.github.vitalyostanin.markdownorg.ui.rememberRemindersUi
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MarkdownOrgTheme {
                Application()
            }
        }
    }
}

/**
 * Which of the three screens is up, and nothing else.
 *
 * Three screens and no navigation library: the agenda, the settings it opens,
 * and the notices behind those. Each comes back to the one it was opened from,
 * and each takes what it needs from the view model itself — the state a screen
 * reads is the screen's business, and gathering all of it here is what made
 * this one function the width of the application.
 */
@Composable
private fun Application() {
    val model: AgendaViewModel = viewModel(factory = AgendaViewModel.Factory)
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var licencesOpen by rememberSaveable { mutableStateOf(false) }

    val insets = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .consumeWindowInsets(WindowInsets.safeDrawing)

    when {
        licencesOpen -> LicencesRoute(onDismiss = { licencesOpen = false }, modifier = insets)

        settingsOpen -> SettingsRoute(
            model = model,
            onDismiss = { settingsOpen = false },
            onOpenLicences = { licencesOpen = true },
            modifier = insets,
        )

        else -> AgendaRoute(
            model = model,
            onOpenSettings = { settingsOpen = true },
            modifier = insets,
        )
    }
}

/** The notices of what the APK carries, read off the assets while they are up. */
@Composable
private fun LicencesRoute(onDismiss: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current.applicationContext

    // Read off the assets once per opening, on a background thread: the texts
    // come to a few hundred kilobytes and the main thread is drawing.
    val catalog by produceState(emptyList<LicenceGroup>(), context) {
        value = withContext(Dispatchers.IO) { licenceCatalog(context) }
    }

    BackHandler(onBack = onDismiss)
    LicensesScreen(catalog = catalog, onDismiss = onDismiss, modifier = modifier)
}

/**
 * The settings of one collection, and the ways of granting what they need.
 *
 * The two launchers and the picker live here rather than beside the agenda:
 * they are answered on this screen, and an agenda that never asks for storage
 * has no reason to hold them.
 */
@Composable
private fun SettingsRoute(
    model: AgendaViewModel,
    onDismiss: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current.applicationContext
    val sync by model.syncState.collectAsStateWithLifecycle()
    val collectionSet by model.collectionSet.collectAsStateWithLifecycle()
    val editingId by model.editingId.collectAsStateWithLifecycle()
    val grouped by model.grouped.collectAsStateWithLifecycle()
    val monthAsGrid by model.monthAsGrid.collectAsStateWithLifecycle()
    val weekStart by model.weekStart.collectAsStateWithLifecycle()

    val storage = rememberStorageUi()
    val reminders = rememberRemindersUi()

    // Read once per opening, so a save followed by a reopen shows what was
    // stored — and again when the form is pointed at another collection, whose
    // remote, token and directory are its own.
    val form = remember(editingId) { model.currentSettings() }

    // What the run that crashed left behind, off the main thread like the
    // notices: a file read is a file read.
    val log = remember(context) { CrashLog(context) }
    var forgotten by remember { mutableStateOf(false) }
    val kept by produceState<String?>(null, log) {
        value = withContext(Dispatchers.IO) { log.read() }
    }

    BackHandler(onBack = onDismiss)
    SyncSettingsScreen(
        initial = SettingsInitial(
            url = form.url,
            branch = form.branch,
            notesPath = form.notesPath,
            name = form.name,
            inbox = form.inbox,
            hasToken = form.hasToken,
            hasKey = form.hasKey,
            // The state wins over what was read when the screen opened: a key
            // made just now is in the first and not in the second, and it is
            // the half that has to be taken to a server.
            publicKey = sync.publicKey.ifEmpty { form.publicKey },
            knownHost = form.knownHost,
            storesLocally = sync.local,
        ),
        onSave = { saved ->
            model.saveSettings(
                url = saved.url,
                branch = saved.branch,
                token = saved.token,
                dropToken = saved.dropToken,
                notesPath = saved.notesPath,
                name = saved.name,
                inbox = saved.inbox,
                sshKey = saved.sshKey,
                sshPassphrase = saved.sshPassphrase,
                dropKey = saved.dropKey,
            )
            onDismiss()
        },
        onDismiss = onDismiss,
        modifier = modifier,
        collections = CollectionsUi(
            all = collectionSet,
            editingId = editingId,
            onEdit = model::editCollection,
            onAdd = model::addCollection,
            onRemove = model::removeCollection,
        ),
        storage = storage,
        diagnostics = DiagnosticsUi(
            crash = kept?.takeUnless { forgotten },
            onForgetCrash = {
                forgotten = true
                log.clear()
            },
            onOpenLicences = onOpenLicences,
        ),
        onKeepLocal = {
            model.keepNotesLocal()
            onDismiss()
        },
        onCreateKey = model::createSshKey,
        onOpenPage = { page -> openPage(context, page) },
        agenda = AgendaUi(
            grouped = grouped,
            onGroupedChange = model::setGrouped,
            monthAsGrid = monthAsGrid,
            onMonthAsGridChange = model::setMonthAsGrid,
            weekStart = weekStart,
            onWeekStartChange = model::setWeekStart,
        ),
        reminders = reminders,
    )
}

/**
 * Hands a page of the server to whatever opens pages on this device.
 *
 * Wrapped rather than thrown: a device with no browser at all answers with
 * [ActivityNotFoundException], and there is nothing to say about it beyond the
 * log — the page is an offer, and the settings form works without it. The flag
 * is what a launch from outside an activity's own task needs.
 */
private fun openPage(context: Context, page: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (absent: ActivityNotFoundException) {
        Log.w("MainActivity", "no application opens $page", absent)
    }
}

/**
 * Where the notes may be kept, and the ways of being allowed to keep them.
 *
 * Everything here is answered outside this application — in a screen of the
 * platform, in a permission dialog, in the system's directory picker — so the
 * launchers and what they set live together, and the form is handed the answer
 * rather than the machinery.
 */
@Composable
private fun rememberStorageUi(): StorageUi {
    val context = LocalContext.current.applicationContext

    // Held as state because the grant happens elsewhere, and the form has to
    // stop complaining as soon as the answer changes.
    var granted by remember { mutableStateOf(StorageAccess.granted(context)) }
    val grantResult: (Any?) -> Unit = { granted = StorageAccess.granted(context) }
    // Two ways to ask, one per platform: the settings screen of all-files
    // access from Android 11, the permission dialog before it. Both answer by
    // way of the state above rather than by their result — the settings screen
    // returns "cancelled" however it went.
    val allFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        grantResult,
    )
    val dialog = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        grantResult,
    )

    // A directory pointed at in the system's picker, on its way to the field.
    // Saved rather than remembered: the picker is another activity, and this
    // one may be rebuilt while it is up.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val pickTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        // Nothing on a cancelled pick, and nothing for a tree that names no
        // directory on the shared storage — the form is left as it was rather
        // than filled with a path that is not there.
        picked = tree?.let(StorageAccess::directoryOf)
    }

    return StorageUi(
        ownNotesPath = remember(context) { ownNotesRoot(context).absolutePath },
        granted = granted,
        onRequestPermission = {
            val screen = StorageAccess.settings(context)
            if (screen == null) {
                dialog.launch(StorageAccess.permissions())
            } else {
                allFiles.launch(screen)
            }
        },
        picked = picked,
        // No initial directory: the picker opens where it was left, which is
        // closer to what someone picking a second time wants than the root
        // every time.
        onPick = { pickTree.launch(null) },
        onPickedTaken = { picked = null },
    )
}

/** The agenda, with the sheet a tapped task opens over it. */
@Composable
private fun AgendaRoute(model: AgendaViewModel, onOpenSettings: () -> Unit, modifier: Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val layout by model.layout.collectAsStateWithLifecycle()
    val span by model.span.collectAsStateWithLifecycle()
    val grouped by model.grouped.collectAsStateWithLifecycle()
    val monthAsGrid by model.monthAsGrid.collectAsStateWithLifecycle()
    val weekStart by model.weekStart.collectAsStateWithLifecycle()
    val sync by model.syncState.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val editedEntry by model.editedEntry.collectAsStateWithLifecycle()
    val creating by model.creating.collectAsStateWithLifecycle()
    val collectionSet by model.collectionSet.collectAsStateWithLifecycle()
    val editIssue by model.editIssue.collectAsStateWithLifecycle()
    val groupResult by model.groupResult.collectAsStateWithLifecycle()
    val editResult by model.editResult.collectAsStateWithLifecycle()
    val collections by model.collectionFilter.collectAsStateWithLifecycle()
    val tags by model.tags.collectAsStateWithLifecycle()
    val currentTag by model.currentTag.collectAsStateWithLifecycle()
    // With the lifecycle, so the ticker behind it stops with the screen and
    // reads the clock again when the screen comes back.
    val now by model.now.collectAsStateWithLifecycle()

    AgendaScreen(
        state = state,
        view = AgendaView(
            layout = layout,
            onLayoutChange = model::setLayout,
            span = span,
            onSpanChange = model::setSpan,
            grouped = grouped,
            monthAsGrid = monthAsGrid,
        ),
        modifier = modifier,
        now = now,
        sync = sync,
        editIssue = editIssue,
        groupResult = groupResult,
        editResult = editResult,
        filters = AgendaFilters(
            collections = collections,
            onCollectionShown = model::setCollectionShown,
            tags = tags,
            currentTag = currentTag,
            onTagChange = model::setTag,
        ),
        actions = AgendaActions(
            onSync = model::syncNow,
            onOpenSettings = onOpenSettings,
            onTaskClick = model::select,
            onTakeRemote = model::takeRemoteNotes,
            onSettleAndSync = model::settleAndSync,
            onTrustHost = model::trustHost,
            onStep = model::stepBy,
            onShowToday = model::showToday,
            onShowDay = model::showDay,
            onGroupAction = { group, action -> model.applyToGroup(group.rows, action) },
            onUndoGroup = model::undoGroup,
            onUndoEdit = model::undoEdit,
            onCreate = model::startCreating,
            onEditIssueShown = model::editIssueShown,
            onGroupResultShown = model::groupResultShown,
            onEditResultShown = model::editResultShown,
        ),
    )

    // Over the agenda rather than instead of it: the list is the context for
    // what was tapped.
    selected?.let { task ->
        val context = LocalContext.current

        TaskActionsSheet(
            task = task,
            onAction = { action -> model.apply(task, action) },
            onDismiss = { model.select(null) },
            // The calendar is cut where the agenda's month grid is cut: the
            // reader answered that question once, in the settings.
            weekStart = weekStart,
            onEdit = { model.edit(task) },
            // Handled here rather than in the view model: this leaves the
            // application, and what it needs is an activity to launch from,
            // not the notes. The sheet closes first, the way it does for
            // every other action -- what comes back is another application's
            // screen, and a sheet left standing behind it is what the user
            // returns to.
            onOpenExternally = {
                model.select(null)
                if (!ExternalNote.open(context, File(task.file))) {
                    model.reportOpenFailure()
                }
            },
        )
    }

    // Over the agenda as well, and after the sheet: what opens it is one of
    // the sheet's own actions.
    editedEntry?.let { draft ->
        EntryEditor(
            draft = draft,
            onSave = model::saveEntry,
            onDismiss = model::cancelEdit,
        )
    }

    // The one screen over the agenda that no task opens: it writes a task that
    // is not there yet, and the button for it sits at the corner of the plan.
    if (creating) {
        TaskCreator(
            collections = collectionSet,
            onCreate = model::createTask,
            onDismiss = model::cancelCreating,
            weekStart = weekStart,
        )
    }
}
