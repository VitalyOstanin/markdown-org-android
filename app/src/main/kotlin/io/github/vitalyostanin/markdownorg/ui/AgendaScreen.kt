package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import io.github.vitalyostanin.markdownorg.ui.theme.collectionTone
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.Task
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AgendaScreen(
    state: AgendaUiState,
    layout: AgendaLayout,
    onLayoutChange: (AgendaLayout) -> Unit,
    modifier: Modifier = Modifier,
    /** The wall clock the marker line follows; see [AgendaViewModel.now]. */
    now: LocalDateTime = LocalDateTime.now(),
    sync: SyncUiState = SyncUiState(),
    editIssue: SyncMessage? = null,
    onEditIssueShown: () -> Unit = {},
    /** The collections to filter by; empty while there is one of them. */
    collections: List<CollectionChoice> = emptyList(),
    onCollectionShown: (String, Boolean) -> Unit = { _, _ -> },
    /** What acting on a whole band did, and what it takes to undo it. */
    groupResult: GroupResult? = null,
    onGroupResultShown: () -> Unit = {},
    onGroupAction: (OverdueGroup, BulkAction) -> Unit = { _, _ -> },
    onUndoGroup: () -> Unit = {},
    onSync: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onTaskClick: (Task) -> Unit = {},
    /** Answer to unrelated histories: take what the server holds. */
    onTakeRemote: () -> Unit = {},
    /** Answer to a directory holding somebody else's checkout: empty it. */
    onReplaceNotes: () -> Unit = {},
    /** Answer to a server key nobody has vouched for yet: it is the right one. */
    onTrustHost: () -> Unit = {},
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

    // What a group action did, with the offer to put it back. The offer stands
    // as long as the line does: an undo the user has to reach a menu for is
    // not an undo, and the wording of the move is what makes it clear which
    // of the four bands was moved.
    val report = groupResult?.let { groupReport(it) }
    val undo = stringResource(R.string.agenda_group_undo)
    LaunchedEffect(groupResult) {
        if (report != null) {
            val answered = snackbar.showSnackbar(
                message = report,
                actionLabel = undo.takeIf { groupResult.canUndo },
                withDismissAction = true,
            )
            if (answered == SnackbarResult.ActionPerformed) {
                onUndoGroup()
            } else {
                onGroupResultShown()
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AgendaBody(
                state,
                layout,
                onLayoutChange,
                now,
                sync,
                onSync,
                onOpenSettings,
                onTaskClick,
                onTakeRemote,
                onReplaceNotes,
                onTrustHost,
                onGroupAction,
                collections,
                onCollectionShown,
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * What the group action did, as the line the snackbar shows.
 *
 * Two sentences rather than one built out of clauses: what was done, and — only
 * when there is anything to say — what was left alone. A count woven into a
 * translated clause is what makes such a line untranslatable.
 */
@Composable
private fun groupReport(result: GroupResult): String {
    val done = pluralStringResource(result.action.done, result.changed, result.changed)
    if (result.refused == 0) {
        return done
    }

    val refused = pluralStringResource(
        R.plurals.agenda_group_refused,
        result.refused,
        result.refused,
    )
    return "$done\n$refused"
}

@Composable
private fun AgendaBody(
    state: AgendaUiState,
    layout: AgendaLayout,
    onLayoutChange: (AgendaLayout) -> Unit,
    now: LocalDateTime,
    sync: SyncUiState,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onTakeRemote: () -> Unit,
    onReplaceNotes: () -> Unit,
    onTrustHost: () -> Unit,
    onGroupAction: (OverdueGroup, BulkAction) -> Unit,
    collections: List<CollectionChoice>,
    onCollectionShown: (String, Boolean) -> Unit,
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
            // The marker line belongs to today; on an agenda for another day
            // there is no current moment to draw, and `null` leaves it out.
            val marker = now.toLocalTime().takeIf { state.date == now.toLocalDate() }
            // Outside the scrolling area: the switch is how the user gets
            // back to the other layout, and it must not scroll away.
            AgendaHeader(state.date, layout, onLayoutChange, sync, onSync, onOpenSettings)
            CollectionFilter(collections, onCollectionShown)
            RefreshingLine(state.refreshing)
            SyncBanner(sync, onTakeRemote, onReplaceNotes, onTrustHost)
            ScanNotices(state.notices)
            when (layout) {
                AgendaLayout.TIME -> TimeLayout(
                    // Rebuilt when the hour turns over, not every minute: the
                    // marker sits on an hour boundary, so that is how often
                    // the axis it belongs to can differ.
                    remember(state.sections, state.date, marker?.hour) {
                        state.sections.toTimeline(marker)
                    },
                    scroll = timeScroll,
                    collapse = collapse,
                    onTaskClick = onTaskClick,
                    onGroupAction = onGroupAction,
                )

                AgendaLayout.LIST -> ListLayout(
                    state.sections,
                    scroll = listScroll,
                    collapse = collapse,
                    onTaskClick = onTaskClick,
                    onGroupAction = onGroupAction,
                )
            }
        }
    }
}

/**
 * The collections, as chips that take their rows off the agenda and back.
 *
 * Under the header and outside the scrolling area, next to the layout switch:
 * both say how much of the agenda is on screen, and a filter that scrolls away
 * leaves a shortened list with nothing to explain it.
 *
 * Nothing is drawn while there is a single collection — see [CollectionChoice].
 * The row scrolls sideways rather than wrapping: the number of collections is
 * the user's to choose, and a filter that grows downwards would push the
 * agenda off the screen.
 */
@Composable
private fun CollectionFilter(
    collections: List<CollectionChoice>,
    onCollectionShown: (String, Boolean) -> Unit,
) {
    if (collections.isEmpty()) {
        return
    }

    val colors = LocalAgendaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
            .testTag("collection-filter"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        collections.forEach { choice ->
            FilterChip(
                selected = choice.shown,
                onClick = { onCollectionShown(choice.label.id, !choice.shown) },
                label = {
                    Text(
                        text = choice.label.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = Sizes.collectionName),
                    )
                },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(Sizes.collectionDot)
                            .clip(CircleShape)
                            .background(colors.collectionTone(choice.label.tone)),
                    )
                },
                modifier = Modifier.testTag("collection-chip-${choice.label.id}"),
            )
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
private fun SyncBanner(
    sync: SyncUiState,
    onTakeRemote: () -> Unit,
    onReplaceNotes: () -> Unit,
    onTrustHost: () -> Unit,
) {
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
        sync.message?.detail?.let { detail ->
            Text(
                text = detailText(detail),
                style = MaterialTheme.typography.labelSmall,
                // Monospace for what a library wrote — a path, an id, a line
                // of diagnostics reads better in it. What is worded here is
                // prose and is set as prose.
                fontFamily = (detail as? Detail.Verbatim)?.let { FontFamily.Monospace },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        CollectionRuns(sync)
        Answer(sync, onTakeRemote, onReplaceNotes, onTrustHost)
        Unpushed(sync)
        LastSynced(sync)
    }
}

/**
 * What each collection of the last run answered, one line apiece.
 *
 * Only from two collections up: with one, the line above already says it, and
 * repeating it under its own name would be the same sentence twice. What this
 * carries that the line above cannot is which repository failed — a run over
 * three of them ends with the answer of the third, whatever the second did.
 */
@Composable
private fun CollectionRuns(sync: SyncUiState) {
    if (sync.runs.size < 2) {
        return
    }

    val colors = LocalAgendaColors.current
    Column(Modifier.fillMaxWidth().testTag("sync-collections")) {
        sync.runs.forEach { run ->
            Text(
                text = stringResource(
                    R.string.sync_collection_line,
                    run.name,
                    stringResource(run.message.text),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (run.message.failed) {
                    colors.deadline.tone
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The button for a state that is waiting on the user rather than on a server.
 *
 * Three of those exist, and none can be resolved by trying again: an SSH
 * server nobody has vouched for, notes on the device and on the remote that
 * share no history, or a directory holding a checkout of somewhere else. Each
 * takes a decision only the user can make, so each is a press rather than
 * something saving a form does.
 */
@Composable
private fun Answer(
    sync: SyncUiState,
    onTakeRemote: () -> Unit,
    onReplaceNotes: () -> Unit,
    onTrustHost: () -> Unit,
) {
    val (label, act, tag) = when {
        sync.running -> return

        // First, because it stops everything else: nothing was fetched, and
        // the two questions below are about notes that never arrived.
        sync.pendingHost != null -> Triple(
            if (sync.pendingHostReplaces != null) {
                R.string.sync_host_accept_new
            } else {
                R.string.sync_host_accept
            },
            onTrustHost,
            "sync-trust-host",
        )

        sync.unrelated != null -> Triple(
            R.string.sync_take_remote,
            onTakeRemote,
            "sync-take-remote",
        )

        sync.message?.text == R.string.settings_other_checkout -> Triple(
            R.string.settings_replace_notes,
            onReplaceNotes,
            "sync-replace-notes",
        )

        else -> return
    }

    TextButton(onClick = act, modifier = Modifier.testTag(tag)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Edits that are still only on this device.
 *
 * Its own line rather than part of the message above: the message is about the
 * attempt just made, and this outlives it — a commit made offline stays unsent
 * across every launch until a sync gets through. Left out while a sync runs,
 * where it would state a count about to change.
 */
@Composable
private fun Unpushed(sync: SyncUiState) {
    val owed = sync.repository?.unpushed?.toInt()?.takeIf { it > 0 } ?: return
    if (sync.running) {
        return
    }

    Text(
        text = pluralStringResource(R.plurals.sync_unpushed, owed, owed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.testTag("sync-unpushed"),
    )
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
