package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import java.time.LocalTime

/**
 * Whether the reader is told what is coming, and on what terms.
 *
 * The terms are hidden until the switch is on: they mean nothing while nothing
 * is announced, and this screen is long enough already.
 *
 * The two notices at the top are the platform's refusals rather than the
 * reader's choices. Notifications may be refused and exact alarms disallowed,
 * and neither stops the application — a refused notification leaves the plan
 * standing and unseen, a disallowed exact alarm turns a meeting announced at a
 * quarter to into one announced within the hour. Both are stated where the
 * choices they undo are made, with the screen that grants them one press away.
 */
@Composable
internal fun RemindersSection(reminders: RemindersUi) {
    Text(
        text = stringResource(R.string.settings_reminders),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    SettingCheck(
        checked = reminders.enabled,
        onCheckedChange = reminders.onEnabledChange,
        tag = "settings-reminders-enabled",
        label = R.string.settings_reminders_enabled,
        hint = R.string.hint_settings_reminders_enabled,
        explanation = R.string.settings_reminders_enabled_hint,
    )
    if (!reminders.enabled) {
        return
    }
    if (reminders.needsNotifications) {
        AccessNotice(
            notice = R.string.settings_reminders_no_notifications,
            action = R.string.settings_reminders_grant,
            hint = R.string.hint_settings_reminders_grant,
            tag = "settings-reminders-grant",
            onClick = reminders.onGrantNotifications,
        )
    }
    if (reminders.needsExactAlarms) {
        AccessNotice(
            notice = R.string.settings_reminders_no_exact_alarms,
            action = R.string.settings_reminders_allow_exact,
            hint = R.string.hint_settings_reminders_allow_exact,
            tag = "settings-reminders-allow-exact",
            onClick = reminders.onAllowExactAlarms,
        )
    }
    LeadChoice(reminders.lead, reminders.onLeadChange)
    SettingCheck(
        checked = reminders.alsoAtStart,
        onCheckedChange = reminders.onAlsoAtStartChange,
        tag = "settings-reminders-at-start",
        label = R.string.settings_reminders_at_start,
        hint = R.string.hint_settings_reminders_at_start,
        explanation = R.string.settings_reminders_at_start_hint,
    )
    DigestChoice(reminders.digestAt, reminders.onDigestChange)
}

/**
 * How long before a timed entry it is announced: five chips, because the
 * answer is a habit rather than a number worth typing.
 *
 * The row wraps: five chips of translated words do not fit one line of the
 * narrowest screen, and a choice past the edge of a row that scrolls is one
 * the reader never learns is there. Wrapping spends a second line instead,
 * which this screen scrolls through anyway.
 */
@Composable
internal fun LeadChoice(current: ReminderLead, onChange: (ReminderLead) -> Unit) {
    HintTooltip(stringResource(R.string.hint_settings_reminders_lead)) {
        Text(
            text = stringResource(R.string.settings_reminders_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings-reminders-lead"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        ReminderLead.entries.forEach { option ->
            FilterChip(
                selected = option == current,
                onClick = { onChange(option) },
                label = { Text(stringResource(option.labelRes)) },
                modifier = Modifier.testTag(option.testTag),
            )
        }
    }
    Text(
        text = stringResource(R.string.settings_reminders_lead_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The hour the day's digest is raised at, picked in the platform's own dial.
 *
 * A button showing the hour rather than a field: what is being answered is a
 * time of day, and the dial is what every other application on the phone asks
 * it with.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DigestChoice(current: LocalTime, onChange: (LocalTime) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    // The hour is written and asked for the way the agenda writes its own:
    // a device set to a 12-hour clock reads `09:00` as neither morning nor
    // evening, and a dial in the other convention beside it is worse still.
    val use24Hour = use24Hour()
    val locale = LocalLocale.current.platformLocale

    HintTooltip(stringResource(R.string.hint_settings_reminders_digest)) {
        Text(
            text = stringResource(R.string.settings_reminders_digest),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    OutlinedButton(
        onClick = { picking = true },
        modifier = Modifier.testTag("settings-reminders-digest"),
    ) {
        Text(timeLabel(current, locale, use24Hour))
    }
    Text(
        text = stringResource(R.string.settings_reminders_digest_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (picking) {
        val state = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = use24Hour,
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picking = false
                        onChange(LocalTime.of(state.hour, state.minute))
                    },
                    modifier = Modifier.testTag("settings-reminders-digest-confirm"),
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = { TimePicker(state = state) },
        )
    }
}

/** A refusal of the platform's, and the screen that takes it back. */
@Composable
private fun AccessNotice(
    @StringRes notice: Int,
    @StringRes action: Int,
    @StringRes hint: Int,
    tag: String,
    onClick: () -> Unit,
) {
    Text(
        text = stringResource(notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    HintTooltip(stringResource(hint)) {
        OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
            Text(stringResource(action))
        }
    }
}

/**
 * One tick of a settings section: the box, its label and the line under it.
 *
 * Shared by this section and the agenda one — the two draw the same control,
 * and a second copy of it would drift the moment either grew a tooltip the
 * other did not.
 */
@Composable
internal fun SettingCheck(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    @StringRes label: Int,
    @StringRes hint: Int,
    @StringRes explanation: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
        )
        // On the label rather than on the box, as with the other ticks here.
        HintTooltip(stringResource(hint)) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    Text(
        text = stringResource(explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
