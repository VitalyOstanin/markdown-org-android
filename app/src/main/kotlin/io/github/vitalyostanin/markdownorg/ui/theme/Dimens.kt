package io.github.vitalyostanin.markdownorg.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale, in the steps Material lays out on.
 *
 * Written down rather than measured per place: the values had grown to
 * twenty-three distinct ones, a third of them off the 4 dp grid, and the
 * places that play the same part in different layouts — the header, the
 * banner, the two lists, the section headings — had ended up on four
 * different left edges. Anything the layouts share is [gutter].
 */
object Spacing {
    /** Between a glyph and the word next to it. */
    val xs = 4.dp

    /** Between neighbouring controls, and inside a small container. */
    val sm = 8.dp

    /** Inside a row or a tile. */
    val md = 12.dp

    /** Between groups, and the left edge everything on the screen stands on. */
    val lg = 16.dp

    /** Around a screen of its own — the settings form, a failure. */
    val xl = 24.dp

    /**
     * Below the last control of a sheet, clear of the gesture bar.
     */
    val xxl = 28.dp

    /** What the header, the banner and both lists are inset by. */
    val gutter = lg
}

/**
 * Fixed sizes, as opposed to the space between things.
 *
 * These are off the spacing scale on purpose: a hairline is a hairline, and
 * the height of an hour on the axis is what makes an hour readable, not a
 * multiple of the grid.
 */
object Sizes {
    /** A drawn line — the hour rule, the dashes of a collapsed stretch. */
    val hairline = 1.dp

    /** The line that marks the current hour, and the one that says "rebuilding". */
    val marker = 2.dp

    /** The dot the current-hour line starts with. */
    val markerDot = 8.dp

    /** The coloured rail that carries the kind of entry in the list layout. */
    val rail = 4.dp

    /** Height of that rail, which is the height of a list row. */
    val railHeight = 40.dp

    /** The priority cookie, which has to hold one character. */
    val badge = 20.dp

    /** An icon inside a control that is not an [androidx.compose.material3.IconButton]. */
    val icon = 20.dp

    /** Width of the time column, so headings line up down the list. */
    val timeColumn = 48.dp

    /** One hour on the axis, and the tile that sits in it. */
    val hourRow = 48.dp
    val tile = 40.dp

    /** Width of the `09:00` label on the axis; the tiles start after it. */
    val hourLabel = 40.dp

    /**
     * Where the current-hour marker starts: half its dot short of the tiles,
     * so the dot is centred on the edge of the hour label rather than pushed
     * off it. Written as the arithmetic so a wider label carries it along.
     */
    val markerInset = hourLabel - markerDot / 2

    /** As much of a stack trace as is shown before it scrolls on its own. */
    val traceHeight = 200.dp
}
