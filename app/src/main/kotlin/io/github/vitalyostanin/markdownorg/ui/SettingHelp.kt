package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import io.github.vitalyostanin.markdownorg.R

// A setting used to explain itself in a tooltip held open by a long press: a
// gesture nothing on the screen announces, on a line of text sized for a
// glance. What a setting decides is worth more room than that — what it does,
// why the answer matters, and one case where it does — so the explanation
// moved to a screen of its own, reached by a mark beside the label.

/**
 * What a setting says about itself, in the order the screen reads it out.
 *
 * [what] is the line the tooltip used to carry — what changes when the setting
 * is set the other way. [why] says what the answer decides for the person
 * setting it, and [example] is one case told with names and numbers rather
 * than in the abstract, because a setting is understood from a case far
 * sooner than from its definition.
 */
@Immutable
internal data class SettingHelp(
    val tag: String,
    @StringRes val title: Int,
    @StringRes val what: Int,
    @StringRes val why: Int,
    @StringRes val example: Int,
) {

    /** Everything the screen shows, for the search to read as one item's text. */
    val texts: List<Int> get() = listOf(title, what, why, example)
}

private fun help(
    tag: String,
    @StringRes title: Int,
    @StringRes what: Int,
    @StringRes why: Int,
    @StringRes example: Int,
) = SettingHelp(tag, title, what, why, example)

/**
 * The explanation of every item the settings screen draws, by its tag.
 *
 * Keyed by the same tags [settingsCatalogue] uses, so an item is explained and
 * searched under one name. An item with no entry here shows no mark beside its
 * label: a setting whose name answers the question — where the notes directory
 * is picked, which weekday a week starts on — is left unexplained rather than
 * given three paragraphs saying what its own label already says.
 *
 * Where the tooltip already carried both what a setting does and why, both are
 * kept and only the case is new; where it carried one line or none, the rest is
 * written here. `SettingHelpTest` holds this list to the catalogue, so a tag
 * that stops existing is a failing test rather than a mark that opens nothing.
 */
internal val settingHelp: Map<String, SettingHelp> = listOf(
    help(
        "settings-collections",
        R.string.settings_collections,
        R.string.hint_settings_collection,
        R.string.help_collections_why,
        R.string.help_collections_example,
    ),
    help(
        "settings-keep-local",
        R.string.settings_keep_local,
        R.string.hint_settings_keep_local,
        R.string.help_keep_local_why,
        R.string.help_keep_local_example,
    ),
    help(
        "settings-url",
        R.string.settings_url,
        R.string.help_url_what,
        R.string.help_url_why,
        R.string.help_url_example,
    ),
    help(
        "settings-branch",
        R.string.settings_branch,
        R.string.help_branch_what,
        R.string.help_branch_why,
        R.string.help_branch_example,
    ),
    help(
        "settings-token",
        R.string.settings_token,
        R.string.help_token_what,
        R.string.help_token_why,
        R.string.help_token_example,
    ),
    help(
        "settings-ssh-key",
        R.string.settings_ssh_key,
        R.string.help_ssh_key_what,
        R.string.help_ssh_key_why,
        R.string.help_ssh_key_example,
    ),
    help(
        "settings-ssh-passphrase",
        R.string.settings_ssh_passphrase,
        R.string.help_ssh_passphrase_what,
        R.string.help_ssh_passphrase_why,
        R.string.help_ssh_passphrase_example,
    ),
    help(
        "settings-ssh-public",
        R.string.settings_ssh_copy,
        R.string.hint_settings_ssh_copy,
        R.string.help_ssh_public_why,
        R.string.help_ssh_public_example,
    ),
    help(
        "settings-notes",
        R.string.settings_notes,
        R.string.help_notes_what,
        R.string.help_notes_why,
        R.string.help_notes_example,
    ),
    help(
        "settings-notes-grant",
        R.string.settings_notes_grant,
        R.string.hint_settings_notes_grant,
        R.string.help_notes_grant_why,
        R.string.help_notes_grant_example,
    ),
    help(
        "settings-inbox",
        R.string.settings_inbox,
        R.string.settings_inbox_support,
        R.string.help_inbox_why,
        R.string.help_inbox_example,
    ),
    help(
        "settings-write-at",
        R.string.settings_write_at,
        R.string.help_write_at_what,
        R.string.help_write_at_why,
        R.string.help_write_at_example,
    ),
    help(
        "settings-main-file",
        R.string.settings_main,
        R.string.settings_main_support,
        R.string.help_main_file_why,
        R.string.help_main_file_example,
    ),
    help(
        "settings-agenda-grouped",
        R.string.settings_agenda_grouped,
        R.string.settings_agenda_grouped_hint,
        R.string.hint_settings_agenda_grouped,
        R.string.help_agenda_grouped_example,
    ),
    help(
        "settings-agenda-month-grid",
        R.string.settings_agenda_month_grid,
        R.string.settings_agenda_month_grid_hint,
        R.string.hint_settings_agenda_month_grid,
        R.string.help_agenda_month_grid_example,
    ),
    help(
        "settings-reminders-enabled",
        R.string.settings_reminders_enabled,
        R.string.settings_reminders_enabled_hint,
        R.string.hint_settings_reminders_enabled,
        R.string.help_reminders_enabled_example,
    ),
    help(
        "settings-reminders-lead",
        R.string.settings_reminders_lead,
        R.string.settings_reminders_lead_hint,
        R.string.hint_settings_reminders_lead,
        R.string.help_reminders_lead_example,
    ),
    help(
        "settings-reminders-at-start",
        R.string.settings_reminders_at_start,
        R.string.settings_reminders_at_start_hint,
        R.string.hint_settings_reminders_at_start,
        R.string.help_reminders_at_start_example,
    ),
    help(
        "settings-reminders-digest",
        R.string.settings_reminders_digest,
        R.string.settings_reminders_digest_hint,
        R.string.hint_settings_reminders_digest,
        R.string.help_reminders_digest_example,
    ),
    help(
        "settings-crash",
        R.string.settings_crash,
        R.string.hint_settings_crash,
        R.string.help_crash_why,
        R.string.help_crash_example,
    ),
).associateBy { it.tag }

/**
 * How a label opens the explanation behind it.
 *
 * A composition local rather than a parameter threaded through the screen:
 * the settings are one column of thirty items drawn by a dozen composables,
 * and the mark belongs to the label rather than to whoever draws it — the
 * same reason [LocalSettingsMatch] is one.
 */
internal val LocalSettingHelp = compositionLocalOf<(String) -> Unit> { {} }
