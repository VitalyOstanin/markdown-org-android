package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Which weekday a week is read as beginning on.
 *
 * The core takes a weekday and defaults to Monday when it is told nothing: it
 * reads no locale of its own, deliberately, so that the same notes render the
 * same agenda wherever they are read. Answering that question is the client's,
 * and the phone has the answer in its own settings — which is what [AUTO]
 * takes. The two fixed values are for the reader whose habit and whose locale
 * disagree: a Russian phone read by somebody who plans on Sundays, or the
 * other way round.
 *
 * It decides more than where the calendar is cut: the week span is grouped
 * from the same weekday, so the two spans step through the same weeks.
 */
enum class WeekStart {
    /** Whatever the phone's own locale says a week starts on. */
    AUTO,
    MONDAY,
    SUNDAY,
    ;

    /** The weekday itself, resolved against [locale] when this is [AUTO]. */
    fun resolve(locale: Locale = Locale.getDefault()): DayOfWeek = when (this) {
        AUTO -> WeekFields.of(locale).firstDayOfWeek
        MONDAY -> DayOfWeek.MONDAY
        SUNDAY -> DayOfWeek.SUNDAY
    }
}

/** What the choice is called where it is made. */
@get:StringRes
internal val WeekStart.labelRes: Int
    get() = when (this) {
        WeekStart.AUTO -> R.string.settings_week_start_auto
        WeekStart.MONDAY -> R.string.settings_week_start_monday
        WeekStart.SUNDAY -> R.string.settings_week_start_sunday
    }

/** Handle for the instrumented tests, as the span switch has one. */
internal val WeekStart.testTag: String get() = "settings-week-start-$name"
