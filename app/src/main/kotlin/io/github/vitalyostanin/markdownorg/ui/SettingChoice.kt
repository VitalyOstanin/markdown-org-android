package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/**
 * One setting whose answer is a single value out of a short list.
 *
 * A row of chips shows every answer at once and pays a line of the screen for
 * each one that does not fit the width — three of them in Russian took two
 * lines, and the settings are one column of thirty. A list that names the
 * answer in place and opens on a tap costs one line whatever the words are,
 * and the words themselves stay whole.
 *
 * The label carries the tooltip, as the ticks around it do: what the setting
 * decides is read by holding its name, not by reading a line under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingChoice(
    current: T,
    options: List<T>,
    onChange: (T) -> Unit,
    tag: String,
    label: String,
    hint: String,
    optionLabel: @Composable (T) -> String,
    optionTag: (T) -> String,
) {
    var open by remember { mutableStateOf(false) }

    HintTooltip(hint) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
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
 * What a setting says about itself when its name is held.
 *
 * Two strings rather than one: [explanation] is the line that used to stand
 * under the control — what happens when it is set the other way — and [hint]
 * is why the answer matters. Both used to be written, one on the screen and
 * one in the tooltip; the screen is one column of thirty settings, so the
 * short one moved in with the long one rather than being thrown away.
 */
@Composable
internal fun settingHint(@StringRes explanation: Int, @StringRes hint: Int): String =
    stringResource(explanation) + "\n\n" + stringResource(hint)
