package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Which locale the calendar is drawn with.
 *
 * The picker takes the first day of the week from its locale and offers no way
 * to state it, so the reader's answer has to be carried in the locale. What is
 * asserted here is the contract rather than the platform: either the weekday
 * asked for is the one the resulting locale reports, or the locale came back
 * unchanged -- a platform that ignores the keyword must not leave a locale
 * whose week begins on some third day.
 */
class CalendarLocaleTest {

    private val sunday = Locale.US
    private val monday = Locale.UK

    @Test
    fun theLocaleIsLeftAloneWhenItAlreadyBeginsTheWeekWhereTheReaderDoes() {
        assertEquals(sunday, WeekStart.AUTO.calendarLocale(sunday))
        assertEquals(monday, WeekStart.AUTO.calendarLocale(monday))
        assertEquals(sunday, WeekStart.SUNDAY.calendarLocale(sunday))
        assertEquals(monday, WeekStart.MONDAY.calendarLocale(monday))
    }

    @Test
    fun aWeekAskedToBeginElsewhereEitherBeginsThereOrIsLeftAsItWas() {
        val asked = WeekStart.MONDAY.calendarLocale(sunday)
        val begins = WeekFields.of(asked).firstDayOfWeek

        assertTrue(
            "the week begins on $begins",
            begins == DayOfWeek.MONDAY || asked == sunday,
        )
    }

    @Test
    fun theLanguageOfTheCalendarIsNeverTradedForItsFirstDay() {
        // A locale of another language would put the month names in it, so a
        // reader whose habit and whose locale disagree must not be answered
        // with an English calendar.
        val russian = Locale.forLanguageTag("ru-RU")

        assertEquals(russian.language, WeekStart.SUNDAY.calendarLocale(russian).language)
        assertEquals(russian.country, WeekStart.SUNDAY.calendarLocale(russian).country)
    }
}
