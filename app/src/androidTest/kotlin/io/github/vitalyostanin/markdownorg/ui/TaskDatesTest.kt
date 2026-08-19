package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TimestampType
import java.time.LocalDate

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

        compose.onNodeWithTag("action-clear-date").performClick()

        assertEquals(listOf<TaskAction>(TaskAction.Plan(PlanningKeyword.DEADLINE, null)), actions)
    }

    @Test
    fun theCalendarOpensOnTheDayTheTaskAlreadySitsOn() {
        show(task(timestampType = TimestampType.SCHEDULED, date = "2026-08-19"))

        compose.onNodeWithTag("action-pick-date").performClick()

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

        compose.onNodeWithTag("action-set-deadline").performClick()

        compose.onNodeWithTag("date-picker").assertIsDisplayed()
        compose.onNodeWithTag("date-set").assertIsNotEnabled()
    }

    @Test
    fun leavingTheCalendarWritesNothing() {
        show(task(timestampType = TimestampType.SCHEDULED))

        compose.onNodeWithTag("action-pick-date").performClick()
        compose.onNodeWithTag("date-cancel").performClick()

        compose.onNodeWithTag("date-picker").assertDoesNotExist()
        assertEquals(emptyList<TaskAction>(), actions)
    }

    private fun show(task: Task) {
        compose.setContent {
            MarkdownOrgTheme {
                TaskDates(task = task, weekStart = WeekStart.AUTO) { action -> actions += action }
            }
        }
    }
}
