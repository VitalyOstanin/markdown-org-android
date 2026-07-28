package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors

/**
 * One line per task, grouped the way the sections come.
 *
 * This is the dense layout: it fits about a dozen tasks on a screen and says
 * nothing about the gaps between them.
 */
@Composable
internal fun ListLayout(sections: AgendaSections, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (sections.isEmpty) {
            item { EmptyAgenda() }
        }
        section(R.string.agenda_section_overdue, sections.overdue, warn = true)
        section(R.string.agenda_section_timed, sections.timed)
        section(R.string.agenda_section_untimed, sections.untimed)
    }
}

private fun LazyListScope.section(
    labelRes: Int,
    rows: List<AgendaRow>,
    warn: Boolean = false,
) {
    if (rows.isEmpty()) {
        return
    }
    item { SectionLabel(stringResource(labelRes), rows.size, warn) }
    items(rows, key = AgendaRow::key) { row -> TaskRow(row) }
}

/** Where the task is, which is what makes a row unique across a rebuild. */
internal val AgendaRow.key: String get() = "${task.file}:${task.line}"

@Composable
private fun TaskRow(row: AgendaRow) {
    val kind = row.task.kind()
    val role = LocalAgendaColors.current.role(kind)
    val overdue = row.daysOffset < 0
    val trailing = daysLabel(row.daysOffset)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(end = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The rail carries the kind of entry without spending width on a
            // label; it clears the 3.0 contrast a non-text carrier needs.
            Box(
                Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(role.tone),
            )
            Spacer(Modifier.width(10.dp))
            TimeCell(row.time)
            // The glyph repeats what the rail says in a form that survives
            // without colour, which is what WCAG 1.4.1 asks for.
            Text(
                text = kind.glyph,
                style = MaterialTheme.typography.labelLarge,
                color = role.tone,
            )
            Spacer(Modifier.width(8.dp))
            // The priority badge leads the line: the eye reads left to right,
            // and a column of badges is what a scrolled list is scanned by.
            row.task.priority?.let { priority ->
                PriorityBadge(priority)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = row.task.heading,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = kind.decoration(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (trailing.isEmpty()) {
                Text(
                    text = EMPTY_CELL,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                // Overdue is the same red at full strength: the date has
                // passed, while a deadline still ahead stays plain text.
                TrailingTag(
                    text = trailing,
                    background = if (overdue) role.tone else Color.Transparent,
                    foreground = if (overdue) {
                        LocalAgendaColors.current.onSolid
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    bold = overdue,
                )
            }
        }
    }
}
