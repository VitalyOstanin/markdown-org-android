package io.github.vitalyostanin.markdownorg.core

import io.github.vitalyostanin.markdownorg.ReminderActions
import io.github.vitalyostanin.markdownorg.ui.day
import io.github.vitalyostanin.markdownorg.ui.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What pressing a button on a reminder means, and which entry it is about.
 *
 * The receiver that carries it out has nine seconds and no screen; what is
 * worth asserting is the decision it makes before either matters -- what the
 * reader gets back in a quarter of an hour, and which task in the day the
 * press is answered against.
 */
class ReminderAnswerTest {

    /**
     * The entry keeps the hour it starts at. Only the moment it is announced
     * moves, so what arrives in a quarter of an hour is the same reminder,
     * worded the same way.
     */
    @Test
    fun `later is a quarter of an hour on, with the hour left alone`() {
        val answer = answerTo(ReminderActions.SNOOZE, REMINDER, at(14, 45))

        assertEquals(
            ReminderAnswer.HoldAside(REMINDER.copy(at = at(15, 0))),
            answer,
        )
    }

    @Test
    fun `done is the entry the reminder was about`() {
        assertEquals(
            ReminderAnswer.Close(REMINDER),
            answerTo(ReminderActions.DONE, REMINDER, at(14, 45)),
        )
    }

    /**
     * A broadcast to this receiver can come from anywhere on the device. One
     * naming an action this application does not offer is ignored rather than
     * answered by guessing which button was meant.
     */
    @Test
    fun `a broadcast naming something else is no answer`() {
        assertNull(answerTo("android.intent.action.VIEW", REMINDER, at(14, 45)))
        assertNull(answerTo(null, REMINDER, at(14, 45)))
    }

    @Test
    fun `the entry is found wherever in the day it sits`() {
        val buckets = listOf(
            day(overdue = listOf(named())),
            day(scheduledTimed = listOf(named())),
            day(scheduledNoTime = listOf(named())),
            day(upcoming = listOf(named())),
        )

        assertEquals(
            List(buckets.size) { ENTRY.heading },
            buckets.map { holding -> holding.entryNamed(ENTRY)?.heading },
        )
    }

    /**
     * The entry may have been closed on another device, or moved, between the
     * plan and the press. Nothing found is that case, and it is the caller's
     * to answer for -- silently closing whatever now stands at the line would
     * close an entry the reader never saw announced.
     */
    @Test
    fun `an entry that has moved off its line is not the one announced`() {
        val holding = day(scheduledTimed = listOf(named(line = 13u)))

        assertNull(holding.entryNamed(ENTRY))
    }

    @Test
    fun `the same line of another note is not the one announced`() {
        val holding = day(scheduledTimed = listOf(named(file = "home.md")))

        assertNull(holding.entryNamed(ENTRY))
    }

    @Test
    fun `the same note in another collection is not the one announced`() {
        val holding = day(scheduledTimed = listOf(named(root = "/notes/home")))

        assertNull(holding.entryNamed(ENTRY))
    }

    private fun named(
        root: String? = ENTRY.root,
        file: String = ENTRY.file,
        line: UInt = ENTRY.line,
    ) = task(heading = ENTRY.heading, line = line, file = file, root = root, time = "15:00")

    private fun at(hour: Int, minute: Int): ZonedDateTime =
        LocalDate.of(2026, 9, 4).atTime(hour, minute).atZone(ZONE)

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")

        val ENTRY = ReminderEntry(
            root = "/notes/work",
            file = "inbox.md",
            line = 12u,
            heading = "Call the notary",
        )

        val REMINDER = TimedReminder(
            at = LocalDate.of(2026, 9, 4).atTime(14, 45).atZone(ZONE),
            starts = LocalDate.of(2026, 9, 4).atTime(15, 0).atZone(ZONE),
            entry = ENTRY,
        )
    }
}
