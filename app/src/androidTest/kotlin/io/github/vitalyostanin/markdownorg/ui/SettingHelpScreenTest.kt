package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Rule
import org.junit.Test

/**
 * What a setting is for, read from the mark beside its name.
 *
 * The explanation used to be a tooltip held open by a long press: a gesture
 * nothing announced, on a line of text sized for a glance. The mark says there
 * is something to read, and the screen behind it has the room to say what the
 * setting does, why the answer matters and one case where it does.
 *
 * The form stays composed underneath: the fields are typed into and not yet
 * saved, and a screen that took their place would take what was typed with it.
 */
class SettingHelpScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theMarkOpensWhatTheSettingIsFor() {
        show()

        compose.onNodeWithTag("settings-inbox-help").performScrollTo().performClick()

        compose.onNodeWithTag("setting-help-title").assertExists()
        compose.onNodeWithTag("setting-help-what").assertExists()
        compose.onNodeWithTag("setting-help-why").assertExists()
        compose.onNodeWithTag("setting-help-example")
            .assertTextEquals(string(R.string.help_inbox_example))
    }

    @Test
    fun theScreenNamesTheSettingItWasOpenedFrom() {
        show()

        compose.onNodeWithTag("settings-url-help").performScrollTo().performClick()

        // By tag rather than by text: the form stays composed under the
        // screen, so the name of the setting is on the screen twice — as the
        // title here and as the label of the field it was opened from.
        compose.onNodeWithTag("setting-help-title").assertTextEquals(string(R.string.settings_url))
        compose.onNodeWithTag("setting-help-why").assertTextEquals(string(R.string.help_url_why))
    }

    @Test
    fun closingItComesBackToTheForm() {
        show()

        compose.onNodeWithTag("settings-inbox-help").performScrollTo().performClick()
        compose.onNodeWithTag("setting-help-close").performClick()

        compose.onNodeWithTag("setting-help-title").assertDoesNotExist()
        compose.onNodeWithTag("settings-inbox").assertExists()
    }

    @Test
    fun whatWasTypedIntoTheFormOutlivesTheReading() {
        // The reason the screen is drawn over the form rather than in place of
        // it: an address half typed is not something to type again because the
        // reader stopped to ask what the field below it is for.
        show()

        compose.onNodeWithTag("settings-url").performScrollTo()
            .performTextReplacement("https://gitlab.com/user/notes.git")
        compose.onNodeWithTag("settings-url-help").performScrollTo().performClick()
        compose.onNodeWithTag("setting-help-close").performClick()

        compose.onNodeWithText("https://gitlab.com/user/notes.git").assertExists()
    }

    @Test
    fun aLongNameLeavesTheWayBackItsWidth() {
        // "Разбивать день на группы" took the whole row and left the button one
        // letter wide, which broke the word it carries down the screen. Drawn
        // here in a narrow box rather than on the screen of whatever device
        // runs this, so that the width the name has to share is the same
        // everywhere.
        compose.setContent {
            MarkdownOrgTheme {
                Box(modifier = Modifier.width(300.dp)) {
                    SettingHelpScreen(
                        help = settingHelp.getValue("settings-agenda-grouped"),
                        onDismiss = {},
                    )
                }
            }
        }

        // 48 dp is the smallest a control is allowed to be to stay tappable;
        // the squeezed button measured 0 dp, and one holding its word measures
        // its word plus padding — 58 dp for "Back".
        compose.onNodeWithTag("setting-help-close").assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun aSettingThatAnswersForItselfCarriesNoMark() {
        // Which weekday a week starts on is not a question three paragraphs
        // improve on, and a mark that opened a screen saying so would be a
        // promise the screen does not keep.
        show()

        compose.onNodeWithTag("settings-week-start").performScrollTo()
        compose.onNodeWithTag("settings-week-start-help").assertDoesNotExist()
    }

    private fun show() {
        compose.setContent {
            MarkdownOrgTheme {
                SyncSettingsScreen(
                    initial = SettingsInitial(),
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun string(id: Int): String = compose.activity.getString(id)
}
