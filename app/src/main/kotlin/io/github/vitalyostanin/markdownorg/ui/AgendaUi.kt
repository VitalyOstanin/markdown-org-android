package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.Immutable
import io.github.vitalyostanin.markdownorg.core.MergedTag
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.Task

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
    val onGroupAction: (OverdueGroup, BulkAction) -> Unit = { _, _ -> },
    val onUndoGroup: () -> Unit = {},
    val onEditIssueShown: () -> Unit = {},
    val onGroupResultShown: () -> Unit = {},
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
