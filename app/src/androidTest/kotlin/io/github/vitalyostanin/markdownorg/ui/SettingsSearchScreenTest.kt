package io.github.vitalyostanin.markdownorg.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The settings screen, searched by name.
 *
 * The screen is one column of everything a collection is set up by, several
 * screenfuls long. What is typed at its head keeps the items that answer to it
 * and drops the rest; an empty field is the screen as it always was.
 */
class SettingsSearchScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nothingIsHiddenUntilSomethingIsTyped() {
        show()

        compose.onNodeWithTag("settings-url").assertExists()
        compose.onNodeWithTag("settings-inbox").assertExists()
        compose.onNodeWithTag("settings-reminders-enabled").assertExists()
    }

    @Test
    fun whatIsTypedLeavesOnlyWhatAnswersToIt() {
        show()

        search(string(R.string.settings_inbox))

        compose.onNodeWithTag("settings-inbox").assertExists()
        compose.onNodeWithTag("settings-url").assertDoesNotExist()
        compose.onNodeWithTag("settings-reminders-enabled").assertDoesNotExist()
    }

    @Test
    fun theLineUnderASettingFindsItAsWell() {
        // The reader remembers what a setting does rather than what it is
        // called, and the line under it is where that is written.
        show()

        search(string(R.string.settings_week_start_hint))

        compose.onNodeWithTag("settings-week-start").assertExists()
        compose.onNodeWithTag("settings-url").assertDoesNotExist()
    }

    @Test
    fun aHeadingCarriesEverythingUnderIt() {
        show()

        search(string(R.string.settings_reminders))

        compose.onNodeWithTag("settings-reminders-enabled").assertExists()
        compose.onNodeWithTag("settings-url").assertDoesNotExist()
    }

    @Test
    fun aSettingBehindTheFoldIsFoundWithoutOpeningIt() {
        // The SSH section is folded away under its heading, and a query that
        // named a field inside it is not answered by the heading alone.
        show()

        search(string(R.string.settings_ssh_passphrase))

        compose.onNodeWithTag("settings-ssh-passphrase").assertExists()
    }

    @Test
    fun aQueryNothingAnswersToSaysSo() {
        show()

        search("zzzz")

        compose.onNodeWithTag("settings-search-empty").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-url").assertDoesNotExist()
    }

    @Test
    fun clearingTheFieldBringsTheScreenBack() {
        show()

        search("zzzz")
        compose.onNodeWithTag("settings-search-clear").performClick()

        compose.onNodeWithTag("settings-url").assertExists()
        compose.onNodeWithTag("settings-search-empty").assertDoesNotExist()
    }

    @Test
    fun theQueryIsReadInTheLanguageTheScreenIsDrawnIn() {
        // The catalogue names string resources rather than words, so a screen
        // drawn in Russian is searched in Russian with nothing said twice.
        show(language = "ru")

        search("недел")

        compose.onNodeWithTag("settings-week-start").assertExists()
        compose.onNodeWithTag("settings-url").assertDoesNotExist()
    }

    @Test
    fun theFormIsStillSavedFromUnderAQuery() {
        // The fields a query hid are the fields of a form that is still whole:
        // what was typed into them is state rather than what is on the screen.
        show()

        search(string(R.string.settings_inbox))
        compose.onNodeWithTag("settings-inbox").performTextReplacement("work.md")
        compose.onNodeWithTag("settings-save").performScrollTo().performClick()

        assertEquals("work.md", saved)
    }

    @Test
    fun everyItemTheScreenDrawsIsFoundByItsOwnLabel() {
        // What holds the catalogue to the screen: a tag renamed on one side
        // and not the other leaves an item that is drawn and cannot be found,
        // which nothing else would report. Only the items this screen draws as
        // it stands — the rest wait on a token, a key or a crash log.
        show()
        val drawn = settingsCatalogue.filter { item ->
            compose.onAllNodesWithTag(item.tag).fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue("the screen drew ${drawn.size} of the catalogue", drawn.size > 5)
        for (item in drawn) {
            search(string(item.texts.first()))

            compose.onNodeWithTag(item.tag).assertExists()
        }
    }

    private var saved: String? = null

    private fun search(text: String) {
        compose.onNodeWithTag("settings-search").performTextReplacement(text)
    }

    private fun show(language: String? = null) {
        compose.setContent {
            val context = LocalContext.current
            val speaking = language?.let {
                val spoken = Configuration(LocalConfiguration.current).apply {
                    setLocale(Locale.forLanguageTag(it))
                }
                context.createConfigurationContext(spoken)
            } ?: context

            CompositionLocalProvider(
                LocalContext provides speaking,
                LocalResources provides speaking.resources,
            ) {
                MarkdownOrgTheme {
                    SyncSettingsScreen(
                        initial = SettingsInitial(),
                        onSave = { values -> saved = values.inbox },
                        onDismiss = {},
                    )
                }
            }
        }
    }

    private fun string(id: Int): String = compose.activity.getString(id)
}
