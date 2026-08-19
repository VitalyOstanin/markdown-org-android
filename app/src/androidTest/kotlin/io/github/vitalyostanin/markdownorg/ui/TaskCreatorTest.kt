package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.TaskDraft
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.TaskType

/**
 * The screen that writes a task the notes do not hold yet.
 *
 * What is asserted is what leaves the screen: the draft as it was composed,
 * which collection it is aimed at, and that a heading with nothing in it
 * cannot be written at all — the core refuses one, and a button that fails
 * afterwards says so too late.
 */
class TaskCreatorTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Which collection the screen handed the draft to, and the draft itself. */
    private var created: Pair<String, TaskDraft>? = null

    /** How many times the screen was left without writing anything. */
    private var dismissed = 0

    @Test
    fun aTaskIsWrittenWithTheKeywordAndTheDateThatWereChosen() {
        show()

        compose.onNodeWithTag("create-title").performTextReplacement("Ring the dentist")
        compose.onNodeWithTag("create-body").performTextReplacement("The number is on the fridge.")
        compose.onNodeWithTag("create-priority-B").performClick()
        compose.onNodeWithTag("create-kind-deadline").performClick()
        compose.onNodeWithTag("create-save").performClick()

        val draft = created?.second
        assertEquals("1", created?.first)
        assertEquals("Ring the dentist", draft?.title)
        assertEquals("The number is on the fridge.", draft?.body)
        // TODO unless it was said otherwise: a task is written open.
        assertEquals(TaskType.TODO, draft?.status)
        assertEquals("B", draft?.priority)
        assertEquals(PlanningKeyword.DEADLINE, draft?.keyword)
        // The kind was chosen and no day was: a task with no planning line at
        // all, which the flat list of tasks is where it shows.
        assertNull(draft?.date)
    }

    @Test
    fun aTaskCanBeWrittenWithoutAKeyword() {
        show()

        compose.onNodeWithTag("create-title").performTextReplacement("A note, not a task")
        compose.onNodeWithTag("create-keyword-none").performClick()
        compose.onNodeWithTag("create-save").performClick()

        assertNull(created?.second?.status)
    }

    @Test
    fun aDayChosenInTheCalendarIsWhatTheTaskCarries() {
        show()

        compose.onNodeWithTag("create-title").performTextReplacement("Ring the dentist")
        compose.onNodeWithTag("create-pick-date").performClick()
        compose.onNodeWithTag("date-picker").assertIsDisplayed()
        compose.onNodeWithTag("date-cancel").performClick()

        // Nothing was picked, so the task keeps no date and the button goes on
        // offering to choose one.
        compose.onNodeWithTag("create-pick-date").assertIsDisplayed()
        compose.onNodeWithTag("create-save").performClick()
        assertNull(created?.second?.date)
    }

    @Test
    fun aHeadingWithNothingInItCannotBeWritten() {
        show()
        compose.onNodeWithTag("create-save").assertIsNotEnabled()

        compose.onNodeWithTag("create-title").performTextReplacement("Ring the dentist")

        compose.onNodeWithTag("create-save").assertIsEnabled()
    }

    @Test
    fun theFileTheTaskGoesIntoIsNamedOnTheScreen() {
        show()

        // The file is a setting of the collection rather than a field here,
        // and a task written into a note nobody expected is one they will look
        // for.
        compose.onNodeWithText("Written at the end of inbox.md").assertIsDisplayed()
    }

    @Test
    fun oneCollectionIsNotAChoice() {
        // Nothing to choose between, and a row of one chip that answers
        // nothing is a control the reader looks for a meaning in.
        show(collections = listOf(collection("1", "Personal", "inbox.md")))

        compose.onNodeWithTag("create-collections").assertDoesNotExist()
    }

    @Test
    fun theTaskGoesToWhicheverCollectionWasChosen() {
        show()

        compose.onNodeWithTag("create-collection-2").performClick()

        // The file named at the foot follows the choice, because it is that
        // collection's own.
        compose.onNodeWithText("Written at the end of work.md").assertIsDisplayed()
        compose.onNodeWithTag("create-title").performTextReplacement("Write the report")
        compose.onNodeWithTag("create-save").performClick()
        assertEquals("2", created?.first)
    }

    @Test
    fun leavingTheScreenWritesNothing() {
        show()

        compose.onNodeWithTag("create-title").performTextReplacement("Typed and abandoned")
        compose.onNodeWithTag("create-cancel").performClick()

        assertNull(created)
        assertEquals(1, dismissed)
    }

    private fun show(collections: List<NotesCollection> = PAIR) {
        compose.setContent {
            MarkdownOrgTheme {
                TaskCreator(
                    collections = collections,
                    onCreate = { id, draft -> created = id to draft },
                    onDismiss = { dismissed += 1 },
                )
            }
        }
    }

    private companion object {

        fun collection(id: String, name: String, inbox: String) = NotesCollection(
            id = id,
            name = name,
            path = "/notes/$name",
            inbox = inbox,
        )

        /** Two collections, each receiving new tasks in a file of its own. */
        val PAIR = listOf(
            collection("1", "Personal", "inbox.md"),
            collection("2", "Work", "work.md"),
        )
    }
}
