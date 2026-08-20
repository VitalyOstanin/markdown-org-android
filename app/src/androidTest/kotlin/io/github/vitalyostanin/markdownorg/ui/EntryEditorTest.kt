package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The screen that edits one entry.
 *
 * What is asserted here is what leaves the screen: the two halves reach the
 * caller as they were typed, and a heading with nothing in it cannot be saved
 * at all — the core refuses to write one, and a button that fails afterwards
 * says so too late.
 */
class EntryEditorTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The title and the body the screen handed back, if it did. */
    private var saved: Pair<String, String>? = null

    /** How many times the screen was left without saving. */
    private var dismissed = 0

    @Test
    fun theEditorOpensOnTheTextItWasGiven() {
        show(title = "Write **the** report", body = "The figures are in the drive.")

        compose.onNodeWithText("Write **the** report").assertIsDisplayed()
        compose.onNodeWithText("The figures are in the drive.").assertIsDisplayed()
    }

    @Test
    fun savingHandsBackBothHalvesAsTheyWereTyped() {
        show(title = "A note", body = "One line.")

        compose.onNodeWithTag("entry-title").performTextReplacement("A note, retitled")
        compose.onNodeWithTag("entry-body").performTextReplacement("Another line.")
        compose.onNodeWithTag("entry-save").performClick()

        assertEquals("A note, retitled" to "Another line.", saved)
    }

    @Test
    fun anEntryEmptiedOfItsBodyIsStillSavable() {
        // Taking the text out is an edit like any other; the entry goes on
        // standing with its heading and its dates.
        show(title = "A note", body = "One line.")

        compose.onNodeWithTag("entry-body").performTextClearance()
        compose.onNodeWithTag("entry-save").performClick()

        assertEquals("A note" to "", saved)
    }

    @Test
    fun aHeadingWithNothingInItCannotBeSaved() {
        show(title = "A note", body = "One line.")
        compose.onNodeWithTag("entry-save").assertIsEnabled()

        compose.onNodeWithTag("entry-title").performTextClearance()

        compose.onNodeWithTag("entry-save").assertIsNotEnabled()
    }

    @Test
    fun leavingTheEditorHandsBackNothing() {
        show(title = "A note", body = "One line.")

        compose.onNodeWithTag("entry-title").performTextReplacement("Typed and abandoned")
        compose.onNodeWithTag("entry-cancel").performClick()

        assertNull(saved)
        assertEquals(1, dismissed)
    }

    @Test
    fun theButtonThatWritesSaysWhatItWrites() {
        // "Save" names the gesture, not what reaches the file: the heading and
        // the lines under it, in one write and one commit.
        show(title = "A note", body = "One line.")

        compose.onNodeWithTag("entry-save").performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_entry_save), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theFieldsSayWhatTheyTakeWithoutAPress() {
        // A long press inside a text field belongs to selecting text, so the
        // fields answer with a line under them instead.
        show(title = "A note", body = "One line.")

        compose.onNodeWithText(string(R.string.entry_heading_support)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.entry_body_support)).assertIsDisplayed()
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun show(title: String, body: String) {
        compose.setContent {
            MarkdownOrgTheme {
                EntryEditor(
                    draft = EntryDraft(task = task(), title = title, body = body),
                    onSave = { newTitle, newBody -> saved = newTitle to newBody },
                    onDismiss = { dismissed += 1 },
                )
            }
        }
    }
}
