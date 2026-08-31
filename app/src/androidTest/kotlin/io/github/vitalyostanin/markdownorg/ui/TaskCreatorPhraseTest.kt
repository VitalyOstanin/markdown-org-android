package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.TaskDraft
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
import java.time.LocalDate
import java.time.LocalTime

/**
 * A task composed by saying it rather than by filling nine controls.
 *
 * What is asserted is the draft that leaves the screen: the phrase is read
 * into the fields, the fields are what is written, and nothing reaches the
 * notes until Create — a phrase the rules misread is a screen to correct.
 *
 * The reference day is handed to the screen rather than taken from the clock,
 * so "tomorrow" is a day the test can name.
 */
class TaskCreatorPhraseTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Which collection the screen handed the draft to, and the draft itself. */
    private var created: Pair<String, TaskDraft>? = null

    @Test
    fun onePhraseFillsTheHeadingTheDayTheHourAndTheRepeater() {
        show()

        say("позвонить врачу завтра в 15:00, каждую неделю")
        create()

        val draft = created?.second
        assertEquals("позвонить врачу", draft?.title)
        assertEquals(TOMORROW, draft?.date)
        assertEquals(LocalTime.of(15, 0), draft?.time)
        assertEquals("+1w", draft?.repeater)
    }

    @Test
    fun theSameIsUnderstoodInEnglish() {
        // Both grammars are consulted whatever language the screen is drawn
        // in: a phone set to one of them is still spoken to in the other.
        show()

        say("call the doctor tomorrow at 15:00")
        create()

        val draft = created?.second
        assertEquals("call the doctor", draft?.title)
        assertEquals(TOMORROW, draft?.date)
        assertEquals(LocalTime.of(15, 0), draft?.time)
    }

    @Test
    fun aSecondPhraseMovesWhatItNamesAndLeavesTheRest() {
        // The refining is the core's, and this is the test of that: the hour
        // moves, the day and the repeater stay, and the heading is not said
        // twice.
        show()

        say("позвонить врачу завтра в 15:00, каждую неделю")
        say("в 16:00")
        create()

        val draft = created?.second
        assertEquals("позвонить врачу", draft?.title)
        assertEquals(TOMORROW, draft?.date)
        assertEquals(LocalTime.of(16, 0), draft?.time)
        assertEquals("+1w", draft?.repeater)
    }

    @Test
    fun whatTheRulesDoNotKnowStaysInTheHeading() {
        // Nothing said is lost — only unsorted. A phrasing outside the rules
        // is text, and text is the heading.
        show()

        say("купить подарок для мамы")
        create()

        val draft = created?.second
        assertEquals("купить подарок для мамы", draft?.title)
        assertNull(draft?.date)
        assertNull(draft?.time)
    }

    @Test
    fun aDeadlineIsHeardAsOne() {
        show()

        say("сдать отчёт до пятницы")
        create()

        assertEquals(PlanningKeyword.DEADLINE, created?.second?.keyword)
    }

    @Test
    fun aFieldCorrectedByHandIsWhatTheNextPhraseRefines() {
        // The draft handed to the core is what the screen shows, not what the
        // last phrase left: a heading rewritten between two phrases is the
        // heading the second one adds to.
        show()

        say("завтра в 15:00")
        compose.onNodeWithTag("create-title").performTextReplacement("позвонить врачу")
        say("каждую неделю")
        create()

        val draft = created?.second
        assertEquals("позвонить врачу", draft?.title)
        assertEquals(TOMORROW, draft?.date)
        assertEquals("+1w", draft?.repeater)
    }

    @Test
    fun anEmptyFieldReadsNothing() {
        // The button is unavailable rather than reading an empty phrase into
        // an entry that would then say nothing.
        show()

        compose.onNodeWithTag("create-phrase-parse").assertIsNotEnabled()
    }

    @Test
    fun theFieldIsEmptiedSoTheNextPhraseIsTypedIntoIt() {
        // What is said next is a new phrase rather than an edit of the last
        // one, and a field still holding the previous sentence would have to
        // be cleared by hand every time. The emptied field is read back
        // through the button, which is unavailable while there is nothing in
        // it.
        show()

        say("завтра в 15:00")

        compose.onNodeWithTag("create-phrase-parse").assertIsNotEnabled()
    }

    @Test
    fun nothingIsWrittenUntilCreate() {
        // The phrase fills the screen; the notes see none of it until the
        // button that writes is pressed.
        show()

        say("позвонить врачу завтра в 15:00")

        assertNull(created)
    }

    private fun say(phrase: String) {
        compose.onNodeWithTag("create-phrase").performScrollTo().performTextReplacement(phrase)
        compose.onNodeWithTag("create-phrase-parse").performClick()
    }

    private fun create() {
        // The button is in the top bar rather than in the scrolling form, so
        // there is nothing to scroll it into view.
        compose.onNodeWithTag("create-save").performClick()
    }

    private fun show() {
        compose.setContent {
            MarkdownOrgTheme {
                TaskCreator(
                    collections = ONE,
                    onCreate = { id, draft -> created = id to draft },
                    onDismiss = {},
                    today = TODAY,
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
