package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.ReminderLead
import uniffi.markdown_org_ffi.ReminderUnit
import uniffi.markdown_org_ffi.TaskType
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the creation screen holds while it is being typed into.
 *
 * The activity declares no `configChanges`, so turning the phone rebuilds the
 * screen: everything typed has to come back through the saver, and a field
 * added to the state without being added to it is lost on the first rotation
 * without anything failing.
 */
class NewTaskStateTest {

    @Test
    fun `everything typed comes back after a rotation`() {
        val state = NewTaskState("work").apply {
            title = "Water the plants"
            body = "The big one by the window first."
            status = TaskType.CANCELLED
            priority = "B"
            keyword = PlanningKeyword.DEADLINE
            day = LocalDate.of(2026, 8, 21)
            time = LocalTime.of(9, 30)
            repeater = "++1w"
            reminder = ReminderLead(3u, ReminderUnit.DAY)
        }

        val restored = restore(state)

        assertEquals("work", restored.collectionId)
        assertEquals("Water the plants", restored.title)
        assertEquals("The big one by the window first.", restored.body)
        assertEquals(TaskType.CANCELLED, restored.status)
        assertEquals("B", restored.priority)
        assertEquals(PlanningKeyword.DEADLINE, restored.keyword)
        assertEquals(LocalDate.of(2026, 8, 21), restored.day)
        assertEquals(LocalTime.of(9, 30), restored.time)
        assertEquals("++1w", restored.repeater)
        assertEquals(ReminderLead(3u, ReminderUnit.DAY), restored.reminder)
    }

    @Test
    fun `a task with no date at all comes back with none`() {
        val restored = restore(NewTaskState("1").apply { title = "A note, not a task" })

        assertNull(restored.day)
        assertNull(restored.time)
        assertNull(restored.repeater)
        assertNull(restored.reminder)
    }

    @Test
    fun `the hour and the repeater go into the draft the writer takes`() {
        val draft = NewTaskState("1").apply {
            title = "Water the plants"
            day = LocalDate.of(2026, 8, 21)
            time = LocalTime.of(9, 0)
            repeater = "++1w"
        }.draft()

        assertEquals(LocalDate.of(2026, 8, 21), draft.date)
        assertEquals(LocalTime.of(9, 0), draft.time)
        assertEquals("++1w", draft.repeater)
    }

    /**
     * A lead time is a field of the entry rather than of its timestamp, so it
     * travels to the writer on its own and is handed to the rules beside the
     * date: a phrase that names one refines what the screen already shows.
     */
    @Test
    fun `the lead time reaches the writer and the rules alike`() {
        val state = NewTaskState("1").apply {
            title = "Ring the dentist"
            day = LocalDate.of(2026, 8, 21)
            reminder = ReminderLead(1u, ReminderUnit.HOUR)
        }

        assertEquals(ReminderLead(1u, ReminderUnit.HOUR), state.draft().reminder)
        assertEquals(ReminderLead(1u, ReminderUnit.HOUR), state.phraseDraft().reminder)
    }

    /** What a phrase filled in is what the screen goes on showing. */
    @Test
    fun `a lead time a phrase named is taken into the screen`() {
        val state = NewTaskState("1").apply { title = "Ring the dentist" }

        state.fill(
            state.phraseDraft().copy(reminder = ReminderLead(30u, ReminderUnit.MINUTE)),
        )

        assertEquals(ReminderLead(30u, ReminderUnit.MINUTE), state.reminder)
    }

    /** Everything held here is saveable, so the scope answers for anything. */
    private fun restore(state: NewTaskState): NewTaskState {
        val saved = with(NewTaskState.Saver) { SaverScope { true }.save(state) }

        return NewTaskState.Saver.restore(saved!!)!!
    }
}
