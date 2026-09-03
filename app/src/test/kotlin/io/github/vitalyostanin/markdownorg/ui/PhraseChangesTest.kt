package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.markdown_org_ffi.PhraseDraft
import uniffi.markdown_org_ffi.PhraseField
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType

/**
 * What the line under the screen says a phrase did.
 *
 * A phrase says several things at once, and "the note has been written" leaves
 * the reader to open the note to see whether all of them were heard. These are
 * the fields that line names, and the values it names them with.
 */
class PhraseChangesTest {

    @Test
    fun `every field the phrase named is listed with both values`() {
        val entry = task(
            taskType = TaskType.TODO,
            priority = "B",
            date = "2026-09-01",
            time = "15:00",
        )
        val said = draft(
            status = TaskType.DONE,
            priority = "A",
            date = "2026-09-04",
            time = "16:00",
        )

        assertEquals(
            listOf(
                PhraseChange(PhraseChangedField.STATUS, "TODO", "DONE"),
                PhraseChange(PhraseChangedField.PRIORITY, "B", "A"),
                PhraseChange(PhraseChangedField.DATE, "2026-09-01", "2026-09-04"),
                PhraseChange(PhraseChangedField.TIME, "15:00", "16:00"),
            ),
            phraseChanges(entry, said),
        )
    }

    @Test
    fun `a field the phrase emptied is named as emptied`() {
        val entry = task(
            priority = "A",
            date = "2026-09-01",
            time = "15:00",
            repeater = "+1w",
        )
        val said = draft(
            cleared = listOf(PhraseField.PRIORITY, PhraseField.TIME, PhraseField.REPEATER),
        )

        assertEquals(
            listOf(
                PhraseChange(PhraseChangedField.PRIORITY, "A", null),
                PhraseChange(PhraseChangedField.TIME, "15:00", null),
                PhraseChange(PhraseChangedField.REPEATER, "+1w", null),
            ),
            phraseChanges(entry, said),
        )
    }

    @Test
    fun `a field named to what the entry already said is not a change`() {
        // The core reports such a phrase as an edit that wrote nothing, and
        // the line has to agree: naming "срочность A → A" would read as an
        // edit that happened.
        val entry = task(taskType = TaskType.TODO, priority = "A", date = "2026-09-01")
        val said = draft(status = TaskType.TODO, priority = "A", date = "2026-09-01")

        assertEquals(emptyList<PhraseChange>(), phraseChanges(entry, said))
    }

    @Test
    fun `emptying a field the entry never carried is not a change either`() {
        val entry = task(priority = null, time = null)
        val said = draft(cleared = listOf(PhraseField.PRIORITY, PhraseField.TIME))

        assertEquals(emptyList<PhraseChange>(), phraseChanges(entry, said))
    }

    @Test
    fun `moving the date to the other planning line is named as well`() {
        val entry = task(timestampType = TimestampType.SCHEDULED, date = "2026-09-01")
        val said = draft(keyword = PlanningKeyword.DEADLINE, date = "2026-09-04")

        assertEquals(
            listOf(
                PhraseChange(PhraseChangedField.PLANNING, "SCHEDULED", "DEADLINE"),
                PhraseChange(PhraseChangedField.DATE, "2026-09-01", "2026-09-04"),
            ),
            phraseChanges(entry, said),
        )
    }

    @Test
    fun `a planning keyword without a date of its own says nothing`() {
        // The keyword travels with the date in the rules: a draft carrying one
        // and no date names no line to move anything to.
        val entry = task(timestampType = TimestampType.SCHEDULED, date = "2026-09-01")
        val said = draft(keyword = PlanningKeyword.DEADLINE, priority = "A")

        assertEquals(
            listOf(PhraseChange(PhraseChangedField.PRIORITY, null, "A")),
            phraseChanges(entry, said),
        )
    }

    @Test
    fun `a plain timestamp is not a planning line the phrase moved off`() {
        val entry = task(timestampType = TimestampType.PLAIN, date = "2026-09-01")
        val said = draft(keyword = PlanningKeyword.SCHEDULED, date = "2026-09-01")

        assertEquals(
            listOf(PhraseChange(PhraseChangedField.PLANNING, null, "SCHEDULED")),
            phraseChanges(entry, said),
        )
    }

    @Test
    fun `an entry with nothing on it names what it gained`() {
        val entry = task(taskType = null, priority = null, timestampType = null, date = null)
        val said = draft(status = TaskType.TODO, date = "2026-09-04", repeater = "+1w")

        assertEquals(
            listOf(
                PhraseChange(PhraseChangedField.STATUS, null, "TODO"),
                PhraseChange(PhraseChangedField.DATE, null, "2026-09-04"),
                PhraseChange(PhraseChangedField.REPEATER, null, "+1w"),
            ),
            phraseChanges(entry, said),
        )
    }
}

/** What the rules read a phrase into, with only the named fields set. */
private fun draft(
    heading: String = "",
    priority: String? = null,
    keyword: PlanningKeyword? = null,
    date: String? = null,
    time: String? = null,
    repeater: String? = null,
    status: TaskType? = null,
    cleared: List<PhraseField> = emptyList(),
): PhraseDraft = PhraseDraft(
    heading = heading,
    priority = priority,
    keyword = keyword,
    date = date,
    time = time,
    repeater = repeater,
    status = status,
    cleared = cleared,
)
