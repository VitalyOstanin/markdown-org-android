package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
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
         * One string rather than a list of names, because "nothing is folded"
         * is a state too. `listSaver` saves nothing at all for an empty list,
         * so the screen that came back folded after a rotation was precisely
         * the one where every band had been opened by hand — the state the
         * saved fold exists to carry across.
         *
         * Names rather than ordinals: a saved state outlives the process, and
         * an ordinal changes meaning the moment a band is added in the middle.
         * A name that no longer matches a band is dropped, which unfolds it —
         * the safe direction, since nothing is then hidden without being asked.
         */
        val Saver: Saver<OverdueCollapse, String> = Saver(
            save = { state ->
                state.collapsed.joinToString(SEPARATOR, transform = OverdueBand::name)
            },
            restore = { saved ->
                OverdueCollapse(
                    saved.split(SEPARATOR).mapNotNullTo(mutableSetOf()) { name ->
                        OverdueBand.entries.firstOrNull { it.name == name }
                    },
                )
            },
        )

        /** Not part of any band name, so a split can never cut one in half. */
        private const val SEPARATOR = ","
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
