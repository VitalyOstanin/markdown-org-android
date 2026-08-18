package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.Immutable
import io.github.vitalyostanin.markdownorg.core.MergedTag
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.Task
import java.time.LocalDate

/**
 * What a tap on the agenda can ask for.
 *
 * One object rather than eight lambdas passed through three composables. Half
 * of them are `() -> Unit`, and the call that handed them down did it
 * positionally: swapping the sync icon with the settings one compiled, ran, and
 * showed nothing wrong until something was pressed.
 */
@Immutable
data class AgendaActions(
    val onSync: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onTaskClick: (Task) -> Unit = {},
    /** Answer to unrelated histories: take what the server holds. */
    val onTakeRemote: () -> Unit = {},
    /** Answer to a server key nobody has vouched for yet: it is the right one. */
    val onTrustHost: () -> Unit = {},
    /**
     * Answer to a sync stopped by changes this application did not commit:
     * commit them and go again.
     */
    val onSettleAndSync: () -> Unit = {},
    /**
     * Move the plan by whole spans: negative back, positive forward. What a
     * span means is the model's to decide — a day agenda steps by a day, a week
     * by a week.
     */
    val onStep: (Int) -> Unit = {},
    /** Back to the day being lived through, from wherever the plan was moved to. */
    val onShowToday: () -> Unit = {},
    /**
     * Open one day of the plan, whatever span is on screen.
     *
     * What a cell of the month calendar answers with: the grid says how much a
     * day carries, and what that is made of is the day span's to show.
     */
    val onShowDay: (LocalDate) -> Unit = {},
    val onGroupAction: (OverdueGroup, BulkAction) -> Unit = { _, _ -> },
    val onUndoGroup: () -> Unit = {},
    val onEditIssueShown: () -> Unit = {},
    val onGroupResultShown: () -> Unit = {},
)

/**
 * How the plan is drawn, and what asking for another view of it takes.
 *
 * The four that decide it travelled as loose parameters through the screen, the
 * header and the row of controls, and the fifth — whether the day keeps its
 * section headings — would have been the thirteenth argument of the screen.
 * They belong together for the same reason [AgendaFilters] does: each is a
 * choice about what is on screen rather than about what the notes hold, and a
 * reader of the call now sees the choice named instead of counted.
 */
@Immutable
data class AgendaView(
    val layout: AgendaLayout = AgendaLayout.LIST,
    val onLayoutChange: (AgendaLayout) -> Unit = {},
    /** Which span the header offers; what is on screen is the state's own. */
    val span: AgendaSpan = AgendaSpan.DAY,
    val onSpanChange: (AgendaSpan) -> Unit = {},
    /** Whether the day is drawn under its section headings — a stored setting. */
    val grouped: Boolean = true,
    /**
     * Whether the month is drawn as a calendar rather than as the list — a
     * stored setting, and the way back to the reading the month had before the
     * grid existed.
     */
    val monthAsGrid: Boolean = true,
)

/** What narrows the agenda: which collections are shown, and the tag in force. */
@Immutable
data class AgendaFilters(
    /** The collections to filter by; empty while there is one of them. */
    val collections: List<CollectionChoice> = emptyList(),
    val onCollectionShown: (String, Boolean) -> Unit = { _, _ -> },
    /** The tags the notes declare; empty while none of them declares any. */
    val tags: List<MergedTag> = emptyList(),
    /** The tag in force, or null while the agenda is not narrowed. */
    val currentTag: String? = null,
    val onTagChange: (String?) -> Unit = {},
)
