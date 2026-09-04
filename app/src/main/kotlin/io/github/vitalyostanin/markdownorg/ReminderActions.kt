package io.github.vitalyostanin.markdownorg

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.vitalyostanin.markdownorg.core.ClosingOutcome
import io.github.vitalyostanin.markdownorg.core.DeviceCollections
import io.github.vitalyostanin.markdownorg.core.NotesCollectionsStore
import io.github.vitalyostanin.markdownorg.core.ReminderAlarms
import io.github.vitalyostanin.markdownorg.core.ReminderAnswer
import io.github.vitalyostanin.markdownorg.core.ReminderChannels
import io.github.vitalyostanin.markdownorg.core.ReminderIntent
import io.github.vitalyostanin.markdownorg.core.ReminderNotifications
import io.github.vitalyostanin.markdownorg.core.ReminderNumbering
import io.github.vitalyostanin.markdownorg.core.ReminderScheduler
import io.github.vitalyostanin.markdownorg.core.RunningWork
import io.github.vitalyostanin.markdownorg.core.TimedReminder
import io.github.vitalyostanin.markdownorg.core.answerTo
import io.github.vitalyostanin.markdownorg.core.byRoot
import io.github.vitalyostanin.markdownorg.core.closingOutcome
import io.github.vitalyostanin.markdownorg.core.entryNamed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The buttons a reminder carries, and where pressing one goes.
 *
 * Both go to a receiver rather than to the screen: the reader pressing them is
 * answering the notification, not asking to be taken anywhere, and opening the
 * agenda over whatever they were doing would be an answer to a question they
 * did not ask.
 */
object ReminderActions {

    /** Say this one again shortly. */
    const val SNOOZE = "io.github.vitalyostanin.markdownorg.action.SNOOZE"

    /** Close the entry, through the core, the way the sheet of the task does. */
    const val DONE = "io.github.vitalyostanin.markdownorg.action.DONE"

    /** How long "later" is. */
    val LATER: Duration = Duration.ofMinutes(15)

    /** Pressing one of them, as something a notification can hold. */
    fun pressing(
        context: Context,
        action: String,
        notification: Int,
        reminder: TimedReminder,
    ): PendingIntent {
        val intent = ReminderIntent
            .fill(Intent(context, ReminderActionReceiver::class.java), reminder)
            .setAction(action)
            .putExtra(EXTRA_NOTIFICATION, notification)

        return PendingIntent.getBroadcast(
            context,
            ReminderNumbering.button(notification, second = action == DONE),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Which notification the button belonged to. */
    fun notificationOf(intent: Intent): Int = intent.getIntExtra(EXTRA_NOTIFICATION, 0)

    private const val EXTRA_NOTIFICATION = "notification"
}

/**
 * A button on a reminder was pressed.
 *
 * The notification goes first, whichever button it was: the reader has
 * answered it, and one that stays up while the answer is carried out reads as
 * a press that did nothing.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val reminder = ReminderIntent.unpack(intent) as? TimedReminder ?: return
        val notification = ReminderActions.notificationOf(intent)

        NotificationManagerCompat.from(app).cancel(notification)
        when (val answer = answerTo(intent.action, reminder, ZonedDateTime.now())) {
            is ReminderAnswer.HoldAside ->
                ReminderAlarms(app).holdAside(key = notification, reminder = answer.reminder)

            is ReminderAnswer.Close -> ReminderCompletionService.close(app, answer.reminder)

            null -> Unit
        }
    }
}

/**
 * Closing an entry from the drawer, off the receiver's clock.
 *
 * A receiver has nine seconds and is killed with the process if it overstays;
 * closing an entry reads the agenda, writes the note and commits it, and a
 * collection kept on slow storage can take longer than that. A short service
 * carries it instead — the platform allows one to be started from a
 * notification the reader has just interacted with, and its own limit is
 * minutes rather than seconds.
 */
class ReminderCompletionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The presses being answered. Every notification carries the button, so
     * two of them a second apart run through this service at once.
     */
    private val work = RunningWork()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reminder = intent?.let { ReminderIntent.unpack(it) } as? TimedReminder

        if (reminder == null) {
            stopSelf(startId)

            return START_NOT_STICKY
        }
        ReminderChannels.declare(this)
        ServiceCompat.startForeground(
            this,
            ReminderNumbering.WORKING_NOTIFICATION,
            working(reminder.entry.heading),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        )
        work.started(startId)
        scope.launch {
            try {
                complete(reminder)
            } finally {
                // Only when nothing else is still running: the piece that
                // finishes first would otherwise take the foreground state
                // from under the other one, and a `stopSelf` under an older
                // start id would destroy the service — and with it the replan
                // that follows the write.
                work.finished()?.let { newest ->
                    ServiceCompat.stopForeground(
                        this@ReminderCompletionService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    stopSelf(newest)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * Find the entry where it was announced and close it there.
     *
     * The agenda is read again rather than trusted from the alarm: the entry
     * may have been closed on another device, or moved, in the time between
     * the plan and the press. An entry no longer where it was said to be is
     * one this does nothing about — there is no screen here to ask.
     */
    private suspend fun complete(reminder: TimedReminder) {
        val scheduler = ReminderScheduler.of(this)
        val read = scheduler.read(reminder.starts.toLocalDate()).onFailure { failure ->
            Log.w(TAG, "the day the reminder was for could not be read", failure)
        }
        val task = read.getOrNull()?.let { holding -> holding.entryNamed(reminder.entry) }
        val editor = task?.let { found ->
            DeviceCollections(this, NotesCollectionsStore(this).collections)
                .byRoot(found.root)
                ?.editor
        }
        val written = task?.let { found ->
            editor?.complete(found, LocalDate.now())
                ?.onFailure { failed -> Log.w(TAG, "the entry could not be closed", failed) }
        }
        val outcome = closingOutcome(
            read = read,
            found = task != null,
            inACollection = editor != null,
            written = written,
        )

        if (outcome != ClosingOutcome.CLOSED) {
            // In place of the notification the press took down. Every failure
            // here otherwise looks like the success — the reminder gone, the
            // entry still open — and is found out by opening the agenda days
            // later.
            Log.w(TAG, "the reminder was answered and the entry is still open: $outcome")
            ReminderNotifications.showNotClosed(this, reminder, outcome)
        }
        scheduler.replan()
            .onFailure { failure -> Log.w(TAG, "the plan was not made again", failure) }
    }

    /**
     * What the platform shows while this runs.
     *
     * A foreground service has to say it is there, so it says what it is
     * doing. It is gone within a second or two of appearing, on the low
     * channel, which is the quietest way to satisfy the requirement.
     */
    private fun working(heading: String): android.app.Notification =
        NotificationCompat.Builder(this, ReminderChannels.WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.reminder_closing))
            .setContentText(heading)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    companion object {

        /** Close [reminder]'s entry, from wherever the button was pressed. */
        fun close(context: Context, reminder: TimedReminder) {
            val intent = ReminderIntent
                .fill(Intent(context, ReminderCompletionService::class.java), reminder)

            // Refused rather than granted when the platform decides this is
            // not a notification the reader just answered: logged and dropped,
            // because ending the process would be a crash out of a button
            // press.
            runCatching { context.startForegroundService(intent) }
                .onFailure { refused ->
                    Log.w(TAG, "the closing service was refused", refused)
                    // The reminder has already been taken down by the press,
                    // and nothing was even started: said here, because there
                    // is no service to say it.
                    ReminderNotifications.showNotClosed(
                        context,
                        reminder,
                        ClosingOutcome.REFUSED,
                    )
                }
        }

        private const val TAG = "ReminderCompletion"
    }
}
