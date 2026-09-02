package io.github.vitalyostanin.markdownorg.ui

import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * The sheet only offers what the task can actually do: an action that fails
 * for a reason visible on the task itself — no date to move, a priority that
 * is not there — is a tap the user should never have been offered.
 */
class TaskActionsSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<TaskAction>()

    /** How many times the sheet asked for the note to be opened elsewhere. */
    private var opened = 0

    /** How many times the sheet asked for the entry to be edited. */
    private var edits = 0

    @Test
    fun theSheetNamesTheTaskAndWhereItIs() {
        show(task(heading = "Water the plants", file = "home.md", line = 12u))

        compose.onNodeWithText("Water the plants").assertIsDisplayed()
        compose.onNodeWithText("home.md:12").assertIsDisplayed()
    }

    // The row a tap comes from counts days -- "in 1 day" -- and says nothing
    // about which day that is. Before this the sheet said nothing either, and
    // the date could only be had by dismissing it and pressing the row again,
    // long, for the tooltip.
    @Test
    fun theSheetNamesTheDayTheTaskStandsOn() {
        show(task(date = "2026-07-28"))

        compose.onNodeWithTag("action-date")
            .assertTextEquals(
                string(
                    R.string.tooltip_scheduled_at,
                    statedDateLabel("2026-07-28", Locale.getDefault()),
                ),
            )
    }

    // An anniversary is the case that sent this: a yearly repeat whose row
    // counts down to a day it never names.
    @Test
    fun aRepeatingTaskNamesTheOccurrenceItIsCountingTowards() {
        show(task(date = "2020-08-24", repeater = "+1y", next = "2026-08-24", daysOffset = 1))

        compose.onNodeWithTag("action-date")
            .assertTextEquals(
                string(
                    R.string.tooltip_repeating_on,
                    " (+1y)",
                    statedDateLabel("2026-08-24", Locale.getDefault()),
                ),
            )
    }

    // The other half of the same question: a row standing on its own day is
    // asked which day that is, not which one comes after it. The tooltip
    // answers the opposite way, because there the row is still on screen.
    @Test
    fun aRepeatingRowOnItsOwnDayNamesThatDay() {
        show(
            task(
                date = "2026-08-23",
                time = "14:00",
                repeater = "++7d",
                next = "2026-08-23",
                nextAfter = "2026-08-30",
            ),
        )

        compose.onNodeWithTag("action-date")
            .assertTextEquals(
                string(
                    R.string.tooltip_repeating_on,
                    " (++7d)",
                    statedDateLabel("2026-08-23", Locale.getDefault()) + " " +
                        statedTimeLabel(
                            "14:00",
                            Locale.getDefault(),
                            DateFormat.is24HourFormat(compose.activity),
                        ),
                ),
            )
    }

    @Test
    fun anEntryWithNoDateStatesNone() {
        show(task(timestampType = null, date = null))

        compose.onNodeWithTag("action-date").assertDoesNotExist()
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

        // Displayed as well as present: the two buttons share the row, and a
        // wrapper that swallowed the weight once pushed this one off screen.
        compose.onNodeWithTag("action-shift-forward").assertIsDisplayed().performClick()

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

    @Test
    fun anActionSaysWhatItWritesIntoTheNote() {
        // The button is named after the outcome — "Done" — and says nothing
        // about the keyword that lands in the file.
        show(task())

        compose.onNodeWithTag("action-complete").performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_action_complete), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun aRepeatingTaskIsToldWhatCompletingMovesInstead() {
        show(task(repeater = "+1w", timestampType = TimestampType.SCHEDULED))

        compose.onNodeWithTag("action-complete").performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_action_complete_repeating), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theLineUnderTheHeadingSaysWhatItPointsAt() {
        show(task(file = "home.md", line = 12u))

        compose.onNodeWithText("home.md:12").performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_action_where), substring = true)
            .assertIsDisplayed()
    }

    // ---- one occurrence of a series ----------------------------------------

    @Test
    fun aTaskThatDoesNotRepeatHasNoOccurrenceToActOn() {
        show(task(repeater = null))

        compose.onNodeWithTag("action-cancel-occurrence").assertDoesNotExist()
        compose.onNodeWithTag("action-move-occurrence").assertDoesNotExist()
    }

    @Test
    fun oneOccurrenceOfASeriesCanBeCancelledOnTheDayItStandsOn() {
        show(task(repeater = "+1w", date = "2026-08-20"))

        compose.onNodeWithTag("action-cancel-occurrence").performScrollTo().performClick()

        assertEquals(
            listOf<TaskAction>(TaskAction.CancelOccurrence(LocalDate.of(2026, 8, 20))),
            actions,
        )
    }

    @Test
    fun movingOneOccurrenceAsksWhichDayFirst() {
        show(task(repeater = "+1w", date = "2026-08-20"))

        compose.onNodeWithTag("action-move-occurrence").performScrollTo().performClick()

        compose.onNodeWithTag("date-picker").assertIsDisplayed()
    }

    @Test
    fun anUntimedSeriesIsMovedByTheDayAlone() {
        show(task(repeater = "+1w", date = "2026-08-20", time = null))

        compose.onNodeWithTag("action-move-occurrence").performScrollTo().performClick()
        compose.onNodeWithTag("date-set").performClick()

        assertEquals(
            listOf<TaskAction>(
                TaskAction.MoveOccurrence(
                    LocalDate.of(2026, 8, 20),
                    LocalDate.of(2026, 8, 20),
                    null,
                ),
            ),
            actions,
        )
    }

    @Test
    fun aSeriesHeldAtAnHourIsAskedForTheHourToo() {
        // The case the whole operation exists for: the class is at three every
        // Thursday, and this Thursday it is at six.
        show(task(repeater = "+1w", date = "2026-08-20", time = "15:00"))

        compose.onNodeWithTag("action-move-occurrence").performScrollTo().performClick()
        compose.onNodeWithTag("date-set").performClick()

        compose.onNodeWithTag("time-picker").assertIsDisplayed()
    }

    @Test
    fun theTimeConfirmedIsTheTimeTheOccurrenceMovesTo() {
        show(task(repeater = "+1w", date = "2026-08-20", time = "15:00"))

        compose.onNodeWithTag("action-move-occurrence").performScrollTo().performClick()
        compose.onNodeWithTag("date-set").performClick()
        compose.onNodeWithTag("time-set").performClick()

        assertEquals(
            listOf<TaskAction>(
                TaskAction.MoveOccurrence(
                    LocalDate.of(2026, 8, 20),
                    LocalDate.of(2026, 8, 20),
                    LocalTime.of(15, 0),
                ),
            ),
            actions,
        )
    }

    @Test
    fun cancellingOneOccurrenceSaysWhatItWritesIntoTheSeries() {
        show(task(repeater = "+1w", date = "2026-08-20"))

        compose.onNodeWithTag("action-cancel-occurrence")
            .performScrollTo()
            .performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_action_cancel_occurrence), substring = true)
            .assertIsDisplayed()
    }

    // ---- handing the note to another application ---------------------------

    @Test
    fun theSheetOffersToOpenTheNoteElsewhere() {
        show(task())

        // Scrolled to first: the sheet is taller than a short screen once a
        // dated task adds its rows of date actions, and the last action of all
        // is this one.
        compose.onNodeWithText(string(R.string.action_open_externally))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun openingElsewhereSaysWhatItLeavesUncommitted() {
        show(task())

        compose.onNodeWithTag("action-open-externally")
            .performScrollTo()
            .performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_action_open_externally), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun editingTheEntryReachesTheCaller() {
        show(task())

        compose.onNodeWithTag("action-edit-entry").performScrollTo().performClick()

        assertEquals(1, edits)
    }

    @Test
    fun openingElsewhereReachesTheCaller() {
        show(task())

        compose.onNodeWithTag("action-open-externally").performScrollTo().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun aCallerWithNowhereToSendItIsOfferedNothing() {
        compose.setContent {
            MarkdownOrgTheme {
                TaskActionsSheet(
                    task = task(),
                    onAction = { action -> actions += action },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("action-open-externally").assertDoesNotExist()
        compose.onNodeWithTag("action-edit-entry").assertDoesNotExist()
    }

    private fun show(task: Task, dictation: Dictation = Dictation { _, _ -> true }) {
        compose.setContent {
            MarkdownOrgTheme {
                TaskActionsSheet(
                    task = task,
                    onAction = { action -> actions += action },
                    onDismiss = {},
                    onEdit = { edits += 1 },
                    onOpenExternally = { opened += 1 },
                    dictation = dictation,
                )
            }
        }
    }

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    // --- changing the entry by phrase --------------------------------------

    @Test
    fun thePhraseIsHandedOverOnlyOnceThereIsOne() {
        show(task())

        // An empty field has nothing to apply: the sentence is what the rules
        // are given, and an empty one would name no field at all.
        compose.onNodeWithTag("action-phrase-apply").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag("action-phrase").performScrollTo().performTextInput("на пятницу")

        compose.onNodeWithTag("action-phrase-apply").assertIsEnabled()
    }

    @Test
    fun whatWasTypedReachesTheActionAndLeavesTheFieldEmpty() {
        show(task())

        compose.onNodeWithTag("action-phrase")
            .performScrollTo()
            .performTextInput("отметь выполненной")
        compose.onNodeWithTag("action-phrase-apply").performClick()

        assertEquals(listOf<TaskAction>(TaskAction.Phrase("отметь выполненной")), actions)
        // Emptied, because the sheet closes on an action: a field left filled
        // would offer the same sentence again the next time it opens.
        compose.onNodeWithTag("action-phrase-apply").assertIsNotEnabled()
    }

    @Test
    fun whatWasHeardJoinsWhatTheFieldAlreadyHolds() {
        // A sentence may be said in two goes, and a word corrected by hand
        // before speaking again is not worth losing.
        show(
            task(),
            dictation = Dictation { _, onSpoken ->
                onSpoken("в 16:00")
                true
            },
        )

        compose.onNodeWithTag("action-phrase")
            .performScrollTo()
            .performTextInput("перенеси на пятницу")
        compose.onNodeWithTag("action-phrase-speak").performClick()
        compose.onNodeWithTag("action-phrase-apply").performClick()

        assertEquals(listOf<TaskAction>(TaskAction.Phrase("перенеси на пятницу в 16:00")), actions)
    }

    @Test
    fun aPhoneWithNothingToListenWithSaysSoUnderTheField() {
        show(task(), dictation = Dictation { _, _ -> false })

        compose.onNodeWithTag("action-phrase-speak").performScrollTo().performClick()

        compose.onNodeWithText(string(R.string.create_phrase_unheard)).assertIsDisplayed()
        // And nothing was handed over: there is no sentence to apply.
        assertEquals(emptyList<TaskAction>(), actions)
    }
}
