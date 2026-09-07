package io.github.vitalyostanin.markdownorg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.vitalyostanin.markdownorg.core.DigestReminder
import io.github.vitalyostanin.markdownorg.core.ReminderChannels
import io.github.vitalyostanin.markdownorg.core.ReminderIntent
import io.github.vitalyostanin.markdownorg.core.ReminderNotifications
import io.github.vitalyostanin.markdownorg.core.ReminderScheduler
import io.github.vitalyostanin.markdownorg.core.TimedReminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An alarm has come due: say what it was for, then plan again.
 *
 * Planning again from here is what keeps the horizon moving. Alarms are only
 * held two days out, and every one that fires is a chance to hold the two days
 * after it — a phone left alone over a weekend still has Monday planned by
 * Sunday morning's digest.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val reminder = ReminderIntent.unpack(intent)

        inTheBackground(this) {
            val scheduler = ReminderScheduler.of(app)

            ReminderChannels.declare(app)
            when (reminder) {
                is TimedReminder -> ReminderNotifications.showTimed(app, reminder)

                is DigestReminder -> scheduler.read(reminder.day)
                    .onSuccess { day -> ReminderNotifications.showDigest(app, day) }
                    .onFailure { failure -> Log.w(TAG, "the digest could not be read", failure) }

                // A broadcast that carries none of it, which is not this
                // application's alarm. The plan below is made all the same:
                // getting here at all means something woke the receiver.
                null -> Unit
            }
            scheduler.replan()
                .onFailure { failure -> Log.w(TAG, "the plan was not made again", failure) }
        }
    }
}

/**
 * The plan is gone or is about to be wrong, so make it again.
 *
 * Five occasions, and what each of them breaks:
 *
 * - the phone was restarted, and alarms do not survive that;
 * - this application was replaced, which drops its alarms too;
 * - the clock was set, so an alarm at a moment is now at another moment;
 * - the time zone changed, and every hour in the notes is read in the phone's
 *   zone;
 * - exact alarms were allowed or disallowed, which decides whether an entry is
 *   announced at its minute or within the hour, and — the platform is explicit
 *   about this — cancels the alarms held when the access is taken away.
 */
class ReminderRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        ReminderChannels.declare(app)
        inTheBackground(this) {
            ReminderScheduler.of(app).replan()
                .onFailure { failure -> Log.w(TAG, "the plan was not made again", failure) }
        }
    }
}

/**
 * Run [work] past the return of `onReceive`, within the time a broadcast has.
 *
 * A budget rather than the platform's own limit: a receiver that overstays is
 * killed with the process, and a collection large enough to be walked slowly
 * would take the application down in the background rather than merely miss a
 * plan. What is missed here is picked up by the next occasion — every launch
 * of the agenda plans again.
 */
internal fun inTheBackground(receiver: BroadcastReceiver, work: suspend () -> Unit) {
    val pending = receiver.goAsync()

    CoroutineScope(Dispatchers.IO).launch {
        try {
            // Both outcomes are written down. Nothing here has a screen, so
            // the reminders failing looks from the outside like reminders
            // that stopped arriving: without these two lines there is nothing
            // to tell a directory that could not be read from a budget that
            // ran out, days later when the question is asked.
            if (!withinTheBudget(work)) {
                Log.w(TAG, "the reminder work outstayed its ${BUDGET / 1000} seconds")
            }
        } finally {
            pending.finish()
        }
    }
}

/**
 * Run [work] under the budget, saying whether it finished inside it.
 *
 * Held apart from the receiver so it can be tested: `goAsync` is the
 * platform's and needs a device, while what is decided here — what counts as
 * a failure, and what counts as the time running out — is decided the same
 * way wherever it runs.
 */
internal suspend fun withinTheBudget(work: suspend () -> Unit): Boolean =
    withTimeoutOrNull(BUDGET) {
        val ran = runCatching { work() }

        // `runCatching` catches the cancellation that ends the work as well.
        // Left inside the result, the process going away would be written
        // down as notes that could not be read, and the work would go on
        // after the caller dropped it.
        (ran.exceptionOrNull() as? CancellationException)?.let { throw it }
        ran.onFailure { failure -> Log.w(TAG, "the reminder work failed", failure) }
    } != null

/** How long the work after `onReceive` is given. */
private const val BUDGET = 9_000L

private const val TAG = "Reminders"
