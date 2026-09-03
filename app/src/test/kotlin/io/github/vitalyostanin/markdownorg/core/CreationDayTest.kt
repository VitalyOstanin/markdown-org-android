package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The day a new task is written for when what was said named an hour and no
 * day.
 *
 * The rules of the extractor set the hour and the repeater without a day --
 * "позвонить врачу в 15:00" names an hour and nothing else -- while a planning
 * line cannot hold an hour without a day to hold it on. Written as it stood,
 * the hour was dropped on the way to the file and the entry arrived as a bare
 * heading, with no screen in between for the loss to be noticed on.
 *
 * The day chosen is the next the hour comes round on: today while it is still
 * ahead, tomorrow once it has passed. Which of the two it was is answered
 * along with the date, because the screen says why the day it was not asked
 * for is the day it wrote.
 */
class CreationDayTest {

    @Test
    fun `an hour still ahead today falls on today`() {
        val draft = TaskDraft(title = "Позвонить врачу", time = LocalTime.of(15, 0))

        assertEquals(
            AssumedDay(date = TODAY, hour = LocalTime.of(15, 0), passed = false),
            draft.assumedDay(TODAY.at(10, 0)),
        )
    }

    @Test
    fun `an hour that has passed falls on tomorrow`() {
        val draft = TaskDraft(title = "Позвонить врачу", time = LocalTime.of(15, 0))

        assertEquals(
            AssumedDay(date = TODAY.plusDays(1), hour = LocalTime.of(15, 0), passed = true),
            draft.assumedDay(TODAY.at(16, 0)),
        )
    }

    /** The minute named is over by the time the file is written. */
    @Test
    fun `an hour that is the current minute falls on tomorrow`() {
        val draft = TaskDraft(title = "Позвонить врачу", time = LocalTime.of(15, 0))

        assertEquals(TODAY.plusDays(1), draft.assumedDay(TODAY.at(15, 0))?.date)
    }

    @Test
    fun `a repeat with no hour starts today`() {
        val draft = TaskDraft(title = "Полить цветы", repeater = "+1w")

        assertEquals(
            AssumedDay(date = TODAY, hour = null, passed = false),
            draft.assumedDay(TODAY.at(23, 0)),
        )
    }

    @Test
    fun `a day that was named is not assumed`() {
        val draft = TaskDraft(
            title = "Позвонить врачу",
            date = LocalDate.of(2026, 9, 10),
            time = LocalTime.of(9, 0),
        )

        assertNull(draft.assumedDay(TODAY.at(10, 0)))
    }

    @Test
    fun `a task naming neither a day nor an hour keeps no planning line`() {
        assertNull(TaskDraft(title = "Купить хлеба").assumedDay(TODAY.at(10, 0)))
    }

    @Test
    fun `the draft written is the draft with the day it was given`() {
        val draft = TaskDraft(title = "Позвонить врачу", time = LocalTime.of(15, 0))

        assertEquals(TODAY, draft.onItsDay(TODAY.at(10, 0)).date)
        assertEquals(LocalTime.of(15, 0), draft.onItsDay(TODAY.at(10, 0)).time)
    }

    @Test
    fun `a draft needing no day is left as it is`() {
        val draft = TaskDraft(title = "Купить хлеба")

        assertEquals(draft, draft.onItsDay(TODAY.at(10, 0)))
    }

    private fun LocalDate.at(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(this, LocalTime.of(hour, minute))

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 4)
    }
}
