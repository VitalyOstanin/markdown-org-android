package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.vitalyostanin.markdownorg.R

// The settings screen is one column of everything a collection is set up by,
// and it is longer than a screen several times over. What is typed here keeps
// the items that answer to it and drops the rest, headings included: the
// reader who knows the word is spared the scroll, and the reader who does not
// sees the screen unchanged, because an empty query matches everything.

/**
 * The stretch of the settings screen an item stands in.
 *
 * A part carries the heading drawn above it, or none where the items stand
 * under no heading of their own. The heading is searched like any other text:
 * a query naming it carries the whole part, since asking for "reminders" is
 * asking for all of them rather than for one line.
 */
internal enum class SettingsPart(@StringRes val title: Int?) {
    COLLECTIONS(R.string.settings_collections),
    REMOTE(null),
    SSH(R.string.settings_ssh),
    NOTES(null),
    AGENDA(R.string.settings_agenda),
    REMINDERS(R.string.settings_reminders),
    DIAGNOSTICS(null),
}

/**
 * One item of the settings screen, as the search knows it.
 *
 * [texts] holds what the reader sees of it: its label first, then the lines
 * under it and the labels of the chips it offers. A word of any of them names
 * the item — the setting is remembered by what it does at least as often as by
 * what it is called.
 */
@Immutable
internal data class SettingsEntry(val tag: String, val part: SettingsPart, val texts: List<Int>)

private fun entry(tag: String, part: SettingsPart, @StringRes vararg texts: Int) =
    SettingsEntry(tag, part, texts.toList())

/**
 * Every item the screen draws, in the order it draws them.
 *
 * A list apart from the composables rather than a property of each: a heading
 * is drawn before the items under it, so whether anything inside a part
 * matched has to be answerable before that part starts drawing.
 *
 * An item added to the screen and not added here is one the search never
 * offers. `SettingsSearchTest` holds the catalogue to what it can promise —
 * every item reachable by its own label, every part holding items — and
 * `SettingsSearchScreenTest` checks the tags against the screen itself.
 */
internal val settingsCatalogue: List<SettingsEntry> = listOf(
    entry(
        "settings-collections",
        SettingsPart.COLLECTIONS,
        R.string.settings_collections,
        R.string.settings_collection_add,
    ),
    entry(
        "settings-collection-name",
        SettingsPart.COLLECTIONS,
        R.string.settings_collection_name,
        R.string.settings_collection_name_hint,
    ),
    entry("settings-keep-local", SettingsPart.COLLECTIONS, R.string.settings_keep_local),
    entry(
        "settings-collection-remove",
        SettingsPart.COLLECTIONS,
        R.string.settings_collection_remove,
        R.string.settings_collection_remove_explain,
    ),
    entry(
        "settings-url",
        SettingsPart.REMOTE,
        R.string.settings_url,
        R.string.settings_url_default,
    ),
    entry(
        "settings-branch",
        SettingsPart.REMOTE,
        R.string.settings_branch,
        R.string.settings_branch_default,
    ),
    entry(
        "settings-token",
        SettingsPart.REMOTE,
        R.string.settings_token,
        R.string.settings_token_kept,
        R.string.settings_token_none,
    ),
    entry("settings-token-page", SettingsPart.REMOTE, R.string.settings_token_page),
    entry("settings-token-drop", SettingsPart.REMOTE, R.string.settings_token_drop),
    entry(
        "settings-ssh-key",
        SettingsPart.SSH,
        R.string.settings_ssh_key,
        R.string.settings_ssh_key_kept,
        R.string.settings_ssh_key_none,
    ),
    entry(
        "settings-ssh-passphrase",
        SettingsPart.SSH,
        R.string.settings_ssh_passphrase,
        R.string.settings_ssh_passphrase_hint,
    ),
    entry("settings-ssh-key-drop", SettingsPart.SSH, R.string.settings_ssh_key_drop),
    entry("settings-ssh-create", SettingsPart.SSH, R.string.settings_ssh_create),
    entry(
        "settings-ssh-public",
        SettingsPart.SSH,
        R.string.settings_ssh_copy,
        R.string.settings_ssh_key_page,
        R.string.settings_ssh_host,
    ),
    entry(
        "settings-notes",
        SettingsPart.NOTES,
        R.string.settings_notes,
        R.string.settings_notes_default,
    ),
    entry("settings-notes-pick", SettingsPart.NOTES, R.string.settings_notes_pick),
    entry("settings-notes-grant", SettingsPart.NOTES, R.string.settings_notes_grant),
    entry(
        "settings-inbox",
        SettingsPart.NOTES,
        R.string.settings_inbox,
        R.string.settings_inbox_support,
    ),
    entry(
        "settings-write-at",
        SettingsPart.NOTES,
        R.string.settings_write_at,
        R.string.settings_write_at_support,
        R.string.settings_write_at_start,
        R.string.settings_write_at_end,
    ),
    entry(
        "settings-main-file",
        SettingsPart.NOTES,
        R.string.settings_main,
        R.string.settings_main_support,
    ),
    entry(
        "settings-agenda-grouped",
        SettingsPart.AGENDA,
        R.string.settings_agenda_grouped,
        R.string.settings_agenda_grouped_hint,
    ),
    entry(
        "settings-agenda-month-grid",
        SettingsPart.AGENDA,
        R.string.settings_agenda_month_grid,
        R.string.settings_agenda_month_grid_hint,
    ),
    entry(
        "settings-week-start",
        SettingsPart.AGENDA,
        R.string.settings_week_start,
        R.string.settings_week_start_hint,
        R.string.settings_week_start_auto,
        R.string.settings_week_start_monday,
        R.string.settings_week_start_sunday,
    ),
    entry(
        "settings-reminders-enabled",
        SettingsPart.REMINDERS,
        R.string.settings_reminders_enabled,
        R.string.settings_reminders_enabled_hint,
    ),
    entry(
        "settings-reminders-lead",
        SettingsPart.REMINDERS,
        R.string.settings_reminders_lead,
        R.string.settings_reminders_lead_hint,
        R.string.settings_reminders_lead_none,
        R.string.settings_reminders_lead_hour,
    ),
    entry(
        "settings-reminders-at-start",
        SettingsPart.REMINDERS,
        R.string.settings_reminders_at_start,
        R.string.settings_reminders_at_start_hint,
    ),
    entry(
        "settings-reminders-digest",
        SettingsPart.REMINDERS,
        R.string.settings_reminders_digest,
        R.string.settings_reminders_digest_hint,
    ),
    entry("settings-licences", SettingsPart.DIAGNOSTICS, R.string.settings_licences),
    entry(
        "settings-crash",
        SettingsPart.DIAGNOSTICS,
        R.string.settings_crash,
        R.string.settings_crash_forget,
    ),
    entry("settings-version", SettingsPart.DIAGNOSTICS, R.string.settings_version),
)

/**
 * What a typed query leaves on the screen.
 *
 * Everything, while nothing is typed: the search is a filter over the screen
 * rather than a second screen, so the reader who never uses it sees what was
 * always there.
 */
@Immutable
internal class SettingsMatch private constructor(
    private val searching: Boolean,
    private val items: Set<String>,
    private val parts: Set<SettingsPart>,
) {
    /** Whether the reader is looking for something rather than reading the screen. */
    val filtering: Boolean get() = searching

    /** Whether the query named nothing at all, which is worth saying in words. */
    val nothingFound: Boolean get() = searching && items.isEmpty()

    fun shows(tag: String): Boolean = !searching || tag in items

    fun shows(part: SettingsPart): Boolean = !searching || part in parts

    companion object {
        /** The whole screen, as it stands when nothing is typed. */
        val everything = SettingsMatch(searching = false, items = emptySet(), parts = emptySet())

        internal fun of(items: List<SettingsEntry>) =
            SettingsMatch(true, items.map { it.tag }.toSet(), items.map { it.part }.toSet())
    }
}

/**
 * The text of a query and of a label, compared as a person would read them.
 *
 * Case is dropped, and so is the difference between `ё` and `е`: the letter is
 * written both ways in Russian, and which way a resource spells it is not
 * something to have to guess at while typing.
 */
private fun folded(text: String) = text.lowercase().replace('ё', 'е')

/**
 * Which items of the screen a query names.
 *
 * [text] reads a string resource — the screen passes the resources of the
 * device, so both languages of the interface are searched in the words they
 * are shown in, with no separate list of search terms to keep in step.
 */
internal fun settingsMatch(query: String, text: (Int) -> String): SettingsMatch {
    val needle = folded(query.trim())
    if (needle.isEmpty()) {
        return SettingsMatch.everything
    }

    val wholeParts = SettingsPart.entries.filter { part ->
        part.title?.let { folded(text(it)).contains(needle) } == true
    }.toSet()

    return SettingsMatch.of(
        settingsCatalogue.filter { item ->
            item.part in wholeParts || item.texts.any { folded(text(it)).contains(needle) }
        },
    )
}

/** The match for a query, over the strings of the device this runs on. */
@Composable
internal fun rememberSettingsMatch(query: String): SettingsMatch {
    // The resources of the composition rather than of the application: the
    // language the screen is drawn in is the language it is searched in.
    val resources = LocalResources.current
    return remember(query, resources) { settingsMatch(query) { resources.getString(it) } }
}

/**
 * What the search left of the screen, for everything drawn under it.
 *
 * Ambient rather than a parameter threaded through every section: the filter
 * is one answer the whole screen reads, and passing it down by hand would add
 * an argument to sections that already carry a dozen — and to the sections
 * nested inside those. Everything, outside the settings screen, so a section
 * drawn on its own in a test is a section with nothing filtered out.
 */
internal val LocalSettingsMatch = compositionLocalOf { SettingsMatch.everything }

/**
 * An item of the screen, drawn while the query names it.
 *
 * A wrapper rather than a flag on each control: what is filtered is whole
 * items — a field with the line under it, a tick with its explanation — and
 * the alternative is the same condition written twice at both ends of one.
 */
@Composable
internal fun Found(tag: String, content: @Composable () -> Unit) {
    if (LocalSettingsMatch.current.shows(tag)) {
        content()
    }
}

/** Whether the query left anything of [part], heading included. */
@Composable
internal fun found(part: SettingsPart): Boolean = LocalSettingsMatch.current.shows(part)

/** Whether the reader is looking for something rather than reading the screen. */
@Composable
internal fun filtering(): Boolean = LocalSettingsMatch.current.filtering

/**
 * Where a setting is looked for by name.
 *
 * Above the scrolling column rather than inside it: the query is edited after
 * reading what it found, and a field that scrolled away with the screen would
 * have to be scrolled back to.
 */
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.settings_search)) },
        singleLine = true,
        trailingIcon = {
            // Only while there is something to clear: an empty field with a
            // button that empties it says the button does something else.
            if (query.isNotEmpty()) {
                TextButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.testTag("settings-search-clear"),
                ) {
                    Text(stringResource(R.string.settings_search_clear))
                }
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth().testTag("settings-search"),
    )
}
