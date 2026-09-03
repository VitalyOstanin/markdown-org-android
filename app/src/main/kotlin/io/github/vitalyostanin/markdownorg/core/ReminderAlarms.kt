package io.github.vitalyostanin.markdownorg.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.vitalyostanin.markdownorg.ReminderReceiver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.absoluteValue

/**
 * Whatever holds the plan until it comes due.
 *
 * An interface for the same reason [ReminderPreferences] is one: what the
 * scheduler decides — which days to read, how far ahead to hold, when to hold
 * nothing at all — is worth a unit test, and the alarms themselves are the
 * platform's and cannot be had without a device.
 */
interface AlarmHolder {

    /** Hold exactly [plan] and nothing else. */
    fun replace(plan: List<PlannedReminder>)

    /** Drop every alarm held. */
    fun cancelAll()

    /**
     * Hold one alarm beside the plan, under a number of the caller's choosing.
     *
     * What putting a reminder off comes to. The plan is remade whenever the
     * notes may have moved, and a reminder the reader has pushed to later is
     * not in the notes at all — held among the plan it would be dropped by the
     * next fetch. Held aside, it survives until it fires. [key] tells one from
     * another; the same key twice replaces the earlier one, which is what a
     * reader pressing the button twice means.
     */
    fun holdAside(key: Int, reminder: PlannedReminder)
}

/**
 * The plan, as alarms the platform holds.
 *
 * The whole plan is replaced at once rather than kept in step entry by entry:
 * what is announced comes out of the agenda, and one fetch can move any number
 * of entries at once. Replacing everything is a few dozen calls and needs no
 * account of what changed, which is the kind of account that goes wrong quietly
 * — an alarm left behind for an entry that was closed a week ago.
 *
 * How many alarms are held is written down, because cancelling one needs the
 * number it was scheduled under and nothing else remembers it. The count
 * survives the process; the alarms do not survive a restart of the phone, which
 * is what the boot receiver is for.
 */
class ReminderAlarms(private val context: Context) : AlarmHolder {

    private val alarms = context.getSystemService(AlarmManager::class.java)

    private val held = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The numbering, and the rule that keeps it in step with the platform.
     *
     * Held apart from the alarms themselves so the rule is unit-tested: what
     * goes wrong here is an interleaving, not a call to the platform.
     */
    private val slots = PlanSlots(
        object : PlanSlots.HeldCount {
            override fun read(): Int = held.getInt(KEY_HELD, 0)

            override fun write(count: Int) {
                held.edit().putInt(KEY_HELD, count).apply()
            }
        },
    )

    /**
     * Whether the alarms are exact is decided here rather than by the caller:
     * the access can be taken away between one plan and the next, and an
     * inexact alarm — delivered within the hour — is what the settings warn
     * about when it is.
     */
    override fun replace(plan: List<PlannedReminder>) {
        if (alarms == null) {
            cancelAll()
            return
        }
        val exact = ReminderAccess.exactAlarmsAllowed(context)
        slots.replace(
            planned = plan.size,
            cancel = ::drop,
            place = { index -> schedule(index, plan[index], exact) },
        )
    }

    /** Drop every alarm held, for the reader who switched reminders off. */
    override fun cancelAll() = slots.clear(::drop)

    /** One alarm of the plan, by the number it was scheduled under. */
    private fun drop(index: Int) {
        // FLAG_NO_CREATE: an alarm already delivered leaves nothing to
        // cancel, and building one to cancel it would be the only place
        // this application ever created it.
        pending(index, intent = Intent(context, ReminderReceiver::class.java), create = false)
            ?.let { waiting ->
                alarms?.cancel(waiting)
                waiting.cancel()
            }
    }

    /**
     * The alarm the reader asked to have again later.
     *
     * Numbered above everything the plan uses, so that replacing the plan —
     * which cancels by number, from zero to the count held — leaves it alone.
     */
    override fun holdAside(key: Int, reminder: PlannedReminder) = slots.inTurn {
        if (alarms == null) {
            return@inTurn
        }
        schedule(
            index = ASIDE + key.absoluteValue % ASIDE_RANGE,
            reminder = reminder,
            exact = ReminderAccess.exactAlarmsAllowed(context),
        )
    }

    private fun schedule(index: Int, reminder: PlannedReminder, exact: Boolean) {
        val errand = ReminderIntent.pack(context, reminder)
        val waiting = pending(index, errand, create = true) ?: return
        val at = reminder.at.toInstant().toEpochMilli()

        // Both of these wake a phone that is dozing; the exact one is the
        // access the reader may not have granted. A SecurityException all the
        // same when the access was withdrawn between the check above and this
        // call, and the reminder is then worth having late rather than not at
        // all.
        val scheduled = heldExactly(reminder, exact) && runCatching {
            alarms?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, waiting)
        }.onFailure { refusal ->
            // Written down because the reminder still arrives, only within
            // the hour rather than at its minute: a complaint that it came
            // late is otherwise unanswerable from the log.
            Log.w(TAG, "the exact alarm was refused, the reminder will be inexact", refusal)
        }.isSuccess

        if (!scheduled) {
            alarms?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, waiting)
        }
    }

    /**
     * The alarm's errand, addressed so that no two of the plan collide.
     *
     * The number is what tells two alarms apart: the platform compares the
     * intent without its extras, so every reminder of one plan would otherwise
     * be the same alarm rescheduled.
     */
    private fun pending(index: Int, intent: Intent, create: Boolean): PendingIntent? {
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE

        return PendingIntent.getBroadcast(context, REQUEST_BASE + index, intent, flags)
    }

    private companion object {
        const val FILE = "reminder-alarms"
        const val KEY_HELD = "held"

        /**
         * Where the numbers of the alarms held aside begin.
         *
         * Above any plan: the plan is capped well below this, and cancelling
         * it walks the numbers it used from zero.
         */
        const val ASIDE = 1_000
        const val ASIDE_RANGE = 100_000

        /** Where the numbering of the plan's alarms starts. */
        const val REQUEST_BASE = 1000

        const val TAG = "ReminderAlarms"
    }
}

/**
 * A reminder on its way through the platform, which carries no objects.
 *
 * Everything a notification says is in the intent rather than read again when
 * it fires: the alarm can arrive with the notes mid-fetch or the directory
 * gone, and an entry that was worth announcing when it was planned is still
 * worth announcing then. The digest is the exception and carries only its day
 * — what a day holds is a list, and a list hours old is worse than one read on
 * the spot.
 */
object ReminderIntent {

    /** The reminder as extras of a broadcast to [ReminderReceiver]. */
    fun pack(context: Context, reminder: PlannedReminder): Intent =
        fill(Intent(context, ReminderReceiver::class.java), reminder)

    /**
     * The same extras on an intent the caller has already addressed.
     *
     * The action buttons send the reminder back to the application, and what
     * they send is what the alarm carried: the entry they are about is named
     * the same way, and [unpack] reads either of them.
     */
    fun fill(intent: Intent, reminder: PlannedReminder): Intent {
        intent.putExtra(EXTRA_AT, reminder.at.toInstant().toEpochMilli())

        return when (reminder) {
            is TimedReminder ->
                intent
                    .putExtra(EXTRA_STARTS, reminder.starts.toInstant().toEpochMilli())
                    .putExtra(EXTRA_ROOT, reminder.entry.root)
                    .putExtra(EXTRA_FILE, reminder.entry.file)
                    .putExtra(EXTRA_LINE, reminder.entry.line.toInt())
                    .putExtra(EXTRA_HEADING, reminder.entry.heading)

            is DigestReminder -> intent.putExtra(EXTRA_DAY, reminder.day.toString())
        }
    }

    /**
     * What was packed, or `null` from an intent that carries none of it.
     *
     * Null rather than an exception: a broadcast to this receiver can come
     * from anywhere on the device, and one that says nothing is one to ignore
     * rather than to end the process over.
     */
    fun unpack(intent: Intent, zone: ZoneId = ZoneId.systemDefault()): PlannedReminder? {
        val at = intent.getLongExtra(EXTRA_AT, 0L).takeIf { it > 0L }?.let { moment(it, zone) }
            ?: return null
        val day = intent.getStringExtra(EXTRA_DAY)

        if (day != null) {
            return runCatching { DigestReminder(at = at, day = LocalDate.parse(day)) }.getOrNull()
        }
        val file = intent.getStringExtra(EXTRA_FILE) ?: return null
        val heading = intent.getStringExtra(EXTRA_HEADING) ?: return null
        val starts = intent.getLongExtra(EXTRA_STARTS, 0L).takeIf { it > 0L } ?: return null

        return TimedReminder(
            at = at,
            starts = moment(starts, zone),
            entry = ReminderEntry(
                root = intent.getStringExtra(EXTRA_ROOT),
                file = file,
                line = intent.getIntExtra(EXTRA_LINE, 0).coerceAtLeast(0).toUInt(),
                heading = heading,
            ),
        )
    }

    private fun moment(millis: Long, zone: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(millis).atZone(zone)

    private const val EXTRA_AT = "at"
    private const val EXTRA_STARTS = "starts"
    private const val EXTRA_DAY = "day"
    private const val EXTRA_ROOT = "root"
    private const val EXTRA_FILE = "file"
    private const val EXTRA_LINE = "line"
    private const val EXTRA_HEADING = "heading"
}

/**
 * Whether this reminder is one to hold at its minute, given the access.
 *
 * The digest never is. Exact alarms are a ration the platform keeps a count
 * of, and an hour picked for a summary of the day is a request for "around
 * nine" rather than for nine exactly — spending part of the ration on it
 * leaves less for the entries held at a minute, which are the ones a minute
 * matters to. Without the access nothing is exact, digest and entries alike.
 */
internal fun heldExactly(reminder: PlannedReminder, allowed: Boolean): Boolean =
    allowed && reminder is TimedReminder
