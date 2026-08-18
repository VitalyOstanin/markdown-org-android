package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.AgendaRole
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.Task
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

/**
 * The month as a calendar rather than as a list of its days.
 *
 * What a month is read for is the shape of it — which weeks are full, where the
 * arrears sit, which stretch is free — and a list answers that only by being
 * scrolled end to end. Under the grid stands the day the reader picked out of
 * it, with its rows: the shape is what the grid is for, and the rows are what
 * the reader was looking at the shape to find.
 *
 * The list stays available for the month behind a setting, because the two
 * answer different questions and the reader knows which one is being asked.
 */
@Composable
internal fun MonthLayout(
    cells: List<MonthCell>,
    load: Map<LocalDate, MonthLoad>,
    days: List<AgendaDay>,
    anchor: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate) -> Unit = {},
    onTaskClick: (Task) -> Unit = {},
) {
    val locale = LocalLocale.current.platformLocale
    // Read off the cells rather than off a constant: the row of headers has to
    // name the columns the grid was actually built with.
    val columns = DayOfWeek.entries.size
    val headers = remember(cells.firstOrNull()?.date, locale) {
        val first = cells.firstOrNull()?.date?.dayOfWeek ?: DayOfWeek.MONDAY
        (0 until columns).map { index ->
            first.plus(index.toLong()).getDisplayName(TextStyle.SHORT, locale)
        }
    }
    val weeks = remember(cells, columns) { cells.chunked(columns) }

    // Forgotten when the reader pages to another month: a day of August picked
    // out of August has nothing to say under September, and the panel would be
    // showing a day the grid above it no longer draws.
    var picked by rememberSaveable(YearMonth.from(anchor)) { mutableStateOf<String?>(null) }
    val shown = picked?.let(LocalDate::parse)
        ?: cells.firstOrNull { it.today }?.date
        ?: cells.firstOrNull { !it.otherMonth }?.date

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The grid asks for the height its cells want and takes less only when
        // the window has less to give. Sharing the whole height between the
        // weeks instead — which is what it did — drew a cell twice as tall as
        // it is wide, with a band of content across the middle.
        //
        // Everything around the cells is counted in: the row of weekday names,
        // the gap above each row of cells, and the padding the grid stands in.
        // Left out, the arithmetic said the grid was some 40 dp shorter than it
        // draws, and the last week went under the bottom of a short window.
        val around = Sizes.monthHeaderRow + Spacing.xs * (weeks.size + 2)
        val roomy = maxHeight - (Sizes.monthCell * weeks.size + around) >= Sizes.monthPanelMin
        val cellHeight = if (roomy) {
            Sizes.monthCell
        } else {
            minOf(Sizes.monthCell, (maxHeight - around) / weeks.size)
        }

        Column(Modifier.fillMaxWidth().testTag("agenda-month")) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    headers.forEach { label ->
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                weeks.forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(cellHeight),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        week.forEach { cell ->
                            MonthCellTile(
                                cell = cell,
                                load = load[cell.date],
                                picked = cell.date == shown,
                                onClick = { picked = cell.date.toString() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }

            // Left out rather than squeezed where the window has no room for
            // it — a landscape phone — and the grid takes the height instead.
            if (roomy && shown != null) {
                MonthDayPanel(
                    date = shown,
                    rows = remember(days, shown) { days.rowsOf(shown) },
                    today = today,
                    onOpen = { onDayClick(shown) },
                    onTaskClick = onTaskClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The rows [date] carries, as the cell counts them.
 *
 * The same rule the count follows (see [monthLoad]): what is dated to that day
 * and nothing else. The copies the core files under today — arrears, and a
 * deadline whose warning has opened — belong to the days they are dated to,
 * and repeating them here would have the panel disagree with the chip above
 * it.
 */
private fun List<AgendaDay>.rowsOf(date: LocalDate): List<AgendaRow> =
    firstOrNull { it.date == date }
        ?.sections
        ?.let { it.timed + it.untimed }
        ?.filter { it.daysOffset <= 0L }
        .orEmpty()

/**
 * The day picked out of the grid, with what falls on it.
 *
 * The rows come from the answer the grid was drawn from, so picking a day
 * costs no scan. Opening it is a button of its own rather than the tap on the
 * cell: the tap now says which day the panel is about, and a reader who wants
 * the day itself — its hour axis, its editing — says so once more.
 */
@Composable
private fun MonthDayPanel(
    date: LocalDate,
    rows: List<AgendaRow>,
    today: LocalDate,
    onOpen: () -> Unit,
    onTaskClick: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val heading = remember(date, locale) { dayHeadingText(date, locale) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Spacing.lg),
        border = BorderStroke(Sizes.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.sm, end = Spacing.sm, bottom = Spacing.sm)
            .testTag("month-panel"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.testTag("month-panel-day"),
                )
                Text(
                    text = pluralStringResource(R.plurals.month_cell_tasks, rows.size, rows.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen, modifier = Modifier.testTag("month-panel-open")) {
                    Text(stringResource(R.string.month_panel_open))
                }
            }

            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.agenda_day_empty),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .padding(start = Spacing.md, top = Spacing.xs)
                        .testTag("month-panel-empty"),
                )
            } else {
                // Scrolled rather than cut: a full day is longer than the
                // panel, and the rows below the fold are the day's as much as
                // the ones above it.
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().testTag("month-panel-rows"),
                    contentPadding = PaddingValues(top = Spacing.xs, bottom = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(rows, key = { it.key }) { row -> TaskRow(row, onTaskClick) }
                }
            }
        }
    }
}

/**
 * One day of the grid: the number it is, and how much it carries.
 *
 * The count is a chip rather than a dot, and it takes the overdue colour as
 * soon as that day's date has gone by with something planned still on it — the
 * same reading the rows of the list are given, so a red 3 means the same thing
 * in both. What that 3 is made of is behind a long press, because a cell this
 * size has room for one number.
 *
 * No outline of its own: forty-two of them are the loudest thing on a grid
 * whose subject is the numbers inside them. A cell draws a ground only where
 * it has something to say — today, the day picked out, a weekend — and the
 * three are told apart by what carries the mark rather than by shade alone:
 * today is a disc under the number, the picked day a tint of the whole cell.
 *
 * Which way the number and the count are laid out is decided by the height the
 * cell was given rather than by the orientation of the device: a window too
 * short for the panel gives the grid what is left, and that is measured in
 * tens of dp on a phone lying on its side.
 */
@Composable
private fun MonthCellTile(
    cell: MonthCell,
    load: MonthLoad?,
    picked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = stringResource(R.string.month_cell_open)
    val hint = if (load == null) {
        open
    } else {
        val tasks = pluralStringResource(R.plurals.month_cell_tasks, load.total, load.total)
        val overdue = if (load.overdue) stringResource(R.string.month_cell_overdue) else ""
        val due = if (!load.overdue && load.dueSoon) stringResource(R.string.month_cell_due) else ""

        listOf(tasks, overdue, due, open).filter { it.isNotEmpty() }.joinToString("\n")
    }

    // The cell is a box of its own, and the tooltip wraps what is inside it.
    // Its weight and height must not be handed to [HintTooltip]: those reach
    // the tooltip box rather than the node this row measures, and a cell that
    // is measured as nothing leaves its week without a height — which is how
    // half the month ended up off the bottom of the screen.
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(Spacing.md))
            .background(
                when {
                    picked -> MaterialTheme.colorScheme.primary.copy(alpha = PICKED_TINT)

                    cell.weekend ->
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = WEEKEND_TINT)

                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .testTag(cell.testTag),
        contentAlignment = Alignment.Center,
    ) {
        val stacked = maxHeight >= Sizes.monthCellRoomy

        HintTooltip(hint) {
            MonthCellContent(cell, load, stacked)
        }
    }
}

/** How much of the primary colour marks the day the panel is about. */
private const val PICKED_TINT = 0.14f

/** And how little of the muted one separates the weekend columns. */
private const val WEEKEND_TINT = 0.06f

/**
 * The number and the count, stacked where there is room and side by side where
 * there is not.
 *
 * Flat rather than smaller: shrinking the two to fit a short row costs the
 * legibility of both, and a row of the calendar is read at arm's length. Side
 * by side they keep their size and ask for one line instead of two.
 */
@Composable
private fun MonthCellContent(cell: MonthCell, load: MonthLoad?, stacked: Boolean) {
    val colors = LocalAgendaColors.current
    val number = @Composable {
        // A disc under today rather than a ground under the whole cell: filled
        // whole, today was the loudest tile of the grid and read as a state of
        // the day's work rather than as the date it is.
        Box(
            modifier = Modifier
                .size(Sizes.monthTodayDisc)
                .clip(CircleShape)
                .background(
                    if (cell.today) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (cell.today) FontWeight.Bold else FontWeight.Normal,
                // A borrowed day is dimmed rather than left out: it opens like
                // any other, and a hole in the first week would read as a day
                // that cannot be looked at.
                color = when {
                    cell.today -> MaterialTheme.colorScheme.onSecondaryContainer
                    cell.otherMonth -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
    val chip = @Composable {
        load?.let { MonthLoadChip(it, colors.deadline, cell.loadTag) }
    }

    if (stacked) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Sizes.monthCellGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Sizes.monthCellGap),
        ) {
            number()
            chip()
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            number()
            chip()
        }
    }
}

/**
 * The count of a day, tinted when part of it has slipped and ringed when a
 * deadline on it is close enough for the core to be warning about.
 *
 * A ring rather than a second fill: the fill is what a date in arrears takes,
 * and two dense tones side by side in a grid read as one state in two shades.
 * A date that has gone by takes the fill alone — what it owes now matters more
 * than what it was due.
 *
 * The fill is the role's container rather than its tone. Half a month is
 * usually behind the reader, so the tone — a dense red at full strength — put
 * twenty glowing chips on a screen whose subject is the shape of the month.
 * The container says the same thing at the weight a chip this size deserves.
 */
@Composable
private fun MonthLoadChip(load: MonthLoad, deadline: AgendaRole, tag: String) {
    val overdue = load.overdue
    val shape = RoundedCornerShape(Spacing.sm)
    Box(
        modifier = Modifier
            .background(
                color = if (overdue) {
                    deadline.container
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = shape,
            )
            .then(
                if (!overdue && load.dueSoon) {
                    Modifier.border(width = Sizes.hairline, color = deadline.tone, shape = shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Spacing.xs),
    ) {
        Text(
            text = load.total.toString(),
            // Named because the number alone does not say which cell it came
            // from: the count of a day and the number of another day are the
            // same text, and a test looking for one finds both.
            modifier = Modifier.testTag(tag),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal,
            color = if (overdue) {
                deadline.onContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Handle for the instrumented tests, one per date the grid draws. */
internal val MonthCell.testTag: String get() = "month-cell-$date"

/** The same for the count inside it, which is read on its own. */
internal val MonthCell.loadTag: String get() = "month-load-$date"
