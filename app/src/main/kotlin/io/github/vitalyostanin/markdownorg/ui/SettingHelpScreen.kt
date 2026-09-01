package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing

/**
 * What one setting is for, at the length the question deserves.
 *
 * Three paragraphs under the setting's own name: what it does, why the answer
 * matters, and one case where it does — told with names and numbers, because
 * a setting is understood from a case sooner than from a definition. The
 * screen is the whole of what a setting says about itself; the label beside
 * the control says nothing but its name.
 *
 * Reached from the mark beside the label and left by Back, which is why the
 * only control on it is the one that goes back: everything here is read, and
 * nothing is answered.
 */
@Composable
internal fun SettingHelpScreen(
    help: SettingHelp,
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
                // The name takes the width that is left rather than all of it:
                // a long one — "Разбивать день на группы" — pushed the button
                // to the width of a single letter and broke the word it
                // carries down the screen, one letter to a line.
                Text(
                    text = stringResource(help.title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = Spacing.sm)
                        .testTag("setting-help-title"),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("setting-help-close")) {
                    Text(stringResource(R.string.setting_help_close))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter)
                    .padding(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Paragraph(R.string.setting_help_what, help.what, "setting-help-what")
                Paragraph(R.string.setting_help_why, help.why, "setting-help-why")
                Paragraph(R.string.setting_help_example, help.example, "setting-help-example")
            }
        }
    }
}

/** One question about the setting and the answer to it, under a heading of its own. */
@Composable
private fun Paragraph(heading: Int, body: Int, tag: String) {
    Text(
        text = stringResource(heading),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(tag),
    )
}
