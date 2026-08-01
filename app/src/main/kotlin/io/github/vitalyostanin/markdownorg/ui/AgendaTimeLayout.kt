package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.Task

/** Height of one hour on the axis, and of the tile that sits in it. */
private val HourHeight = Sizes.hourRow
private val TileHeight = Sizes.tile

/**
 * The agenda on an hour axis: a filled tile per entry, empty hours left empty
 * and long empty stretches collapsed to a line.
 *
 * What has slipped and what has no hour of its own cannot go on the axis, so
 * they ride above it as bands — in the same order the list layout shows them.
 */
@Composable
internal fun TimeLayout(
    timeline: Timeline,
    modifier: Modifier = Modifier,
    scroll: LazyListState = rememberLazyListState(),
    collapse: OverdueCollapse = rememberOverdueCollapse(),
    onTaskClick: (Task) -> Unit = {},
) {
    // Paired here rather than in the list body below. That body is a lambda
    // the list runs again on every recomposition — on each frame of a scroll
    // among others — and the pairing depends on nothing but the band itself.
    val bands = remember(timeline.allDay) { timeline.allDay.chunked(2) }
    val overdue = remember(timeline.overdue) { timeline.overdue.intoBands() }

    LazyColumn(
        modifier = modifier,
        state = scroll,
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.xs),
    ) {
        if (timeline.overdue.isEmpty() && timeline.allDay.isEmpty() && timeline.axis.isEmpty()) {
            item { EmptyAgenda() }
        }

        overdueBands(overdue, collapse) { row -> OverdueRow(row, onTaskClick) }

        if (timeline.allDay.isNotEmpty()) {
            item {
                SectionLabel(
                    stringResource(R.string.agenda_section_untimed),
                    timeline.allDay.size,
                )
            }
            // Two to a row: a band has to fit a heading, and a single column
            // of them would push the axis off the screen.
            items(bands, key = { pair -> pair.first().key }) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    pair.forEach { row -> Band(row, onTaskClick, Modifier.weight(1f)) }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        items(timeline.axis, key = ::axisKey) { entry ->
            when (entry) {
                is AxisEntry.Hour -> HourRow(entry, onTaskClick)
                is AxisEntry.Gap -> GapRow(entry)
                AxisEntry.Now -> NowLine()
            }
        }
    }
}

/**
 * Stable across a rebuild, and unique: two gaps cannot start at the same hour
 * and neither can two hour rows, so the start is enough to tell them apart.
 */
private fun axisKey(entry: AxisEntry): String = when (entry) {
    is AxisEntry.Hour -> "h${entry.hour}"
    is AxisEntry.Gap -> "g${entry.from}"
    AxisEntry.Now -> "now"
}

@Composable
private fun HourRow(entry: AxisEntry.Hour, onTaskClick: (Task) -> Unit) {
    val hairline = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HourHeight)
            .drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = Sizes.hairline.toPx(),
                )
            },
    ) {
        Text(
            text = hourLabel(entry.hour, LocalLocale.current.platformLocale, use24Hour()),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            // A minimum, not a fixed width: the column is cut to `09:00` and
            // a 12-hour label does not fit in it.
            modifier = Modifier.widthIn(min = Sizes.hourLabel).padding(top = Spacing.xs),
        )
        // An hour with several entries grows instead of squeezing them: the
        // row height is a minimum, not a fixed size.
        Column(
            modifier = Modifier.weight(1f)
                .padding(start = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            entry.entries.forEach { row -> Tile(row, onTaskClick) }
        }
    }
}

@Composable
private fun Tile(row: AgendaRow, onTaskClick: (Task) -> Unit) {
    val role = LocalAgendaColors.current.role(row.task.kind())
    val actionsLabel = stringResource(R.string.agenda_task_actions)

    TaskTooltip(row.task) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TileHeight)
                .clip(MaterialTheme.shapes.medium)
                .background(role.container)
                .clickable(onClickLabel = actionsLabel) { onTaskClick(row.task) }
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val time = rowTimeLabel(row)

            TaskRowHead(row.task, glyph = role.onContainer)
            if (time.isNotEmpty()) {
                Spacer(Modifier.width(Spacing.sm))
                TrailingTag(
                    text = time,
                    // A veil of the text colour rather than of white: it has to
                    // darken the tile in the light theme and lighten it in the
                    // dark one, which one fixed colour cannot do.
                    background = role.onContainer.copy(alpha = 0.09f),
                    foreground = role.onContainer,
                )
            }
        }
    }
}

/**
 * A task whose date has passed, at full width.
 *
 * Two to a row would truncate the heading, and the heading is the part that
 * says what was missed.
 */
@Composable
private fun OverdueRow(row: AgendaRow, onTaskClick: (Task) -> Unit) {
    val colors = LocalAgendaColors.current
    // Only while the age is still news — see `statesAge`. On a note kept for
    // years the label grew to "overdue by 1947 days" and took the heading's
    // width with it.
    val trailing = if (row.statesAge()) daysLabel(row.daysOffset) else ""
    val actionsLabel = stringResource(R.string.agenda_task_actions)

    TaskTooltip(row.task) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.sm)
                .clip(MaterialTheme.shapes.medium)
                .background(colors.deadline.tone)
                .clickable(onClickLabel = actionsLabel) { onTaskClick(row.task) }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val time = rowTimeLabel(row)

            TaskRowHead(row.task, glyph = colors.onSolid, bold = true, onDenseFill = true)
            if (time.isNotEmpty()) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSolid,
                )
            }
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(Spacing.sm))
                // Inverted rather than veiled: white on a veil over a dense
                // tone lands around 2.6, and the label has to stay readable.
                TrailingTag(
                    text = trailing,
                    background = colors.onSolid,
                    foreground = colors.deadline.tone,
                    bold = true,
                    spoken = daysSpoken(row.daysOffset),
                )
            }
        }
    }
}

/** An all-day task or a deadline still ahead, as a light container. */
@Composable
private fun Band(row: AgendaRow, onTaskClick: (Task) -> Unit, modifier: Modifier = Modifier) {
    val role = LocalAgendaColors.current.role(row.task.kind())
    val trailing = daysLabel(row.daysOffset)
    val date = row.task.timestampDate.orEmpty()
    val actionsLabel = stringResource(R.string.agenda_task_actions)

    TaskTooltip(row.task) {
        Column(
            modifier = modifier
                .clip(MaterialTheme.shapes.medium)
                .background(role.container)
                .clickable(onClickLabel = actionsLabel) { onTaskClick(row.task) }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TaskRowHead(row.task, glyph = role.onContainer, bold = true)
            }
            Text(
                text = listOf(date, trailing).filter(String::isNotEmpty).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = role.onContainer,
            )
        }
    }
}

/** A collapsed empty stretch, as one line instead of blank hours. */
@Composable
private fun GapRow(entry: AxisEntry.Gap) {
    val hairline = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(start = Sizes.hourLabel, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val locale = LocalLocale.current.platformLocale
        val use24Hour = use24Hour()

        Text(
            text = stringResource(
                R.string.agenda_free_between,
                hourLabel(entry.from, locale, use24Hour),
                hourLabel(entry.until, locale, use24Hour),
            ),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(Spacing.sm))
        Box(
            Modifier
                .weight(1f)
                .height(Sizes.hairline)
                .drawBehind {
                    drawLine(
                        color = hairline,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = size.height,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(Spacing.xs.toPx(), Spacing.xs.toPx()),
                        ),
                    )
                },
        )
    }
}

/** Where the current hour falls, as a line across the axis. */
@Composable
private fun NowLine() {
    val marker = MaterialTheme.colorScheme.tertiary
    val label = stringResource(R.string.agenda_now)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.markerDot)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(Sizes.markerInset))
        Box(Modifier.size(Sizes.markerDot).clip(CircleShape).background(marker))
        Box(Modifier.weight(1f).height(Sizes.marker).background(marker))
    }
}
