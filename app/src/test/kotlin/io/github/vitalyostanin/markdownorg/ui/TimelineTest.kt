package io.github.vitalyostanin.markdownorg.ui

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    @Test
    fun `an hour with entries becomes a row of the axis`() {
        val timeline = sections(
            timed = listOf(task(heading = "Standup", time = "09:30")),
        ).toTimeline(now = null)

        assertEquals(listOf<Any>(9), timeline.axis.hours())
        assertEquals(listOf("Standup"), timeline.axis.headingsAt(9))
    }

    @Test
    fun `entries in the same hour stay together and in order`() {
        val timeline = sections(
            timed = listOf(
                task(heading = "First", time = "09:00", line = 1u),
                task(heading = "Second", time = "09:45", line = 2u),
            ),
        ).toTimeline(now = null)

        assertEquals(1, timeline.axis.size)
        assertEquals(listOf("First", "Second"), timeline.axis.headingsAt(9))
    }

    @Test
    fun `the axis spans from the first busy hour to the last`() {
        val timeline = sections(
            timed = listOf(
                task(heading = "Morning", time = "09:00", line = 1u),
                task(heading = "Noon", time = "11:00", line = 2u),
            ),
        ).toTimeline(now = null)

        // Two empty hours would be needed to collapse; one stays a blank row.
        assertEquals(listOf(9, 10, 11), timeline.axis.hours())
        assertTrue(timeline.axis.filterIsInstance<AxisEntry.Gap>().isEmpty())
    }

    @Test
    fun `two empty hours are shown rather than collapsed`() {
        val timeline = sections(
            timed = listOf(
                task(heading = "Morning", time = "10:00", line = 1u),
                task(heading = "Afternoon", time = "13:00", line = 2u),
            ),
        ).toTimeline(now = null)

        assertEquals(listOf(10, 11, 12, 13), timeline.axis.hours())
    }

    @Test
    fun `three empty hours collapse into one gap`() {
        val timeline = sections(
            timed = listOf(
                task(heading = "Morning", time = "12:00", line = 1u),
                task(heading = "Afternoon", time = "16:00", line = 2u),
            ),
        ).toTimeline(now = null)

        val gap = timeline.axis.filterIsInstance<AxisEntry.Gap>().single()
        assertEquals(13, gap.from)
        // Exclusive end: the gap covers 13:00 through 15:59.
        assertEquals(16, gap.until)
        assertEquals(listOf(12, 16), timeline.axis.hours())
    }

    @Test
    fun `the marker goes before the first hour that is still ahead`() {
        val timeline = sections(
            timed = listOf(
                task(heading = "Morning", time = "09:00", line = 1u),
                task(heading = "Noon", time = "12:00", line = 2u),
            ),
        ).toTimeline(now = LocalTime.of(10, 15))

        assertEquals(listOf("h9", "h10", "now", "h11", "h12"), timeline.axis.shape())
    }

    @Test
    fun `the marker goes last when the whole axis is behind`() {
        val timeline = sections(
            timed = listOf(task(heading = "Morning", time = "09:00")),
        ).toTimeline(now = LocalTime.of(23, 0))

        assertEquals(listOf("h9", "now"), timeline.axis.shape())
    }

    @Test
    fun `the marker goes first when the axis has not started yet`() {
        val timeline = sections(
            timed = listOf(task(heading = "Evening", time = "18:00")),
        ).toTimeline(now = LocalTime.of(6, 30))

        assertEquals(listOf("now", "h18"), timeline.axis.shape())
    }

    @Test
    fun `the marker lands before a collapsed stretch that starts later`() {
        val timeline = sections(
            timed = listOf(
                task(heading = "Morning", time = "09:00", line = 1u),
                task(heading = "Afternoon", time = "14:00", line = 2u),
            ),
        ).toTimeline(now = LocalTime.of(9, 30))

        assertEquals(listOf("h9", "now", "g10", "h14"), timeline.axis.shape())
    }

    @Test
    fun `another day carries no marker`() {
        val timeline = sections(
            timed = listOf(task(heading = "Morning", time = "09:00")),
        ).toTimeline(now = null)

        assertTrue(timeline.axis.none { it is AxisEntry.Now })
    }

    @Test
    fun `an empty axis carries no marker either`() {
        val timeline = sections(untimed = listOf(task(heading = "Someday")))
            .toTimeline(now = LocalTime.of(10, 0))

        assertTrue(timeline.axis.isEmpty())
    }

    @Test
    fun `a timed task with an unreadable time falls back to the band`() {
        val timeline = sections(
            timed = listOf(task(heading = "Broken", time = "later")),
            untimed = listOf(task(heading = "Someday")),
        ).toTimeline(now = null)

        // Dropping it would break the promise that both layouts show the same
        // tasks, so it joins the untimed band instead.
        assertEquals(listOf("Broken", "Someday"), timeline.allDay.map { it.task.heading })
        assertTrue(timeline.axis.isEmpty())
    }

    @Test
    fun `overdue and untimed pass through untouched`() {
        val timeline = sections(
            overdue = listOf(task(heading = "Late", daysOffset = -2)),
            untimed = listOf(task(heading = "Ahead", daysOffset = 3)),
        ).toTimeline(now = null)

        assertEquals(listOf("Late"), timeline.overdue.map { it.task.heading })
        assertEquals(listOf("Ahead"), timeline.allDay.map { it.task.heading })
    }
}

private fun sections(
    overdue: List<uniffi.markdown_org_ffi.Task> = emptyList(),
    timed: List<uniffi.markdown_org_ffi.Task> = emptyList(),
    untimed: List<uniffi.markdown_org_ffi.Task> = emptyList(),
): AgendaSections = agenda(
    day(overdue = overdue, scheduledTimed = timed, scheduledNoTime = untimed),
).toSections()

private fun List<AxisEntry>.hours(): List<Int> =
    filterIsInstance<AxisEntry.Hour>().map { it.hour }

/** The axis as short tags, so a test can assert on order and not on fields. */
private fun List<AxisEntry>.shape(): List<String> = map { entry ->
    when (entry) {
        is AxisEntry.Hour -> "h${entry.hour}"
        is AxisEntry.Gap -> "g${entry.from}"
        AxisEntry.Now -> "now"
    }
}
