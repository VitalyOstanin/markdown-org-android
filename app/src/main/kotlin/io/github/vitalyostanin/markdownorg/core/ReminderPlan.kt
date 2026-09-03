package io.github.vitalyostanin.markdownorg.core

import uniffi.markdown_org_ffi.Day
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * What the reader asked to be told about.
 *
 * On the device rather than in the notes: a lead time written into a note
 * would have to be read by the core and would mean the same in the editor
 * extension, and neither is worth deciding before it is known whether one
 * lead time for everything is enough. A key such as `REMINDER: 30m` can be
 * added over this later, and these become the defaults it overrides.
 */
data class ReminderChoices(
    val enabled: Boolean = false,
    /** How long before a timed entry the notification is raised. */
    val leadMinutes: Int = DEFAULT_LEAD_MINUTES,
    /**
     * Whether the moment itself is announced as well as the lead time.
     *
     * Off by default, and a switch rather than the rule: a lead time of zero
     * already produces a notification at the moment, so the switch is only for
     * a reader who wants both.
     */
    val alsoAtStart: Boolean = false,
    /** The hour the day's digest is raised at. */
    val digestAt: LocalTime = DEFAULT_DIGEST_TIME,
)

/** The entry a reminder is about, as much of it as a notification needs. */
data class ReminderEntry(
    /** The collection the entry came from, as the task carries it. */
    val root: String?,
    val file: String,
    val line: UInt,
    val heading: String,
)

/** One notification the plan intends to raise, and when. */
sealed interface PlannedReminder {

    val at: ZonedDateTime
}

/**
 * A moment exists, so this names it: one entry, announced ahead of its time.
 *
 * [starts] is kept beside [at] because the notification says how far off the
 * entry is, and the two part as soon as the lead time is not zero.
 */
data class TimedReminder(
    override val at: ZonedDateTime,
    val starts: ZonedDateTime,
    val entry: ReminderEntry,
) : PlannedReminder

/**
 * A day has no moment, so it gets one notification naming what it holds.
 *
 * Carries the day and not its contents: what the day holds is read again when
 * the notification is raised, which is the only way it can account for an
 * entry closed in the hours between planning and firing.
 */
data class DigestReminder(override val at: ZonedDateTime, val day: LocalDate) : PlannedReminder

/**
 * What should fire between [now] and the end of the horizon, from the agenda
 * the core returned for those days.
 *
 * A pure function of the agenda, the clock and the choices, so the decision of
 * what to announce and when is a unit test; the alarms and the channels are
 * the thin part around it.
 *
 * Nothing is read from the entry beyond its day and its time: the core has
 * already left out what an inactive timestamp means (it is the reader saying
 * "not on the agenda"), rewritten the date of a repeating entry to the day the
 * row is drawn on, and opened the warning window a `DEADLINE` is announced in.
 * What is left to decide here is which of those days are close enough to
 * schedule and which entries are still open.
 */
fun planReminders(
    days: List<Day>,
    choices: ReminderChoices,
    now: ZonedDateTime,
    horizon: Duration = HORIZON,
): List<PlannedReminder> {
    if (!choices.enabled) {
        return emptyList()
    }
    val until = now.plus(horizon)
    val timed = days
        .flatMap { day -> day.scheduledTimed.mapNotNull { task -> moment(day, task, now) } }
        .flatMap { (task, starts) -> signals(task, starts, choices) }
        .filter { it.at.isAfter(now) && !it.at.isAfter(until) }
        // Two signals can land on the same second — a lead time of zero with
        // the switch on — and one entry announced twice at once is one
        // notification overwriting itself.
        .distinctBy { it.at to it.entry }
        .sortedBy { it.at }
        // The platform caps the alarms one application may hold. Every firing
        // re-plans, so an entry left out here is picked up by the time the
        // ones ahead of it have fired.
        .take(LIMIT)

    return (timed + digest(choices, now)).sortedBy { it.at }
}

/**
 * When the entry of [task] starts, or `null` when it is not one to announce.
 *
 * The day comes from the bucket rather than from the task: the entry is drawn
 * under this day, and reading its own date would answer for the anchor of a
 * repeating entry instead of the occurrence.
 */
private fun moment(day: Day, task: Task, now: ZonedDateTime): Pair<Task, ZonedDateTime>? {
    if (task.taskType == TaskType.DONE || task.taskType == TaskType.CANCELLED) {
        return null
    }
    val time = statedTime(task.timestampTime) ?: return null
    val starts = statedDate(day.date)?.atTime(time)?.atZone(now.zone)

    return starts?.let { task to it }
}

/** The one or two notifications one occurrence is announced by. */
private fun signals(
    task: Task,
    starts: ZonedDateTime,
    choices: ReminderChoices,
): List<TimedReminder> {
    val entry = ReminderEntry(
        root = task.root,
        file = task.file,
        line = task.line,
        heading = task.heading,
    )
    val lead = starts.minusMinutes(choices.leadMinutes.toLong())
    val moments = if (choices.alsoAtStart) listOf(lead, starts) else listOf(lead)

    return moments.map { TimedReminder(at = it, starts = starts, entry = entry) }
}

/**
 * The next digest, which is today's when the hour is still ahead and
 * tomorrow's once it has passed.
 *
 * One rather than every digest inside the horizon: the digest that fires
 * re-plans, and holding tomorrow's alarm as well would only mean cancelling it
 * again in the morning.
 */
private fun digest(choices: ReminderChoices, now: ZonedDateTime): DigestReminder {
    val today = now.toLocalDate().atTime(choices.digestAt).atZone(now.zone)
    val at = if (today.isAfter(now)) today else today.plusDays(1)

    return DigestReminder(at = at, day = at.toLocalDate())
}

/** Lead time until the reader chooses another. */
const val DEFAULT_LEAD_MINUTES: Int = 15

/** The hour the digest is raised at until the reader chooses another. */
val DEFAULT_DIGEST_TIME: LocalTime = LocalTime.of(9, 0)

/**
 * How far ahead alarms are held.
 *
 * A daily repeater over a year is thousands of occurrences, and the platform
 * holds nothing like that many alarms. Two days is enough that a phone left
 * switched off overnight still wakes up with its morning planned.
 */
val HORIZON: Duration = Duration.ofDays(2)

/** How many timed alarms one plan may hold. */
private const val LIMIT = 64
