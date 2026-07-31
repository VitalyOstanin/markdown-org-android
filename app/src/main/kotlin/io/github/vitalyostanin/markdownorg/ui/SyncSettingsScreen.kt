package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.BuildConfig
import io.github.vitalyostanin.markdownorg.R
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
    initialUrl: String,
    initialBranch: String,
    hasToken: Boolean,
    onSave: (
        url: String,
        branch: String,
        token: String,
        dropToken: Boolean,
        notesPath: String,
    ) -> Unit,
    onDismiss: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
    initialNotesPath: String = "",
    ownNotesPath: String = "",
    storageGranted: Boolean = false,
    onRequestStorage: () -> Unit = {},
    /** A directory chosen in the system's picker, until it has been taken in. */
    pickedNotesPath: String? = null,
    onPickNotesDirectory: () -> Unit = {},
    onPickedNotesTaken: () -> Unit = {},
    crash: String? = null,
    onForgetCrash: () -> Unit = {},
) {
    // Saved rather than merely remembered: the activity declares no
    // configChanges, so a turn of the phone rebuilds it, and a URL typed by
    // hand or a token pasted from a browser would be gone.
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var branch by rememberSaveable { mutableStateOf(initialBranch) }
    // The token included. It goes into the saved state of the activity, which
    // lives in the process and in the private storage the process is killed
    // to — the same storage the token is already stored in, and only a
    // rotation away from being typed again by hand.
    var token by rememberSaveable { mutableStateOf("") }
    var dropToken by rememberSaveable { mutableStateOf(false) }
    var notesPath by rememberSaveable { mutableStateOf(initialNotesPath) }

    // Saving empties the working copy, and edits made here are committed
    // locally and never pushed — so an address that cannot work is caught in
    // the field rather than after the clone has failed over an empty
    // directory. An empty field is not an error yet, only a disabled button.
    val problem = remember(url) { remoteUrlProblem(url) }
    val malformed = problem != null && problem != RemoteUrlProblem.EMPTY

    // The picker runs in another application, and its answer arrives after
    // this composition was rebuilt — so it lands in the field here rather than
    // being passed as an initial value, which is read once.
    LaunchedEffect(pickedNotesPath) {
        pickedNotesPath?.let {
            notesPath = it
            onPickedNotesTaken()
        }
    }

    val own = remember(ownNotesPath) { File(ownNotesPath) }
    // Keyed on the permission as well as on the path: the grant happens in
    // another application, and coming back has to clear the complaint about
    // it without the path being touched.
    val pathProblem = remember(notesPath, storageGranted, own) {
        notesPathProblem(notesPath, own, storageGranted)
    }
    val pathRefused = pathProblem != null && pathProblem != NotesPathProblem.EMPTY

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.settings_url)) },
                placeholder = { Text("https://gitlab.com/user/notes.git") },
                isError = malformed,
                supportingText = problem
                    ?.takeIf { malformed }
                    ?.let { { Text(stringResource(it.toMessage().text)) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.fillMaxWidth().testTag("settings-url"),
            )

            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it },
                label = { Text(stringResource(R.string.settings_branch)) },
                // Supporting text rather than a placeholder: a placeholder is
                // only drawn while the field has focus, and what an empty
                // field means has to be readable before touching it.
                supportingText = { Text(stringResource(R.string.settings_branch_default)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.fillMaxWidth().testTag("settings-branch"),
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.settings_token)) },
                // The stored token is never read back into the field, so
                // whether one is saved has to be said in text that is visible
                // without focusing the field.
                supportingText = {
                    val kept = R.string.settings_token_kept
                    Text(stringResource(if (hasToken) kept else R.string.settings_token_none))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("settings-token"),
            )

            // Only way back to a remote that needs no credentials: an empty
            // field means "keep what is stored", and the stored one is never
            // shown to be deleted by hand.
            if (hasToken) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = dropToken,
                        onCheckedChange = { dropToken = it },
                        modifier = Modifier.testTag("settings-token-drop"),
                    )
                    Text(
                        text = stringResource(R.string.settings_token_drop),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            OutlinedTextField(
                value = notesPath,
                onValueChange = { notesPath = it },
                label = { Text(stringResource(R.string.settings_notes)) },
                placeholder = { Text("/storage/emulated/0/Documents/notes") },
                isError = pathRefused,
                // What is wrong with the path, or — while the field is empty —
                // where the notes go instead. A filled field that is fine says
                // nothing: the path is the answer, and a line under it
                // repeating the default would contradict what is above it.
                supportingText = notesSupport(pathProblem, notesPath)?.let {
                    { Text(stringResource(it)) }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.fillMaxWidth().testTag("settings-notes"),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // A phone keyboard is a poor way to enter a path — it
                // capitalises, autocorrects and turns `/sdcard` into
                // `/SD card`. The picker only fills the field in; the notes
                // are read by path all the same.
                TextButton(
                    onClick = onPickNotesDirectory,
                    modifier = Modifier.testTag("settings-notes-pick"),
                ) {
                    Text(stringResource(R.string.settings_notes_pick))
                }

                // Only while it is the missing permission that stands in the
                // way: a button offering what has already been granted, or
                // what would not help, is a button that answers nothing.
                if (pathProblem == NotesPathProblem.NEEDS_PERMISSION) {
                    TextButton(
                        onClick = onRequestStorage,
                        modifier = Modifier.testTag("settings-notes-grant"),
                    ) {
                        Text(stringResource(R.string.settings_notes_grant))
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xs))

            // The way to the notices of everything the APK carries. Here
            // rather than on the agenda: it is read once, if ever, and the
            // agenda's header is for what the reader came for.
            TextButton(
                onClick = onOpenLicences,
                modifier = Modifier.testTag("settings-licences"),
            ) {
                Text(stringResource(R.string.settings_licences))
            }

            // What is left of the run that ended in a crash. Here rather than
            // on the agenda: it is read once, by whoever is about to report
            // it, and the trace is the whole of what makes such a report
            // worth anything.
            crash?.let { trace ->
                Text(
                    text = stringResource(R.string.settings_crash),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("settings-crash"),
                )
                Text(
                    text = trace,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = Sizes.traceHeight)
                        .verticalScroll(rememberScrollState())
                        .testTag("settings-crash-trace"),
                )
                TextButton(
                    onClick = onForgetCrash,
                    modifier = Modifier.testTag("settings-crash-forget"),
                ) {
                    Text(stringResource(R.string.settings_crash_forget))
                }
            }

            // Which build is installed. Two APKs of the same version differ
            // only by the run that produced them, so the code and the commit
            // are here as well: a report about a build nobody can identify
            // cannot be acted on.
            Text(
                text = stringResource(
                    R.string.settings_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                    BuildConfig.COMMIT,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings-version"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-cancel")) {
                    Text(stringResource(R.string.settings_cancel))
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = { onSave(url, branch, token, dropToken, notesPath) },
                    // An empty address is not an obstacle any more: the form
                    // also carries where the notes are kept, and notes already
                    // on the device need no remote at all.
                    enabled = !malformed && !pathRefused,
                    modifier = Modifier.testTag("settings-save"),
                ) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
    }
}

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
