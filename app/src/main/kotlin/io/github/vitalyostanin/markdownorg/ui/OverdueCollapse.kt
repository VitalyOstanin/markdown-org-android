package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Which overdue bands are folded away.
 *
 * Held above both layouts rather than inside either: the two show the same
 * agenda in two visual languages, and a band folded in one of them has been
 * answered in both. Saveable, so the fold survives a rotation — reopening the
 * archive after every turn of the phone is the thing the fold is there to
 * avoid.
 */
@Stable
class OverdueCollapse(collapsed: Set<OverdueBand> = setOf(OverdueBand.LONG_AGO)) {

    var collapsed by mutableStateOf(collapsed)
        private set

    fun isCollapsed(band: OverdueBand): Boolean = band in collapsed

    fun toggle(band: OverdueBand) {
        collapsed = if (band in collapsed) collapsed - band else collapsed + band
    }

    companion object {
        /**
         * Names rather than ordinals: a saved state outlives the process, and
         * an ordinal changes meaning the moment a band is added in the middle.
         * A name that no longer matches a band is dropped, which unfolds it —
         * the safe direction, since nothing is then hidden without being asked.
         */
        val Saver = listSaver<OverdueCollapse, String>(
            save = { state -> state.collapsed.map(OverdueBand::name) },
            restore = { names ->
                OverdueCollapse(
                    names.mapNotNullTo(mutableSetOf()) { name ->
                        OverdueBand.entries.firstOrNull { it.name == name }
                    },
                )
            },
        )
    }
}

/**
 * The fold state of a screen, with the oldest band folded to begin with.
 *
 * That default is the whole point of the grouping: a file kept for years opens
 * on what can be acted on today, and what cannot is one tap away rather than
 * ten screens of scrolling.
 */
@Composable
fun rememberOverdueCollapse(): OverdueCollapse =
    rememberSaveable(saver = OverdueCollapse.Saver) { OverdueCollapse() }
