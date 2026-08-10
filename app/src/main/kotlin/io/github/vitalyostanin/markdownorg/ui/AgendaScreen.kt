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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.vitalyostanin.markdownorg.core.MergedTag
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import io.github.vitalyostanin.markdownorg.ui.theme.collectionTone
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.Task
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun AgendaScreen(
    state: AgendaUiState,
    layout: AgendaLayout,
    onLayoutChange: (AgendaLayout) -> Unit,
    modifier: Modifier = Modifier,
    /** Which span the header offers; what is on screen is the state's own. */
    span: AgendaSpan = AgendaSpan.DAY,
    onSpanChange: (AgendaSpan) -> Unit = {},
    /** The wall clock the marker line follows; see [AgendaViewModel.now]. */
    now: LocalDateTime = LocalDateTime.now(),
    sync: SyncUiState = SyncUiState(),
    editIssue: SyncMessage? = null,
    onEditIssueShown: () -> Unit = {},
    /** The collections to filter by; empty while there is one of them. */
    collections: List<CollectionChoice> = emptyList(),
    onCollectionShown: (String, Boolean) -> Unit = { _, _ -> },
    /** The tags the notes declare; empty while none of them declares any. */
    tags: List<MergedTag> = emptyList(),
    /** The tag in force, or null while the agenda is not narrowed. */
    currentTag: String? = null,
    onTagChange: (String?) -> Unit = {},
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
                span,
                onSpanChange,
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
                tags,
                currentTag,
                onTagChange,
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
    span: AgendaSpan,
    onSpanChange: (AgendaSpan) -> Unit,
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
    tags: List<MergedTag>,
    currentTag: String?,
    onTagChange: (String?) -> Unit,
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
            AgendaHeader(
                state,
                layout,
                onLayoutChange,
                span,
                onSpanChange,
                sync,
                onSync,
                onOpenSettings,
                tags,
                currentTag,
                onTagChange,
            )
            CollectionFilter(collections, onCollectionShown)
            RefreshingLine(state.refreshing)
            SyncBanner(sync, onTakeRemote, onReplaceNotes, onTrustHost)
            ScanNotices(state.notices)
            // The axis covers one day; every wider span is read as the list,
            // whatever the layout switch was left on last time it was shown.
            if (layout == AgendaLayout.TIME && state.span.fitsTimeLayout) {
                TimeLayout(
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
            } else {
                ListLayout(
                    days = state.days,
                    span = state.span,
                    today = state.date,
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
            // The chip carries the name the collection was given, which need
            // not say anything about where it reads from — and two of them
            // may be named alike. The directory is what the press answers.
            HintTooltip(
                stringResource(
                    R.string.hint_collection_chip,
                    choice.label.name,
                    choice.label.root,
                ),
            ) {
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
    state: AgendaUiState.Ready,
    layout: AgendaLayout,
    onLayoutChange: (AgendaLayout) -> Unit,
    span: AgendaSpan,
    onSpanChange: (AgendaSpan) -> Unit,
    sync: SyncUiState,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
    tags: List<MergedTag>,
    currentTag: String?,
    onTagChange: (String?) -> Unit,
) {
    // The device locale, not the application's: the interface is English, but
    // a date is read in whatever language the user reads dates in. Read from
    // the composition rather than from Locale.getDefault(), which is not
    // observable — the header would keep yesterday's language until something
    // else redrew it.
    val locale = LocalLocale.current.platformLocale
    // What the span on screen is called, and what it covers. The day says its
    // weekday and its date; the wider spans say their name and the dates they
    // run between, which is the one thing the list below cannot repeat on
    // every row.
    val weekday = headingOf(state, locale)
    val full = captionOf(state, locale)

    // The day gets a line of its own, the controls another one. Sharing a
    // single row left the day about a quarter of the width -- less than a
    // long name of a day needs, and a word with nowhere to wrap breaks
    // mid-letter ("Воскрес / енье"). The date under it fared no better,
    // falling onto three lines. Neither depends on the language or on how
    // large the system font is set once the width is the whole screen.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.gutter,
                end = Spacing.sm,
                top = Spacing.sm,
                bottom = Spacing.sm,
            ),
    ) {
        Text(
            text = weekday,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("agenda-heading"),
        )
        // The flat list of tasks has no dates to state, and an empty line
        // where the date was would leave a gap under the heading.
        if (full.isNotEmpty()) {
            Text(
                text = full,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag("agenda-caption"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sync and settings sit next to the layout switch rather than in
            // a menu: with three controls on the screen a menu would hide two
            // of them behind a tap for no gain.
            HeaderAction(
                icon = R.drawable.ic_sync,
                label = stringResource(R.string.sync_now),
                // A disabled control says why it is disabled rather than what
                // it would do: "fetch, then push" reads as an offer, and this
                // one cannot be taken up until a collection has an address.
                hint = if (sync.configured) {
                    stringResource(R.string.hint_sync_now)
                } else {
                    stringResource(R.string.hint_sync_unavailable)
                },
                tag = "sync-now",
                enabled = sync.configured && !sync.running,
                onClick = onSync,
            )
            HeaderAction(
                icon = R.drawable.ic_settings,
                label = stringResource(R.string.settings_title),
                hint = stringResource(R.string.hint_open_settings),
                tag = "open-settings",
                onClick = onOpenSettings,
            )
            Spacer(Modifier.weight(1f))
            TagMenu(tags, currentTag, onTagChange)
            SpanMenu(span, onSpanChange)
            // Left out rather than left there doing nothing: the axis draws
            // one day, and a switch that changes nothing is a control the user
            // presses twice before deciding it is broken.
            if (span.fitsTimeLayout) {
                LayoutSwitch(layout, onLayoutChange)
            }
        }
    }
}

/**
 * The heading of the header: what the span on screen is.
 *
 * A day names its weekday, because that is what it is looked up by; the wider
 * spans name themselves, and a month names the month it is.
 */
@Composable
private fun headingOf(state: AgendaUiState.Ready, locale: Locale): String = when (state.span) {
    AgendaSpan.DAY -> remember(state.date, locale) {
        state.date.format(DateTimeFormatter.ofPattern("EEEE", locale))
            .replaceFirstChar { it.titlecase(locale) }
    }

    AgendaSpan.MONTH -> remember(state.date, locale) {
        state.date.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            .replaceFirstChar { it.titlecase(locale) }
    }

    else -> stringResource(state.span.labelRes)
}

/**
 * The line under the heading: which dates the span covers.
 *
 * Read off the days that came back rather than worked out here: the boundaries
 * of a week and of a month are the core's to decide — which day a week starts
 * on among them — and a second opinion about it here would eventually differ.
 * Empty for the flat list of tasks, which covers no dates at all.
 */
@Composable
private fun captionOf(state: AgendaUiState.Ready, locale: Locale): String {
    if (state.span == AgendaSpan.TASKS) {
        return ""
    }

    val dates = remember(state.days) { state.days.mapNotNull(AgendaDay::date) }
    val first = dates.firstOrNull() ?: state.date
    val last = dates.lastOrNull() ?: state.date

    return remember(first, last, locale) {
        val written = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
        val short = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
        if (first ==
            last
        ) {
            first.format(written)
        } else {
            "${first.format(short)} — ${last.format(short)}"
        }
    }
}

/**
 * How much of the plan to show, as a menu rather than a row of buttons.
 *
 * Four spans in a segmented row would take the width the header keeps for the
 * layout switch, and three of the four are chosen rarely: the day is what a
 * phone is opened on. The button carries the name of the span on screen, so
 * the choice is readable without opening anything.
 */
@Composable
private fun SpanMenu(current: AgendaSpan, onSpanChange: (AgendaSpan) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        HintTooltip(stringResource(R.string.hint_span_menu)) {
            TextButton(
                onClick = { open = true },
                modifier = Modifier.testTag("span-menu"),
            ) {
                Text(
                    text = stringResource(current.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AgendaSpan.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        open = false
                        onSpanChange(option)
                    },
                    modifier = Modifier.testTag(option.testTag),
                )
            }
        }
    }
}

/**
 * One control in the header.
 *
 * [IconButton] rather than a hand-built `Box`: the ripple, the size of the
 * touch target and how a disabled control reads all come with it, and they
 * are the same ones the buttons on the other screens get.
 *
 * [hint] is what a long press says. The icon is a glyph, [label] is two words,
 * and neither states what pressing it will do to the notes — which is the
 * question worth answering before a control that writes to them is pressed.
 */
@Composable
private fun HeaderAction(
    @DrawableRes icon: Int,
    label: String,
    hint: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    HintTooltip(hint) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Icon(
                painter = painterResource(icon),
                // Named rather than described: what the button does is what
                // the user needs to hear, and it is the same wording the
                // settings screen uses for the place it leads to.
                contentDescription = label,
            )
        }
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
        HintTooltip(
            stringResource(R.string.hint_sync_banner),
            // On the anchor rather than on the line inside it: see HintTooltip.
            modifier = Modifier.testTag("sync-banner"),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (sync.message?.failed == true) {
                    colors.deadline.tone
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
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

    HintTooltip(
        stringResource(R.string.hint_sync_unpushed),
        modifier = Modifier.testTag("sync-unpushed"),
    ) {
        Text(
            text = pluralStringResource(R.plurals.sync_unpushed, owed, owed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
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

    HintTooltip(
        stringResource(R.string.hint_sync_last_synced),
        modifier = Modifier.testTag("sync-last-synced"),
    ) {
        Text(
            text = stringResource(R.string.sync_last_synced, moment),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
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
            HintTooltip(stringResource(R.string.hint_scan_notice)) {
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
}

/**
 * Which of the two layouts is on screen.
 *
 * A segmented button is what Material 3 offers for a choice of two or three
 * views of the same content, and it is what this was hand-built out of before.
 */
@Composable
private fun LayoutSwitch(current: AgendaLayout, onLayoutChange: (AgendaLayout) -> Unit) {
    // The whole switch, not a tooltip per button. The row measures its
    // children to a common height, and a box between it and them is one more
    // thing for that measurement to go through; one anchor over the pair also
    // says what the choice is, which is what a reader of two glyphs is after.
    HintTooltip(stringResource(R.string.hint_layout_switch)) {
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
