package io.github.vitalyostanin.markdownorg.core

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.vitalyostanin.markdownorg.R
import uniffi.markdown_org_ffi.Day
import uniffi.markdown_org_ffi.Task
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.absoluteValue

/**
 * What a reminder looks like once it reaches the screen.
 *
 * Every notification opens the agenda and nothing more particular: the
 * application has one screen and no addresses within it, so a notification
 * that promised to open the entry would open the agenda anyway, one tap later.
 * What the entry is stands in the notification itself.
 *
 * Nothing here reads the notes. The timed reminder says what was planned, and
 * the digest is handed a day the caller has just read — this decides how the
 * two are worded and no more.
 */
object ReminderNotifications {

    /** One entry, ahead of its hour. */
    fun showTimed(context: Context, reminder: TimedReminder) {
        val hour = reminder.starts.toLocalTime()
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

        raise(
            context = context,
            id = idOf(reminder.entry),
            channel = ReminderChannels.TIMED,
            title = reminder.entry.heading,
            text = context.getString(R.string.reminder_starts_at, hour),
            // The hour of the entry rather than the hour the notification
            // arrived: sorted among the others in the drawer, this is what the
            // reader is being told about.
            moment = reminder.starts.toInstant().toEpochMilli(),
        )
    }

    /**
     * What the day holds, or nothing when it holds none of it.
     *
     * A digest of three zeroes is a notification saying there is nothing to
     * say, raised every morning; the alarm still fired and the next plan was
     * still made, which is all that firing was for.
     */
    fun showDigest(context: Context, day: Day) {
        val counts = listOfNotNull(
            day.scheduledNoTime.size.takeIf { it > 0 }?.let { count ->
                context.resources.getQuantityString(R.plurals.reminder_digest_dated, count, count)
            },
            day.upcoming.size.takeIf { it > 0 }?.let { count ->
                context.resources
                    .getQuantityString(R.plurals.reminder_digest_deadlines, count, count)
            },
            day.overdue.size.takeIf { it > 0 }?.let { count ->
                context.getString(R.string.reminder_digest_overdue, count)
            },
        )
        if (counts.isEmpty()) {
            return
        }

        raise(
            context = context,
            id = DIGEST_ID,
            channel = ReminderChannels.DIGEST,
            title = context.getString(R.string.reminder_digest_title),
            text = counts.joinToString(context.getString(R.string.reminder_digest_separator)),
            headings = headings(day),
        )
    }

    /**
     * Take back everything raised, for a reader who switched reminders off.
     *
     * All of it rather than the digest alone: an entry announced a quarter of
     * an hour ago stands in the drawer just as the digest does, and a switch
     * that leaves half of them there has not stopped anything the reader can
     * see. Nothing else in this application raises a notification, so what is
     * taken back is exactly what reminders put there.
     */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun raise(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        moment: Long? = null,
        headings: List<String> = emptyList(),
    ) {
        // Refused notifications are not an error to report anywhere: the
        // settings screen says so where the switch is, and `notify` on a
        // platform that refuses them is a call that does nothing.
        if (!notificationsAllowed(context)) {
            return
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(opensTheAgenda(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        moment?.let { builder.setWhen(it).setShowWhen(true) }
        if (headings.isNotEmpty()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .setSummaryText(text)
                    .bigText(headings.joinToString(separator = System.lineSeparator())),
            )
        }
        // Caught rather than left to fly: the permission can go away between
        // the check above and this call, and that is a reminder not shown
        // rather than a process ended in the background where nothing would
        // report it.
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (refused: SecurityException) {
            Log.w(TAG, "the notification was refused", refused)
        }
    }

    /**
     * Whether anything raised would be seen, asked of the platform directly.
     *
     * The same answer [ReminderAccess] gives, spelled out here because the
     * lint that guards `notify` reads the call site: a check behind another
     * function's name does not satisfy it, and neither does catching what it
     * warns about.
     */
    private fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                )

    /**
     * The first few headings of the day, for the drawer when it is expanded.
     *
     * A few rather than all of them: the platform truncates a long text, and
     * the counts above already say how much there is. The order is the
     * agenda's, which is the order the screen shows them in.
     */
    private fun headings(day: Day): List<String> =
        (day.scheduledNoTime + day.upcoming + day.overdue)
            .take(LISTED)
            .map(Task::heading)

    /**
     * A notification of its own per entry, and the same one when the entry is
     * announced twice.
     *
     * Keyed by the note and the line rather than by the heading, so the
     * reminder at the hour replaces the one that came before it instead of
     * standing beside it. Two entries whose keys collide share a notification,
     * which costs the earlier of the two — rare enough, and the alternative is
     * a table of identifiers to be kept as long as the alarms are.
     */
    private fun idOf(entry: ReminderEntry): Int {
        val key = "${entry.root} ${entry.file} ${entry.line}".hashCode()

        return TIMED_BASE + (key.absoluteValue % TIMED_RANGE)
    }

    private fun opensTheAgenda(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null

        return PendingIntent.getActivity(
            context,
            0,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** The digest is one notification, replaced daily. */
    private const val DIGEST_ID = 1

    private const val TIMED_BASE = 100
    private const val TIMED_RANGE = 100_000

    /** How many headings the expanded digest names. */
    private const val LISTED = 5

    private const val TAG = "ReminderNotifications"
}
