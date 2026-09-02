package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.TaskType
import java.time.LocalDate
import java.time.LocalTime

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

    @Test
    fun anEntryWhoseTitleStayedSaysOnlyThatItWasRewritten() {
        assertEquals(
            "Rewrite the text of \"Pay rent\"",
            entryMessage("Pay rent", "Pay rent"),
        )
    }

    @Test
    fun aDateWrittenNamesTheKindAndTheDay() {
        assertEquals(
            "Set the SCHEDULED of \"Pay rent\" to 2026-08-19",
            planningMessage("Pay rent", PlanningKeyword.SCHEDULED, LocalDate.of(2026, 8, 19)),
        )
    }

    @Test
    fun aDateTakenOffSaysWhichKindWentRatherThanNamingADay() {
        assertEquals(
            "Take the DEADLINE off \"Pay rent\"",
            planningMessage("Pay rent", PlanningKeyword.DEADLINE, null),
        )
    }

    @Test
    fun anUndoneEditNamesTheTaskItPutBack() {
        assertEquals(
            "Undo the edit of \"Pay rent\"",
            undoEditMessage("Pay rent"),
        )
    }

    @Test
    fun aTaskWrittenFromNothingSaysItWasCreated() {
        assertEquals(
            "Create \"Pay rent\"",
            creationMessage("  Pay rent  "),
        )
    }

    @Test
    fun anUndoneCreationSaysTheEntryWentRatherThanThatANoteWentBack() {
        // The two undos put the same file back and mean different things: one
        // returns a line to what it said, the other takes an entry out.
        assertEquals(
            "Undo the creation of \"Pay rent\"",
            undoCreationMessage("Pay rent"),
        )
    }

    @Test
    fun aRetitledEntryNamesBothTitles() {
        // What a reader of the history looks for is the title they remember,
        // so the message has to carry the one that is no longer in the file.
        assertEquals(
            "Rewrite \"Pay rent\", now \"Pay the rent\"",
            entryMessage("Pay rent", "  Pay the rent  "),
        )
    }

    @Test
    fun cancellingOneOccurrenceSaysWhichDayLeftTheSeries() {
        assertEquals(
            "Take the occurrence of \"English\" on 2026-08-20 out of the series",
            occurrenceCancelMessage("English", LocalDate.of(2026, 8, 20)),
        )
    }

    @Test
    fun movingOneOccurrenceNamesTheDayItLeftAndWhereItWent() {
        assertEquals(
            "Move the occurrence of \"English\" on 2026-08-20 to 2026-08-20 18:00",
            occurrenceMoveMessage(
                "English",
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 20),
                LocalTime.of(18, 0),
            ),
        )
    }

    @Test
    fun anOccurrenceMovedToAnotherDayAloneNamesNoTime() {
        assertEquals(
            "Move the occurrence of \"English\" on 2026-08-20 to 2026-08-27",
            occurrenceMoveMessage(
                "English",
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 27),
                null,
            ),
        )
    }

    @Test
    fun aGroupEditNamesTheActionAndHowManyItTouched() {
        assertEquals(
            "Move 3 overdue tasks to today",
            groupMessage(BulkAction.MOVE_TO_TODAY, 3),
        )
        assertEquals("Drop the date of 2 tasks", groupMessage(BulkAction.DROP_PLANNING, 2))
        assertEquals("Cancel 4 tasks", groupMessage(BulkAction.CANCEL, 4))
    }

    @Test
    fun aGroupOfOneIsNotPluralised() {
        assertEquals("Cancel 1 task", groupMessage(BulkAction.CANCEL, 1))
        assertEquals("Undo the group edit of 1 note", undoMessage(1))
    }

    @Test
    fun anUndoneGroupSaysHowManyNotesWentBack() {
        assertEquals("Undo the group edit of 5 notes", undoMessage(5))
    }

    @Test
    fun anEntryMovedToAnotherFileNamesBoth() {
        assertEquals(
            "Move \"Pay rent\" to inbox.md",
            moveMessage("  Pay rent  ", "inbox.md"),
        )
    }

    @Test
    fun anEntryChangedByPhraseSaysSoWithoutRepeatingTheSentence() {
        // The sentence is the request, not the outcome: a phrase that moved a
        // day and set a priority would read as neither in the history.
        assertEquals("Change \"English\" by phrase", phraseMessage("English"))
    }

    @Test
    fun anHourWrittenNamesTheKeywordAndTheHour() {
        assertEquals(
            "Set the SCHEDULED of \"English\" to 18:00",
            planningTimeMessage("English", PlanningKeyword.SCHEDULED, LocalTime.of(18, 0)),
        )
    }

    @Test
    fun anHourTakenOffSaysTheHourWentRatherThanNamingOne() {
        assertEquals(
            "Take the hour off the DEADLINE of \"Report\"",
            planningTimeMessage("Report", PlanningKeyword.DEADLINE, null),
        )
    }
}
