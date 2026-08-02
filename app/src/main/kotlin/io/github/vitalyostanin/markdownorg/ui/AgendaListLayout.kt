package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.Task

/**
 * One line per task, grouped the way the sections come.
 *
 * This is the dense layout: it fits about a dozen tasks on a screen and says
 * nothing about the gaps between them.
 */
@Composable
internal fun ListLayout(
    sections: AgendaSections,
    modifier: Modifier = Modifier,
    scroll: LazyListState = rememberLazyListState(),
    collapse: OverdueCollapse = rememberOverdueCollapse(),
    onTaskClick: (Task) -> Unit = {},
    onGroupAction: (OverdueGroup, BulkAction) -> Unit = { _, _ -> },
) {
    // Built once per set of rows rather than on every recomposition: the body
    // of a lazy list runs again on each frame of a scroll, and the split walks
    // every overdue row.
    val bands = remember(sections.overdue) { sections.overdue.intoBands() }

    LazyColumn(
        modifier = modifier,
        state = scroll,
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (sections.isEmpty) {
            item { EmptyAgenda() }
        }
        overdueBands(bands, collapse, onGroupAction) { row -> TaskRow(row, onTaskClick) }
        section(R.string.agenda_section_timed, sections.timed, onTaskClick)
        section(R.string.agenda_section_untimed, sections.untimed, onTaskClick)
    }
}

private fun LazyListScope.section(
    @StringRes labelRes: Int,
    rows: List<AgendaRow>,
    onTaskClick: (Task) -> Unit,
    warn: Boolean = false,
) {
    if (rows.isEmpty()) {
        return
    }
    item { SectionLabel(stringResource(labelRes), rows.size, warn = warn) }
    items(rows, key = AgendaRow::key) { row -> TaskRow(row, onTaskClick) }
}

@Composable
private fun TaskRow(row: AgendaRow, onTaskClick: (Task) -> Unit) {
    val role = LocalAgendaColors.current.role(row.task.kind())
    val overdue = row.daysOffset < 0
    // Past the recent band the age is not spelled out: see `statesAge`. The
    // date stays in the time column, and the band heading says the rest.
    val trailing = if (row.statesAge()) daysLabel(row.daysOffset) else ""

    val actionsLabel = stringResource(R.string.agenda_task_actions)

    TaskTooltip(row.task) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = actionsLabel) { onTaskClick(row.task) },
        ) {
            Row(
                modifier = Modifier.padding(end = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The rail carries the kind of entry without spending width on
                // a label; it clears the 3.0 contrast a non-text carrier needs.
                Box(
                    Modifier
                        .width(Sizes.rail)
                        .height(Sizes.railHeight)
                        .clip(
                            MaterialTheme.shapes.medium.copy(
                                topEnd = ZeroCornerSize,
                                bottomEnd = ZeroCornerSize,
                            ),
                        )
                        .background(role.tone),
                )
                Spacer(Modifier.width(Spacing.md))
                TimeCell(rowTimeLabel(row))
                // The glyph repeats what the rail says in a form that survives
                // without colour, which is what WCAG 1.4.1 asks for. The
                // priority badge leads the line after it: the eye reads left to
                // right, and a column of badges is what a scrolled list is
                // scanned by.
                TaskRowHead(
                    row.task,
                    glyph = role.tone,
                    heading = MaterialTheme.colorScheme.onSurface,
                    collection = row.collection,
                )
                Spacer(Modifier.width(Spacing.sm))
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
                        spoken = daysSpoken(row.daysOffset),
                    )
                }
            }
        }
    }
}
