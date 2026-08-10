package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * What the screen does with a span wider than a day.
 *
 * The projection behind it is tested on the JVM; what is asserted here is the
 * part only a device can answer — that a week reads as a week, that the day
 * being lived through is marked among the seven, and that the switch which
 * cannot apply is not left on screen doing nothing.
 */
class AgendaSpanTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** A week whose Monday has an entry, whose Tuesday is today, and whose Wednesday is empty. */
    private val week = listOf(
        AgendaDay(
            MONDAY,
            agenda(
                day(
                    date = "2026-07-27",
                    scheduledNoTime = listOf(task(heading = "Order the parts")),
                ),
            ).toSections(),
        ),
        AgendaDay(
            TODAY,
            agenda(
                day(
                    date = "2026-07-28",
                    scheduledTimed = listOf(task(heading = "Daily standup", time = "09:30")),
                ),
            ).toSections(),
        ),
        AgendaDay(WEDNESDAY, agenda(day(date = "2026-07-29")).toSections()),
    )

    @Test
    fun aWeekPutsAHeadingOverEveryDayOfIt() {
        showWeek()

        // Three days, and the entries of each under its own heading: pooled
        // together they would say what there is to do and not when. Two of the
        // headings are plain and the third is today's, which carries a tag of
        // its own.
        assertEquals(
            "a heading per day other than today",
            2,
            compose.onAllNodesWithTag("day-heading").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Order the parts").assertIsDisplayed()
        compose.onNodeWithText("Daily standup").assertIsDisplayed()
    }

    @Test
    fun theDayBeingLivedThroughIsMarkedAmongTheRest() {
        showWeek()

        compose.onNodeWithTag("day-heading-today").assertIsDisplayed()
    }

    @Test
    fun aDayOfTheWeekWithNothingOnItSaysSo() {
        showWeek()

        // A heading with nothing under it reads as a list that failed to draw.
        compose.onNodeWithTag("day-empty").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.agenda_day_empty)).assertIsDisplayed()
    }

    @Test
    fun theLayoutSwitchIsNotOfferedWhereItCannotApply() {
        showWeek()

        // The hour axis covers one day. A switch that changes nothing is a
        // control the user presses twice before deciding it is broken.
        compose.onNodeWithTag(AgendaLayout.TIME.testTag).assertDoesNotExist()
        compose.onNodeWithTag("span-menu").assertIsDisplayed()
    }

    @Test
    fun theMenuAnswersWithTheSpanThatWasPicked() {
        var picked: AgendaSpan? = null
        show(days = week, span = AgendaSpan.WEEK, onSpanChange = { picked = it })

        compose.onNodeWithTag("span-menu").performClick()
        compose.onNodeWithTag(AgendaSpan.MONTH.testTag).performClick()

        assertEquals(AgendaSpan.MONTH, picked)
    }

    @Test
    fun theFlatListOfTasksNamesItselfAndStatesNoDates() {
        show(
            days = listOf(
                AgendaDay(
                    date = null,
                    sections = flatAgenda(task(heading = "Someday", daysOffset = null))
                        .toSections(),
                ),
            ),
            span = AgendaSpan.TASKS,
        )

        // The span with no dates at all: the heading says what is on screen,
        // and the line that would carry the dates is left out rather than left
        // blank.
        compose.onNodeWithTag("agenda-heading")
            .assertTextEquals(string(R.string.agenda_span_tasks))
        compose.onNodeWithTag("agenda-caption").assertDoesNotExist()
        compose.onNodeWithText("Someday").assertIsDisplayed()
    }

    private fun showWeek() = show(days = week, span = AgendaSpan.WEEK)

    private fun show(
        days: List<AgendaDay>,
        span: AgendaSpan,
        onSpanChange: (AgendaSpan) -> Unit = {},
    ) {
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Ready(date = TODAY, days = days, span = span),
                    // Left on the axis on purpose: what decides the layout is
                    // the span, and a week must be drawn as the list whatever
                    // the switch was last set to.
                    layout = AgendaLayout.TIME,
                    onLayoutChange = {},
                    span = span,
                    onSpanChange = onSpanChange,
                    now = TODAY.atTime(9, 0),
                )
            }
        }
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private companion object {
        val MONDAY: LocalDate = LocalDate.of(2026, 7, 27)
        val TODAY: LocalDate = LocalDate.of(2026, 7, 28)
        val WEDNESDAY: LocalDate = LocalDate.of(2026, 7, 29)
    }
}
