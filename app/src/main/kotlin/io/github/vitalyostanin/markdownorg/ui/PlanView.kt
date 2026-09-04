package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.core.UiPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * How much of the plan is on screen and how it is drawn.
 *
 * Six answers the reader gives and the application remembers: the layout, the
 * span, whether a day announces its sections, whether a month is a calendar or
 * a list, which weekday a week begins on, and which date the plan is asked
 * around. They are held together because they decide one thing between them --
 * what the next scan asks the core for -- and because each of them has the
 * same two consequences to settle: what is written back to [ui], and whether
 * the answer in hand can be redrawn or has to be asked for again.
 *
 * Which of the two applies is stated on each setter rather than left to the
 * reader of the call site: a scan is a read of the notes directory, and taking
 * one where the rows in hand would have done shows as an agenda that blinks.
 *
 * @param ui where these answers are kept between launches
 * @param clock what day it is, so a step back to today can clear the anchor
 * @param rescan asks the core for the plan again, for the changes that need it
 */
class PlanView(
    private val ui: UiPreferences,
    private val clock: () -> LocalDateTime,
    private val rescan: () -> Unit,
) {

    /**
     * Kept apart from the agenda state so switching the layout redraws without
     * going back through Loading -- the data is the same, only its shape
     * changes. Read from the stored preference, so the agenda opens the way it
     * was left rather than always on the hour axis.
     */
    private val _layout = MutableStateFlow(ui.layout)
    val layout: StateFlow<AgendaLayout> = _layout.asStateFlow()

    /**
     * How much of the plan is asked for, read from the stored preference for
     * the same reason as the layout: a span chosen yesterday is the one the
     * screen opens on today.
     *
     * Unlike the layout, changing it costs a scan -- the core groups the tasks
     * against the span, and a week is not something the day agenda on screen
     * can be regrouped into.
     */
    private val _span = MutableStateFlow(ui.span)
    val span: StateFlow<AgendaSpan> = _span.asStateFlow()

    /**
     * Whether the day is drawn under its section headings.
     *
     * Costs no scan, like the layout: the sections are already in hand and the
     * setting decides whether they announce themselves.
     */
    private val _grouped = MutableStateFlow(ui.grouped)
    val grouped: StateFlow<Boolean> = _grouped.asStateFlow()

    /**
     * Whether the month is read as a calendar or as the list of its days.
     *
     * Costs no scan either: the same month is in hand, and this decides which
     * of the two readings of it is drawn.
     */
    private val _monthAsGrid = MutableStateFlow(ui.monthAsGrid)
    val monthAsGrid: StateFlow<Boolean> = _monthAsGrid.asStateFlow()

    /**
     * Which weekday a week is read as beginning on.
     *
     * Unlike the two above it, this one costs a scan: where a week starts is
     * the core's to apply -- it groups the week span and cuts the calendar into
     * rows -- so a changed answer is a different agenda, not a different
     * drawing of the same one.
     */
    private val _weekStart = MutableStateFlow(ui.weekStart)
    val weekStart: StateFlow<WeekStart> = _weekStart.asStateFlow()

    /**
     * Which date the plan is asked around, or `null` for whatever day it is.
     *
     * `null` rather than today's date written down at launch: a phone is left
     * running over midnight, and a date taken once would keep showing yesterday
     * until something reset it. Not stored either -- a step away from today is
     * an act of looking something up, and an application reopened tomorrow
     * opens on tomorrow.
     */
    private val _anchor = MutableStateFlow<LocalDate?>(null)
    val anchor: StateFlow<LocalDate?> = _anchor.asStateFlow()

    fun setLayout(layout: AgendaLayout) {
        _layout.value = layout
        ui.layout = layout
    }

    /** Draw the day under its section headings, or as one list. */
    fun setGrouped(grouped: Boolean) {
        _grouped.value = grouped
        ui.grouped = grouped
    }

    /**
     * Draw the month as a calendar, or as the list the week uses.
     *
     * No scan follows, unlike [setSpan]: both readings are of the same month
     * the core has already answered with, and which of them is drawn is the
     * screen's decision alone.
     */
    fun setMonthAsGrid(asGrid: Boolean) {
        if (asGrid == _monthAsGrid.value) return

        _monthAsGrid.value = asGrid
        ui.monthAsGrid = asGrid
        // The two readings of a month are no longer the same answer read two
        // ways: the calendar is asked for the whole weeks it draws, the list
        // for the month alone. Only the month is affected, and only while it
        // is the span on screen -- switching this from the day view changes
        // what the next month will ask for and nothing that is drawn now.
        if (_span.value == AgendaSpan.MONTH) {
            rescan()
        }
    }

    /**
     * Read a week as beginning on another weekday.
     *
     * A scan follows wherever the choice is visible: the calendar is cut into
     * weeks by the core, and so is the week span itself, so neither can be
     * redrawn from the answer already in hand.
     */
    fun setWeekStart(start: WeekStart) {
        if (start == _weekStart.value) return

        _weekStart.value = start
        ui.weekStart = start
        if (_span.value == AgendaSpan.WEEK ||
            (_span.value == AgendaSpan.MONTH && _monthAsGrid.value)
        ) {
            rescan()
        }
    }

    /**
     * The weekday a week begins on, for the spans drawn in weeks.
     *
     * The core takes a fixed Monday when it is told nothing, and reads no
     * locale to do better: it is a library with one answer per input, and the
     * phone is what knows how its owner reads a calendar. The setting answers,
     * and its own default is the system locale.
     */
    fun firstDayOfWeek(): DayOfWeek = _weekStart.value.resolve()

    /**
     * Ask the core for another span of the plan.
     *
     * A scan follows, because the grouping is the core's: a week is the same
     * notes read against seven dates, and the day already on screen carries
     * neither the other six nor the tasks that have no date at all. The one on
     * screen stays up while it runs.
     */
    fun setSpan(span: AgendaSpan) {
        if (span == _span.value) {
            return
        }

        _span.value = span
        ui.span = span
        rescan()
    }

    /**
     * Move the plan [steps] spans away from where it stands: a day at a time
     * under the day span, a week under the week, a month under the month.
     *
     * The step follows the span rather than always being a day, because the
     * span is what is on screen: stepping a week agenda by one day would answer
     * with six of the same seven days and read as nothing having happened. The
     * flat list of tasks has no dates to step through and stays where it is.
     *
     * Costs a scan, like [setSpan]: the grouping into overdue, timed and
     * untimed is the core's, and it is made against the date asked for.
     */
    fun stepBy(steps: Int) {
        if (steps == 0 || !_span.value.hasDays) {
            return
        }

        val from = _anchor.value ?: clock().toLocalDate()
        val moved = when (_span.value) {
            AgendaSpan.WEEK -> from.plusWeeks(steps.toLong())
            AgendaSpan.MONTH -> from.plusMonths(steps.toLong())
            else -> from.plusDays(steps.toLong())
        }
        // Back to following the clock rather than pinned to today's date: a
        // step back to today should leave the screen as it was before the first
        // step forward, midnight included.
        _anchor.value = moved.takeIf { it != clock().toLocalDate() }
        rescan()
    }

    /**
     * Show one day, whatever span was on screen.
     *
     * What a cell of the month calendar asks for. Both the anchor and the span
     * move, and a scan follows for the reason [setSpan] takes one: a day is a
     * different grouping of the notes, not a slice of the month already in
     * hand. The anchor is cleared when the day asked for is today, so the plan
     * goes back to following the clock -- the same rule [stepBy] keeps.
     */
    fun showDay(date: LocalDate) {
        _anchor.value = date.takeIf { it != clock().toLocalDate() }
        if (_span.value != AgendaSpan.DAY) {
            _span.value = AgendaSpan.DAY
            ui.span = AgendaSpan.DAY
        }
        rescan()
    }

    /** Back to the day being lived through, from wherever the plan was moved to. */
    fun showToday() {
        if (_anchor.value == null) {
            return
        }

        _anchor.value = null
        rescan()
    }
}
