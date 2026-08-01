package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.Task
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AgendaScreen(
    state: AgendaUiState,
    layout: AgendaLayout,
    onLayoutChange: (AgendaLayout) -> Unit,
    modifier: Modifier = Modifier,
    sync: SyncUiState = SyncUiState(),
    editIssue: SyncMessage? = null,
    onEditIssueShown: () -> Unit = {},
    onSync: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onTaskClick: (Task) -> Unit = {},
) {
    // What an edit answered with, if it could not be made. A snackbar rather
    // than the banner: it is about the tap that was just made, it goes away on
    // its own, and it leaves the line under the header to the checkout.
    val snackbar = remember { SnackbarHostState() }
    val issue = editIssue?.let { stringResource(it.text) }
    LaunchedEffect(editIssue) {
        if (issue != null) {
            snackbar.showSnackbar(issue)
            onEditIssueShown()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AgendaBody(state, layout, onLayoutChange, sync, onSync, onOpenSettings, onTaskClick)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun AgendaBody(
    state: AgendaUiState,
    layout: AgendaLayout,
    onLayoutChange: (AgendaLayout) -> Unit,
    sync: SyncUiState,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onTaskClick: (Task) -> Unit,
) {
    // Held above the state, so a rebuild of the agenda comes back to the same
    // place in the list; one per layout, since the two scroll independently.
    val timeScroll = rememberLazyListState()
    val listScroll = rememberLazyListState()
    // Shared by both layouts, unlike the scroll positions: a band answered in
    // one of them is answered in the other, and the two show the same agenda.
    val collapse = rememberOverdueCollapse()

    when (state) {
        AgendaUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }

        is AgendaUiState.Failed -> FailureMessage(state)

        is AgendaUiState.Ready -> Column(Modifier.fillMaxSize()) {
            // Outside the scrolling area: the switch is how the user gets
            // back to the other layout, and it must not scroll away.
            AgendaHeader(state.date, layout, onLayoutChange, sync, onSync, onOpenSettings)
            RefreshingLine(state.refreshing)
            SyncBanner(sync)
            ScanNotices(state.notices)
            when (layout) {
                AgendaLayout.TIME -> TimeLayout(
                    state.timeline,
                    scroll = timeScroll,
                    collapse = collapse,
                    onTaskClick = onTaskClick,
                )

                AgendaLayout.LIST -> ListLayout(
                    state.sections,
                    scroll = listScroll,
                    collapse = collapse,
                    onTaskClick = onTaskClick,
                )
            }
        }
    }
}

/**
 * That another scan is under way, as a line under the header.
 *
 * A line rather than a spinner in place of the agenda: what is on screen is
 * still the agenda, one edit behind.
 */
@Composable
private fun RefreshingLine(refreshing: Boolean) {
    if (!refreshing) {
        return
    }
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.marker)
            .testTag("agenda-refreshing"),
    )
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
    // a date is read in whatever language the user reads dates in. Read from
    // the composition rather than from Locale.getDefault(), which is not
    // observable — the header would keep yesterday's language until something
    // else redrew it.
    val locale = LocalLocale.current.platformLocale
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
            .padding(
                start = Spacing.gutter,
                end = Spacing.sm,
                top = Spacing.sm,
                bottom = Spacing.sm,
            ),
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
            icon = R.drawable.ic_sync,
            label = stringResource(R.string.sync_now),
            tag = "sync-now",
            enabled = sync.configured && !sync.running,
            onClick = onSync,
        )
        HeaderAction(
            icon = R.drawable.ic_settings,
            label = stringResource(R.string.settings_title),
            tag = "open-settings",
            onClick = onOpenSettings,
        )
        Spacer(Modifier.width(Spacing.xs))
        LayoutSwitch(layout, onLayoutChange)
    }
}

/**
 * One control in the header.
 *
 * [IconButton] rather than a hand-built `Box`: the ripple, the size of the
 * touch target and how a disabled control reads all come with it, and they
 * are the same ones the buttons on the other screens get.
 */
@Composable
private fun HeaderAction(
    @DrawableRes icon: Int,
    label: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(
            painter = painterResource(icon),
            // Named rather than described: what the button does is what the
            // user needs to hear, and it is the same wording the settings
            // screen uses for the place it leads to.
            contentDescription = label,
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

    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
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
                text = detailText(detail),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        LastSynced(sync)
    }
}

/**
 * When the notes on screen last matched the server.
 *
 * A line of its own under whatever the banner says, and it outlives that: the
 * message above is about the attempt just made, while this answers how old
 * the notes are — which is exactly the question a failed attempt raises.
 * Hidden while a sync runs, where it would state a time about to be replaced.
 */
@Composable
private fun LastSynced(sync: SyncUiState) {
    if (sync.running) {
        return
    }
    val moment = syncedAtLabel(sync.lastSyncedAt) ?: return

    Text(
        text = stringResource(R.string.sync_last_synced, moment),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.testTag("sync-last-synced"),
    )
}

/**
 * What the walk behind the agenda skipped.
 *
 * Above the list rather than in place of it: the tasks that were found are
 * still worth showing, and a note that was skipped would otherwise leave no
 * trace at all — an empty agenda reads as "nothing scheduled".
 */
@Composable
private fun ScanNotices(notices: List<ScanNotice>) {
    if (notices.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .testTag("scan-notices"),
    ) {
        notices.forEach { notice ->
            Text(
                text = when (notice) {
                    is ScanNotice.Counted ->
                        pluralStringResource(notice.text, notice.count, notice.count)

                    is ScanNotice.Flag -> stringResource(notice.text)
                },
                style = MaterialTheme.typography.labelMedium,
                color = LocalAgendaColors.current.deadline.tone,
            )
        }
    }
}

/**
 * Which of the two layouts is on screen.
 *
 * A segmented button is what Material 3 offers for a choice of two or three
 * views of the same content, and it is what this was hand-built out of before.
 */
@Composable
private fun LayoutSwitch(current: AgendaLayout, onLayoutChange: (AgendaLayout) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        AgendaLayout.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == current,
                onClick = { onLayoutChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, AgendaLayout.entries.size),
                // The check mark the default slot draws would say the same
                // thing the fill already says, in the space the icon needs.
                icon = {},
                // Tagged rather than found by its icon: the tests should
                // survive a change of drawable.
                modifier = Modifier.testTag(option.testTag),
            ) {
                Icon(
                    painter = painterResource(option.iconRes),
                    contentDescription = stringResource(option.labelRes),
                    modifier = Modifier.size(Sizes.icon),
                )
            }
        }
    }
}

/** Icon on the switch: a day band for the axis, ruled lines for the list. */
private val AgendaLayout.iconRes: Int
    get() = when (this) {
        AgendaLayout.TIME -> R.drawable.ic_layout_time
        AgendaLayout.LIST -> R.drawable.ic_layout_list
    }

@get:StringRes
private val AgendaLayout.labelRes: Int
    get() = when (this) {
        AgendaLayout.TIME -> R.string.agenda_layout_time
        AgendaLayout.LIST -> R.string.agenda_layout_list
    }

/** Handle for the instrumented tests; see [LayoutSwitch]. */
internal val AgendaLayout.testTag: String get() = "layout-$name"

@Composable
private fun FailureMessage(state: AgendaUiState.Failed) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(state.reason.text),
            style = MaterialTheme.typography.titleMedium,
            color = LocalAgendaColors.current.deadline.tone,
        )
        state.reason.detail?.let { detail ->
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = detailText(detail),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The second line of a message, whoever worded it.
 *
 * Diagnostics from a library go on screen as they arrived — nothing here can
 * translate libgit2 — while everything the application words itself is read
 * out of the resources of the current language.
 */
@Composable
private fun detailText(detail: Detail): String = when (detail) {
    is Detail.Verbatim -> detail.text

    is Detail.Worded -> when (val arg = detail.arg) {
        null -> stringResource(detail.text)
        else -> stringResource(detail.text, arg)
    }

    is Detail.Counted -> pluralStringResource(detail.text, detail.count, detail.count)
}
