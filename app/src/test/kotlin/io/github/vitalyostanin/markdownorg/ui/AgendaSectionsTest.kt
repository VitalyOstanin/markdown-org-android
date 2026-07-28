package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.TaskType

class AgendaSectionsTest {

    @Test
    fun `buckets become the three sections`() {
        val sections = agenda(
            day(
                overdue = listOf(task(heading = "Renew certificate", daysOffset = -3)),
                scheduledTimed = listOf(task(heading = "Standup", time = "09:30")),
                scheduledNoTime = listOf(task(heading = "Update pins")),
                upcoming = listOf(task(heading = "Quarterly report", daysOffset = 5)),
            ),
        ).toSections()

        assertEquals(listOf("Renew certificate"), sections.overdue.headings())
        assertEquals(listOf("Standup"), sections.timed.headings())
        // All-day first, then what is ahead: today outranks later.
        assertEquals(listOf("Update pins", "Quarterly report"), sections.untimed.headings())
    }

    @Test
    fun `days are flattened in order`() {
        val sections = agenda(
            day(date = "2026-07-28", scheduledTimed = listOf(task(heading = "First", time = "10:00"))),
            day(date = "2026-07-29", scheduledTimed = listOf(task(heading = "Second", time = "09:00"))),
        ).toSections()

        assertEquals(listOf("First", "Second"), sections.timed.headings())
    }

    @Test
    fun `the flat scope lands in the untimed section`() {
        val sections = flatAgenda(task(heading = "Someday", daysOffset = null)).toSections()

        assertEquals(listOf("Someday"), sections.untimed.headings())
        assertTrue(sections.overdue.isEmpty())
        assertTrue(sections.timed.isEmpty())
    }

    @Test
    fun `an empty agenda reports itself empty`() {
        assertTrue(agenda(day()).toSections().isEmpty)
    }

    @Test
    fun `a timed row shows its time`() {
        val row = agenda(day(scheduledTimed = listOf(task(time = "09:30")))).toSections().timed.single()

        assertEquals("09:30", row.time)
    }

    @Test
    fun `an overdue row without a time shows the date it slipped from`() {
        val row = agenda(
            day(overdue = listOf(task(date = "2026-07-24", daysOffset = -4))),
        ).toSections().overdue.single()

        assertEquals("24.07", row.time)
        assertEquals(-4L, row.daysOffset)
    }

    @Test
    fun `a future all-day row shows no time at all`() {
        val row = agenda(
            day(upcoming = listOf(task(date = "2026-08-02", daysOffset = 5))),
        ).toSections().untimed.single()

        assertEquals("", row.time)
    }

    @Test
    fun `a missing offset counts as today`() {
        val row = flatAgenda(task(daysOffset = null)).toSections().untimed.single()

        assertEquals(0L, row.daysOffset)
    }

    @Test
    fun `the keyword outranks the timestamp when picking a kind`() {
        val cancelled = task(taskType = TaskType.CANCELLED, timestampType = "DEADLINE", repeater = "++7d")
        val done = task(taskType = TaskType.DONE, repeater = "++7d")
        val repeating = task(timestampType = "DEADLINE", repeater = "++7d")

        assertEquals(AgendaKind.CANCELLED, cancelled.kind())
        assertEquals(AgendaKind.DONE, done.kind())
        assertEquals(AgendaKind.REPEAT, repeating.kind())
        assertEquals(AgendaKind.DEADLINE, task(timestampType = "DEADLINE").kind())
        assertEquals(AgendaKind.SCHEDULED, task().kind())
        // A heading with no keyword is still an entry, and it reads as
        // scheduled rather than as nothing.
        assertEquals(AgendaKind.SCHEDULED, task(taskType = null).kind())
    }

    @Test
    fun `only finished and dropped tasks are struck through`() {
        assertEquals(null, AgendaKind.SCHEDULED.decoration())
        assertEquals(null, AgendaKind.DEADLINE.decoration())
        assertEquals(null, AgendaKind.REPEAT.decoration())
        assertTrue(AgendaKind.DONE.decoration() != null)
        assertTrue(AgendaKind.CANCELLED.decoration() != null)
    }
}

private fun List<AgendaRow>.headings(): List<String> = map { it.task.heading }
