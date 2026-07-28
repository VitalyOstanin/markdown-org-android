package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.AgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.AgendaRole
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType

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
    timestampType == "DEADLINE" -> AgendaKind.DEADLINE
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
fun AgendaKind.decoration(): TextDecoration? =
    if (this == AgendaKind.DONE || this == AgendaKind.CANCELLED) TextDecoration.LineThrough else null

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
 * The priority cookie, coloured by the priority rather than by the kind of
 * entry: it has to stay legible on top of a tile already filled with the
 * kind's own colour, and A/B/C is its own scale.
 *
 * [onDenseFill] flips it for a row that is already filled with a dense tone —
 * an overdue task. There the badge's own fill would be the same red as the
 * row, leaving the letter floating with no badge around it.
 */
@Composable
fun PriorityBadge(
    priority: String,
    modifier: Modifier = Modifier,
    onDenseFill: Boolean = false,
) {
    val colors = LocalAgendaColors.current
    val tone: Color = when (priority.uppercase()) {
        "A" -> colors.deadline.tone
        "B" -> colors.repeat.tone
        "C" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
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
