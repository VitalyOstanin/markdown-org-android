package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.BuildConfig
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.NotesPathProblem
import io.github.vitalyostanin.markdownorg.core.RemoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.notesPathProblem
import io.github.vitalyostanin.markdownorg.core.remoteUrlProblem
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import java.io.File

/**
 * Where the notes are fetched from, and where they are kept.
 *
 * Four fields: the repository, the branch, a token, and the directory the
 * notes live in. The device-flow sign-in will fill the token in without
 * typing, but a token pasted by hand is the only path that works for a server
 * other than GitHub, so the field stays either way.
 *
 * What the directory field holds is a path, and it stays a path even when the
 * system's picker filled it in: the core opens the directory with libgit2 and
 * walks it with `std::fs`, and neither can do anything with the URI a picker
 * hands back. That is also why a directory outside the application's own
 * storage needs access to all files, which is granted in a screen of the
 * platform — hence the button rather than a dialog.
 */
@Composable
fun SyncSettingsScreen(
    initial: SettingsInitial,
    onSave: (SyncFormValues) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    collections: CollectionsUi = CollectionsUi(),
    storage: StorageUi = StorageUi(),
    diagnostics: DiagnosticsUi = DiagnosticsUi(),
    onKeepLocal: () -> Unit = {},
    onCreateKey: () -> Unit = {},
    /** Whether the agenda draws its section headings; see [AgendaSection]. */
    grouped: Boolean = true,
    onGroupedChange: (Boolean) -> Unit = {},
) {
    val form = rememberSyncForm(
        editingId = collections.editingId,
        url = initial.url,
        branch = initial.branch,
        name = initial.name,
        notesPath = initial.notesPath,
    )
    // Which collection the confirmation is about, and nothing while there is
    // no dialog up: removing one takes a directory off the agenda, and a
    // stray tap on a list of chips must not be enough to do it.
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    val issues = rememberFormIssues(form, storage)

    // The picker runs in another application, and its answer arrives after
    // this composition was rebuilt — so it lands in the field here rather than
    // being passed as an initial value, which is read once.
    LaunchedEffect(storage.picked) {
        storage.picked?.let {
            form.notesPath = it
            storage.onPickedTaken()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SettingsHeading(
                form = form,
                collections = collections,
                storesLocally = initial.storesLocally,
                onKeepLocal = onKeepLocal,
            )

            RemoteSection(
                form = form,
                problem = issues.remote?.takeIf { issues.remoteRefused },
                hasToken = initial.hasToken,
            )

            KeySection(form = form, initial = initial, onCreateKey = onCreateKey)

            NotesDirectorySection(
                form = form,
                problem = issues.path,
                refused = issues.pathRefused,
                onPickNotesDirectory = storage.onPick,
                onRequestStorage = storage.onRequestPermission,
            )

            Spacer(Modifier.height(Spacing.xs))

            // Only while there is another collection to fall back to: an
            // agenda over nothing has no way back except a reinstall.
            if (collections.all.size > 1) {
                RemoveCollectionButton(onClick = { removing = collections.editingId })
            }

            AgendaSection(grouped = grouped, onGroupedChange = onGroupedChange)

            DiagnosticsSection(
                crash = diagnostics.crash,
                onForgetCrash = diagnostics.onForgetCrash,
                onOpenLicences = diagnostics.onOpenLicences,
            )

            FormButtons(
                onSave = { onSave(form.values()) },
                onDismiss = onDismiss,
                // An empty address is not an obstacle any more: the form also
                // carries where the notes are kept, and notes already on the
                // device need no remote at all. A collection with no name is
                // one the filter offers as a blank chip.
                canSave = !issues.remoteRefused && !issues.pathRefused &&
                    (collections.all.isEmpty() || form.name.isNotBlank()),
            )
        }
    }

    removing?.let { id ->
        RemoveCollectionDialog(
            onConfirm = {
                removing = null
                collections.onRemove(id)
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * What is wrong with the two fields that can be filled in wrongly.
 *
 * An empty field is not an error in either of them, and the difference matters
 * twice over: an empty address is the store on this device, and an empty
 * directory is the one the application owns. Hence the pair of `Refused` flags
 * next to the problems themselves — the field states what it was given, while
 * Save asks only whether it may be pressed.
 */
private data class FormIssues(val remote: RemoteUrlProblem?, val path: NotesPathProblem?) {
    val remoteRefused: Boolean get() = remote != null && remote != RemoteUrlProblem.EMPTY

    val pathRefused: Boolean get() = path != null && path != NotesPathProblem.EMPTY
}

/**
 * Both fields checked as they are typed in, rather than after a clone failed.
 *
 * An address that cannot work says so where it was typed, and a directory that
 * cannot be walked says so before the form is saved over it.
 */
@Composable
private fun rememberFormIssues(form: SyncFormState, storage: StorageUi): FormIssues {
    val remote = remember(form.url) { remoteUrlProblem(form.url) }
    val own = remember(storage.ownNotesPath) { File(storage.ownNotesPath) }
    // Keyed on the permission as well as on the path: the grant happens in
    // another application, and coming back has to clear the complaint about
    // it without the path being touched.
    val path = remember(form.notesPath, storage.granted, own) {
        notesPathProblem(form.notesPath, own, storage.granted)
    }

    return remember(remote, path) { FormIssues(remote, path) }
}

/**
 * Which collection the form is about, and what it is called.
 *
 * Above everything else on the screen, because the answer decides what every
 * field below it means: an address, a token and a directory belong to one
 * collection, and the same form shows another one's on the next tap.
 */
@Composable
private fun SettingsHeading(
    form: SyncFormState,
    collections: CollectionsUi,
    storesLocally: Boolean,
    onKeepLocal: () -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(
            if (storesLocally) R.string.settings_local_hint else R.string.settings_hint,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    CollectionsSection(
        collections = collections.all,
        editingId = collections.editingId,
        onEditCollection = collections.onEdit,
        onAddCollection = collections.onAdd,
    )

    if (collections.all.isNotEmpty()) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { form.name = it },
            label = { Text(stringResource(R.string.settings_collection_name)) },
            supportingText = { Text(stringResource(R.string.settings_collection_name_hint)) },
            isError = form.name.isBlank(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings-collection-name"),
        )
    }

    // Offered while there is no remote and none has been declined: the answer
    // to a first launch that would otherwise keep asking for an address the
    // user has no intention of giving.
    if (!storesLocally && form.url.isBlank()) {
        HintTooltip(stringResource(R.string.hint_settings_keep_local)) {
            TextButton(onClick = onKeepLocal, modifier = Modifier.testTag("settings-keep-local")) {
                Text(stringResource(R.string.settings_keep_local))
            }
        }
    }
}

/** The way to take a collection off the agenda, asked about before it happens. */
@Composable
private fun RemoveCollectionButton(onClick: () -> Unit) {
    HintTooltip(stringResource(R.string.hint_settings_collection_remove)) {
        TextButton(onClick = onClick, modifier = Modifier.testTag("settings-collection-remove")) {
            Text(
                text = stringResource(R.string.settings_collection_remove),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The question a removal is answered by.
 *
 * Removing a collection takes a directory off the agenda, and a stray tap on a
 * row of chips must not be enough to do it. The notes themselves stay where
 * they are — this is about the set, not about the files.
 */
@Composable
private fun RemoveCollectionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_collection_remove)) },
        text = { Text(stringResource(R.string.settings_collection_remove_explain)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("settings-collection-remove-confirm"),
            ) {
                Text(stringResource(R.string.settings_collection_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_collection_remove_cancel))
            }
        },
    )
}

/** The key half of the form, over what the settings already hold. */
@Composable
private fun KeySection(form: SyncFormState, initial: SettingsInitial, onCreateKey: () -> Unit) {
    SshSection(
        hasKey = initial.hasKey,
        publicKey = initial.publicKey.ifEmpty { null },
        knownHost = initial.knownHost.ifEmpty { null },
        onCreateKey = onCreateKey,
        key = form.sshKey,
        onKeyChange = { form.sshKey = it },
        passphrase = form.sshPassphrase,
        onPassphraseChange = { form.sshPassphrase = it },
        dropKey = form.dropKey,
        onDropKeyChange = { form.dropKey = it },
        // The address as it was stored, not as it is being typed: this decides
        // the section's first state and nothing after it.
        startsOpen = initial.hasKey || reachedOverSsh(initial.url),
    )
}

/**
 * Where the notes are fetched from, and what reaches the server over https.
 *
 * The address, the branch and the token stand together because they are one
 * answer: a token is issued by the host in the address above it, and dropping
 * one is about that host and no other.
 */
@Composable
private fun RemoteSection(form: SyncFormState, problem: RemoteUrlProblem?, hasToken: Boolean) {
    OutlinedTextField(
        value = form.url,
        onValueChange = { form.url = it },
        label = { Text(stringResource(R.string.settings_url)) },
        placeholder = { Text("https://gitlab.com/user/notes.git") },
        isError = problem != null,
        // What is wrong with the address, or — while nothing is — what an
        // address here may be. A field that says nothing until it is wrong
        // leaves the shape of the answer to be guessed at.
        supportingText = {
            Text(stringResource(problem?.toMessage()?.text ?: R.string.settings_url_default))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().testTag("settings-url"),
    )

    OutlinedTextField(
        value = form.branch,
        onValueChange = { form.branch = it },
        label = { Text(stringResource(R.string.settings_branch)) },
        // Supporting text rather than a placeholder: a placeholder is only
        // drawn while the field has focus, and what an empty field means has
        // to be readable before touching it.
        supportingText = { Text(stringResource(R.string.settings_branch_default)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().testTag("settings-branch"),
    )

    OutlinedTextField(
        value = form.token,
        onValueChange = { form.token = it },
        label = { Text(stringResource(R.string.settings_token)) },
        // The stored token is never read back into the field, so whether one
        // is saved has to be said in text that is visible without focusing the
        // field.
        supportingText = {
            val kept = R.string.settings_token_kept
            Text(stringResource(if (hasToken) kept else R.string.settings_token_none))
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("settings-token"),
    )

    // Only way back to a remote that needs no credentials: an empty field
    // means "keep what is stored", and the stored one is never shown to be
    // deleted by hand.
    if (hasToken) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = form.dropToken,
                onCheckedChange = { form.dropToken = it },
                modifier = Modifier.testTag("settings-token-drop"),
            )
            // On the label rather than on the box: a long press over a
            // checkbox is a press over the thing it toggles, and the line is
            // what the tick will do when the form is saved.
            HintTooltip(stringResource(R.string.hint_settings_token_drop)) {
                Text(
                    text = stringResource(R.string.settings_token_drop),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Which directory the notes live in, and the two ways of reaching one.
 *
 * The picker only fills the field in — what is stored is a path, because the
 * core opens the directory with libgit2 and walks it with `std::fs`, and
 * neither can do anything with the URI a picker hands back.
 */
@Composable
private fun NotesDirectorySection(
    form: SyncFormState,
    problem: NotesPathProblem?,
    refused: Boolean,
    onPickNotesDirectory: () -> Unit,
    onRequestStorage: () -> Unit,
) {
    OutlinedTextField(
        value = form.notesPath,
        onValueChange = { form.notesPath = it },
        label = { Text(stringResource(R.string.settings_notes)) },
        placeholder = { Text("/storage/emulated/0/Documents/notes") },
        isError = refused,
        // What is wrong with the path, or — while the field is empty — where
        // the notes go instead. A filled field that is fine says nothing: the
        // path is the answer, and a line under it repeating the default would
        // contradict what is above it.
        supportingText = notesSupport(problem, form.notesPath)?.let {
            { Text(stringResource(it)) }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().testTag("settings-notes"),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        // A phone keyboard is a poor way to enter a path — it capitalises,
        // autocorrects and turns `/sdcard` into `/SD card`.
        HintTooltip(stringResource(R.string.hint_settings_notes_pick)) {
            TextButton(
                onClick = onPickNotesDirectory,
                modifier = Modifier.testTag("settings-notes-pick"),
            ) {
                Text(stringResource(R.string.settings_notes_pick))
            }
        }

        // Only while it is the missing permission that stands in the way: a
        // button offering what has already been granted, or what would not
        // help, is a button that answers nothing.
        if (problem == NotesPathProblem.NEEDS_PERMISSION) {
            HintTooltip(stringResource(R.string.hint_settings_notes_grant)) {
                TextButton(
                    onClick = onRequestStorage,
                    modifier = Modifier.testTag("settings-notes-grant"),
                ) {
                    Text(stringResource(R.string.settings_notes_grant))
                }
            }
        }
    }
}

/**
 * How the agenda draws a day: under its section headings, or as one list.
 *
 * Takes effect on the tick rather than on Save, unlike everything above it:
 * the fields above describe a checkout, and half a checkout is not a state
 * worth applying, while this one is about what is drawn and nothing is left
 * half-changed by it. It also has to be seen to be judged — the reader ticks
 * it, goes back and looks.
 */
@Composable
private fun AgendaSection(grouped: Boolean, onGroupedChange: (Boolean) -> Unit) {
    Text(
        text = stringResource(R.string.settings_agenda),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = grouped,
            onCheckedChange = onGroupedChange,
            modifier = Modifier.testTag("settings-agenda-grouped"),
        )
        // On the label rather than on the box, as with the other ticks here.
        HintTooltip(stringResource(R.string.hint_settings_agenda_grouped)) {
            Text(
                text = stringResource(R.string.settings_agenda_grouped),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    Text(
        text = stringResource(R.string.settings_agenda_grouped_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What a report about this build would have to carry.
 *
 * Here rather than on the agenda: all of it is read once, if ever, by whoever
 * is about to write that report, while the agenda's header is for what the
 * reader came for.
 */
@Composable
private fun DiagnosticsSection(
    crash: String?,
    onForgetCrash: () -> Unit,
    onOpenLicences: () -> Unit,
) {
    HintTooltip(stringResource(R.string.hint_settings_licences)) {
        TextButton(onClick = onOpenLicences, modifier = Modifier.testTag("settings-licences")) {
            Text(stringResource(R.string.settings_licences))
        }
    }

    // What is left of the run that ended in a crash: the trace is the whole of
    // what makes such a report worth anything.
    crash?.let { trace ->
        HintTooltip(
            stringResource(R.string.hint_settings_crash),
            // On the anchor rather than on the line inside it: see HintTooltip.
            modifier = Modifier.testTag("settings-crash"),
        ) {
            Text(
                text = stringResource(R.string.settings_crash),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = trace,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = Sizes.traceHeight)
                .verticalScroll(rememberScrollState())
                .testTag("settings-crash-trace"),
        )
        TextButton(onClick = onForgetCrash, modifier = Modifier.testTag("settings-crash-forget")) {
            Text(stringResource(R.string.settings_crash_forget))
        }
    }

    // Which build is installed. Two APKs of the same version differ only by
    // the run that produced them, so the code and the commit are here as
    // well: a report about a build nobody can identify cannot be acted on.
    HintTooltip(
        stringResource(R.string.hint_settings_version),
        modifier = Modifier.testTag("settings-version"),
    ) {
        Text(
            text = stringResource(
                R.string.settings_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.COMMIT,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The two ways out of the form, at its foot. */
@Composable
private fun FormButtons(onSave: () -> Unit, onDismiss: () -> Unit, canSave: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HintTooltip(stringResource(R.string.hint_settings_cancel)) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-cancel")) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        HintTooltip(stringResource(R.string.hint_settings_save)) {
            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.testTag("settings-save"),
            ) {
                Text(stringResource(R.string.settings_save))
            }
        }
    }
}

/**
 * The collections there are, and the way to add another.
 *
 * Chips rather than a list: the set is short, switching between them is the
 * common move, and which one the form below is about has to be readable
 * without scrolling back up. Nothing is drawn until the collections are known
 * — a screen shown before they arrive would offer a row of one chip that
 * turns into two.
 */
@Composable
private fun CollectionsSection(
    collections: List<NotesCollection>,
    editingId: String,
    onEditCollection: (String) -> Unit,
    onAddCollection: (String) -> Unit,
) {
    if (collections.isEmpty()) {
        return
    }

    val newName = stringResource(R.string.collection_new_name)

    // On the heading rather than on each chip: the chips already carry a name
    // apiece, and what needs saying is what picking one of them changes.
    HintTooltip(stringResource(R.string.hint_settings_collection)) {
        Text(
            text = stringResource(R.string.settings_collections),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("settings-collections"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        collections.forEach { collection ->
            FilterChip(
                selected = collection.id == editingId,
                onClick = { onEditCollection(collection.id) },
                label = {
                    Text(
                        text = collection.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = Sizes.collectionName),
                    )
                },
                modifier = Modifier.testTag("settings-collection-${collection.id}"),
            )
        }
        HintTooltip(stringResource(R.string.hint_settings_collection_add)) {
            TextButton(
                onClick = { onAddCollection(newName) },
                modifier = Modifier.testTag("settings-collection-add"),
            ) {
                Text(stringResource(R.string.settings_collection_add))
            }
        }
    }
}

/**
 * Everything an `ssh://` remote needs, and nothing an `https://` one does.
 *
 * Folded away behind its heading, and open from the start only when there is
 * something in it: a key already stored, or an address that is reached with
 * one. Four more fields standing open under an HTTPS remote push the save
 * button off the screen for settings that remote has no use for.
 *
 * Opened by a press rather than by what is being typed above: a section that
 * appears and disappears while an address is half-typed moves everything
 * under the fingers.
 *
 * The key itself is write-only, the way the token is: what is stored is never
 * read back into the field, so the line under it says whether there is one.
 */
@Composable
@Suppress("LongParameterList")
private fun SshSection(
    hasKey: Boolean,
    publicKey: String?,
    knownHost: String?,
    onCreateKey: () -> Unit,
    key: String,
    onKeyChange: (String) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    dropKey: Boolean,
    onDropKeyChange: (Boolean) -> Unit,
    startsOpen: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    var open by rememberSaveable { mutableStateOf(startsOpen) }

    HintTooltip(stringResource(R.string.hint_settings_ssh)) {
        TextButton(
            onClick = { open = !open },
            modifier = Modifier.testTag("settings-ssh-toggle"),
        ) {
            Text(
                text = stringResource(R.string.settings_ssh),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (!open) {
        return
    }

    OutlinedTextField(
        value = key,
        onValueChange = onKeyChange,
        label = { Text(stringResource(R.string.settings_ssh_key)) },
        supportingText = {
            val kept = R.string.settings_ssh_key_kept
            Text(stringResource(if (hasKey) kept else R.string.settings_ssh_key_none))
        },
        // Several lines rather than one: a private key is many lines long, and
        // a field showing one of them says nothing about what was pasted.
        minLines = 2,
        maxLines = 4,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().testTag("settings-ssh-key"),
    )

    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphraseChange,
        label = { Text(stringResource(R.string.settings_ssh_passphrase)) },
        supportingText = { Text(stringResource(R.string.settings_ssh_passphrase_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("settings-ssh-passphrase"),
    )

    if (hasKey) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = dropKey,
                onCheckedChange = onDropKeyChange,
                modifier = Modifier.testTag("settings-ssh-key-drop"),
            )
            HintTooltip(stringResource(R.string.hint_settings_ssh_key_drop)) {
                Text(
                    text = stringResource(R.string.settings_ssh_key_drop),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    HintTooltip(stringResource(R.string.hint_settings_ssh_create)) {
        TextButton(onClick = onCreateKey, modifier = Modifier.testTag("settings-ssh-create")) {
            Text(stringResource(R.string.settings_ssh_create))
        }
    }

    // The public half is of no use on the phone: it is taken to a server, and
    // the way it gets there from here is the clipboard.
    publicKey?.let { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("settings-ssh-public"),
        )
        HintTooltip(stringResource(R.string.hint_settings_ssh_copy)) {
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(line)) },
                modifier = Modifier.testTag("settings-ssh-copy"),
            ) {
                Text(stringResource(R.string.settings_ssh_copy))
            }
        }
    }

    // What the server is pinned by, once somebody has said it is the right
    // server. Shown so it can be compared with what the server says about
    // itself, which is the only check there is.
    knownHost?.let { fingerprint ->
        Text(
            text = stringResource(R.string.settings_ssh_host, fingerprint),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("settings-ssh-host"),
        )
    }
}

/**
 * Whether an address is one a key is needed for, in both its spellings.
 *
 * `git@host:path` is ssh written the scp way, and the core takes it as such —
 * see `ensure_supported`. Recognised loosely on purpose: this only decides
 * whether a section starts open.
 */
private fun reachedOverSsh(url: String): Boolean =
    url.startsWith("ssh://") || (url.contains('@') && !url.contains("://"))

/**
 * What stands under the directory field: the problem with what is in it, the
 * default while it is empty, or nothing.
 *
 * Outside the composable because it is the whole of the rule and reads as one
 * sentence here; inside, it was three branches in the middle of a form.
 */
@StringRes
private fun notesSupport(problem: NotesPathProblem?, path: String): Int? = when {
    problem != null && problem != NotesPathProblem.EMPTY -> problem.toMessage()?.text
    path.isBlank() -> R.string.settings_notes_default
    else -> null
}
