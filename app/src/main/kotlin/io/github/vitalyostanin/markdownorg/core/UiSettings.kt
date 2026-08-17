package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import io.github.vitalyostanin.markdownorg.ui.AgendaLayout
import io.github.vitalyostanin.markdownorg.ui.AgendaSpan
import io.github.vitalyostanin.markdownorg.ui.WeekStart

/**
 * What the user chose about the interface itself, as everything above the
 * storage sees it.
 *
 * Kept apart from [SyncPreferences]: that one describes where the notes come
 * from and carries a token, this one describes how they are drawn. An
 * interface for the same reason — the view model is exercised without a device.
 */
interface UiPreferences {

    /** Which of the two layouts the agenda opens in. */
    var layout: AgendaLayout

    /** How much of the plan the agenda opens on: a day, a week, a month, the tasks. */
    var span: AgendaSpan

    /**
     * Whether a day is split into named sections, or drawn as one list.
     *
     * The headings name what a row is and carry the group menu; a screen this
     * narrow is also where they cost the most, which is why the choice is the
     * reader's rather than the layout's.
     */
    var grouped: Boolean

    /**
     * Whether the month is drawn as a calendar, or as the list the week uses.
     *
     * The two answer different questions — the grid says how the month is
     * shaped and where the arrears sit, the list says what is in it — and which
     * one a reader wants is not something the screen can tell.
     */
    var monthAsGrid: Boolean

    /**
     * Which weekday a week is read as beginning on.
     *
     * The phone's own locale answers it by default. Stated here as well
     * because the two can disagree — a reader whose habit is not the one the
     * locale carries — and because the answer decides both where the calendar
     * is cut and how the week span is grouped.
     */
    var weekStart: WeekStart
}

/**
 * The interface preferences, in a file of their own.
 *
 * A separate file from `sync` so that forgetting the repository — which clears
 * that one outright — does not also reset how the agenda is drawn.
 */
class UiSettings(context: Context) : UiPreferences {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var layout: AgendaLayout
        get() = stored(KEY_LAYOUT, AgendaLayout.entries, AgendaLayout.TIME)
        set(value) = store(KEY_LAYOUT, value.name)

    override var span: AgendaSpan
        // The day, because that is what a phone is opened to look at: what is
        // on today. The wider spans are a question asked deliberately, and one
        // asked once should not become the answer on every launch after it.
        get() = stored(KEY_SPAN, AgendaSpan.entries, AgendaSpan.DAY)
        set(value) = store(KEY_SPAN, value.name)

    override var grouped: Boolean
        // Grouped, because that is what the agenda has always drawn and what
        // names the overdue bands apart; a reader who wants the height back
        // asks for it.
        get() = preferences.getBoolean(KEY_GROUPED, true)
        set(value) = preferences.edit().putBoolean(KEY_GROUPED, value).apply()

    override var monthAsGrid: Boolean
        // The calendar, because that is what a month is asked for: thirty-one
        // days of rows is the week's reading stretched past what a screen can
        // hold, and the shape of the month is what the list cannot show at all.
        get() = preferences.getBoolean(KEY_MONTH_GRID, true)
        set(value) = preferences.edit().putBoolean(KEY_MONTH_GRID, value).apply()

    override var weekStart: WeekStart
        // The phone's own answer, because that is the one the reader already
        // gets from every other calendar on the device. The two fixed values
        // are for saying otherwise.
        get() = stored(KEY_WEEK_START, WeekStart.entries, WeekStart.AUTO)
        set(value) = store(KEY_WEEK_START, value.name)

    /**
     * The stored choice among [options], or [fallback].
     *
     * A value written by a newer version, or a name that has since been
     * dropped, reads as the default rather than as a crash on startup.
     */
    private fun <T : Enum<T>> stored(key: String, options: List<T>, fallback: T): T =
        preferences.getString(key, null)
            ?.let { name -> options.firstOrNull { it.name == name } }
            ?: fallback

    private fun store(key: String, name: String) = preferences.edit().putString(key, name).apply()

    private companion object {
        const val FILE = "ui"
        const val KEY_LAYOUT = "agenda_layout"
        const val KEY_SPAN = "agenda_span"
        const val KEY_GROUPED = "agenda_grouped"
        const val KEY_MONTH_GRID = "agenda_month_grid"
        const val KEY_WEEK_START = "agenda_week_start"
    }
}
