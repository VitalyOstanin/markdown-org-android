package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The way back to the day being lived through.
 *
 * A press on the date has always done it, and nothing said so: a week stepped
 * forward twice left the reader looking for a control, and the one that existed
 * was a strip of text nobody presses. The button says it is there, and it takes
 * the list to the day as well as the plan to the span holding it — a week whose
 * last day is today opens on its first, with today below the fold.
 */
class AgendaTodayTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aSpanHoldingTodayIsNotOfferedTheWayBackToIt() {
        show(weekOf(TODAY))

        // Nothing for it to do: the header already carries four controls in
        // that row, and a fifth that changes nothing is one the user presses
        // twice before deciding it is broken.
        compose.onNodeWithTag("agenda-today").assertDoesNotExist()
    }

    @Test
    fun aSpanSteppedOffTodayShowsTheWayBack() {
        show(weekOf(NEXT_MONDAY))

        compose.onNodeWithTag("agenda-today").assertIsDisplayed()
    }

    @Test
    fun theWayBackAsksForTheSpanHoldingToday() {
        var came = 0
        show(weekOf(NEXT_MONDAY), onShowToday = { came++ })

        compose.onNodeWithTag("agenda-today").performClick()

        assertEquals(1, came)
    }

    /**
     * The press does two things, and this is the second: the span comes back
     * under the model, and the list is taken to the day inside it. Without the
     * scroll, a week of days full of rows opens where the list was left, and
     * the day the button was pressed for is somewhere below the fold — which is
     * what the test below asserts is the case beforehand.
     */
    @Test
    fun theWayBackTakesTheListToTheDayItself() {
        var days by mutableStateOf(weekOf(NEXT_MONDAY))
        // What the model does when the plan is asked to come back: it answers
        // with the days of the span holding today.
        show({ days }, onShowToday = { days = weekOf(TODAY.minusDays(6)) })

        compose.onNodeWithTag("agenda-today").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("day-heading-today").assertIsDisplayed()
    }

    @Test
    fun theLastDayOfAWeekIsOutOfSightUntilSomethingScrollsToIt() {
        show(weekOf(TODAY.minusDays(6)))

        // A lazy list draws what fits and a little beyond it; six days of rows
        // stand between the top and today, so nothing of it exists yet.
        assertTrue(
            "today should be below the fold of a week full of rows",
            compose.onAllNodesWithTag("day-heading-today").fetchSemanticsNodes().isEmpty(),
        )
    }

    /** Seven days from [first], each carrying enough rows to fill a screen. */
    private fun weekOf(first: LocalDate): List<AgendaDay> = (0L until DAYS_OF_WEEK).map { offset ->
        val date = first.plusDays(offset)
        AgendaDay(
            date = date,
            sections = agenda(
                day(
                    date = date.toString(),
                    scheduledNoTime = (1..ROWS_PER_DAY).map { row ->
                        task(heading = "Day $offset row $row", line = row.toUInt())
                    },
                ),
            ).toSections(),
        )
    }

    private fun show(days: List<AgendaDay>, onShowToday: () -> Unit = {}) =
        show({ days }, onShowToday)

    /**
     * [daysOf] is read inside the composition, so a test that answers the press
     * with another week has that week drawn — which is what the model does.
     */
    private fun show(daysOf: () -> List<AgendaDay>, onShowToday: () -> Unit = {}) {
        compose.setContent {
            val days = daysOf()
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Ready(
                        date = days.first().date ?: TODAY,
                        days = days,
                        span = AgendaSpan.WEEK,
                    ),
                    view = AgendaView(layout = AgendaLayout.LIST, span = AgendaSpan.WEEK),
                    // The clock today is read off, so nothing here depends on
                    // the day the test is run on.
                    now = TODAY.atTime(9, 0),
                    actions = AgendaActions(onShowToday = onShowToday),
                )
            }
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 7, 28)
        val NEXT_MONDAY: LocalDate = LocalDate.of(2026, 8, 3)
        const val DAYS_OF_WEEK = 7L
        const val ROWS_PER_DAY = 6
    }
}
