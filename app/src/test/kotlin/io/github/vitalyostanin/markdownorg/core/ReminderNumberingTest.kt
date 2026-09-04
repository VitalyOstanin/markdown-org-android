package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers a reminder is addressed by, and what sharing one would cost.
 *
 * Nothing here reaches the platform, and that is the point: a notification
 * raised under a number another one holds replaces it, an alarm placed on a
 * request code another alarm holds reschedules it, and neither is reported
 * anywhere. What the reader sees of such a collision is a reminder that never
 * arrived, days after the entry it was about.
 */
class ReminderNumberingTest {

    @Test
    fun `an entry is announced under the same number every time`() {
        assertEquals(
            ReminderNumbering.notification(entry()),
            ReminderNumbering.notification(entry()),
        )
    }

    /**
     * The line is part of the address for the reason the edit uses it:
     * headings repeat, and two entries of one note announced under one number
     * would leave the reader with the later of them alone.
     */
    @Test
    fun `two entries of one note are announced apart`() {
        assertNotEquals(
            ReminderNumbering.notification(entry(line = 12u)),
            ReminderNumbering.notification(entry(line = 13u)),
        )
    }

    @Test
    fun `the same line of two notes is announced apart`() {
        assertNotEquals(
            ReminderNumbering.notification(entry(file = "work.md")),
            ReminderNumbering.notification(entry(file = "home.md")),
        )
    }

    @Test
    fun `the same note in two collections is announced apart`() {
        assertNotEquals(
            ReminderNumbering.notification(entry(root = "/notes/work")),
            ReminderNumbering.notification(entry(root = "/notes/home")),
        )
    }

    /**
     * The digest and the notification the closing service stands behind are
     * numbered below where entries begin, so an entry announced while an
     * entry is being closed does not take the service's notification down
     * with it.
     */
    @Test
    fun `an entry is never announced as the digest or as the work`() {
        val numbers = (0 until MANY).map { line ->
            ReminderNumbering.notification(entry(line = line.toUInt()))
        }

        assertTrue(
            numbers.all { number ->
                number != ReminderNumbering.DIGEST_NOTIFICATION &&
                    number != ReminderNumbering.WORKING_NOTIFICATION
            },
        )
    }

    /**
     * A hash is as likely to be the smallest integer as any other number, and
     * the absolute value of that one is itself -- still negative. Taken as a
     * remainder it put the notification outside the range set aside for it,
     * for one entry in four billion and for that entry every time.
     */
    @Test
    fun `a key of any sign falls inside the range`() {
        val keys = listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -1, 0, 1, Int.MAX_VALUE)

        assertTrue(
            keys.all { key ->
                ReminderNumbering.slot(key, ReminderNumbering.TIMED_COUNT) in
                    0 until ReminderNumbering.TIMED_COUNT
            },
        )
    }

    @Test
    fun `the two buttons of one reminder are told apart`() {
        assertNotEquals(
            ReminderNumbering.button(NOTIFICATION, second = false),
            ReminderNumbering.button(NOTIFICATION, second = true),
        )
    }

    /**
     * Pending intents are told apart by their request code and by the intent,
     * and extras count for neither: one code shared by two reminders would
     * leave both buttons holding whichever entry was packed last.
     */
    @Test
    fun `no button of one reminder is a button of another`() {
        val codes = (0 until MANY).flatMap { notification ->
            listOf(
                ReminderNumbering.button(notification, second = false),
                ReminderNumbering.button(notification, second = true),
            )
        }

        assertEquals(codes.size, codes.toSet().size)
    }

    /**
     * A reminder put off is not in the notes at all, so the next plan does not
     * name it. Numbered among the plan it would be cancelled by the replacing,
     * and the quarter of an hour the reader asked for would pass in silence.
     */
    @Test
    fun `an alarm held aside is never numbered where the plan is`() {
        val plan = (0 until ReminderNumbering.PLAN_COUNT).map(ReminderNumbering::planAlarm)
        val aside = listOf(Int.MIN_VALUE, -1, 0, 1, ReminderNumbering.TIMED_COUNT, Int.MAX_VALUE)
            .map(ReminderNumbering::alarmHeldAside)

        assertTrue(aside.all { held -> held > plan.max() })
    }

    private fun entry(root: String? = ROOT, file: String = FILE, line: UInt = LINE) =
        ReminderEntry(root = root, file = file, line = line, heading = "Call the notary")

    private companion object {

        /** Enough of them that a rule holding by accident would show. */
        const val MANY = 1_000

        const val NOTIFICATION = 4_321

        const val ROOT = "/notes/work"
        const val FILE = "inbox.md"
        const val LINE = 12u
    }
}
