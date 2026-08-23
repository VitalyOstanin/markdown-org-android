package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType

/**
 * What the tooltip says about a task, decided here rather than on screen: the
 * wording lives in the resources, and what is picked from them is the part
 * worth pinning down.
 */
class TaskTooltipTest {

    @Test
    fun `a deadline is named with its date`() {
        val line = task(timestampType = TimestampType.DEADLINE, date = "2026-08-12").line()

        assertEquals(TooltipLine(R.string.tooltip_deadline_at, listOf("<2026-08-12>")), line)
    }

    @Test
    fun `a time of day joins the date it belongs to`() {
        val line = task(date = "2026-08-12", time = "14:00").line()

        assertEquals(
            TooltipLine(R.string.tooltip_scheduled_at, listOf("<2026-08-12> [14:00]")),
            line,
        )
    }

    @Test
    fun `a repeating task names the occurrence the core resolved`() {
        val line = task(date = "2026-07-01", time = "09:00", repeater = "+7d", next = "2026-08-05")
            .line()

        assertEquals(
            TooltipLine(
                R.string.tooltip_repeating_next,
                listOf(" (+7d)", "<2026-08-05> [09:00]"),
            ),
            line,
        )
    }

    @Test
    fun `a row drawn under a day names the occurrence after that day`() {
        // The reader is looking at one day, so "next" has to mean "after this
        // one". The core resolves that per rendered day and fills it in the
        // scheduled buckets alone (its ADR-0029); the copies it borrows into
        // today keep answering from today, which is what a row of arrears is
        // there to say.
        val dated = task(
            date = "2026-07-01",
            repeater = "+1d",
            next = "2026-08-05",
            nextAfter = "2026-08-13",
        ).line()
        val borrowed = task(date = "2026-07-01", repeater = "+1d", next = "2026-08-05").line()

        assertEquals(
            TooltipLine(R.string.tooltip_repeating_next, listOf(" (+1d)", "<2026-08-13>")),
            dated,
        )
        assertEquals(
            TooltipLine(R.string.tooltip_repeating_next, listOf(" (+1d)", "<2026-08-05>")),
            borrowed,
        )
    }

    @Test
    fun `asked for this occurrence a repeating row names the day it stands on`() {
        // What the sheet asks, where the tooltip asks the opposite: the sheet
        // covers the row it was opened from, so the occurrence after it is of
        // no use there. The core rewrites the date of every copy it renders
        // onto the occurrence that copy stands on, which is what is read here.
        val line = task(
            date = "2026-08-06",
            time = "14:00",
            repeater = "++7d",
            next = "2026-08-06",
            nextAfter = "2026-08-13",
        ).line(Occurrence.THIS)

        assertEquals(
            TooltipLine(R.string.tooltip_repeating_on, listOf(" (++7d)", "<2026-08-06> [14:00]")),
            line,
        )
    }

    @Test
    fun `a deadline coming due answers with the day it is counting towards`() {
        // The one copy the core does not re-date: a deadline borrowed into
        // today keeps what the file states, which for a yearly repeat is the
        // anchor years back. Naming that would answer a row reading "in 1 day"
        // with a date from another decade, so the resolved occurrence is named
        // instead. Arrears, whose date the core does rewrite, keep answering
        // with the occurrence they are in arrears for.
        val anniversary =
            task(date = "2020-08-24", repeater = "+1y", next = "2026-08-24", daysOffset = 1)
        val owed = task(date = "2026-08-20", repeater = "+1w", next = "2026-08-27", daysOffset = -3)

        assertEquals(
            TooltipLine(R.string.tooltip_repeating_on, listOf(" (+1y)", "<2026-08-24>")),
            anniversary.line(Occurrence.THIS),
        )
        assertEquals(
            TooltipLine(R.string.tooltip_repeating_on, listOf(" (+1w)", "<2026-08-20>")),
            owed.line(Occurrence.THIS),
        )
    }

    @Test
    fun `an hour repeater drops the time its next occurrence does not keep`() {
        // The next occurrence of an hour repeater is projected onto a whole-day
        // grid with the interval ignored, so the stated clock time is not its
        // time — see extract ADR-0023.
        val line = task(date = "2026-07-01", time = "09:00", repeater = "+3h", next = "2026-08-05")
            .line()

        assertEquals(
            TooltipLine(R.string.tooltip_repeating_next, listOf(" (+3h)", "<2026-08-05>")),
            line,
        )
        assertEquals(
            TooltipLine(R.string.tooltip_repeating_on, listOf(" (+3h)", "<2026-07-01>")),
            task(date = "2026-07-01", time = "09:00", repeater = "+3h", next = "2026-08-05")
                .line(Occurrence.THIS),
        )
    }

    @Test
    fun `without a resolved occurrence the task's own date is named as its own`() {
        val line = task(date = "2026-07-01", repeater = "++1m").line()

        assertEquals(
            TooltipLine(R.string.tooltip_repeating_on, listOf(" (++1m)", "<2026-07-01>")),
            line,
        )
    }

    @Test
    fun `a finished task says so instead of stating a plan`() {
        assertEquals(
            TooltipLine(R.string.tooltip_done),
            task(taskType = TaskType.DONE, date = "2026-07-01").line(),
        )
        assertEquals(
            TooltipLine(R.string.tooltip_cancelled),
            task(taskType = TaskType.CANCELLED, date = "2026-07-01").line(),
        )
    }

    @Test
    fun `a heading with no timestamp states nothing beyond itself`() {
        assertNull(task(timestampType = null, date = null).line())
    }

    @Test
    fun `a timestamp with no date still says what it is for`() {
        assertEquals(TooltipLine(R.string.tooltip_scheduled), task(date = null).line())
    }

    @Test
    fun `the ends of the priority scale are named, the middle is not`() {
        assertEquals(
            TooltipLine(R.string.tooltip_priority_highest, listOf("A")),
            task(priority = "A").tooltipPriority(),
        )
        assertEquals(
            TooltipLine(R.string.tooltip_priority_lowest, listOf("C")),
            task(priority = "C").tooltipPriority(),
        )
        assertEquals(
            TooltipLine(R.string.tooltip_priority, listOf("B")),
            task(priority = "B").tooltipPriority(),
        )
    }

    @Test
    fun `a priority the note writes in another form is passed through`() {
        assertEquals(
            TooltipLine(R.string.tooltip_priority, listOf("12")),
            task(priority = "12").tooltipPriority(),
        )
        assertNull(task(priority = null).tooltipPriority())
    }
}

/**
 * The kind line with the two formatters replaced by markers, so a test can see
 * which value went through which of them without depending on a locale.
 */
private fun Task.line(occurrence: Occurrence = Occurrence.NEXT): TooltipLine? =
    tooltipKind(date = { "<$it>" }, time = { "[$it]" }, occurrence = occurrence)
