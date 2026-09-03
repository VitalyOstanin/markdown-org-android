package io.github.vitalyostanin.markdownorg.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The day a task was written for when nobody named one.
 *
 * A phrase may name an hour or a repeat and no day at all -- the rules of the
 * extractor set the three independently -- and a planning line has nowhere to
 * put an hour without a day. The day is chosen rather than the hour dropped,
 * and this says which day and on what grounds, so the screen can state both
 * instead of a task quietly arriving as a bare heading.
 *
 * @property date the day written into the entry.
 * @property hour the hour that was named, where one was.
 * @property passed whether that hour was already behind, which is what moved
 * the entry to tomorrow.
 */
data class AssumedDay(val date: LocalDate, val hour: LocalTime?, val passed: Boolean)

/**
 * The day this draft has to be given, or `null` where it names one already or
 * needs none.
 *
 * The next time the hour comes round: today while it is still ahead, tomorrow
 * once it has gone by. An hour equal to the current minute counts as gone --
 * the file is written after it.
 */
internal fun TaskDraft.assumedDay(now: LocalDateTime): AssumedDay? {
    if (date != null || (time == null && repeater == null)) {
        return null
    }

    val passed = time != null && !time.isAfter(now.toLocalTime())

    return AssumedDay(
        date = if (passed) now.toLocalDate().plusDays(1) else now.toLocalDate(),
        hour = time,
        passed = passed,
    )
}

/** The draft as it is written: with the day it named, or with the day it is given. */
internal fun TaskDraft.onItsDay(now: LocalDateTime): TaskDraft =
    assumedDay(now)?.let { assumed -> copy(date = assumed.date) } ?: this
