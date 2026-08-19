package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.Task

/**
 * An entry opened for editing: the task it was reached through, and the text
 * the core read out of the file.
 *
 * The title is the heading as written, markup included — not the display text
 * the agenda shows, which would lose the markup on the first save.
 */
data class EntryDraft(val task: Task, val title: String, val body: String)

/**
 * The text of one entry, as a screen over the agenda.
 *
 * A screen rather than the sheet the other actions live in: this one is typed
 * into rather than tapped, and a sheet with the keyboard over it leaves the
 * body a line high. What it edits is bounded to the entry — the heading's own
 * title and the lines under it — for the reason the core states: the notes are
 * merged line by line, and an edit reaching past the entry turns an automatic
 * merge into a conflict.
 */
@Composable
fun EntryEditor(
    draft: EntryDraft,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // The platform's dialog width is meant for a question, not for a text
        // being edited.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Kept across a rotation rather than re-read, which is what the reader
        // expects of half-typed text. Saved state travels to the system in a
        // transaction bounded at about a megabyte, and this is what the caller's
        // limit on the length of an entry keeps it well inside.
        //
        // Keyed by the entry, so a different task opened after this one starts
        // from its own text rather than from what was typed into the last.
        val key = "${draft.task.file}:${draft.task.line}"
        var title by rememberSaveable(key) { mutableStateOf(draft.title) }
        var body by rememberSaveable(key) { mutableStateOf(draft.body) }

        Surface(modifier = modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    EntryEditorBar(
                        savable = title.isNotBlank(),
                        onSave = { onSave(title, body) },
                        onDismiss = onDismiss,
                    )
                },
            ) { padding ->
                EntryFields(
                    title = title,
                    onTitleChange = { title = it },
                    body = body,
                    onBodyChange = { body = it },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/** What the screen does: leave it, or write what is in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditorBar(savable: Boolean, onSave: () -> Unit, onDismiss: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.entry_title)) },
        navigationIcon = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("entry-cancel")) {
                Text(stringResource(R.string.entry_cancel))
            }
        },
        actions = {
            // A heading with no title is not a heading, and the core refuses
            // to write one; the button says so by being unavailable rather
            // than by failing afterwards.
            TextButton(
                onClick = onSave,
                enabled = savable,
                modifier = Modifier.testTag("entry-save"),
            ) {
                Text(stringResource(R.string.entry_save))
            }
        },
    )
}

/**
 * The two fields, one line and many.
 *
 * The body scrolls with the column rather than inside itself: a field that
 * scrolls within a scrolling parent takes the gesture away from it, and the
 * text here is an entry's worth rather than a file's.
 */
@Composable
private fun EntryFields(
    title: String,
    onTitleChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.entry_heading)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("entry-title"),
        )
        OutlinedTextField(
            value = body,
            onValueChange = onBodyChange,
            label = { Text(stringResource(R.string.entry_body)) },
            modifier = Modifier
                .fillMaxWidth()
                .withoutAutofill()
                .testTag("entry-body"),
        )
        Text(
            text = stringResource(R.string.entry_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
