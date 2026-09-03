package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.markdown_org_ffi.Day
import uniffi.markdown_org_ffi.Scope
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.coroutines.CoroutineContext

/**
 * Keeps what the platform holds in step with what the notes say.
 *
 * The whole plan is worked out again from the agenda every time anything could
 * have moved — a fetch, an edit, a change of settings, a reminder firing. What
 * is announced is a property of the notes, and a note can change on another
 * device: an entry closed there stops being announced here as soon as the
 * fetch lands, which no account of the difference between two plans could
 * arrange.
 *
 * The agenda is asked for a day at a time rather than for a week, because a
 * week is drawn around its first weekday: on a Sunday the week window ends
 * today and tomorrow's entries would fall outside it, unplanned until the next
 * morning.
 */
class ReminderScheduler(
    private val agenda: AgendaLoader,
    private val preferences: ReminderPreferences,
    private val alarms: AlarmHolder,
    private val horizon: Duration = HORIZON,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    /**
     * Where the plan is made, which is not where it is asked for.
     *
     * Everything `replan` does touches storage or the platform: the choices
     * are a preference file, and each entry of the plan is a `PendingIntent`
     * and a call to the alarm manager — a full plan is hundreds of binder
     * transactions. Asked for after an edit and from the settings screen, both
     * of which run on the main thread, that is the frame the reader is
     * looking at. A parameter so a test can name the thread it expects.
     */
    private val io: CoroutineContext = Dispatchers.IO,
) {

    /**
     * Replace every alarm held with what the notes now call for.
     *
     * Quiet about a failure to read the agenda: this runs where there is no
     * screen to report to — a broadcast after a fetch, the boot of the phone —
     * and the alarms held are left alone rather than dropped, so a directory
     * momentarily unreadable does not cost the reader the day's reminders.
     * Quiet here means "returned rather than shown": what the caller does
     * with the answer is the caller's own, and every one of them says
     * something about it.
     */
    suspend fun replan(): Result<Unit> = withContext(io) {
        val choices = preferences.choices
        if (!choices.enabled) {
            // Under `runCatching` like the rest: holding the alarms is a call
            // into the platform, and a platform that refuses it answers with
            // an exception. Left outside, that exception would leave a
            // function declared as returning a Result by being thrown out of
            // it — into a coroutine whose only handler ends the process.
            return@withContext runCatching { alarms.cancelAll() }
        }
        val now = clock()

        days(now).mapCatching { days ->
            alarms.replace(planReminders(days, choices, now, horizon))
        }
    }

    /**
     * The day as it stands now, for the digest about to be raised.
     *
     * Read when the notification fires rather than when it was planned: hours
     * separate the two, and a digest that names an entry closed in between is
     * a reminder the reader has to check against the notes.
     */
    suspend fun read(date: LocalDate): Result<Day> {
        val now = clock()

        return agenda.load(Scope.DAY, today = now.toLocalDate(), shown = date, zone = now.zone)
            .mapCatching { result -> result.days.first { it.date == date.toString() } }
    }

    /**
     * Every day the horizon reaches, today first.
     *
     * Today is asked for as itself so that what is late is counted against it;
     * the days after it are drawn as themselves, since arrears follow the
     * reader otherwise and would be announced again in tomorrow's digest.
     */
    private suspend fun days(now: ZonedDateTime): Result<List<Day>> {
        val today = now.toLocalDate()
        val last = now.plus(horizon).toLocalDate()
        val dates = generateSequence(today) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .toList()

        return runCatching {
            dates.flatMap { date ->
                agenda.load(Scope.DAY, today = today, shown = date, zone = now.zone)
                    .getOrThrow()
                    .days
            }
        }
    }

    companion object {

        /**
         * The scheduler as it runs on the device, reading the collections the
         * settings name.
         *
         * Built where it is needed rather than held: a broadcast receiver is a
         * few milliseconds of process, and the working copies this opens are
         * closed with it.
         */
        fun of(context: Context): ReminderScheduler {
            val collections = NotesCollectionsStore(context).collections

            return ReminderScheduler(
                agenda = AgendaSource(DeviceCollections(context, collections)),
                preferences = ReminderSettings(context),
                alarms = ReminderAlarms(context),
            )
        }
    }
}
