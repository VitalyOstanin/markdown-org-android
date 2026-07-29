package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.TaskType

/**
 * What the history of someone's notes ends up reading like.
 *
 * The messages are built from the outcome of the edit rather than from the
 * request, so a completion that turned out to be a repeat has to say that and
 * not "done".
 */
class CommitMessageTest {

    @Test
    fun aCompletionOfARepeatingTaskSaysItMovedRatherThanFinished() {
        assertEquals(
            "Move \"Water the plants\" to its next occurrence",
            completionMessage("Water the plants", repeated = true),
        )
        assertEquals(
            "Mark \"Water the plants\" as done",
            completionMessage("Water the plants", repeated = false),
        )
    }

    @Test
    fun clearingTheKeywordIsNotDescribedAsSettingOne() {
        assertEquals("Clear the keyword on \"Call back\"", statusMessage("Call back", null))
    }

    @Test
    fun eachKeywordIsNamedInTheMessage() {
        assertEquals("Set \"Call back\" to TODO", statusMessage("Call back", TaskType.TODO))
        assertEquals("Set \"Call back\" to DONE", statusMessage("Call back", TaskType.DONE))
        assertEquals(
            "Set \"Call back\" to CANCELLED",
            statusMessage("Call back", TaskType.CANCELLED),
        )
    }

    @Test
    fun droppingThePriorityIsNotDescribedAsSettingOne() {
        assertEquals("Drop the priority of \"Pay rent\"", priorityMessage("Pay rent", null))
        assertEquals("Set the priority of \"Pay rent\" to A", priorityMessage("Pay rent", "A"))
    }

    @Test
    fun aShiftNamesTheDateThatMoved() {
        assertEquals(
            "Move the SCHEDULED of \"Pay rent\" forward by 7 days",
            shiftMessage("Pay rent", PlanningKeyword.SCHEDULED, 7),
        )
        assertEquals(
            "Move the DEADLINE of \"Pay rent\" forward by 7 days",
            shiftMessage("Pay rent", PlanningKeyword.DEADLINE, 7),
        )
    }

    @Test
    fun aShiftOfOneDayIsNotPluralised() {
        assertEquals(
            "Move the SCHEDULED of \"Pay rent\" forward by 1 day",
            shiftMessage("Pay rent", PlanningKeyword.SCHEDULED, 1),
        )
    }

    @Test
    fun aShiftBackwardsReadsAsBackwardsRatherThanAsMinusOne() {
        assertEquals(
            "Move the SCHEDULED of \"Pay rent\" back by 1 day",
            shiftMessage("Pay rent", PlanningKeyword.SCHEDULED, -1),
        )
        assertEquals(
            "Move the DEADLINE of \"Pay rent\" back by 3 days",
            shiftMessage("Pay rent", PlanningKeyword.DEADLINE, -3),
        )
    }

    @Test
    fun aShiftOfNothingSaysNothingMoved() {
        assertEquals(
            "Leave the SCHEDULED of \"Pay rent\" where it is",
            shiftMessage("Pay rent", PlanningKeyword.SCHEDULED, 0),
        )
    }
}
