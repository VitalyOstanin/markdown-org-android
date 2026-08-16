package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The month as a calendar rather than as a list of its days.
 *
 * What a month is read for is the shape of it — which weeks are full, where the
 * arrears sit, which stretch is free — and a list answers that only by being
 * scrolled end to end. The cells say how much and not what: a day is opened to
 * read its rows, which is the one thing the grid deliberately leaves to the day
 * span, and the same drill-down the extension's calendar offers.
 *
 * The list stays available for the month behind a setting, because the two
 * answer different questions and the reader knows which one is being asked.
 */
@Composable
internal fun MonthLayout(
    cells: List<MonthCell>,
    load: Map<LocalDate, MonthLoad>,
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate) -> Unit = {},
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

    Column(
        // The whole month at once, rather than a grid that scrolls: what a
        // calendar is read for is the shape of the month, and a shape with its
        // last week below the fold is not one. The rows share whatever height
        // is given, so six weeks fit where five did.
        //
        // Height comes from the caller, as a weight inside the screen's own
        // column. `fillMaxSize` here would take the height of that column
        // whole — the header included — and push the last weeks off the
        // bottom of the screen, which is what it did.
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
            .testTag("agenda-month"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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

        cells.chunked(columns).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = Sizes.monthCell),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                week.forEach { cell ->
                    MonthCellTile(
                        cell = cell,
                        load = load[cell.date],
                        onClick = { onDayClick(cell.date) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * One day of the grid: the number it is, and how much it carries.
 *
 * The count is a chip rather than a dot, and it takes the overdue colour as
 * soon as any of that day's work has slipped — the same reading the rows of the
 * list are given, so a red 3 means the same thing in both. What that 3 is made
 * of is behind a long press, because a cell this size has room for one number.
 */
@Composable
private fun MonthCellTile(
    cell: MonthCell,
    load: MonthLoad?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAgendaColors.current
    val open = stringResource(R.string.month_cell_open)
    val hint = if (load == null) {
        open
    } else {
        val tasks = pluralStringResource(R.plurals.month_cell_tasks, load.total, load.total)
        val overdue = if (load.overdue > 0) {
            stringResource(R.string.month_cell_overdue, load.overdue)
        } else {
            ""
        }
        listOf(tasks, overdue, open).filter { it.isNotEmpty() }.joinToString("\n")
    }

    // The cell is a box of its own, and the tooltip wraps what is inside it.
    // Its weight and height must not be handed to [HintTooltip]: those reach
    // the tooltip box rather than the node this row measures, and a cell that
    // is measured as nothing leaves its week without a height — which is how
    // half the month ended up off the bottom of the screen.
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Spacing.sm))
            .background(
                when {
                    cell.today -> MaterialTheme.colorScheme.secondaryContainer
                    cell.weekend -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                },
            )
            .border(
                width = Sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(Spacing.sm),
            )
            .clickable(onClick = onClick)
            .testTag(cell.testTag),
        contentAlignment = Alignment.Center,
    ) {
        HintTooltip(hint) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = cell.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (cell.today) FontWeight.Bold else FontWeight.Normal,
                    // A borrowed day is dimmed rather than left out: it opens
                    // like any other, and a hole in the first week would read
                    // as a day that cannot be looked at.
                    color = when {
                        cell.otherMonth -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (load != null) {
                    MonthLoadChip(load, colors.deadline.tone, cell.loadTag)
                }
            }
        }
    }
}

/** The count of a day, tinted when part of it has slipped. */
@Composable
private fun MonthLoadChip(load: MonthLoad, overdueTone: Color, tag: String) {
    val overdue = load.overdue > 0
    Box(
        modifier = Modifier
            .background(
                color = if (overdue) overdueTone else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(Spacing.sm),
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
            color = if (overdue) {
                LocalAgendaColors.current.onSolid
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
