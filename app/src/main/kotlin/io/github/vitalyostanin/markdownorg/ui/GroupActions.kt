package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.FileRollback

/**
 * What a group action did, and what it takes to put it back.
 *
 * The rollback travels with the result because the two are one offer: the
 * screen says what happened and, in the same line, offers to undo it. A group
 * that changed nothing carries none, and there is nothing to offer.
 */
data class GroupResult(
    val action: BulkAction,
    val changed: Int,
    /** Tasks the core left alone — a heading that moved, a missing date. */
    val refused: Int,
    val rollback: List<FileRollback>,
) {
    val canUndo: Boolean get() = rollback.isNotEmpty()
}

/** What the action is called where it is chosen. */
@get:StringRes
internal val BulkAction.label: Int
    get() = when (this) {
        BulkAction.MOVE_TO_TODAY -> R.string.agenda_group_move_today
        BulkAction.DROP_PLANNING -> R.string.agenda_group_drop_date
        BulkAction.CANCEL -> R.string.agenda_group_cancel
    }

/** What the screen says the action did, counting the tasks it did it to. */
@get:PluralsRes
internal val BulkAction.done: Int
    get() = when (this) {
        BulkAction.MOVE_TO_TODAY -> R.plurals.agenda_group_moved
        BulkAction.DROP_PLANNING -> R.plurals.agenda_group_dropped
        BulkAction.CANCEL -> R.plurals.agenda_group_cancelled
    }

/**
 * The actions a whole band offers, behind the mark at the end of its heading.
 *
 * A menu rather than a row of buttons: there are four bands on the screen, and
 * a row of three controls under each of them would take four lines from an
 * agenda that is already the thing being scrolled past. The heading itself
 * stays what it was — the control that folds the band — because folding is
 * what it is pressed for most.
 */
@Composable
internal fun GroupMenu(band: OverdueBand, onAction: (BulkAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = stringResource(band.label)

    Text(
        text = MENU_MARK,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .testTag(band.menuTag)
            .clickable(onClickLabel = stringResource(R.string.agenda_group_actions)) {
                open = true
            }
            // The mark is one glyph wide, well under the size a finger aims
            // at; the padding is what makes the target a target.
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            // Named after the band it belongs to: a reader hearing "actions"
            // four times down the screen learns nothing about which is which.
            .semantics { contentDescription = name },
    )

    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (action in BulkAction.entries) {
            DropdownMenuItem(
                text = { Text(stringResource(action.label)) },
                onClick = {
                    open = false
                    onAction(action)
                },
                modifier = Modifier.testTag(action.menuTag),
            )
        }
    }
}

/** Handle for the tests: which band's menu, and which action in it. */
internal val OverdueBand.menuTag: String get() = "group-menu-$name"

internal val BulkAction.menuTag: String get() = "group-action-$name"

/** The mark that opens the menu, in the monospace language of the row. */
private const val MENU_MARK = "⋮"
