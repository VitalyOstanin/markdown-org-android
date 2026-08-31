package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.TaskDraft
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * A phrase said out loud rather than typed.
 *
 * The recogniser is the phone's own activity, and these tests stand in for it:
 * what is being checked is this screen's half of the exchange — where the text
 * lands, what happens to a phone that cannot listen, and what a cancelled
 * attempt leaves behind. Driving the real recogniser would test the phone
 * rather than the screen, and an emulator has none to drive.
 */
class TaskCreatorDictationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Which collection the screen handed the draft to, and the draft itself. */
    private var created: Pair<String, TaskDraft>? = null

    @Test
    fun whatWasHeardGoesIntoTheFieldAndNoFurther() {
        // Speaking fills the field, not the form: the sentence is read only
        // when the button that reads it is pressed, so a misheard word is
        // corrected in one line instead of across nine fields.
        show(heard = "позвонить врачу завтра в 15:00")

        speak()

        compose.onNodeWithTag("create-phrase").assertTextContains("позвонить врачу завтра в 15:00")
        // The heading is still empty, which is what the button that writes
        // goes by: nothing of the sentence has reached the form.
        compose.onNodeWithTag("create-save").assertIsNotEnabled()
    }

    @Test
    fun theHeardPhraseIsReadByTheSameButton() {
        show(heard = "позвонить врачу завтра в 15:00")

        speak()
        fillIn()
        create()

        val draft = created?.second
        assertEquals("позвонить врачу", draft?.title)
        assertEquals(TOMORROW, draft?.date)
        assertEquals(LocalTime.of(15, 0), draft?.time)
    }

    @Test
    fun aSecondSpeakingJoinsTheFirstRatherThanReplacingIt() {
        // A sentence said in two goes is one sentence: the field is where it
        // is assembled, and the rules see it whole.
        show(heard = "завтра в 15:00")
        compose.onNodeWithTag("create-phrase").performScrollTo()
            .performTextReplacement("позвонить врачу")

        speak()

        compose.onNodeWithTag("create-phrase").assertTextContains("позвонить врачу завтра в 15:00")
    }

    @Test
    fun aPhoneWithNothingToListenWithSaysSo() {
        // The button stays where it is on such a phone: a control that is
        // sometimes drawn and sometimes not is a difference nothing explains,
        // and what to do instead — type the sentence — is right beside it.
        show(dictation = { _, _ -> false })

        speak()

        compose.onNodeWithText(string(R.string.create_phrase_unheard)).assertIsDisplayed()
    }

    @Test
    fun aCancelledAttemptLeavesTheFieldAsItWas() {
        // The recogniser opened and nothing was said. Neither the typed text
        // nor the reader's attention is spent on it.
        show(dictation = { _, _ -> true })
        compose.onNodeWithTag("create-phrase").performScrollTo()
            .performTextReplacement("позвонить врачу")

        speak()

        compose.onNodeWithTag("create-phrase").assertTextContains("позвонить врачу")
        compose.onNodeWithText(string(R.string.create_phrase_unheard)).assertDoesNotExist()
    }

    @Test
    fun theFieldIsUsableAgainOnceTheSentenceIsRead() {
        // The complaint belongs to the attempt that failed, not to the field:
        // it goes when the phrase is read, however the phrase got there.
        show(dictation = { _, _ -> false })

        speak()
        compose.onNodeWithTag("create-phrase").performScrollTo()
            .performTextReplacement("позвонить врачу")
        fillIn()

        compose.onNodeWithText(string(R.string.create_phrase_unheard)).assertDoesNotExist()
    }

    private fun speak() {
        compose.onNodeWithTag("create-phrase-speak").performScrollTo().performClick()
    }

    private fun fillIn() {
        compose.onNodeWithTag("create-phrase-parse").performScrollTo().performClick()
    }

    private fun create() {
        compose.onNodeWithTag("create-save").performClick()
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    /** The screen with a recogniser that answers [heard] the moment it is asked. */
    private fun show(heard: String) {
        show(dictation = { _, onSpoken ->
            onSpoken(heard)
            true
        })
    }

    private fun show(dictation: Dictation) {
        compose.setContent {
            MarkdownOrgTheme {
                TaskCreator(
                    collections = ONE,
                    onCreate = { id, draft -> created = id to draft },
                    onDismiss = {},
                    today = TODAY,
                    dictation = dictation,
                )
            }
        }
    }

    private companion object {

        /** A Monday, so that a weekday named in a phrase falls this week. */
        val TODAY: LocalDate = LocalDate.of(2026, 8, 31)
        val TOMORROW: LocalDate = TODAY.plusDays(1)

        val ONE = listOf(
            NotesCollection(
                id = "1",
                name = "Personal",
                path = "/notes/personal",
                inbox = "inbox.md",
            ),
        )
    }
}
