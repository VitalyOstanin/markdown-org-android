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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import uniffi.markdown_org_ffi.Task

/** Height of one hour on the axis, and of the tile that sits in it. */
private val HourHeight = 46.dp
private val TileHeight = 40.dp

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
    onTaskClick: (Task) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
    ) {
        if (timeline.overdue.isEmpty() && timeline.allDay.isEmpty() && timeline.axis.isEmpty()) {
            item { EmptyAgenda() }
        }

        if (timeline.overdue.isNotEmpty()) {
            item {
                SectionLabel(
                    stringResource(R.string.agenda_section_overdue),
                    timeline.overdue.size,
                    warn = true,
                )
            }
            items(timeline.overdue, key = AgendaRow::key) { row -> OverdueRow(row, onTaskClick) }
        }

        if (timeline.allDay.isNotEmpty()) {
            item {
                SectionLabel(
                    stringResource(R.string.agenda_section_untimed),
                    timeline.allDay.size,
                )
            }
            // Two to a row: a band has to fit a heading, and a single column
            // of them would push the axis off the screen.
            items(timeline.allDay.chunked(2), key = { pair -> pair.first().key }) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
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
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        Text(
            text = "%02d:00".format(entry.hour),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(40.dp).padding(top = 4.dp),
        )
        // An hour with several entries grows instead of squeezing them: the
        // row height is a minimum, not a fixed size.
        Column(
            modifier = Modifier.weight(1f).padding(start = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            entry.entries.forEach { row -> Tile(row, onTaskClick) }
        }
    }
}

@Composable
private fun Tile(row: AgendaRow, onTaskClick: (Task) -> Unit) {
    val kind = row.task.kind()
    val role = LocalAgendaColors.current.role(kind)
    val actionsLabel = stringResource(R.string.agenda_task_actions)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TileHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(role.container)
            .clickable(onClickLabel = actionsLabel) { onTaskClick(row.task) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = kind.glyph,
            style = MaterialTheme.typography.labelLarge,
            color = role.onContainer,
        )
        Spacer(Modifier.width(8.dp))
        row.task.priority?.let { priority ->
            PriorityBadge(priority)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = row.task.heading,
            style = MaterialTheme.typography.bodyMedium,
            color = role.onContainer,
            textDecoration = kind.decoration(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.time.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            TrailingTag(
                text = row.time,
                // A veil of the text colour rather than of white: it has to
                // darken the tile in the light theme and lighten it in the
                // dark one, which one fixed colour cannot do.
                background = role.onContainer.copy(alpha = 0.09f),
                foreground = role.onContainer,
            )
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
    val kind = row.task.kind()
    val trailing = daysLabel(row.daysOffset)
    val actionsLabel = stringResource(R.string.agenda_task_actions)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.deadline.tone)
            .clickable(onClickLabel = actionsLabel) { onTaskClick(row.task) }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = kind.glyph,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSolid,
        )
        Spacer(Modifier.width(8.dp))
        row.task.priority?.let { priority ->
            PriorityBadge(priority, onDenseFill = true)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = row.task.heading,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSolid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.time.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.time,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = colors.onSolid,
            )
        }
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            // Inverted rather than veiled: white on a veil over a dense tone
            // lands around 2.6, and the label has to stay readable.
            TrailingTag(
                text = trailing,
                background = colors.onSolid,
                foreground = colors.deadline.tone,
                bold = true,
            )
        }
    }
}

/** An all-day task or a deadline still ahead, as a light container. */
@Composable
private fun Band(row: AgendaRow, onTaskClick: (Task) -> Unit, modifier: Modifier = Modifier) {
    val kind = row.task.kind()
    val role = LocalAgendaColors.current.role(kind)
    val trailing = daysLabel(row.daysOffset)
    val date = row.task.timestampDate.orEmpty()
    val actionsLabel = stringResource(R.string.agenda_task_actions)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(role.container)
            .clickable(onClickLabel = actionsLabel) { onTaskClick(row.task) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = kind.glyph,
                style = MaterialTheme.typography.labelLarge,
                color = role.onContainer,
            )
            Spacer(Modifier.width(6.dp))
            row.task.priority?.let { priority ->
                PriorityBadge(priority)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = row.task.heading,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = role.onContainer,
                textDecoration = kind.decoration(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = listOf(date, trailing).filter(String::isNotEmpty).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = role.onContainer,
        )
    }
}

/** A collapsed empty stretch, as one line instead of blank hours. */
@Composable
private fun GapRow(entry: AxisEntry.Gap) {
    val hairline = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.agenda_free_between,
                "%02d:00".format(entry.from),
                "%02d:00".format(entry.until),
            ),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .drawBehind {
                    drawLine(
                        color = hairline,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = size.height,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
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
            .height(8.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(36.dp))
        Box(Modifier.size(8.dp).clip(CircleShape).background(marker))
        Box(Modifier.weight(1f).height(2.dp).background(marker))
    }
}
