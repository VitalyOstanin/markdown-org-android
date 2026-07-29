package io.github.vitalyostanin.markdownorg.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import java.time.LocalDate
import java.time.LocalTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

// How the agenda writes an hour and a date. Built on `java.time` rather than
// on `android.text.format.DateFormat` so the rules can be pinned down by a JVM
// test: the same locale data lies behind both, and what matters here is that
// neither the order of the fields nor the separator between them is decided by
// this file.

/**
 * A time of day, on the clock the reader uses.
 *
 * [use24Hour] is the system setting, which overrides what the locale would
 * choose on its own — a reader on a 12-hour locale can ask for 24-hour time
 * and the agenda has to follow.
 */
internal fun timeLabel(time: LocalTime, locale: Locale, use24Hour: Boolean): String =
    DateTimeFormatter.ofPattern(hourPattern(locale, use24Hour), locale).format(time)

/** The label of a whole hour, as the axis of the time layout writes it. */
internal fun hourLabel(hour: Int, locale: Locale, use24Hour: Boolean): String =
    timeLabel(LocalTime.of(hour, 0), locale, use24Hour)

/**
 * Whether the device is set to a 24-hour clock.
 *
 * The setting stands apart from the locale: a reader on `en-US` can turn it
 * on, and then the agenda writes 24-hour time in an otherwise 12-hour locale.
 */
@Composable
internal fun use24Hour(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

/**
 * What the time column of [row] shows, in the locale and clock of the reader.
 *
 * A composable rather than a plain function so both come from the
 * composition: a locale read through [java.util.Locale.getDefault] is not
 * observable state, and the agenda would keep the language it was first drawn
 * in after the system language changes.
 */
@Composable
internal fun rowTimeLabel(row: AgendaRow): String {
    val locale = LocalLocale.current.platformLocale
    val use24Hour = use24Hour()

    return when (val time = row.time) {
        is RowTime.Clock -> timeLabel(time.at, locale, use24Hour)
        is RowTime.Since -> dayMonthLabel(time.date, locale)
        is RowTime.Verbatim -> time.text
        RowTime.None -> ""
    }
}

/**
 * A day and a month, in the order and with the separator of the locale.
 *
 * The year is dropped rather than never asked for: no locale states a pattern
 * for a bare day and month, and taking the short date apart is what keeps the
 * order — `7/3` against `03.07` for the same day.
 */
internal fun dayMonthLabel(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofPattern(dayMonthPattern(locale), locale).format(date)

private fun hourPattern(locale: Locale, use24Hour: Boolean): String {
    val short = localizedPattern(dateStyle = null, timeStyle = FormatStyle.SHORT, locale)

    return if (use24Hour) short.to24Hour() else short.to12Hour()
}

private fun dayMonthPattern(locale: Locale): String =
    localizedPattern(dateStyle = FormatStyle.SHORT, timeStyle = null, locale)
        .replace(YEAR, "")
        .trim { !it.isLetter() }

private fun localizedPattern(dateStyle: FormatStyle?, timeStyle: FormatStyle?, locale: Locale) =
    DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        dateStyle,
        timeStyle,
        IsoChronology.INSTANCE,
        locale,
    )

/**
 * A 24-hour reading of a pattern that may state either.
 *
 * The hour is padded to two digits even where the locale writes one: the
 * labels stand in a column down the axis, and a ragged left edge there is
 * read as an indent rather than as a time.
 */
private fun String.to24Hour(): String = replace(HOUR_12, "HH")
    .replace(PERIOD, "")
    .replace(SINGLE_HOUR_24, "HH")
    .trim { !it.isLetter() }

private fun String.to12Hour(): String {
    val twelve = replace(HOUR_24, "h")

    return if (twelve.contains('a')) twelve else "$twelve a"
}

/** Every letter a pattern can spell an hour with, by the clock it belongs to. */
private val HOUR_12 = Regex("[hK]+")
private val HOUR_24 = Regex("[Hk]+")
private val SINGLE_HOUR_24 = Regex("(?<!H)H(?!H)")
private val PERIOD = Regex("\\s*a\\s*")
private val YEAR = Regex("[yu]+")
