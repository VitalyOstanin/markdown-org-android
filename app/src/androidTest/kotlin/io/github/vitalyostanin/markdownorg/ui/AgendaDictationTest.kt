package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Saying an entry from the agenda itself, without the creation screen.
 *
 * The button is the short way in: the recogniser opens on a tap, and what it
 * heard is written as it stands. What is checked here is this screen's half of
 * that exchange — that the sentence reaches the model whole, that a phone with
 * nothing to listen with is answered rather than left silent, and that the
 * plus beside it still opens the screen it always did.
 *
 * The recogniser is the phone's own activity and is stood in for, as it is on
 * the creation screen: driving the real one would test the phone, and an
 * emulator has none to drive.
 */
class AgendaDictationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The sentence the screen handed over, if it handed one over at all. */
    private var dictated: String? = null

    /** Whether the screen reported a phone that cannot listen. */
    private var unavailable = false

    /** Whether the plus opened the creation screen. */
    private var creating = false

    @Test
    fun whatWasHeardIsHandedOverAsItWasSaid() {
        // Whole and unread: which fields the sentence fills is the model's to
        // decide with the same rules a typed phrase goes through, so the
        // screen neither trims it nor looks inside it.
        show(heard = "позвонить врачу завтра в 15:00")

        dictate()

        assertEquals("позвонить врачу завтра в 15:00", dictated)
        assertEquals(false, unavailable)
    }

    @Test
    fun aPhoneWithNothingToListenWithSaysSo() {
        // The button stays where it is on such a phone, for the reason it does
        // on the creation screen: a control that is sometimes drawn and
        // sometimes not is a difference nothing explains, and what to do
        // instead — the plus below it — is already there.
        show(dictation = { _, _ -> false })

        dictate()

        assertNull(dictated)
        assertEquals(true, unavailable)
    }

    @Test
    fun aCancelledAttemptWritesNothing() {
        // The recogniser opened and nothing was said. Silence is not an entry,
        // and there is nothing to report either.
        show(dictation = { _, _ -> true })

        dictate()

        assertNull(dictated)
        assertEquals(false, unavailable)
    }

    @Test
    fun bothWaysOfWritingAreOnTheAgendaAtOnce() {
        // The microphone is a second way in rather than a replacement: the
        // plus opens the screen where a task is set out field by field, and
        // that is still the way to write one that a sentence cannot say.
        show(heard = "позвонить врачу")

        compose.onNodeWithTag("agenda-dictate").assertIsDisplayed()
        compose.onNodeWithTag("agenda-create").assertIsDisplayed().performClick()

        assertEquals(true, creating)
        assertNull(dictated)
    }

    private fun dictate() {
        compose.onNodeWithTag("agenda-dictate").performClick()
    }

    /** The agenda with a recogniser that answers [heard] the moment it is asked. */
    private fun show(heard: String) {
        show(dictation = { _, onSpoken ->
            onSpoken(heard)
            true
        })
    }

    private fun show(dictation: Dictation) {
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Ready(date = TODAY, sections = EMPTY_DAY),
                    actions = AgendaActions(
                        onCreate = { creating = true },
                        onDictated = { dictated = it },
                        onDictationUnavailable = { unavailable = true },
                    ),
                    dictation = dictation,
                )
            }
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 31)

        /** A day with nothing on it: what is in question is the corner. */
        val EMPTY_DAY = AgendaSections(
            overdue = emptyList(),
            timed = emptyList(),
            untimed = emptyList(),
        )
    }
}
