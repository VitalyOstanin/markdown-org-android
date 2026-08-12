package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Moving the plan off the day it opens on.
 *
 * Two ways of asking for the same step, and both are here for now: the arrows
 * beside the heading, which say the plan can be moved at all, and a sideways
 * drag of the heading, which is at hand but has to be found. What each of them
 * asks for is asserted; which one earns its place is a question for the phone.
 */
class AgendaStepsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theForwardArrowAsksForTheNextSpan() {
        val asked = mutableListOf<Int>()
        show(onStep = { asked += it })

        // Displayed, and only then pressed: a button squeezed to no width at
        // the edge of the row still answers to its name and to the action
        // behind it, and a press that lands beside it says nothing about why.
        compose.onNodeWithTag("agenda-step-forward").assertIsDisplayed()
        compose.onNodeWithTag("agenda-step-forward").performClick()

        assertEquals(listOf(1), asked)
    }

    @Test
    fun theBackArrowAsksForThePreviousSpan() {
        val asked = mutableListOf<Int>()
        show(onStep = { asked += it })

        compose.onNodeWithTag("agenda-step-back").assertIsDisplayed()
        compose.onNodeWithTag("agenda-step-back").performClick()

        assertEquals(listOf(-1), asked)
    }

    @Test
    fun theHeadingIsDraggedTheWayAPageIsTurned() {
        val asked = mutableListOf<Int>()
        show(onStep = { asked += it })

        // Pulling the plan to the left brings what comes after it into view,
        // which is the way every page on the phone is turned.
        compose.onNodeWithTag("agenda-date-strip").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("agenda-date-strip").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(listOf(1, -1), asked)
    }

    @Test
    fun pressingTheHeadingComesBackToToday() {
        var came = 0
        show(onShowToday = { came++ })

        compose.onNodeWithTag("agenda-date-strip").performClick()

        assertEquals(1, came)
    }

    /**
     * The flat list of tasks covers no dates, so there is nothing to step
     * through: the arrows are left out rather than left there doing nothing.
     */
    @Test
    fun theFlatListOfTasksIsNotOfferedTheArrows() {
        val asked = mutableListOf<Int>()
        show(span = AgendaSpan.TASKS, onStep = { asked += it })

        compose.onNodeWithTag("agenda-step-forward").assertDoesNotExist()
        compose.onNodeWithTag("agenda-step-back").assertDoesNotExist()
        compose.onNodeWithTag("agenda-heading").assertIsDisplayed()
        // No strip either: the heading of the flat list is drawn plainly, so
        // there is nothing to drag and nothing to press for today.
        compose.onNodeWithTag("agenda-date-strip").assertDoesNotExist()
        compose.waitForIdle()

        assertEquals(emptyList<Int>(), asked)
    }

    private fun show(
        span: AgendaSpan = AgendaSpan.DAY,
        onStep: (Int) -> Unit = {},
        onShowToday: () -> Unit = {},
    ) {
        val sections = agenda(
            day(scheduledNoTime = listOf(task(heading = "Pay the tax"))),
        ).toSections()
        // The flat list of tasks carries no date to sit under, and the header
        // reads the span off the state rather than off the parameter.
        val days = listOf(AgendaDay(TODAY.takeIf { span.hasDays }, sections))
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Ready(date = TODAY, days = days, span = span),
                    view = AgendaView(layout = AgendaLayout.LIST, span = span),
                    actions = AgendaActions(onStep = onStep, onShowToday = onShowToday),
                )
            }
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 7, 28)
    }
}
