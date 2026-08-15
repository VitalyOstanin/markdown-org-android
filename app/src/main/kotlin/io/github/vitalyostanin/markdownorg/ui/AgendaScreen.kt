package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.abs

@Composable
fun AgendaScreen(
    state: AgendaUiState,
    /** Which view of the plan is on screen, and what changing it asks for. */
    view: AgendaView = AgendaView(),
    modifier: Modifier = Modifier,
    /** The wall clock the marker line follows; see [AgendaViewModel.now]. */
    now: LocalDateTime = LocalDateTime.now(),
    sync: SyncUiState = SyncUiState(),
    editIssue: SyncMessage? = null,
    /** What acting on a whole band did, and what it takes to undo it. */
    groupResult: GroupResult? = null,
    filters: AgendaFilters = AgendaFilters(),
    actions: AgendaActions = AgendaActions(),
) {
    // What an edit answered with, if it could not be made. A snackbar rather
    // than the banner: it is about the tap that was just made, it goes away on
    // its own, and it leaves the line under the header to the checkout.
    val snackbar = remember { SnackbarHostState() }
    val issue = editIssue?.let { stringResource(it.text) }
    LaunchedEffect(editIssue) {
        if (issue != null) {
            snackbar.showSnackbar(issue)
            actions.onEditIssueShown()
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
                actions.onUndoGroup()
            } else {
                actions.onGroupResultShown()
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AgendaBody(
                state = state,
                view = view,
                now = now,
                sync = sync,
                filters = filters,
                actions = actions,
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
    view: AgendaView,
    now: LocalDateTime,
    sync: SyncUiState,
    filters: AgendaFilters,
    actions: AgendaActions,
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

        is AgendaUiState.Ready -> BoxWithConstraints(Modifier.fillMaxSize()) {
            // How much room there is above the plan decides how the header is
            // set out. Measured rather than read off the orientation: what
            // matters is the height the agenda was given, and a window shared
            // with another application is short while the device is upright.
            val short = maxHeight < Sizes.shortWindow

            Column(Modifier.fillMaxSize()) {
                // The marker line belongs to today; on an agenda for another
                // day there is no current moment to draw, and `null` leaves it
                // out.
                val marker = now.toLocalTime().takeIf { state.date == now.toLocalDate() }
                // Outside the scrolling area: the switch is how the user gets
                // back to the other layout, and it must not scroll away.
                // Everything standing between the top of the screen and the
                // plan, under one tag: how much of the screen it takes is what
                // the short layout is measured by, and each of the five parts
                // is worth little on its own.
                Column(Modifier.testTag("agenda-header-area")) {
                    AgendaHeader(
                        state = state,
                        view = view,
                        sync = sync,
                        filters = filters,
                        actions = actions,
                        short = short,
                    )
                    CollectionFilter(filters.collections, filters.onCollectionShown, short)
                    RefreshingLine(state.refreshing)
                    SyncBanner(
                        sync = sync,
                        onTakeRemote = actions.onTakeRemote,
                        onTrustHost = actions.onTrustHost,
                        onOpenSettings = actions.onOpenSettings,
                        short = short,
                    )
                    ScanNotices(state.notices)
                }
                // The axis covers one day; every wider span is read as the
                // list, whatever the layout switch was left on last time.
                if (view.layout == AgendaLayout.TIME && state.span.fitsTimeLayout) {
                    TimeLayout(
                        // Rebuilt when the hour turns over, not every minute:
                        // the marker sits on an hour boundary, so that is how
                        // often the axis it belongs to can differ.
                        remember(state.sections, state.date, marker?.hour) {
                            state.sections.toTimeline(marker)
                        },
                        scroll = timeScroll,
                        collapse = collapse,
                        grouped = view.grouped,
                        onTaskClick = actions.onTaskClick,
                        onGroupAction = actions.onGroupAction,
                    )
                } else {
                    ListLayout(
                        days = state.days,
                        span = state.span,
                        // The day being lived through, not the day on screen:
                        // once the plan can be stepped away from today, the two
                        // differ, and it is today that the headings stand out
                        // against.
                        today = now.toLocalDate(),
                        scroll = listScroll,
                        collapse = collapse,
                        grouped = view.grouped,
                        onTaskClick = actions.onTaskClick,
                        onGroupAction = actions.onGroupAction,
                    )
                }
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
    /** Whether the window is too short to spend a row per thing; see [Sizes.shortWindow]. */
    short: Boolean = false,
) {
    if (collections.isEmpty()) {
        return
    }

    val colors = LocalAgendaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = Spacing.gutter,
                // The chips carry their own padding, and on a short screen
                // what this adds around them is a row of the plan.
                vertical = if (short) Spacing.none else Spacing.xs,
            )
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
    view: AgendaView,
    sync: SyncUiState,
    filters: AgendaFilters,
    actions: AgendaActions,
    /** Whether the window is too short to spend a row per thing; see [Sizes.shortWindow]. */
    short: Boolean = false,
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

    val padding = Modifier
        .fillMaxWidth()
        .padding(
            start = Spacing.gutter,
            end = Spacing.sm,
            top = if (short) Spacing.xs else Spacing.sm,
            // Nothing below on a short screen: the controls carry a touch
            // target's worth of padding of their own, and what this would add
            // under them is another row taken off the plan.
            bottom = if (short) Spacing.none else Spacing.sm,
        )

    if (short) {
        // Everything on one row: on a screen this short the rows the header
        // takes come straight off the plan, which is what the screen is for.
        // The day and its date share the space the controls leave, and it is
        // the date that gives way — the name of the day is what the header is
        // read for, and a control pushed off the edge cannot be pressed.
        Row(modifier = padding, verticalAlignment = Alignment.CenterVertically) {
            Steps(
                span = view.span,
                onStep = actions.onStep,
                onShowToday = actions.onShowToday,
                modifier = Modifier.weight(1f),
            ) {
                Heading(weekday, MaterialTheme.typography.titleMedium)
                if (full.isNotEmpty()) {
                    Spacer(Modifier.width(Spacing.sm))
                    Caption(full, Modifier.weight(1f, fill = false))
                }
            }
            HeaderControls(view, sync, filters, actions)
        }
        return
    }

    // The day gets a line of its own, the controls another one. Sharing a
    // single row left the day about a quarter of the width -- less than a
    // long name of a day needs, and a word with nowhere to wrap breaks
    // mid-letter ("Воскрес / енье"). The date under it fared no better,
    // falling onto three lines. Neither depends on the language or on how
    // large the system font is set once the width is the whole screen.
    Column(modifier = padding) {
        Steps(span = view.span, onStep = actions.onStep, onShowToday = actions.onShowToday) {
            Column(modifier = Modifier.weight(1f)) {
                Heading(weekday, MaterialTheme.typography.headlineSmall)
                // The flat list of tasks has no dates to state, and an empty
                // line where the date was would leave a gap under the heading.
                if (full.isNotEmpty()) {
                    Caption(full)
                }
            }
        }
        HeaderControls(
            view,
            sync,
            filters,
            actions,
            modifier = Modifier.fillMaxWidth(),
            // The width is the whole screen, and controls bunched at the left
            // edge of it read as one group with the day above them.
            apart = true,
        )
    }
}

/**
 * The heading, with the two ways of moving the plan off today around it: a
 * button on either side, and a sideways drag of the heading itself.
 *
 * Both on purpose, and for now: the buttons say the plan can be moved at all,
 * where a drag has to be found; the drag is at hand where the buttons are two
 * small targets among five others. Which of them earns its place is a question
 * for the phone rather than for the reading of the code.
 *
 * The flat list of tasks has no dates to step through, and gets neither.
 */
@Composable
private fun Steps(
    span: AgendaSpan,
    onStep: (Int) -> Unit,
    onShowToday: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    if (!span.hasDays) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, content = content)
        return
    }

    // The drag is answered when it ends rather than while it runs: a step
    // costs a scan of the notes, and one per frame of a swipe would be a scan
    // the finger outruns. What the swipe carries is a direction, not a
    // distance — two days at once is not a thing the heading can say.
    var travelled by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { Sizes.stepSwipe.toPx() }

    Row(
        modifier = modifier.draggable(
            state = rememberDraggableState { delta -> travelled += delta },
            orientation = Orientation.Horizontal,
            onDragStopped = {
                // Dragged the way a page turns: pulling the plan to the left
                // brings what comes after it into view.
                if (abs(travelled) >= threshold) {
                    onStep(if (travelled < 0) 1 else -1)
                }
                travelled = 0f
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(
            icon = R.drawable.ic_step_back,
            label = R.string.agenda_step_back,
            hint = R.string.hint_step_back,
            tag = "agenda-step-back",
        ) {
            onStep(-1)
        }
        // The press that undoes any number of steps, on the thing they moved.
        // A button of its own would sit there doing nothing on the day the
        // agenda opens on, which is most of the time.
        // The weight is spent on a box of this row's own rather than handed to
        // the tooltip: a weight given to HintTooltip reaches the box inside it
        // and not the one this row measures, so the strip took the whole width
        // and the arrow after it was measured to nothing — present, tappable
        // by name, and reachable by no finger.
        Box(Modifier.weight(1f)) {
            HintTooltip(stringResource(R.string.hint_show_today)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = stringResource(R.string.agenda_show_today),
                            onClick = onShowToday,
                        )
                        // The press merges the day and its date into one node,
                        // so the strip needs a handle of its own: the tag on
                        // the heading inside it is no longer reachable from
                        // the tree the tests read.
                        .testTag("agenda-date-strip"),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
        StepButton(
            icon = R.drawable.ic_step_forward,
            label = R.string.agenda_step_forward,
            hint = R.string.hint_step_forward,
            tag = "agenda-step-forward",
        ) {
            onStep(1)
        }
    }
}

/** One of the two arrows, narrower than the controls of the header proper. */
@Composable
private fun StepButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    @StringRes hint: Int,
    tag: String,
    onClick: () -> Unit,
) {
    HintTooltip(stringResource(hint)) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.width(Sizes.stepButton).testTag(tag),
        ) {
            Icon(painter = painterResource(icon), contentDescription = stringResource(label))
        }
    }
}

/** What the span on screen is called, in the size the header has room for. */
@Composable
private fun Heading(text: String, style: TextStyle) {
    Text(
        text = text,
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier.testTag("agenda-heading"),
    )
}

/** Which dates the span covers, cut short rather than wrapped when it must be. */
@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag("agenda-caption"),
    )
}

/**
 * The controls of the header, in the order they are reached in.
 *
 * One row whichever way the header is set out: what they are and how they are
 * ordered does not depend on the room there is, only on where the row sits.
 * [apart] pushes the two icon buttons away from the menus, which is what the
 * full-width row wants and what the short one, sharing its row with the day,
 * must not do.
 */
@Composable
private fun HeaderControls(
    view: AgendaView,
    sync: SyncUiState,
    filters: AgendaFilters,
    actions: AgendaActions,
    modifier: Modifier = Modifier,
    apart: Boolean = false,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
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
            onClick = actions.onSync,
        )
        HeaderAction(
            icon = R.drawable.ic_settings,
            label = stringResource(R.string.settings_title),
            hint = stringResource(R.string.hint_open_settings),
            tag = "open-settings",
            onClick = actions.onOpenSettings,
        )
        if (apart) {
            Spacer(Modifier.weight(1f))
        }
        TagMenu(filters.tags, filters.currentTag, filters.onTagChange)
        SpanMenu(view.span, view.onSpanChange)
        // Left out rather than left there doing nothing: the axis draws
        // one day, and a switch that changes nothing is a control the user
        // presses twice before deciding it is broken.
        if (view.span.fitsTimeLayout) {
            LayoutSwitch(view.layout, view.onLayoutChange)
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
 * On a first launch it says instead where the notes are and what the settings
 * are for. That line is not the "not configured" notice this banner used to be
 * kept clear of: it is shown while nobody has said anything at all — no
 * address, and no decision to keep the notes here — and it goes for good as
 * soon as either is given. Without it the first screen offers a sync button
 * that is disabled and says why only under a long press.
 */
@Composable
private fun SyncBanner(
    sync: SyncUiState,
    onTakeRemote: () -> Unit,
    onTrustHost: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Whether the window is too short to spend a row per thing; see [Sizes.shortWindow]. */
    short: Boolean = false,
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

        sync.unsettled -> stringResource(R.string.sync_unconfigured)

        else -> return
    }

    val tone = if (sync.message?.failed == true) {
        colors.deadline.tone
    } else {
        MaterialTheme.colorScheme.outline
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        // On a short screen the three short lines join the first one rather
        // than being dropped: what is still unsent and when the last run got
        // through are stated nowhere else in the application, and a checkout
        // the user cannot see the state of is what the banner exists against.
        // The line that gives way is the wordiest one, which is also the one
        // the two beside it are read against.
        if (short) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BannerLine(text, tone, Modifier.weight(1f, fill = false), maxLines = 1)
                Spacer(Modifier.width(Spacing.sm))
                Unpushed(sync)
                Spacer(Modifier.width(Spacing.sm))
                LastSynced(sync)
            }
            // Kept whatever the room: it is a decision only the user can make,
            // and nothing else on the screen offers it.
            Answer(sync, onTakeRemote, onTrustHost, onOpenSettings)
            return@Column
        }

        BannerLine(text, tone)
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
        Answer(sync, onTakeRemote, onTrustHost, onOpenSettings)
        Unpushed(sync)
        LastSynced(sync)
    }
}

/**
 * What the last sync did, as the line the banner is read by.
 *
 * [maxLines] is what the room decides: given a screen to wrap onto, a failure
 * is worth reading in full, and a screen with no such room shows as much of it
 * as fits rather than eating the rows the plan is drawn in.
 */
@Composable
private fun BannerLine(
    text: String,
    tone: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    HintTooltip(
        stringResource(R.string.hint_sync_banner),
        // On the anchor rather than on the line inside it: see HintTooltip.
        modifier = modifier.testTag("sync-banner"),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
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
 * Two of those exist, and neither can be resolved by trying again: an SSH
 * server nobody has vouched for, and notes on the device and on the remote
 * that share no history. Each takes a decision only the user can make, so each
 * is a press rather than something saving a form does.
 *
 * A directory holding somebody else's checkout is a third such state and has
 * no button, because the only move from it is over files this application did
 * not write. It is answered outside the application, and the message says so.
 *
 * The first launch is offered the settings by the same button rather than by a
 * screen of its own: what it needs is the form the two above lead to anyway,
 * and it is the only thing on this screen that is not reached by a glyph.
 */
@Composable
private fun Answer(
    sync: SyncUiState,
    onTakeRemote: () -> Unit,
    onTrustHost: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val (label, act, tag) = when {
        sync.running -> return

        // First, because it stops everything else: nothing was fetched, and
        // the question below is about notes that never arrived.
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

        // Last, because the two above are questions about a server that was
        // already given, and this one is the state of having given none.
        sync.unsettled -> Triple(
            R.string.sync_unconfigured_open,
            onOpenSettings,
            "sync-open-settings",
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
