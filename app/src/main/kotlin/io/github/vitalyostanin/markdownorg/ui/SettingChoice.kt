package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes

/**
 * One setting whose answer is a single value out of a short list.
 *
 * A row of chips shows every answer at once and pays a line of the screen for
 * each one that does not fit the width — three of them in Russian took two
 * lines, and the settings are one column of thirty. A list that names the
 * answer in place and opens on a tap costs one line whatever the words are,
 * and the words themselves stay whole.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingChoice(
    current: T,
    options: List<T>,
    onChange: (T) -> Unit,
    tag: String,
    label: String,
    optionLabel: @Composable (T) -> String,
    optionTag: (T) -> String,
    /** What the label says when held, for a setting with no explanation of its own. */
    hint: String? = null,
) {
    var open by remember { mutableStateOf(false) }

    SettingLabel(label = label, tag = tag, hint = hint)
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = optionLabel(current),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .testTag(tag),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onChange(option)
                        open = false
                    },
                    modifier = Modifier.testTag(optionTag(option)),
                )
            }
        }
    }
}

/**
 * The name of a setting, with the way to what it is for beside it.
 *
 * A mark rather than a long press, for a setting that has a screen behind it:
 * the gesture was announced by nothing, and a column of thirty settings gave
 * no sign that any of them explained itself. The mark is drawn only where
 * something opens, so a mark on the screen is a promise rather than a guess.
 *
 * [hint] is for the settings left without that screen — the ones whose label
 * answers the question — and keeps the tooltip they already had. A setting has
 * one or the other and never both: two ways of asking the same question of the
 * same label is what this replaced.
 */
@Composable
internal fun SettingLabel(
    label: String,
    tag: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    val name = @Composable {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (settingHelp[tag] == null && hint != null) {
        HintTooltip(hint, modifier = modifier) { name() }
        return
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        name()
        SettingHelpMark(tag)
    }
}

/**
 * The mark that opens what a setting is for, or nothing where there is none.
 *
 * Drawn beside a label and inside a field alike — a field carries its name in
 * its own outline, and a second copy of that name above it to hang the mark on
 * would say everything twice.
 */
@Composable
internal fun SettingHelpMark(tag: String, modifier: Modifier = Modifier) {
    val open = LocalSettingHelp.current
    if (settingHelp[tag] == null) {
        return
    }

    IconButton(onClick = { open(tag) }, modifier = modifier.testTag("$tag-help")) {
        Icon(
            painter = painterResource(R.drawable.ic_help),
            contentDescription = stringResource(R.string.setting_help_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Sizes.helpMark),
        )
    }
}

/**
 * What a setting says about itself when its name is held.
 *
 * Two strings rather than one: [explanation] is the line that used to stand
 * under the control — what happens when it is set the other way — and [hint]
 * is why the answer matters. Left for the settings that are read at a glance;
 * the ones with a screen behind them read the same two texts from there.
 */
@Composable
internal fun settingHint(@StringRes explanation: Int, @StringRes hint: Int): String =
    stringResource(explanation) + "\n\n" + stringResource(hint)
