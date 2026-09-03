package io.github.vitalyostanin.markdownorg.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.format.SignStyle
import java.time.temporal.ChronoField

/**
 * The date the note states, or `null` where what it states is not one.
 *
 * Both this and [statedTime] read a field that arrives as the file has it.
 * The extractor captures a date by shape -- four digits, two, two -- and an
 * hour as one or two digits and two, and neither capture says the value is one
 * the calendar or the clock has. Read with `LocalDate.parse` and
 * `LocalTime.parse`, which are the strict ISO readings, `2026-02-30` throws
 * under whatever screen shows it and `9:00` is refused for the one digit the
 * note is entitled to write. So both are read here: strictly enough that what
 * is not a date or an hour answers `null`, leniently enough that an hour
 * written the shorter way is still the hour it says.
 */
internal fun statedDate(stated: String?): LocalDate? =
    stated?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * The hour the note states, or `null` where what it states is not one.
 *
 * An hour of one digit is the same hour: org-mode writes `9:00`, and so does
 * anyone typing a timestamp by hand.
 */
internal fun statedTime(stated: String?): LocalTime? =
    stated?.let { runCatching { LocalTime.parse(it, CLOCK) }.getOrNull() }

/**
 * `H:mm` and `HH:mm` alike, which is what a note may hold.
 *
 * Resolved strictly, so that `24:00` is refused rather than turned into the
 * midnight the day begins with -- a reminder a day early is worse than one a
 * note asked for in a way the clock does not have.
 */
private val CLOCK: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NOT_NEGATIVE)
    .appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
    .toFormatter()
    .withResolverStyle(ResolverStyle.STRICT)
