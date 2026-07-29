package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.RemoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.remoteUrlProblem
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing

/**
 * Where the notes are fetched from.
 *
 * Three fields and nothing else: the repository, the branch, and a token. The
 * device-flow sign-in will fill the last one in without typing, but a token
 * pasted by hand is the only path that works for a server other than GitHub,
 * so the field stays either way.
 */
@Composable
fun SyncSettingsScreen(
    initialUrl: String,
    initialBranch: String,
    hasToken: Boolean,
    onSave: (url: String, branch: String, token: String, dropToken: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var branch by remember { mutableStateOf(initialBranch) }
    var token by remember { mutableStateOf("") }
    var dropToken by remember { mutableStateOf(false) }

    // Saving empties the working copy, and edits made here are committed
    // locally and never pushed — so an address that cannot work is caught in
    // the field rather than after the clone has failed over an empty
    // directory. An empty field is not an error yet, only a disabled button.
    val problem = remember(url) { remoteUrlProblem(url) }
    val malformed = problem != null && problem != RemoteUrlProblem.EMPTY

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
                    onClick = { onSave(url, branch, token, dropToken) },
                    enabled = problem == null,
                    modifier = Modifier.testTag("settings-save"),
                ) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
    }
}
