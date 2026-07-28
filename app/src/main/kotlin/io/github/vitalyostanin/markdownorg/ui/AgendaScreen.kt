package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.AgendaRole
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import uniffi.markdown_org_ffi.TaskType

@Composable
fun AgendaScreen(state: AgendaUiState, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            AgendaUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }

            is AgendaUiState.Failed -> FailureMessage(state)

            is AgendaUiState.Ready -> AgendaList(state)
        }
    }
}

@Composable
private fun FailureMessage(state: AgendaUiState.Failed) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.agenda_failed),
            style = MaterialTheme.typography.titleMedium,
            color = LocalAgendaColors.current.deadline.tone,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgendaList(state: AgendaUiState.Ready) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (state.rows.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.agenda_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.rows, key = { "${it.task.file}:${it.task.line}" }) { row ->
            TaskRow(row)
        }
    }
}

@Composable
private fun TaskRow(row: AgendaRow) {
    val colors = LocalAgendaColors.current
    val role: AgendaRole = when {
        row.task.taskType == TaskType.CANCELLED -> colors.cancelled
        row.task.taskType == TaskType.DONE -> colors.done
        row.task.timestampRepeater != null -> colors.repeat
        row.task.timestampType == "DEADLINE" -> colors.deadline
        else -> colors.scheduled
    }
    val overdue = row.daysOffset < 0
    val trailing = when {
        row.daysOffset < 0 -> pluralStringResource(
            R.plurals.agenda_days_overdue,
            (-row.daysOffset).toInt(),
            (-row.daysOffset).toInt(),
        )

        row.daysOffset > 0 -> pluralStringResource(
            R.plurals.agenda_days_ahead,
            row.daysOffset.toInt(),
            row.daysOffset.toInt(),
        )

        else -> ""
    }

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
            Text(
                text = row.time,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(46.dp),
            )
            // The priority badge leads the line: the eye reads left to right,
            // and a column of badges is what a scrolled list is scanned by.
            row.task.priority?.let { priority ->
                PriorityBadge(priority, role)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = row.task.heading,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                TrailingLabel(trailing, role, overdue)
            }
        }
    }
}

@Composable
private fun PriorityBadge(priority: String, role: AgendaRole) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(role.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = priority,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = role.onContainer,
        )
    }
}

@Composable
private fun TrailingLabel(text: String, role: AgendaRole, overdue: Boolean) {
    // Overdue is the same red at full strength: the date has passed, while a
    // deadline still ahead stays a light container.
    val background: Color = if (overdue) role.tone else Color.Transparent
    val foreground: Color =
        if (overdue) LocalAgendaColors.current.onSolid else MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .padding(horizontal = if (overdue) 7.dp else 0.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal,
            color = foreground,
        )
    }
}
