package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TimestampType
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the sheet offers about a task's dates, which depends on whether it has
 * one at all.
 *
 * A task with a planning line can be moved, put on another day or cleared; a
 * task without one has to be told which kind of date it is being given, and
 * cannot be cleared of a date it does not carry.
 */
class TaskDatesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<TaskAction>()

    @Test
    fun aTaskWithADateIsOfferedTheCalendarAndTheWayOut() {
        show(task(timestampType = TimestampType.SCHEDULED))

        compose.onNodeWithTag("action-pick-date").assertIsDisplayed()
        compose.onNodeWithTag("action-clear-date").assertIsDisplayed()
        compose.onNodeWithTag("action-set-scheduled").assertDoesNotExist()
        compose.onNodeWithTag("action-set-deadline").assertDoesNotExist()
    }

    @Test
    fun aTaskWithNoDateIsAskedWhichKindItIsBeingGiven() {
        show(task(timestampType = null, date = null))

        compose.onNodeWithTag("action-set-scheduled").assertIsDisplayed()
        compose.onNodeWithTag("action-set-deadline").assertIsDisplayed()
        compose.onNodeWithTag("action-clear-date").assertDoesNotExist()
        compose.onNodeWithTag("action-pick-date").assertDoesNotExist()
    }

    @Test
    fun clearingADateAsksForThatKeywordWithNoDayInIt() {
        show(task(timestampType = TimestampType.DEADLINE))

        compose.onNodeWithTag("action-clear-date").performScrollTo().performClick()

        assertEquals(listOf<TaskAction>(TaskAction.Plan(PlanningKeyword.DEADLINE, null)), actions)
    }

    @Test
    fun theCalendarOpensOnTheDayTheTaskAlreadySitsOn() {
        show(task(timestampType = TimestampType.SCHEDULED, date = "2026-08-19"))

        compose.onNodeWithTag("action-pick-date").performScrollTo().performClick()

        // Already answerable: the day is chosen, so confirming writes the day
        // the task carries rather than nothing.
        compose.onNodeWithTag("date-set").assertIsEnabled().performClick()

        assertEquals(
            listOf<TaskAction>(
                TaskAction.Plan(PlanningKeyword.SCHEDULED, LocalDate.of(2026, 8, 19)),
            ),
            actions,
        )
    }

    @Test
    fun aCalendarOpenedForATaskWithNoDateCannotBeConfirmedUntilADayIsPicked() {
        show(task(timestampType = null, date = null))

        compose.onNodeWithTag("action-set-deadline").performScrollTo().performClick()

        compose.onNodeWithTag("date-picker").assertIsDisplayed()
        compose.onNodeWithTag("date-set").assertIsNotEnabled()
    }

    @Test
    fun leavingTheCalendarWritesNothing() {
        show(task(timestampType = TimestampType.SCHEDULED))

        compose.onNodeWithTag("action-pick-date").performScrollTo().performClick()
        compose.onNodeWithTag("date-cancel").performClick()

        compose.onNodeWithTag("date-picker").assertDoesNotExist()
        assertEquals(emptyList<TaskAction>(), actions)
    }

    @Test
    fun aTaskWithADateIsOfferedTheClockAsWell() {
        show(task(timestampType = TimestampType.SCHEDULED, time = "10:00"))

        compose.onNodeWithTag("action-pick-hour").assertIsDisplayed()
        compose.onNodeWithTag("action-clear-hour").assertIsDisplayed()
    }

    /**
     * A date held all day has no hour to take off, so nothing offers to.
     *
     * The same rule the date actions follow: a button that would do nothing is
     * not shown, rather than shown and answered with a message.
     */
    @Test
    fun aDateHeldAllDayIsOfferedTheClockButNoWayToClearIt() {
        show(task(timestampType = TimestampType.SCHEDULED, time = null))

        compose.onNodeWithTag("action-pick-hour").assertIsDisplayed()
        compose.onNodeWithTag("action-clear-hour").assertDoesNotExist()
    }

    /** An hour goes into a timestamp, and a task with no date has none. */
    @Test
    fun aTaskWithNoDateIsNotOfferedAnHourAtAll() {
        show(task(timestampType = null, date = null))

        compose.onNodeWithTag("action-pick-hour").assertDoesNotExist()
        compose.onNodeWithTag("action-clear-hour").assertDoesNotExist()
    }

    @Test
    fun takingTheHourOffAsksForThatKeywordWithNoHourInIt() {
        show(task(timestampType = TimestampType.DEADLINE, time = "18:30"))

        compose.onNodeWithTag("action-clear-hour").performScrollTo().performClick()

        assertEquals(
            listOf<TaskAction>(TaskAction.PlanTime(PlanningKeyword.DEADLINE, null)),
            actions,
        )
    }

    @Test
    fun theClockOpensOnTheHourTheTaskIsAlreadyHeldAt() {
        show(task(timestampType = TimestampType.SCHEDULED, time = "18:30"))

        compose.onNodeWithTag("action-pick-hour").performScrollTo().performClick()
        compose.onNodeWithTag("time-set").performClick()

        assertEquals(
            listOf<TaskAction>(
                TaskAction.PlanTime(PlanningKeyword.SCHEDULED, LocalTime.of(18, 30)),
            ),
            actions,
        )
    }

    /** The start of a working day, for a date that names no hour yet. */
    @Test
    fun aDateWithNoHourOpensTheClockAtTheStartOfTheWorkingDay() {
        show(task(timestampType = TimestampType.SCHEDULED, time = null))

        compose.onNodeWithTag("action-pick-hour").performScrollTo().performClick()
        compose.onNodeWithTag("time-set").performClick()

        assertEquals(
            listOf<TaskAction>(
                TaskAction.PlanTime(PlanningKeyword.SCHEDULED, LocalTime.of(9, 0)),
            ),
            actions,
        )
    }

    @Test
    fun leavingTheClockWritesNothing() {
        show(task(timestampType = TimestampType.SCHEDULED, time = "10:00"))

        compose.onNodeWithTag("action-pick-hour").performScrollTo().performClick()
        compose.onNodeWithTag("time-cancel").performClick()

        compose.onNodeWithTag("time-picker").assertDoesNotExist()
        assertEquals(emptyList<TaskAction>(), actions)
    }

    /**
     * The actions as the sheet draws them: in a column that scrolls.
     *
     * The sheet itself scrolls, because how tall it is depends on the task,
     * and a test that laid the actions out without a scroll would put the
     * last of them past the bottom of a short screen -- where a tap lands on
     * nothing. The emulator CI runs on is exactly such a screen.
     */
    private fun show(task: Task) {
        compose.setContent {
            MarkdownOrgTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TaskDates(task = task, weekStart = WeekStart.AUTO) { action ->
                        actions += action
                    }
                }
            }
        }
    }
}
