package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.Task
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    grouped: Boolean = true,
    onTaskClick: (Task) -> Unit = {},
    onGroupAction: (OverdueGroup, BulkAction) -> Unit = { _, _ -> },
) = ListLayout(
    days = listOf(AgendaDay(date = null, sections = sections)),
    modifier = modifier,
    scroll = scroll,
    collapse = collapse,
    grouped = grouped,
    onTaskClick = onTaskClick,
    onGroupAction = onGroupAction,
)

/**
 * The same list over a span of days.
 *
 * A day of its own per heading rather than one list of everything: a week whose
 * entries are pooled together says what there is to do and not when, which is
 * the one thing a week is read for. A single day keeps the screen exactly as it
 * was — no heading is drawn above it, because the header already names it.
 *
 * [today] is what the day headings are read against, so the day being lived
 * through stands out among the six around it.
 */
@Composable
internal fun ListLayout(
    days: List<AgendaDay>,
    modifier: Modifier = Modifier,
    span: AgendaSpan = AgendaSpan.DAY,
    today: LocalDate? = null,
    scroll: LazyListState = rememberLazyListState(),
    collapse: OverdueCollapse = rememberOverdueCollapse(),
    /** False drops the section headings; the rows and their order are the same. */
    grouped: Boolean = true,
    onTaskClick: (Task) -> Unit = {},
    onGroupAction: (OverdueGroup, BulkAction) -> Unit = { _, _ -> },
) {
    // Built once per set of rows rather than on every recomposition: the body
    // of a lazy list runs again on each frame of a scroll, and the split walks
    // every overdue row.
    val bands = remember(days) { days.map { it.sections.overdue.intoBands() } }
    // Only where there is more than one: a heading over the single day the
    // header above already names would be that name twice.
    val headed = days.size > 1

    LazyColumn(
        // Named so a test can scroll it: an ungrouped day draws every overdue
        // row it holds, and the oldest of them stand below the fold of any
        // screen. What is asserted about them is what they say once reached.
        modifier = modifier.testTag("agenda-list"),
        state = scroll,
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // An empty week still draws its seven days — that emptiness is the
        // answer it was asked for. Anywhere else a single line says it.
        if (days.all { it.sections.isEmpty } && !span.showsEmptyDays) {
            item { EmptyAgenda() }
        }

        days.forEachIndexed { index, day ->
            val empty = day.sections.isEmpty
            if (empty && !span.showsEmptyDays) {
                return@forEachIndexed
            }

            // What tells one day's rows from another's: a repeating task falls
            // on several days of a week under the same file and line.
            val prefix = if (headed) day.date?.toString() ?: TASKS_PREFIX else ""
            if (headed) {
                item(key = "head:$prefix") { DayHeading(day.date, today) }
            }
            if (empty) {
                item(key = "empty:$prefix") { DayEmpty() }
                return@forEachIndexed
            }

            overdueBands(bands[index], collapse, onGroupAction, prefix, grouped) { row ->
                TaskRow(row, onTaskClick)
            }
            section(
                R.string.agenda_section_timed,
                day.sections.timed,
                prefix,
                onTaskClick,
                grouped = grouped,
            )
            section(
                R.string.agenda_section_untimed,
                day.sections.untimed,
                prefix,
                onTaskClick,
                grouped = grouped,
            )
        }
    }
}

/** Stands in for the date of the one span whose entries have none. */
private const val TASKS_PREFIX = "tasks"

/**
 * Which day the rows below belong to.
 *
 * The weekday is abbreviated and the date is written the way the reader's
 * locale writes dates: a month of headings is read down the left edge, and a
 * full weekday name in front of every one of them is a column of the longest
 * word on the screen.
 */
@Composable
private fun DayHeading(date: LocalDate?, today: LocalDate?) {
    val locale = LocalLocale.current.platformLocale
    val text = date?.let {
        remember(it, locale) {
            val weekday = it.format(DateTimeFormatter.ofPattern("EEE", locale))
            val written = it.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
            )
            "$weekday, $written"
        }
    } ?: stringResource(R.string.agenda_span_tasks)
    val current = date != null && date == today

    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        // The day being lived through is the one the reader is looking for;
        // the rest of the span is context around it.
        color = if (current) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .testTag(if (current) "day-heading-today" else "day-heading"),
    )
}

/**
 * A day of the span with nothing on it.
 *
 * A word rather than a blank gap: a heading with nothing under it reads as a
 * list that failed to draw, and "nothing on this day" is the answer a week was
 * asked for.
 */
@Composable
private fun DayEmpty() {
    Text(
        text = stringResource(R.string.agenda_day_empty),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.testTag("day-empty"),
    )
}

private fun LazyListScope.section(
    @StringRes labelRes: Int,
    rows: List<AgendaRow>,
    prefix: String,
    onTaskClick: (Task) -> Unit,
    warn: Boolean = false,
    grouped: Boolean = true,
) {
    if (rows.isEmpty()) {
        return
    }
    if (grouped) {
        item(key = "$prefix|section:$labelRes") {
            SectionLabel(stringResource(labelRes), rows.size, warn = warn)
        }
    }
    items(rows, key = { row -> row.keyIn(prefix) }) { row -> TaskRow(row, onTaskClick) }
}
