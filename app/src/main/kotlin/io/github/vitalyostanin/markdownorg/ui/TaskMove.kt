package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.Task

/**
 * What the sheet offers about where the entry is kept.
 *
 * Two ways of asking the same thing, and they are separate because of what
 * they cost the reader. The file a collection calls its main one is one tap —
 * the answer for a note written into the receiving file and worth keeping.
 * Any other file is two, a list and a choice, because there is nothing to
 * guess at.
 *
 * Both are absent where they would do nothing: an entry already in the main
 * file is not offered a move into it, and a collection of one file has nowhere
 * to move anything.
 */
@Composable
internal fun TaskMove(task: Task, targets: MoveTargets, onAction: (TaskAction) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }

    val main = targets.mainFile?.takeUnless { it == task.file }
    val elsewhere = targets.files.filterNot { it == task.file }

    if (main != null) {
        SheetButton(
            label = stringResource(R.string.action_move_to_main, main),
            tag = "action-move-main",
            hint = stringResource(R.string.hint_action_move_to_main),
        ) { onAction(TaskAction.MoveToFile(main)) }
    }

    if (elsewhere.isNotEmpty()) {
        SheetButton(
            label = stringResource(R.string.action_move_to_file),
            tag = "action-move-file",
            hint = stringResource(R.string.hint_action_move_to_file),
        ) { picking = true }
    }

    if (picking) {
        FileChoice(
            files = elsewhere,
            onDismiss = { picking = false },
            onPicked = { file ->
                picking = false
                onAction(TaskAction.MoveToFile(file))
            },
        )
    }
}

/**
 * Which file of the collection the entry goes to.
 *
 * The files as the notes hold them — a path relative to the collection's
 * directory, in the monospaced face the sheet writes a file in — rather than
 * titles read out of them: what a move has to be sure of is the file, and two
 * notes headed the same way are told apart by nothing else.
 *
 * The list scrolls inside a bounded height. A collection of a hundred notes is
 * ordinary, and a dialog as tall as the list would push its own dismissal off
 * the screen.
 */
@Composable
private fun FileChoice(files: List<String>, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_move_to_file)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = Sizes.fileList)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                for (file in files) {
                    TextButton(
                        onClick = { onPicked(file) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("move-to-$file"),
                    ) {
                        Text(
                            text = file,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("move-dismiss")) {
                Text(stringResource(R.string.move_dismiss))
            }
        },
    )
}
