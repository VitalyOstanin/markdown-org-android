package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.MergedTag
import io.github.vitalyostanin.markdownorg.core.TagOrigin
import io.github.vitalyostanin.markdownorg.core.TagRole
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing

/**
 * The tag the agenda is narrowed to, and the way to change it.
 *
 * Left out entirely while no collection declares a tag: a control over an empty
 * vocabulary is a button that opens a menu with one entry meaning "as you
 * were".
 *
 * The button carries the name of the tag in force, so what is being filtered by
 * is readable without opening anything -- and the first entry of the menu is
 * the way back to the whole agenda, which is where the screen starts.
 */
@Composable
internal fun TagMenu(tags: List<MergedTag>, current: String?, onTagChange: (String?) -> Unit) {
    if (tags.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    var explaining by remember { mutableStateOf(false) }

    Box {
        HintTooltip(stringResource(R.string.hint_tag_menu)) {
            TextButton(
                onClick = { open = true },
                modifier = Modifier.testTag("tag-menu"),
            ) {
                Text(
                    text = current ?: stringResource(R.string.agenda_tag_none),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.agenda_tag_none)) },
                onClick = {
                    open = false
                    onTagChange(null)
                },
                modifier = Modifier.testTag("tag-none"),
            )
            tags.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(tag.name) },
                    onClick = {
                        open = false
                        onTagChange(tag.name)
                    },
                    modifier = Modifier.testTag("tag-${tag.name}"),
                )
            }
            HorizontalDivider()
            // The dictionary is merged from a file per collection, so the name
            // on a menu entry is the end of a story the user did not watch.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.agenda_tags_explain)) },
                onClick = {
                    open = false
                    explaining = true
                },
                modifier = Modifier.testTag("tag-explain"),
            )
        }
    }

    if (explaining) {
        TagDictionaryDialog(tags) { explaining = false }
    }
}

/**
 * What every tag takes, refuses, and who said so.
 *
 * The answer to "why is this note not here": a tag is merged out of what each
 * collection declared, and the merge is invisible on the screen the tag is
 * chosen from.
 */
@Composable
private fun TagDictionaryDialog(tags: List<MergedTag>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agenda_tags_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag("tag-dictionary"),
            ) {
                tags.forEach { tag ->
                    Text(text = tag.name, style = MaterialTheme.typography.titleSmall)
                    tag.origins.forEach { origin ->
                        Text(
                            text = stringResource(
                                R.string.agenda_tag_origin,
                                describe(origin),
                                origin.collection,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (tag.include.isNotEmpty() && tag.exclude.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.agenda_tag_exclusion_wins),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("tag-dictionary-close")) {
                Text(stringResource(R.string.agenda_tags_close))
            }
        },
    )
}

/** One declared pattern in words. */
@Composable
private fun describe(origin: TagOrigin): String = when (origin.role) {
    TagRole.INCLUDE -> if (origin.pattern.isEmpty()) {
        stringResource(R.string.agenda_tag_takes_every)
    } else {
        stringResource(R.string.agenda_tag_takes, origin.pattern)
    }

    TagRole.EXCLUDE -> stringResource(R.string.agenda_tag_keeps_out, origin.pattern)

    TagRole.REST -> stringResource(R.string.agenda_tag_takes_rest)
}
