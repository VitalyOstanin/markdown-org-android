package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TimestampType
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What the sheet offers about a task's dates.
 *
 * Three questions, and which of them apply depends on what the task already
 * carries. A task with a planning line can move that date by a day, put it on
 * another day outright, or lose it; a task with none can be given one, and
 * has to be told which kind — a day work starts, or a day something is due.
 *
 * The two kinds are not interchangeable, which is why a task without a date
 * is offered both rather than a single button and a guess.
 */
@Composable
internal fun TaskDates(task: Task, weekStart: WeekStart, onAction: (TaskAction) -> Unit) {
    var picking by rememberSaveable { mutableStateOf<PlanningKeyword?>(null) }
    var pickingHour by rememberSaveable { mutableStateOf(false) }
    val keyword = task.planningKeyword()

    if (keyword != null) {
        ShiftRow(keyword, onAction)
    }

    HintTooltip(
        stringResource(
            if (keyword != null) R.string.hint_action_date else R.string.hint_action_no_date,
        ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (keyword != null) {
                SheetAction(
                    label = stringResource(R.string.action_pick_date),
                    tag = "action-pick-date",
                    modifier = Modifier.weight(1f),
                ) { picking = keyword }
                SheetAction(
                    label = stringResource(R.string.action_clear_date),
                    tag = "action-clear-date",
                    modifier = Modifier.weight(1f),
                ) { onAction(TaskAction.Plan(keyword, null)) }
            } else {
                SheetAction(
                    label = stringResource(R.string.action_set_scheduled),
                    tag = "action-set-scheduled",
                    modifier = Modifier.weight(1f),
                ) { picking = PlanningKeyword.SCHEDULED }
                SheetAction(
                    label = stringResource(R.string.action_set_deadline),
                    tag = "action-set-deadline",
                    modifier = Modifier.weight(1f),
                ) { picking = PlanningKeyword.DEADLINE }
            }
        }
    }

    if (keyword != null) {
        HourRow(keyword, task.startTime(), onAction) { pickingHour = true }
    }

    picking?.let { kind ->
        DateChoice(
            // The day the task already sits on, so a date being moved opens
            // where it is rather than in whatever month today falls in. A task
            // with no date of its own opens on the current month with nothing
            // selected, and the calendar cannot be confirmed until a day is.
            initial = task.timestampDate?.let(LocalDate::parse),
            weekStart = weekStart,
            onDismiss = { picking = null },
            onPicked = { date ->
                picking = null
                onAction(TaskAction.Plan(kind, date))
            },
        )
    }

    if (pickingHour && keyword != null) {
        TimeChoice(
            // The hour the task is held at, so a time being changed opens
            // where it is. A date with no hour yet opens at the start of a
            // working day, which is a shorter way from most answers than
            // midnight is -- the same hour the screen that creates a task
            // opens on.
            initial = task.startTime() ?: WORKING_DAY_STARTS,
            onDismiss = { pickingHour = false },
            onPicked = { time ->
                pickingHour = false
                onAction(TaskAction.PlanTime(keyword, time))
            },
        )
    }
}

/**
 * The hour the date is held at, and the way to take it off.
 *
 * Only shown for a task that carries a date: an hour is a token inside a
 * timestamp, and a task with no planning line has none to put it in. Taking
 * the hour off is offered only where there is one, the same way clearing a
 * date is -- a button that would do nothing is not a button.
 */
@Composable
private fun HourRow(
    keyword: PlanningKeyword,
    hour: LocalTime?,
    onAction: (TaskAction) -> Unit,
    onPick: () -> Unit,
) {
    HintTooltip(stringResource(R.string.hint_action_hour)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SheetAction(
                label = stringResource(R.string.action_pick_hour),
                tag = "action-pick-hour",
                modifier = Modifier.weight(1f),
                onClick = onPick,
            )
            if (hour != null) {
                SheetAction(
                    label = stringResource(R.string.action_clear_hour),
                    tag = "action-clear-hour",
                    modifier = Modifier.weight(1f),
                ) { onAction(TaskAction.PlanTime(keyword, null)) }
            }
        }
    }
}

/** Where the clock opens for a date that names no hour yet. */
private val WORKING_DAY_STARTS: LocalTime = LocalTime.of(9, 0)

/**
 * Which planning line the task carries, and therefore which one can be moved.
 *
 * The extractor reports the kind it found; the two that are not planning lines
 * are spelled out rather than left to `else`, so a kind added to the core has
 * to be answered for here.
 */
internal fun Task.planningKeyword(): PlanningKeyword? = when (timestampType) {
    TimestampType.DEADLINE -> PlanningKeyword.DEADLINE

    TimestampType.SCHEDULED -> PlanningKeyword.SCHEDULED

    // A closing date records when the task was finished, and a bare timestamp
    // carries no keyword to move.
    TimestampType.CLOSED, TimestampType.PLAIN, null -> null
}

/**
 * Moving the planning date by a day, both ways.
 *
 * One line for both buttons: what a day earlier and a day later write differs
 * only in the sign, and the rest — the time, the repeater, the weekday — is
 * what is worth saying. The tooltip goes around the row rather than around
 * each button: a weight handed to a tooltip is not the weight the row
 * measures, and the second button ended up off the screen. A long press on
 * either of them reaches the box all the same — a button takes the tap and
 * leaves the long press alone.
 */
@Composable
private fun ShiftRow(keyword: PlanningKeyword, onAction: (TaskAction) -> Unit) {
    HintTooltip(stringResource(R.string.hint_action_shift)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SheetAction(
                label = stringResource(R.string.action_shift_back),
                tag = "action-shift-back",
                modifier = Modifier.weight(1f),
            ) { onAction(TaskAction.Shift(keyword, -1)) }
            SheetAction(
                label = stringResource(R.string.action_shift_forward),
                tag = "action-shift-forward",
                modifier = Modifier.weight(1f),
            ) { onAction(TaskAction.Shift(keyword, 1)) }
        }
    }
}

/**
 * The calendar, as a dialog over the sheet.
 *
 * The chosen day is held apart from the picker's own state because that state
 * cannot be saved: its saver is not public, and the locale below is not one
 * the remembered picker accepts. Keeping the day here means a rotation with
 * the calendar open comes back to the day that was chosen in it.
 *
 * Shared with the screen that writes a new task, which asks the same question
 * about a task that is not in the notes yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateChoice(
    initial: LocalDate?,
    weekStart: WeekStart,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf(initial?.toEpochDay()?.times(A_DAY)) }
    val locale = weekStart.calendarLocale()
    val room = aCalendarFits(LocalConfiguration.current.screenHeightDp.dp)
    val state = remember(locale, room) {
        DatePickerState(
            locale = locale,
            initialSelectedDateMillis = chosen,
            initialDisplayMode = if (room) DisplayMode.Picker else DisplayMode.Input,
        )
    }
    LaunchedEffect(state.selectedDateMillis) { chosen = state.selectedDateMillis }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { chosen?.let { onPicked(LocalDate.ofEpochDay(it / A_DAY)) } },
                enabled = chosen != null,
                modifier = Modifier.testTag("date-set"),
            ) {
                Text(stringResource(R.string.date_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("date-cancel")) {
                Text(stringResource(R.string.date_cancel))
            }
        },
        modifier = Modifier.testTag("date-picker"),
    ) {
        DatePicker(
            state = state,
            showModeToggle = room,
            modifier = Modifier.testTag(if (room) "date-calendar" else "date-typed"),
        )
    }
}

/**
 * The clock, as a dialog over whatever asked for it.
 *
 * Wrapped in a dialog by hand, which is what Material's own guide for time
 * pickers gives: the picker is a composable, and the dialog around it is the
 * caller's. Whether it is drawn as a twelve- or a twenty-four-hour clock is
 * the phone's setting, which is what the picker's state reads when it is not
 * told otherwise.
 *
 * Shared with the screen that writes a new task, the same way the calendar
 * above it is: the hour an entry is held at is one question, asked of a task
 * in the notes and of one being typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeChoice(initial: LocalTime, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
    )

    val room = with(LocalConfiguration.current) { aClockFits(screenWidthDp.dp, screenHeightDp.dp) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onPicked(LocalTime.of(state.hour, state.minute)) },
                modifier = Modifier.testTag("time-set"),
            ) {
                Text(stringResource(R.string.date_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("time-cancel")) {
                Text(stringResource(R.string.date_cancel))
            }
        },
        text = {
            if (room) {
                TimePicker(state = state, modifier = Modifier.testTag("time-clock"))
            } else {
                TimeInput(state = state, modifier = Modifier.testTag("time-typed"))
            }
        },
        modifier = Modifier.testTag("time-picker"),
    )
}

/**
 * The locale the calendar is drawn with: the phone's own, told which weekday a
 * week begins on when the reader has said something other than the locale
 * does.
 *
 * The picker takes the first day of the week from its locale and offers no way
 * to state it, so the answer is carried in the locale itself — the Unicode
 * `fw` keyword, which is what that extension is for. The result is checked
 * rather than trusted: a platform that ignores the keyword would otherwise
 * leave the calendar cut one way and the agenda's month grid another, and the
 * phone's own locale is the better of the two answers to fall back on.
 */
internal fun WeekStart.calendarLocale(locale: Locale = Locale.getDefault()): Locale {
    val wanted = resolve(locale)
    if (WeekFields.of(locale).firstDayOfWeek == wanted) {
        return locale
    }

    val asked = Locale.Builder()
        .setLocale(locale)
        .setUnicodeLocaleKeyword("fw", wanted.name.take(WEEKDAY_KEY).lowercase(Locale.ROOT))
        .build()
    return if (WeekFields.of(asked).firstDayOfWeek == wanted) asked else locale
}

/**
 * Whether a window this tall has room for a calendar to be picked from.
 *
 * Material's calendar is not compressible: the grid asks for six rows of the
 * height a finger needs, and the dialog around it for a fixed height on top of
 * a header and a row of buttons. Given less, it draws at its full height and
 * is cut by the edges of the window — the last week and the buttons that
 * confirm the choice go off screen, and the date cannot be set at all. A phone
 * held sideways is short enough for that, so the dialog is asked for its typed
 * form instead, which is a single field and always fits.
 */
internal fun aCalendarFits(windowHeight: Dp): Boolean = windowHeight >= Sizes.calendarHeight

/**
 * Whether a window of this shape has room for a clock face to be turned.
 *
 * Material draws the clock across rather than down as soon as the window is
 * wider than it is tall, and that arrangement — the hour and minute fields, a
 * gap, and the dial — is wider than a dialog is allowed to be. The dial is
 * then cut by the right edge and its far half cannot be reached, so a sideways
 * window is given the typed form of the same question.
 */
internal fun aClockFits(windowWidth: Dp, windowHeight: Dp): Boolean = windowHeight >= windowWidth

/** How many letters of a weekday the `fw` keyword is spelled with: `mon`, `sun`. */
private const val WEEKDAY_KEY = 3

/** The picker counts in milliseconds from the epoch, at UTC midnight. */
private const val A_DAY = 86_400_000L
