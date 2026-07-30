package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.AgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.AgendaRole
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType

// Everything both layouts draw with, and nothing either of them draws alone.
// The two of them promise to differ in visual language rather than in how much
// they show, and that promise is only as good as the number of places a row is
// described in: what lives here is described once.

/**
 * What an entry is, as far as the agenda is concerned.
 *
 * The glyph rides along with the kind on purpose: colour alone must not carry
 * the distinction (WCAG 1.4.1), so wherever a role is picked the mark that
 * survives without colour is picked with it.
 */
enum class AgendaKind(val glyph: String) {
    DEADLINE("⚑"),
    SCHEDULED("◷"),
    REPEAT("↻"),
    DONE("✓"),
    CANCELLED("⊘"),
}

/**
 * Keyword first, then the repeater, then the timestamp kind: a cancelled task
 * is cancelled whatever its date says, and a repeating one reads as repeating
 * before it reads as scheduled.
 */
fun Task.kind(): AgendaKind = when {
    taskType == TaskType.CANCELLED -> AgendaKind.CANCELLED
    taskType == TaskType.DONE -> AgendaKind.DONE
    timestampRepeater != null -> AgendaKind.REPEAT
    timestampType == TimestampType.DEADLINE -> AgendaKind.DEADLINE
    else -> AgendaKind.SCHEDULED
}

fun AgendaColors.role(kind: AgendaKind): AgendaRole = when (kind) {
    AgendaKind.DEADLINE -> deadline
    AgendaKind.SCHEDULED -> scheduled
    AgendaKind.REPEAT -> repeat
    AgendaKind.DONE -> done
    AgendaKind.CANCELLED -> cancelled
}

/** A finished or dropped task keeps its heading struck through in both layouts. */
fun AgendaKind.decoration(): TextDecoration? = if (this == AgendaKind.DONE ||
    this == AgendaKind.CANCELLED
) {
    TextDecoration.LineThrough
} else {
    null
}

/** How far off the date is, as text: `2 days late`, `in 5 days`, or nothing for today. */
@Composable
fun daysLabel(daysOffset: Long): String = when {
    daysOffset < 0 -> pluralStringResource(
        R.plurals.agenda_days_overdue,
        (-daysOffset).toInt(),
        (-daysOffset).toInt(),
    )

    daysOffset > 0 -> pluralStringResource(
        R.plurals.agenda_days_ahead,
        daysOffset.toInt(),
        daysOffset.toInt(),
    )

    else -> ""
}

/**
 * What a row says about its task: the glyph for its kind, the priority cookie
 * if it carries one, and the heading.
 *
 * Written once for every kind of row — tile, overdue line, band, list row —
 * because this is where the promise the layouts make is kept: they differ in
 * visual language, not in how much they show. As four copies they had already
 * drifted apart, and the overdue row had lost the strike-through of a
 * cancelled task. What is left to the caller is the visual language itself:
 * which colours the row is drawn in and how heavy the heading sits.
 */
@Composable
fun RowScope.TaskRowHead(
    task: Task,
    glyph: Color,
    heading: Color = glyph,
    bold: Boolean = false,
    onDenseFill: Boolean = false,
) {
    val kind = task.kind()

    Text(
        text = kind.glyph,
        style = MaterialTheme.typography.labelLarge,
        color = glyph,
    )
    Spacer(Modifier.width(Spacing.sm))
    task.priority?.let { priority ->
        PriorityBadge(priority, onDenseFill = onDenseFill)
        Spacer(Modifier.width(Spacing.xs))
    }
    Text(
        text = task.heading,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        color = heading,
        textDecoration = kind.decoration(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
}

/**
 * The priority cookie, coloured by the priority rather than by the kind of
 * entry: it has to stay legible on top of a tile already filled with the
 * kind's own colour, and A/B/C is its own scale.
 *
 * [onDenseFill] flips it for a row that is already filled with a dense tone —
 * an overdue task. There the badge's own fill would be the same red as the
 * row, leaving the letter floating with no badge around it.
 */
@Composable
fun PriorityBadge(priority: String, modifier: Modifier = Modifier, onDenseFill: Boolean = false) {
    val colors = LocalAgendaColors.current
    val tone: Color = when (priority.uppercase()) {
        "A" -> colors.deadline.tone
        "B" -> colors.repeat.tone
        "C" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = modifier
            .size(Sizes.badge)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (onDenseFill) colors.onSolid else tone),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = priority,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            // Each of the three fills is a dense tone, so the label on top
            // inverts with the theme exactly as an overdue row does.
            color = if (onDenseFill) tone else colors.onSolid,
        )
    }
}

/** Where the task is, which is what makes a row unique across a rebuild. */
internal val AgendaRow.key: String get() = "${task.file}:${task.line}"

/**
 * Heading over a group of entries, with how many are in it. The overdue group
 * is the one that gets the tone: the rest are neutral.
 */
@Composable
internal fun SectionLabel(
    text: String,
    count: Int,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(LocalLocale.current.platformLocale),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal,
            color = if (warn) {
                LocalAgendaColors.current.deadline.tone
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * The small label at the end of a row — a time, a duration, how many days off
 * the date is.
 *
 * On a dense fill it inverts rather than sitting on a translucent veil: a
 * white veil over a dense tone lands around 2.6 against white text, well
 * under the 4.5 the rest of the palette clears.
 */
@Composable
internal fun TrailingTag(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .padding(horizontal = Spacing.sm),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = foreground,
        )
    }
}

/** Placeholder for a column that has nothing to show on this row. */
internal const val EMPTY_CELL = "—"

@Composable
internal fun EmptyAgenda(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.agenda_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = Spacing.md),
    )
}

/**
 * Width the time column starts at, so headings line up down the list.
 *
 * A minimum rather than a fixed size: `09:30` fits in it and `9:30 AM` does
 * not, and a locale that writes the second one would have its times cut off
 * mid-label.
 */
internal val TimeColumnWidth = Sizes.timeColumn

@Composable
internal fun TimeCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.ifEmpty { EMPTY_CELL },
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        modifier = modifier.widthIn(min = TimeColumnWidth),
    )
}
