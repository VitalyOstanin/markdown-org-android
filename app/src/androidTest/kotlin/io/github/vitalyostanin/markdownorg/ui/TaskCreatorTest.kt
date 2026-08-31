package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.TaskDraft
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.TaskType
import java.time.LocalDate
import java.time.LocalTime

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
    fun theHourAndTheRepeatAreOfferedOnlyOnceThereIsADay() {
        show()

        // Nothing to hold at an hour and nothing to repeat: both belong to the
        // timestamp, and a task with no date has none.
        compose.onNodeWithTag("create-pick-time").assertDoesNotExist()
        compose.onNodeWithTag("create-repeat-weekly").assertDoesNotExist()

        pickToday()

        compose.onNodeWithTag("create-pick-time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("create-repeat-weekly").performScrollTo().assertIsDisplayed()

        // The last of the intervals ends no further right than the rest of the
        // page: a row that scrolls sideways would put the chip taking a
        // repeater of its own past the edge, where nothing on screen says it
        // is there. The heading field is the width the page has to spare, and
        // both are measured unclipped — the page itself scrolls up and down,
        // so a chip below the fold is no fault.
        val edge = compose.onNodeWithTag("create-title").getUnclippedBoundsInRoot().right
        val chip = compose.onNodeWithTag("create-repeat-custom").getUnclippedBoundsInRoot()

        assertTrue(
            "the chip ends at ${chip.right}, past the $edge the page is wide",
            chip.right <= edge,
        )
    }

    @Test
    fun theIntervalChosenIsWhatTheTaskRepeatsBy() {
        show()

        compose.onNodeWithTag("create-title").performTextReplacement("Water the plants")
        pickToday()
        compose.onNodeWithTag("create-repeat-weekly").performScrollTo().performClick()
        compose.onNodeWithTag("create-save").performClick()

        // Catch-up rather than a single step: a weekly task completed after
        // three missed weeks is due next week, not three weeks ago.
        assertEquals("++1w", created?.second?.repeater)
        assertEquals(LocalDate.now(), created?.second?.date)
    }

    @Test
    fun aRepeaterTypedByHandIsAnsweredWhileItIsBeingTyped() {
        show()

        compose.onNodeWithTag("create-title").performTextReplacement("Water the plants")
        pickToday()
        // The chips wrap onto a second line, which on a short screen is
        // below the fold of a page that scrolls.
        compose.onNodeWithTag("create-repeat-custom").performScrollTo().performClick()
        compose.onNodeWithTag("create-repeat-dialog").assertIsDisplayed()
        compose.onNodeWithTag("create-repeat-field").performTextReplacement("weekly")

        // A word is not a repeater, and the field says so before the task has
        // been composed rather than after the core has refused it.
        compose.onNodeWithTag("create-repeat-set").assertIsNotEnabled()

        compose.onNodeWithTag("create-repeat-field").performTextReplacement("+007d")
        compose.onNodeWithTag("create-repeat-set").assertIsEnabled().performClick()
        compose.onNodeWithTag("create-save").performClick()

        // What comes back is what goes into the file, which is the check as
        // well as the answer.
        assertEquals("+7d", created?.second?.repeater)
    }

    @Test
    fun theHourChosenOnTheClockIsWhatTheTaskIsHeldAt() {
        show()

        compose.onNodeWithTag("create-title").performTextReplacement("Water the plants")
        pickToday()
        compose.onNodeWithTag("create-pick-time").performScrollTo().performClick()
        compose.onNodeWithTag("time-picker").assertIsDisplayed()
        compose.onNodeWithTag("time-set").performClick()
        compose.onNodeWithTag("create-save").performClick()

        // The clock opens at nine for a task that names no hour yet, and
        // confirming it unchanged is what that offer means.
        assertEquals(LocalTime.of(9, 0), created?.second?.time)
    }

    @Test
    fun anHourTakenOffLeavesTheTaskOnTheWholeDay() {
        show()

        compose.onNodeWithTag("create-title").performTextReplacement("Water the plants")
        pickToday()
        compose.onNodeWithTag("create-pick-time").performScrollTo().performClick()
        compose.onNodeWithTag("time-set").performClick()
        compose.onNodeWithTag("create-clear-time").performScrollTo().performClick()
        compose.onNodeWithTag("create-save").performClick()

        assertNull(created?.second?.time)
        assertEquals(LocalDate.now(), created?.second?.date)
    }

    @Test
    fun aTaskWithADayAndNothingElseSaidRepeatsNotAtAll() {
        show()

        compose.onNodeWithTag("create-title").performTextReplacement("Ring the dentist")
        pickToday()
        compose.onNodeWithTag("create-save").performClick()

        assertNull(created?.second?.repeater)
        assertNull(created?.second?.time)
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
        // for. Scrolled to: it stands at the foot of a form several screenfuls
        // long.
        compose.onNodeWithText("Written at the end of inbox.md").performScrollTo()
            .assertIsDisplayed()
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
        compose.onNodeWithText("Written at the end of work.md").performScrollTo()
            .assertIsDisplayed()
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

    @Test
    fun aChoiceSaysWhatItWritesIntoTheFile() {
        // The chips carry the format itself -- TODO, DEADLINE -- and a heading
        // of one word above them. What each choice puts in the note is said by
        // the heading, which is the question the chips answer.
        show()

        compose.onNodeWithText(string(R.string.create_keyword)).performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_create_keyword), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theTwoKindsOfDateAreToldApart() {
        show()

        compose.onNodeWithText(string(R.string.create_date)).performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_create_date), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theButtonThatWritesSaysWhereItWrites() {
        show()

        compose.onNodeWithTag("create-save").performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_create_save), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theFieldsExplainThemselvesWithoutAPress() {
        // A long press inside a text field belongs to selecting text, so what
        // the field takes is a line under it rather than a tooltip.
        show()

        compose.onNodeWithText(string(R.string.create_heading_support)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.create_body_support)).assertIsDisplayed()
    }

    /**
     * Choose today in the calendar the screen opens with nothing chosen in.
     *
     * Today rather than a fixed day: the picker draws the month it opens on,
     * and a date in another month would have to be navigated to.
     *
     * A cell of the calendar carries the whole date as its text -- "Sunday,
     * August 23, 2026" -- and today's carries "Today" ahead of it, which is
     * the one label that names a cell without naming a date. The day number
     * the cell shows is not in the semantics at all, so it cannot be clicked
     * by what it reads as.
     */
    private fun pickToday() {
        compose.onNodeWithTag("create-pick-date").performClick()
        compose.onNodeWithText("Today", substring = true, useUnmergedTree = true).performClick()
        compose.onNodeWithTag("date-set").performClick()
    }

    private fun string(id: Int): String = compose.activity.getString(id)

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
