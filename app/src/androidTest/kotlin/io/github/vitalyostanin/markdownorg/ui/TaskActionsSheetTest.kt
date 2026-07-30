package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType

/**
 * The sheet only offers what the task can actually do: an action that fails
 * for a reason visible on the task itself — no date to move, a priority that
 * is not there — is a tap the user should never have been offered.
 */
class TaskActionsSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<TaskAction>()

    @Test
    fun theSheetNamesTheTaskAndWhereItIs() {
        show(task(heading = "Water the plants", file = "home.md", line = 12u))

        compose.onNodeWithText("Water the plants").assertIsDisplayed()
        compose.onNodeWithText("home.md:12").assertIsDisplayed()
    }

    @Test
    fun completingIsWordedByWhetherTheTaskRepeats() {
        show(task(repeater = "+1w"))

        compose.onNodeWithText(string(R.string.action_complete_repeating)).assertIsDisplayed()
    }

    @Test
    fun aOneOffTaskSaysPlainlyThatItIsDone() {
        show(task())

        compose.onNodeWithText(string(R.string.action_complete)).assertIsDisplayed()
    }

    @Test
    fun completingReportsTheAction() {
        show(task())

        compose.onNodeWithTag("action-complete").performClick()

        assertEquals(listOf<TaskAction>(TaskAction.Complete), actions)
    }

    @Test
    fun aTaskAlreadyOpenIsNotOfferedReopening() {
        show(task(taskType = TaskType.TODO))

        compose.onNodeWithTag("action-reopen").assertDoesNotExist()
    }

    @Test
    fun aFinishedTaskCanBeReopened() {
        show(task(taskType = TaskType.DONE))

        compose.onNodeWithTag("action-reopen").performClick()

        assertEquals(listOf<TaskAction>(TaskAction.Status(TaskType.TODO)), actions)
    }

    @Test
    fun aTaskAlreadyDoneIsNotOfferedCompletion() {
        // Completing it again writes the same keyword back and commits, which
        // is a commit that changes nothing.
        show(task(taskType = TaskType.DONE))

        compose.onNodeWithTag("action-complete").assertDoesNotExist()
    }

    @Test
    fun aRepeatingTaskIsOfferedCompletionEvenWhenDone() {
        // For a repeater "done" means the next occurrence, so it stays
        // meaningful however the keyword reads now.
        show(task(taskType = TaskType.DONE, repeater = "+1w"))

        compose.onNodeWithTag("action-complete").assertExists()
    }

    @Test
    fun aTaskAlreadyCancelledIsNotOfferedCancelling() {
        show(task(taskType = TaskType.CANCELLED))

        compose.onNodeWithTag("action-cancel").assertDoesNotExist()
    }

    @Test
    fun everyPriorityTheAgendaCanShowCanAlsoBeSet() {
        // The badge tells A, B and C apart; the sheet used to offer A alone,
        // so a task that arrived from the notes with B could only be cleared.
        show(task(priority = null))

        compose.onNodeWithTag("priority-B").performClick()

        assertEquals(listOf<TaskAction>(TaskAction.Priority("B")), actions)
    }

    @Test
    fun thePriorityAlreadySetIsNotWrittenAgain() {
        show(task(priority = "B"))

        compose.onNodeWithTag("priority-B").performClick()

        assertEquals(emptyList<TaskAction>(), actions)
    }

    @Test
    fun aPriorityOutsideTheDefaultRangeCanBeSetBack() {
        // The core takes any uppercase letter and any number in 0..64, so a
        // task can arrive from the notes with [#D]. Offering A, B and C alone
        // would leave no way back to the value it had.
        show(task(priority = "D"))

        compose.onNodeWithTag("priority-D").assertIsSelected()
        compose.onNodeWithTag("priority-A").performClick()

        assertEquals(listOf<TaskAction>(TaskAction.Priority("A")), actions)
    }

    @Test
    fun aPriorityCanBeDropped() {
        show(task(priority = "B"))

        compose.onNodeWithTag("priority-none").performClick()

        assertEquals(listOf<TaskAction>(TaskAction.Priority(null)), actions)
    }

    @Test
    fun aDeadlineMovesItsOwnPlanningLine() {
        show(task(timestampType = TimestampType.DEADLINE))

        compose.onNodeWithTag("action-shift-forward").performClick()

        assertEquals(
            listOf<TaskAction>(TaskAction.Shift(PlanningKeyword.DEADLINE, 1)),
            actions,
        )
    }

    @Test
    fun movingBackwardsIsOfferedToo() {
        show(task(timestampType = TimestampType.SCHEDULED))

        compose.onNodeWithTag("action-shift-back").performClick()

        assertEquals(
            listOf<TaskAction>(TaskAction.Shift(PlanningKeyword.SCHEDULED, -1)),
            actions,
        )
    }

    @Test
    fun aTaskWithNoPlanningLineIsNotOfferedToMoveOne() {
        // A bare timestamp in the body carries no keyword, and there is
        // nothing for the core to move.
        show(task(timestampType = null))

        compose.onNodeWithTag("action-shift-forward").assertDoesNotExist()
        compose.onNodeWithTag("action-shift-back").assertDoesNotExist()
    }

    private fun show(task: Task) {
        compose.setContent {
            MarkdownOrgTheme {
                TaskActionsSheet(
                    task = task,
                    onAction = { action -> actions += action },
                    onDismiss = {},
                )
            }
        }
    }

    private fun string(id: Int): String = compose.activity.getString(id)
}
