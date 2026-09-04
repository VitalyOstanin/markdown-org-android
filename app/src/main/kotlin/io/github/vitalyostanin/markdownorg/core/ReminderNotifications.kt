package io.github.vitalyostanin.markdownorg.core

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import io.github.vitalyostanin.markdownorg.MainActivity
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ReminderActions
import io.github.vitalyostanin.markdownorg.ui.timeLabel
import uniffi.markdown_org_ffi.Day
import uniffi.markdown_org_ffi.Task
import java.util.Locale

/**
 * What a reminder looks like once it reaches the screen.
 *
 * A notification opens the day it is about: the timed one with its entry
 * picked out, the digest on the day it counted. What the entry is stands in
 * the notification itself as well, for a reader who only wants to know.
 *
 * Nothing here reads the notes. The timed reminder says what was planned, and
 * the digest is handed a day the caller has just read — this decides how the
 * two are worded and no more.
 */
object ReminderNotifications {

    /** One entry, ahead of its hour. */
    fun showTimed(context: Context, reminder: TimedReminder) {
        // The hour the agenda would write, by the same rule: the clock of the
        // device overrides what the locale would choose on its own, and a
        // reader on `en-US` who asked for 24-hour time was being shown "1:05
        // PM" in the drawer beside "13:05" on the screen behind it.
        val hour = timeLabel(
            reminder.starts.toLocalTime(),
            readerLocale(context),
            DateFormat.is24HourFormat(context),
        )
        val id = ReminderNumbering.notification(reminder.entry)

        raise(
            context = context,
            id = id,
            channel = ReminderChannels.TIMED,
            title = reminder.entry.heading,
            text = context.getString(R.string.reminder_starts_at, hour),
            // The hour of the entry rather than the hour the notification
            // arrived: sorted among the others in the drawer, this is what the
            // reader is being told about.
            moment = reminder.starts.toInstant().toEpochMilli(),
            target = AgendaTarget(
                day = reminder.starts.toLocalDate(),
                entry = reminder.entry,
            ),
            actions = buttons(context, id, reminder),
        )
    }

    /**
     * The reminder that was answered and could not be closed.
     *
     * Raised in place of the one the press took down, under the same number,
     * so the drawer holds one line about the entry rather than two. Without
     * it every failure looks like the success: the reminder is gone and the
     * entry is still open, which is found out days later by opening the
     * agenda.
     *
     * No buttons on it. The one that was pressed did not work, and offering
     * it again from here would ask the reader to find out by pressing whether
     * this time is different; the notification opens the entry instead.
     */
    fun showNotClosed(context: Context, reminder: TimedReminder, outcome: ClosingOutcome) {
        val said = outcome.said ?: return

        raise(
            context = context,
            id = ReminderNumbering.notification(reminder.entry),
            channel = ReminderChannels.TIMED,
            title = reminder.entry.heading,
            text = context.getString(said),
            target = AgendaTarget(
                day = reminder.starts.toLocalDate(),
                entry = reminder.entry,
            ),
        )
    }

    /** What the day holds, or nothing when it holds none of it. */
    fun showDigest(context: Context, day: Day) {
        val counts = digestCounts(day)

        if (counts.silent) {
            return
        }
        val said = listOfNotNull(
            counts.dated.takeIf { it > 0 }?.let { count ->
                context.resources.getQuantityString(R.plurals.reminder_digest_dated, count, count)
            },
            counts.deadlines.takeIf { it > 0 }?.let { count ->
                context.resources
                    .getQuantityString(R.plurals.reminder_digest_deadlines, count, count)
            },
            counts.overdue.takeIf { it > 0 }?.let { count ->
                context.getString(R.string.reminder_digest_overdue, count)
            },
        )

        raise(
            context = context,
            id = ReminderNumbering.DIGEST_NOTIFICATION,
            channel = ReminderChannels.DIGEST,
            title = context.getString(R.string.reminder_digest_title),
            text = said.joinToString(context.getString(R.string.reminder_digest_separator)),
            headings = digestHeadings(day),
            // The day it counted, with nothing picked out within it: the
            // digest is about all of them, and the screen it opens is where
            // the reader chooses which.
            target = statedDate(day.date)
                ?.let { date -> AgendaTarget(day = date, entry = null) },
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
        target: AgendaTarget? = null,
        actions: List<NotificationCompat.Action> = emptyList(),
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
            .setContentIntent(opens(context, id, target))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        moment?.let { builder.setWhen(it).setShowWhen(true) }
        actions.forEach(builder::addAction)
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
     * The two the reader can answer a timed reminder with.
     *
     * Only the timed one carries them: the digest is about a day's worth of
     * entries, and there is no one of them for a button to be about. Neither
     * button is offered without a channel to answer through — an entry named
     * by nothing but a heading could not be found again.
     */
    private fun buttons(
        context: Context,
        id: Int,
        reminder: TimedReminder,
    ): List<NotificationCompat.Action> = listOf(
        action(context, R.string.reminder_action_snooze, ReminderActions.SNOOZE, id, reminder),
        action(context, R.string.reminder_action_done, ReminderActions.DONE, id, reminder),
    )

    private fun action(
        context: Context,
        label: Int,
        deed: String,
        id: Int,
        reminder: TimedReminder,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(
        // No icon: the platform has drawn actions as text alone since the
        // drawer was redesigned, and an icon here would be a resource nothing
        // displays.
        null,
        context.getString(label),
        ReminderActions.pressing(context, deed, id, reminder),
    ).build()

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
     * The tap: the agenda, opened where the notification points.
     *
     * The request code is the notification's own. Pending intents are told
     * apart by their request code and by the intent itself, and extras count
     * for neither — one code for all of them would leave every notification
     * holding whichever address was packed last.
     */
    private fun opens(context: Context, id: Int, target: AgendaTarget?): PendingIntent? {
        // The activity by name rather than the launcher's intent for the
        // package: what comes back from the launcher starts the application
        // as the home screen does, and a screen already up is brought forward
        // without being told what was tapped. Named and marked single top, the
        // running screen is handed the address instead.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        target?.let { AgendaAddress.pack(intent, it) }

        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The language the notification is worded in, taken from the resources.
     *
     * Read from the configuration rather than from [Locale.getDefault]: a
     * reminder is raised from a receiver in the background, where the default
     * of the process may be the one it started with rather than the one the
     * screens are drawn in.
     */
    private fun readerLocale(context: Context): Locale =
        ConfigurationCompat.getLocales(context.resources.configuration)[0]
            ?: Locale.getDefault()

    private const val TAG = "ReminderNotifications"
}

/**
 * What a digest has to say about a day.
 *
 * The counting is apart from the wording so that it can be asserted without a
 * device: which buckets are counted, and which day amounts to nothing at all,
 * is the decision here -- the strings around it are the platform's.
 */
internal data class DigestCounts(val dated: Int, val deadlines: Int, val overdue: Int) {

    /**
     * Nothing to say.
     *
     * A digest of three zeroes is a notification saying there is nothing to
     * say, raised every morning; the alarm still fired and the next plan was
     * still made, which is all that firing was for.
     */
    val silent: Boolean get() = dated == 0 && deadlines == 0 && overdue == 0
}

/** How much of each kind the day holds, in the order the digest says it. */
internal fun digestCounts(day: Day): DigestCounts = DigestCounts(
    dated = day.scheduledNoTime.size,
    deadlines = day.upcoming.size,
    overdue = day.overdue.size,
)

/**
 * The first few headings of the day, for the drawer when it is expanded.
 *
 * A few rather than all of them: the platform truncates a long text, and the
 * counts already say how much there is. The order is the agenda's, which is
 * the order the screen shows them in.
 */
internal fun digestHeadings(day: Day): List<String> =
    (day.scheduledNoTime + day.upcoming + day.overdue)
        .take(LISTED)
        .map(Task::heading)

/** How many headings the expanded digest names. */
private const val LISTED = 5
