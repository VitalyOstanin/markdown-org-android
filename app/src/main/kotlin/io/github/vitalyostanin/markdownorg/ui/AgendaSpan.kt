package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R
import uniffi.markdown_org_ffi.Scope

/**
 * How much of the plan is asked for at once.
 *
 * The core has answered all four of these since the day the bindings were
 * written; what the phone showed was the day, because a day is what fits an
 * hour axis. The wider spans are read as a list of days, and [TASKS] as a list
 * with no days in it at all — the core fills a different field for it, and the
 * entries in that field carry no date to sit under.
 *
 * A span of the interface rather than the core's [Scope] carried around
 * directly: the screen decides what a span means for the header, for the layout
 * switch and for which days are worth a heading, and none of that belongs to
 * the bindings.
 */
enum class AgendaSpan(val scope: Scope) {
    DAY(Scope.DAY),
    WEEK(Scope.WEEK),
    MONTH(Scope.MONTH),

    /**
     * Everything still to do, dates or no dates, ordered by priority.
     *
     * The one span that answers "what is left" rather than "what is planned":
     * a task with no timestamp appears in no day agenda at all, and this is the
     * only place on the phone it can be seen.
     */
    TASKS(Scope.TASKS),
    ;

    /** Whether its entries sit on days that are worth a heading of their own. */
    val hasDays: Boolean get() = this != TASKS

    /**
     * Whether the hour axis can draw it.
     *
     * One axis covers one day: a week on it would be seven axes stacked, which
     * is a calendar and not what that layout is. The wider spans are drawn as
     * the list, and the switch between the two is left out while they are on
     * screen rather than left there doing nothing.
     */
    val fitsTimeLayout: Boolean get() = this == DAY

    /**
     * Whether every day of the span gets a heading, empty ones included.
     *
     * A week reads as a week when all seven days are there — an empty Thursday
     * is the answer to "what is on Thursday". A month of thirty-one headings,
     * most of them empty, is a screen the user scrolls past to reach the days
     * that have anything on them.
     */
    val showsEmptyDays: Boolean get() = this == WEEK
}

/** What the span is called where it is chosen. */
@get:StringRes
internal val AgendaSpan.labelRes: Int
    get() = when (this) {
        AgendaSpan.DAY -> R.string.agenda_span_day
        AgendaSpan.WEEK -> R.string.agenda_span_week
        AgendaSpan.MONTH -> R.string.agenda_span_month
        AgendaSpan.TASKS -> R.string.agenda_span_tasks
    }

/** Handle for the instrumented tests; the same shape as the layout switch. */
internal val AgendaSpan.testTag: String get() = "span-$name"
