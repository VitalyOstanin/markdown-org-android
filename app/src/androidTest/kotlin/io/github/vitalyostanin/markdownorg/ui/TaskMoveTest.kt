package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.Task

/**
 * What the sheet offers about where an entry is kept.
 *
 * Which of the two offers is there depends on the collection and on the file
 * the entry is already in: an action that would move a note onto itself is not
 * an action, and a collection with one file has nowhere to move anything. Both
 * are decided here rather than in the view model, so they are read off what
 * the sheet was handed.
 */
class TaskMoveTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<TaskAction>()

    @Test
    fun anEntryOutsideTheMainFileIsOfferedTheOneTapMove() {
        show(task(file = "inbox.md"), MoveTargets(mainFile = "main.md"))

        compose.onNodeWithTag("action-move-main").assertIsDisplayed()
    }

    @Test
    fun anEntryAlreadyInTheMainFileIsNotOfferedAMoveIntoIt() {
        show(task(file = "main.md"), MoveTargets(mainFile = "main.md"))

        compose.onNodeWithTag("action-move-main").assertDoesNotExist()
    }

    @Test
    fun aCollectionWithNoMainFileOffersNoOneTapMove() {
        show(task(file = "inbox.md"), MoveTargets(files = listOf("inbox.md", "main.md")))

        compose.onNodeWithTag("action-move-main").assertDoesNotExist()
        // The list is still there: what is missing is the file to name in a
        // label, not somewhere to move to.
        compose.onNodeWithTag("action-move-file").assertIsDisplayed()
    }

    @Test
    fun aCollectionOfOneFileHasNowhereToMoveAnything() {
        show(task(file = "inbox.md"), MoveTargets(files = listOf("inbox.md")))

        compose.onNodeWithTag("action-move-file").assertDoesNotExist()
    }

    @Test
    fun theOneTapMoveNamesTheMainFile() {
        show(task(file = "inbox.md"), MoveTargets(mainFile = "main.md"))

        compose.onNodeWithTag("action-move-main").performScrollTo().performClick()

        assertEquals(listOf(TaskAction.MoveToFile("main.md")), actions)
    }

    @Test
    fun theListHoldsEveryFileButTheOneTheEntryIsIn() {
        val files = listOf("inbox.md", "main.md", "work/plans.md")
        show(task(file = "inbox.md"), MoveTargets(files = files))

        compose.onNodeWithTag("action-move-file").performScrollTo().performClick()

        compose.onNodeWithTag("move-to-main.md").assertIsDisplayed()
        compose.onNodeWithTag("move-to-work/plans.md").assertIsDisplayed()
        compose.onNodeWithTag("move-to-inbox.md").assertDoesNotExist()
    }

    @Test
    fun aFileChosenFromTheListIsTheFileTheEntryGoesTo() {
        show(task(file = "inbox.md"), MoveTargets(files = listOf("inbox.md", "work/plans.md")))

        compose.onNodeWithTag("action-move-file").performScrollTo().performClick()
        compose.onNodeWithTag("move-to-work/plans.md").performClick()

        assertEquals(listOf(TaskAction.MoveToFile("work/plans.md")), actions)
        // And the list is gone: a dialog left standing over the sheet reads as
        // the move not having registered.
        compose.onNodeWithTag("move-to-work/plans.md").assertDoesNotExist()
    }

    @Test
    fun leavingTheListWritesNothing() {
        show(task(file = "inbox.md"), MoveTargets(files = listOf("inbox.md", "main.md")))

        compose.onNodeWithTag("action-move-file").performScrollTo().performClick()
        compose.onNodeWithTag("move-dismiss").performClick()

        compose.onNodeWithTag("move-to-main.md").assertDoesNotExist()
        assertEquals(emptyList<TaskAction>(), actions)
    }

    /**
     * The actions as the sheet draws them: in a column that scrolls, for the
     * reason [TaskDatesTest] gives — the emulator CI runs on is short enough
     * for the last action of a sheet to fall past its bottom edge.
     */
    private fun show(task: Task, targets: MoveTargets) {
        compose.setContent {
            MarkdownOrgTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TaskMove(task = task, targets = targets) { action -> actions += action }
                }
            }
        }
    }
}
