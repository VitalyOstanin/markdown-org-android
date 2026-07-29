package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.Component
import io.github.vitalyostanin.markdownorg.core.LicenceGroup
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing

/**
 * What the application carries besides its own sources, and under what terms.
 *
 * The APK is published as a release, which makes it a distribution of every
 * library compiled into it: Apache-2.0 asks for its text to travel along,
 * MPL-2.0 asks that the recipient be told where the source is, and libgit2 is
 * GPL-2.0 with a linking exception that is worth stating rather than leaving
 * to be discovered. A repository is not where a reader of an installed
 * application looks, so the list is here.
 */
@Composable
fun LicensesScreen(
    catalog: List<LicenceGroup>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.licences_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("licences-close")) {
                    Text(stringResource(R.string.licences_close))
                }
            }

            if (catalog.isEmpty()) {
                Text(
                    text = stringResource(R.string.licences_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.lg)
                        .testTag("licences-unavailable"),
                )
                return@Column
            }

            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.gutter,
                    end = Spacing.gutter,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(catalog, key = { it.id + it.text.take(TEXT_KEY_LENGTH) }) { group ->
                    LicenceCard(group)
                }
            }
        }
    }
}

/**
 * One licence, folded.
 *
 * The texts run to eleven kilobytes each and there are dozens of them; opened
 * all at once the list is unreadable, and what a reader usually wants is the
 * name of the component and the licence it is under.
 */
@Composable
private fun LicenceCard(group: LicenceGroup) {
    var open by rememberSaveable(group.id + group.text.take(TEXT_KEY_LENGTH)) {
        mutableStateOf(false)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open },
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = group.id,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = group.usedBy.joinToString(", ", transform = Component::stated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                open && group.text.isNotEmpty() -> Text(
                    text = group.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Spacing.sm),
                )

                // A licence nobody collected a text for still has somewhere to
                // be read; the link is what the artifact itself stated.
                open && group.url.isNotEmpty() -> Text(
                    text = group.url,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }
}

/** A component as it is written in the list: what it is and which version. */
private fun Component.stated(): String = "$name $version"

/**
 * Enough of the text to tell two entries of the same licence apart, without
 * carrying eleven kilobytes in a key.
 */
private const val TEXT_KEY_LENGTH = 64
