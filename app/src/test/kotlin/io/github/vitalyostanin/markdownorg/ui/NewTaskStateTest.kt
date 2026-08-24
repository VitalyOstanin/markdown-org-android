package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.markdown_org_ffi.PlanningKeyword
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
    }

    @Test
    fun `a task with no date at all comes back with none`() {
        val restored = restore(NewTaskState("1").apply { title = "A note, not a task" })

        assertNull(restored.day)
        assertNull(restored.time)
        assertNull(restored.repeater)
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

    /** Everything held here is saveable, so the scope answers for anything. */
    private fun restore(state: NewTaskState): NewTaskState {
        val saved = with(NewTaskState.Saver) { SaverScope { true }.save(state) }

        return NewTaskState.Saver.restore(saved!!)!!
    }
}
