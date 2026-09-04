package io.github.vitalyostanin.markdownorg.core

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * That what an alarm carries is what the notification reads back.
 *
 * The platform carries no objects, so everything a reminder says travels as
 * extras and is read again in another process, hours later. What is lost
 * between the two is lost silently: a reminder that unpacks to nothing is a
 * broadcast ignored, and the reader is told nothing at the hour they asked to
 * be told about.
 */
@RunWith(AndroidJUnit4::class)
class ReminderIntentTest {

    @Test
    fun anEntryComesBackWhole() {
        val packed = ReminderIntent.fill(Intent(), TIMED)

        assertEquals(TIMED, ReminderIntent.unpack(packed, ZONE))
    }

    @Test
    fun aDigestComesBackWithItsDay() {
        val digest = DigestReminder(at = at(9, 0), day = DAY)

        assertEquals(digest, ReminderIntent.unpack(ReminderIntent.fill(Intent(), digest), ZONE))
    }

    /**
     * A broadcast to this receiver can come from anywhere on the device. One
     * that says none of it is ignored rather than ending the process.
     */
    @Test
    fun anIntentSayingNothingIsNoReminder() {
        assertNull(ReminderIntent.unpack(Intent(), ZONE))
    }

    /**
     * Half of it is no better than none: an entry whose hour did not survive
     * the journey would be announced as starting at the epoch.
     *
     * The extra is taken out by the name it is packed under, and the reminder
     * is read back whole first -- a name that no longer matches would leave
     * this passing while asserting nothing.
     */
    @Test
    fun anIntentWithoutTheHourItStartsAtIsNoReminder() {
        val packed = ReminderIntent.fill(Intent(), TIMED)
        assertEquals(TIMED, ReminderIntent.unpack(packed, ZONE))

        packed.removeExtra(STARTS)

        assertNull(ReminderIntent.unpack(packed, ZONE))
    }

    /**
     * The buttons of a notification send the reminder back to the application,
     * and what they send is what the alarm carried: an intent already
     * addressed keeps its address and gains the entry.
     */
    @Test
    fun anIntentAlreadyAddressedKeepsWhereItWasGoing() {
        val addressed = Intent().setAction(ACTION)

        val packed = ReminderIntent.fill(addressed, TIMED)

        assertEquals(ACTION, packed.action)
        assertEquals(TIMED, ReminderIntent.unpack(packed, ZONE))
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
        val DAY: LocalDate = LocalDate.of(2026, 9, 4)

        const val ACTION = "io.github.vitalyostanin.markdownorg.action.SNOOZE"
        const val STARTS = "starts"

        fun at(hour: Int, minute: Int): ZonedDateTime = DAY.atTime(hour, minute).atZone(ZONE)

        val TIMED = TimedReminder(
            at = at(14, 45),
            starts = at(15, 0),
            entry = ReminderEntry(
                root = "/notes/work",
                file = "inbox.md",
                line = 12u,
                heading = "Call the notary",
            ),
        )
    }
}
