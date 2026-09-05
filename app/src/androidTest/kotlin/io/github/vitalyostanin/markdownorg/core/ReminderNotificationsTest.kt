package io.github.vitalyostanin.markdownorg.core

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.day
import io.github.vitalyostanin.markdownorg.ui.task
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * That a reminder reaches the drawer, and says there what it was raised about.
 *
 * Everything before this is a plan: which alarms are held and what they carry
 * is settled by `ReminderPlanTest` and [ReminderIntentTest]. This is the last
 * step, and the only one the reader ever sees — a notification that is not
 * raised, or is raised into the wrong channel, or without the buttons it is
 * answered through, is indistinguishable from reminders that simply stopped
 * working.
 *
 * Instrumented because it asks the platform what it is holding:
 * `getActiveNotifications` answers for this application's own notifications
 * and needs no listener access to do it.
 */
@RunWith(AndroidJUnit4::class)
class ReminderNotificationsTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotificationsAndDeclareTheChannels() {
        // Granted through the shell rather than through a rule: this is the
        // only runtime permission these tests need, and the rule for it lives
        // in a test dependency the project does not otherwise carry.
        //
        // Waited for, because `executeShellCommand` returns as soon as the
        // command has been handed over: the grant lands afterwards, and a
        // notification raised before it does is dropped without a word --
        // `ReminderNotifications` checks the permission and returns. It is the
        // first test of the class that pays for this, whichever one that is,
        // and the rest run against a permission granted by then. The
        // descriptor is drained rather than closed unread so that the shell
        // is not writing into a pipe nobody reads.
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            )
            .use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        awaitTheGrant()
        // The receiver does this before it raises anything; a notification
        // into a channel that was never declared is dropped by the platform.
        ReminderChannels.declare(context)
        ReminderNotifications.cancelAll(context)
    }

    @After
    fun takeBackWhatWasRaised() {
        ReminderNotifications.cancelAll(context)
    }

    @Test
    fun aTimedReminderReachesTheDrawerAsItsEntry() {
        ReminderNotifications.showTimed(context, TIMED)

        val raised = requireNotNull(waitFor(ReminderNumbering.notification(TIMED.entry))) {
            "nothing was raised"
        }

        assertEquals(ReminderChannels.TIMED, raised.notification.channelId)
        assertEquals(
            TIMED.entry.heading,
            raised.notification.extras.getString("android.title"),
        )
        // The hour of the entry rather than the hour it was raised at: this is
        // what the reader is being told, and the two part as soon as the lead
        // time is not zero.
        assertEquals(
            STARTS.toInstant().toEpochMilli(),
            raised.notification.`when`,
        )
    }

    /**
     * Both of the buttons, because a reminder is answered through them: one
     * asks for it again shortly, the other closes the entry. A notification
     * that arrives without them can only be swiped away.
     */
    @Test
    fun aTimedReminderCarriesTheTwoAnswersToIt() {
        ReminderNotifications.showTimed(context, TIMED)

        val raised = requireNotNull(waitFor(ReminderNumbering.notification(TIMED.entry))) {
            "nothing was raised"
        }
        val labels = raised.notification.actions
            ?.map { action -> action.title.toString() }
            .orEmpty()

        assertEquals(
            listOf(
                context.getString(R.string.reminder_action_snooze),
                context.getString(R.string.reminder_action_done),
            ),
            labels,
        )
    }

    @Test
    fun aDigestReachesTheDrawerAsTheDayItCounted() {
        ReminderNotifications.showDigest(
            context,
            day(
                date = DAY.toString(),
                overdue = listOf(task(heading = "Renew the certificate")),
                scheduledNoTime = listOf(task(heading = "Water the plants")),
            ),
        )

        val raised = requireNotNull(waitFor(ReminderNumbering.DIGEST_NOTIFICATION)) {
            "the digest was not raised"
        }

        assertEquals(ReminderChannels.DIGEST, raised.notification.channelId)
        assertEquals(
            context.getString(R.string.reminder_digest_title),
            raised.notification.extras.getString("android.title"),
        )
        // The counts the digest is made of, in the order it says them.
        val text = raised.notification.extras.getString("android.text").orEmpty()
        assertTrue("the digest said nothing of the day: $text", text.isNotEmpty())
    }

    /**
     * A day holding nothing is not worth waking a reader for. The alarm still
     * fired and the next plan was still made, which is all that firing was
     * for — but the drawer stays as it was.
     */
    @Test
    fun aDayHoldingNothingRaisesNoDigest() {
        ReminderNotifications.showDigest(context, day(date = DAY.toString()))

        assertNull(waitFor(ReminderNumbering.DIGEST_NOTIFICATION, patience = SHORT))
    }

    /**
     * Switching reminders off takes back what they put there. A switch that
     * leaves an entry announced a quarter of an hour ago standing in the
     * drawer has not stopped anything the reader can see.
     */
    @Test
    fun switchingThemOffTakesBackWhatWasRaised() {
        ReminderNotifications.showTimed(context, TIMED)
        assertNotNull(
            "nothing was raised to take back",
            waitFor(ReminderNumbering.notification(TIMED.entry)),
        )

        ReminderNotifications.cancelAll(context)

        assertNull(waitFor(ReminderNumbering.notification(TIMED.entry), patience = SHORT))
    }

    /**
     * That the permission is actually held before anything is raised.
     *
     * The same question `ReminderNotifications` asks itself before it raises
     * anything, so what is waited for is exactly what decides whether the
     * notification appears.
     */
    private fun awaitTheGrant() {
        val deadline = System.currentTimeMillis() + PATIENCE

        while (System.currentTimeMillis() < deadline) {
            val held = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

            if (held && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                return
            }
            Thread.sleep(POLL)
        }
    }

    /** The platform holds it a moment after `notify` returns, so it is waited for. */
    private fun waitFor(id: Int, patience: Long = PATIENCE): StatusBarNotification? {
        val deadline = System.currentTimeMillis() + patience

        do {
            val raised = manager.activeNotifications.firstOrNull { it.id == id }

            if (raised != null) {
                return raised
            }
            Thread.sleep(POLL)
        } while (System.currentTimeMillis() < deadline)

        return null
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.systemDefault()
        val DAY: LocalDate = LocalDate.of(2026, 9, 5)
        val STARTS: ZonedDateTime = DAY.atTime(15, 0).atZone(ZONE)

        /** How long a notification is waited for, and how long an absent one is. */
        const val PATIENCE = 5_000L
        const val SHORT = 1_000L
        const val POLL = 100L

        val TIMED = TimedReminder(
            at = DAY.atTime(14, 45).atZone(ZONE),
            starts = STARTS,
            entry = ReminderEntry(
                root = "/notes/work",
                file = "inbox.md",
                line = 12u,
                heading = "Call the notary",
            ),
        )
    }
}
