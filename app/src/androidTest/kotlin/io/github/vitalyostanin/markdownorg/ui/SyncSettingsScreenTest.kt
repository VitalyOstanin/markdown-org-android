package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The form that says where the notes come from. */
class SyncSettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var saved: Triple<String, String, String>? = null
    private var dismissed = false

    @Test
    fun whatWasTypedIsWhatGetsSaved() {
        showForm()

        compose.onNodeWithTag("settings-url")
            .performTextInput("https://example.org/notes.git")
        compose.onNodeWithTag("settings-branch").performTextInput("main")
        compose.onNodeWithTag("settings-token").performTextInput("ghp_secret")
        compose.onNodeWithTag("settings-save").performClick()

        assertEquals(Triple("https://example.org/notes.git", "main", "ghp_secret"), saved)
    }

    @Test
    fun theStoredSettingsFillTheForm() {
        showForm(url = "https://example.org/notes.git", branch = "notes")

        compose.onNodeWithText("https://example.org/notes.git").assertIsDisplayed()
        compose.onNodeWithText("notes").assertIsDisplayed()
    }

    @Test
    fun savingIsRefusedWithoutARepository() {
        showForm()

        // Nothing to clone from: the button stays out of reach rather than
        // failing on a blank URL later.
        compose.onNodeWithTag("settings-save").assertIsNotEnabled()

        compose.onNodeWithTag("settings-url").performTextInput("https://example.org/notes.git")
        compose.onNodeWithTag("settings-save").assertIsEnabled()
    }

    @Test
    fun anAddressTheCoreCannotFetchIsRefusedInTheField() {
        showForm()

        // Saving empties the working copy, and edits made on the device are
        // committed locally and never pushed. An address that cannot work has
        // to be stopped here, not after the clone failed over an empty
        // directory.
        compose.onNodeWithTag("settings-url").performTextInput("http://example.org/notes.git")

        compose.onNodeWithText(string(R.string.settings_url_scheme)).assertIsDisplayed()
        compose.onNodeWithTag("settings-save").assertIsNotEnabled()
    }

    @Test
    fun aBlankTokenKeepsWhateverIsStored() {
        showForm(url = "https://example.org/notes.git", hasToken = true)

        // The stored token is never read back into the field, so the hint has
        // to say that leaving it alone is safe.
        compose.onNodeWithText(string(R.string.settings_token_kept)).assertIsDisplayed()

        compose.onNodeWithTag("settings-save").performClick()
        assertTrue(saved?.third.isNullOrEmpty())
    }

    @Test
    fun withoutATokenTheFieldSaysWhenItIsNeeded() {
        showForm(url = "https://example.org/notes.git")

        compose.onNodeWithText(string(R.string.settings_token_none)).assertIsDisplayed()
    }

    @Test
    fun cancellingChangesNothing() {
        showForm(url = "https://example.org/notes.git")

        compose.onNodeWithTag("settings-url").performTextReplacement("https://elsewhere/x.git")
        compose.onNodeWithTag("settings-cancel").performClick()

        assertTrue(dismissed)
        assertNull(saved)
    }

    private fun showForm(url: String = "", branch: String = "", hasToken: Boolean = false) {
        compose.setContent {
            MarkdownOrgTheme {
                SyncSettingsScreen(
                    initialUrl = url,
                    initialBranch = branch,
                    hasToken = hasToken,
                    onSave = { savedUrl, savedBranch, token ->
                        saved = Triple(savedUrl, savedBranch, token)
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }
    }

    private fun string(id: Int): String = compose.activity.getString(id)
}
