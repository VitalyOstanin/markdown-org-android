package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * What the reader chose about being told what is coming.
 *
 * An interface for the same reason the other preferences are one: the planner
 * and the screen are exercised without a device. Everything here is per device
 * rather than per collection — the agenda over the collections is one agenda,
 * and a reminder is a property of how it is read, not of where the notes are
 * kept.
 */
interface ReminderPreferences {

    /** Whether anything is announced at all. */
    var enabled: Boolean

    /** How long before a timed entry it is announced. */
    var leadMinutes: Int

    /** Whether the moment itself is announced as well as the lead time. */
    var alsoAtStart: Boolean

    /** The hour the day's digest is raised at. */
    var digestAt: LocalTime

    /** The four of them together, as the planner reads them. */
    val choices: ReminderChoices
        get() = ReminderChoices(
            enabled = enabled,
            leadMinutes = leadMinutes,
            alsoAtStart = alsoAtStart,
            digestAt = digestAt,
        )
}

/**
 * The reminder preferences, in a file of their own.
 *
 * Apart from `ui` because the two are forgotten at different moments: a reader
 * who resets how the agenda is drawn has not asked to stop being told when a
 * meeting starts.
 */
class ReminderSettings(context: Context) : ReminderPreferences {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var enabled: Boolean
        // Off until it is asked for: the notification permission and the
        // exact-alarm access are both the reader's to grant, and an
        // application that starts by asking for them has not yet shown why.
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    override var leadMinutes: Int
        get() = preferences.getInt(KEY_LEAD, DEFAULT_LEAD_MINUTES).coerceIn(LEAD_RANGE)
        set(value) = preferences.edit().putInt(KEY_LEAD, value.coerceIn(LEAD_RANGE)).apply()

    override var alsoAtStart: Boolean
        get() = preferences.getBoolean(KEY_AT_START, false)
        set(value) = preferences.edit().putBoolean(KEY_AT_START, value).apply()

    override var digestAt: LocalTime
        // Stored as `HH:mm` rather than as a number of minutes: the file is
        // read by a person looking at what the application kept, and the two
        // cost the same to parse.
        get() = preferences.getString(KEY_DIGEST, null)?.let(::readTime) ?: DEFAULT_DIGEST_TIME
        set(value) = preferences.edit().putString(KEY_DIGEST, value.toString()).apply()

    /**
     * A stored time, or the default when it cannot be read.
     *
     * A value written by a newer version, or one edited by hand, reads as the
     * default rather than as a crash on the first plan.
     */
    private fun readTime(value: String): LocalTime = try {
        LocalTime.parse(value)
    } catch (_: DateTimeParseException) {
        DEFAULT_DIGEST_TIME
    }

    private companion object {
        const val FILE = "reminders"
        const val KEY_ENABLED = "enabled"
        const val KEY_LEAD = "lead_minutes"
        const val KEY_AT_START = "also_at_start"
        const val KEY_DIGEST = "digest_at"

        /**
         * Nothing beyond half a day: a lead time longer than that is a
         * different question — "warn me some days before" — which a `DEADLINE`
         * and its warning window already answer.
         */
        val LEAD_RANGE = 0..720
    }
}
