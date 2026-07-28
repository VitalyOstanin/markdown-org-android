package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun AgendaScreen(
    state: AgendaUiState,
    layout: AgendaLayout,
    onLayoutChange: (AgendaLayout) -> Unit,
    modifier: Modifier = Modifier,
    sync: SyncUiState = SyncUiState(),
    onSync: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            AgendaUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }

            is AgendaUiState.Failed -> FailureMessage(state)

            is AgendaUiState.Ready -> Column(Modifier.fillMaxSize()) {
                // Outside the scrolling area: the switch is how the user gets
                // back to the other layout, and it must not scroll away.
                AgendaHeader(state.date, layout, onLayoutChange, sync, onSync, onOpenSettings)
                SyncBanner(sync)
                when (layout) {
                    AgendaLayout.TIME -> TimeLayout(state.timeline)
                    AgendaLayout.LIST -> ListLayout(state.sections)
                }
            }
        }
    }
}

@Composable
private fun AgendaHeader(
    date: LocalDate,
    layout: AgendaLayout,
    onLayoutChange: (AgendaLayout) -> Unit,
    sync: SyncUiState,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // The device locale, not the application's: the interface is English, but
    // a date is read in whatever language the user reads dates in.
    val locale = Locale.getDefault()
    val weekday = remember(date, locale) {
        date.format(DateTimeFormatter.ofPattern("EEEE", locale))
            .replaceFirstChar { it.titlecase(locale) }
    }
    val full = remember(date, locale) {
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = weekday,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = full,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        // Sync and settings sit next to the layout switch rather than in a
        // menu: with three controls on the screen a menu would hide two of
        // them behind a tap for no gain.
        HeaderAction(
            glyph = "⟳",
            label = stringResource(R.string.sync_now),
            tag = "sync-now",
            enabled = sync.configured && !sync.running,
            onClick = onSync,
        )
        HeaderAction(
            glyph = "⚙",
            label = stringResource(R.string.settings_title),
            tag = "open-settings",
            onClick = onOpenSettings,
        )
        Spacer(Modifier.width(4.dp))
        LayoutSwitch(layout, onLayoutChange)
    }
}

@Composable
private fun HeaderAction(
    glyph: String,
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .testTag(tag),
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        )
    }
}

/**
 * One line under the header: what the last sync did, or where the checkout
 * stands when nothing has been attempted yet.
 *
 * Nothing is shown before a remote is configured — an empty state that says
 * "not configured" on every launch would be noise.
 */
@Composable
private fun SyncBanner(sync: SyncUiState) {
    val colors = LocalAgendaColors.current
    val text = when {
        sync.running -> stringResource(R.string.sync_running)
        sync.message != null -> stringResource(sync.message.text)
        sync.repository != null -> stringResource(
            R.string.sync_checkout,
            sync.repository.branch,
            sync.repository.headSummary,
        )

        else -> return
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (sync.message?.failed == true) {
                colors.deadline.tone
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.testTag("sync-banner"),
        )
        sync.message?.detail?.takeIf { sync.message.failed }?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun LayoutSwitch(current: AgendaLayout, onLayoutChange: (AgendaLayout) -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(LocalAgendaColors.current.hairSoft)
            .padding(3.dp),
    ) {
        for (option in AgendaLayout.entries) {
            val selected = option == current
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .clickable(onClickLabel = stringResource(option.labelRes)) {
                        onLayoutChange(option)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    // Tagged rather than found by its glyph: the tests should
                    // survive a change of symbol.
                    .testTag(option.testTag),
            ) {
                Text(
                    text = option.glyph,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Glyph shown on the switch: a filled block for the axis, ruled lines for the list. */
private val AgendaLayout.glyph: String
    get() = when (this) {
        AgendaLayout.TIME -> "◫"
        AgendaLayout.LIST -> "▤"
    }

private val AgendaLayout.labelRes: Int
    get() = when (this) {
        AgendaLayout.TIME -> R.string.agenda_layout_time
        AgendaLayout.LIST -> R.string.agenda_layout_list
    }

/** Handle for the instrumented tests; see [LayoutSwitch]. */
internal val AgendaLayout.testTag: String get() = "layout-$name"

/**
 * Heading over a group of entries, with how many are in it. The overdue group
 * is the one that gets the tone: the rest are neutral.
 */
@Composable
internal fun SectionLabel(text: String, count: Int, warn: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(Locale.getDefault()),
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
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 2.dp),
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

/** Placeholder for a column that has nothing to show on this row. */
internal const val EMPTY_CELL = "—"

@Composable
internal fun EmptyAgenda() {
    Text(
        text = stringResource(R.string.agenda_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

/** Fixed width of the time column, so headings line up down the list. */
internal val TimeColumnWidth = 46.dp

@Composable
internal fun TimeCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.ifEmpty { EMPTY_CELL },
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.width(TimeColumnWidth),
    )
}
