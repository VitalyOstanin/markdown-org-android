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
    /** No space at all, where a layout has to state that it wants none. */
    val none = 0.dp

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

    /** The dot that carries a collection's colour, at the head of a row. */
    val collectionDot = 6.dp

    /** The mark beside a setting's name that opens what the setting is for. */
    val helpMark = 18.dp

    /**
     * How much of a collection's name a chip shows before it is cut.
     *
     * Enough for a word: the chips above the list are what the dots on the
     * rows are read against, and a row of chips wide enough for a sentence
     * would push the agenda itself down the screen.
     */
    val collectionName = 72.dp

    /** An icon inside a control that is not an [androidx.compose.material3.IconButton]. */
    val icon = 20.dp

    /** Width of the time column, so headings line up down the list. */
    val timeColumn = 48.dp

    /** One hour on the axis, and the tile that sits in it. */
    val hourRow = 48.dp
    val tile = 40.dp

    /**
     * The height a cell of the month grid takes where there is room for it.
     *
     * A cell is as wide as a seventh of the screen — about 45 dp on a phone —
     * so this is what keeps it roughly square. Sharing the whole height
     * between the weeks instead drew a cell twice as tall as it is wide, with
     * the number and the count in a band across the middle and half the cell
     * empty above and below them.
     */
    val monthCell = 56.dp

    /**
     * The height a cell of the month grid needs to stack what it holds.
     *
     * A window too short for [monthCell] rows and the panel under them gives
     * the grid what is left, which a landscape phone measures in tens of dp.
     * Above this the cell puts the count under the day number; below it the
     * two go side by side, because a stack that does not fit is a chip sliced
     * into a stripe, and that is what landscape drew.
     */
    val monthCellRoomy = 56.dp

    /**
     * The least the panel under the month grid is worth drawing in.
     *
     * A heading and two rows. Below that it is left out and the grid takes the
     * window, which is what a landscape phone gets: a panel showing one row of
     * a day is not the day.
     */
    val monthPanelMin = 132.dp

    /** The row of weekday names above the grid, in the height arithmetic. */
    val monthHeaderRow = 20.dp

    /** The disc the day number sits in where the cell is today. */
    val monthTodayDisc = 28.dp

    /**
     * Between the day number and the count under it.
     *
     * Off the spacing scale on purpose: the cell is 56 dp tall and the disc
     * and the chip take 46 of them, so what is left to separate the two is
     * what is left.
     */
    val monthCellGap = 2.dp

    /**
     * How tall the list of files in the move dialog is allowed to grow.
     *
     * About eight rows. A collection of a hundred notes is ordinary, and a
     * dialog as tall as its list would push its own dismissal off the screen;
     * past this the list scrolls inside the dialog.
     */
    val fileList = 320.dp

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

    /**
     * The window height below which the header stops spending a row per thing.
     *
     * A phone on its side leaves the agenda about 330 dp, and a header that
     * takes one row for the day, another for the date, a third for the
     * controls, a fourth for the collections and a fifth for the sync line
     * takes two thirds of that — the plan itself is left with a row and a
     * half. The value is Material's own boundary between a compact window
     * height and a medium one, so a tablet on its side, which has the height
     * for it, keeps the roomy header.
     */
    val shortWindow = 480.dp

    /**
     * The window height a calendar needs before it can be picked from.
     *
     * Material's date dialog draws itself at this height whatever it is given:
     * six rows of days at the height a finger needs, a header above them and a
     * row of buttons below. A window shorter than this cuts the last week and
     * the buttons off, so the dialog is asked for its typed form instead.
     */
    val calendarHeight = 568.dp

    /**
     * How far the heading has to be dragged sideways before the plan steps.
     *
     * Short enough for a thumb resting on the header, long enough not to fire
     * on the sideways part of a press that was meant as a press.
     */
    val stepSwipe = 40.dp

    /**
     * The width of an arrow beside the heading.
     *
     * Narrower than the 48 dp of a header control: two of these sit on the row
     * the day, the date and five controls already share, and the height of the
     * touch target — which is what a thumb misses on — is left alone.
     */
    val stepButton = 36.dp
}
