package io.github.vitalyostanin.markdownorg.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours the agenda needs beyond the Material roles.
 *
 * Material 3 offers five roles; the agenda distinguishes more than that —
 * deadline, scheduled, repeating, done, cancelled, overdue. These sit next to
 * the [androidx.compose.material3.ColorScheme] rather than inside it, and
 * they never change meaning between themes: an overdue task stays red.
 *
 * Each entry comes as a triple — [tone] for glyphs and rails, [container]
 * with [onContainer] for a filled tile.
 */
@Immutable
data class AgendaRole(val tone: Color, val container: Color, val onContainer: Color)

@Immutable
data class AgendaColors(
    val deadline: AgendaRole,
    val scheduled: AgendaRole,
    val repeat: AgendaRole,
    val done: AgendaRole,
    val cancelled: AgendaRole,
    /**
     * Text over a solid [AgendaRole.tone] fill — an overdue task or the
     * current-time marker. A dense fill carries the tone itself, so the label
     * on top has to invert with the theme.
     */
    val onSolid: Color,
    /**
     * One colour per notes collection, taken by position.
     *
     * Apart from the roles above because it says something else: a role is
     * what the entry is — a deadline, a repeat — and this is where it came
     * from. Colours a row carries for both reasons have to stay told apart, so
     * these are used for a small mark rather than for the row itself.
     *
     * A set larger than the list wraps round it. Six is past what a phone can
     * usefully filter by, and a seventh colour distinct from the six and from
     * the roles would not be distinct enough to read at this size.
     */
    val collections: List<Color>,
    /**
     * The same collections over a solid [AgendaRole.tone] fill.
     *
     * A dense fill inverts the lightness of what a mark sits on — the tone is
     * light where the screen is dark, and dark where it is light — so the
     * palette of the theme goes flat there. These are the tones of the other
     * theme, which is what that fill amounts to. One colour for every
     * collection, as [collections] would give, rather than the row's text
     * colour: a screen of overdue rows would otherwise say nothing about
     * where any of them came from.
     */
    val collectionsOnSolid: List<Color>,
)

/**
 * The colour of collection number [index], wrapping round for a long set.
 *
 * [onSolid] picks the palette for a row already filled with a dense tone.
 */
fun AgendaColors.collectionTone(index: Int, onSolid: Boolean = false): Color {
    val palette = if (onSolid) collectionsOnSolid else collections

    return palette[index.mod(palette.size)]
}

/**
 * The collection tones that read over a light surface.
 *
 * Kept apart from the two schemes because each of them needs both lists: the
 * light theme carries these on its background and the dark ones over a solid
 * fill, and the dark theme the other way round.
 */
private val CollectionsOverLight = listOf(
    Color(0xFF6750A4),
    Color(0xFF0F7B6C),
    Color(0xFFB3261E),
    Color(0xFF7D5260),
    Color(0xFF00639B),
    Color(0xFF7A5900),
)

/** And the ones that read over a dark surface. */
private val CollectionsOverDark = listOf(
    Color(0xFFCFBCFF),
    Color(0xFF6FDBC6),
    Color(0xFFFFB4AB),
    Color(0xFFEFB8C8),
    Color(0xFF9CCAFF),
    Color(0xFFEDC26B),
)

internal val LightAgendaColors = AgendaColors(
    deadline = AgendaRole(Color(0xFFC4262E), Color(0xFFFFDCDA), Color(0xFF470004)),
    scheduled = AgendaRole(Color(0xFF1266C8), Color(0xFFD8E5FF), Color(0xFF00214C)),
    // Darkened from #A76A00, which measured 4.46 against white text — below
    // the 4.5 the rest of the palette clears.
    repeat = AgendaRole(Color(0xFFA06600), Color(0xFFFFE0B4), Color(0xFF3B2200)),
    done = AgendaRole(Color(0xFF2E7D4F), Color(0xFFC4F0D4), Color(0xFF00210F)),
    cancelled = AgendaRole(Color(0xFF6E6A78), Color(0xFFE6E2EF), Color(0xFF2A2733)),
    onSolid = Color(0xFFFFFFFF),
    collections = CollectionsOverLight,
    collectionsOnSolid = CollectionsOverDark,
)

internal val DarkAgendaColors = AgendaColors(
    deadline = AgendaRole(Color(0xFFFFB3AE), Color(0xFF7B1116), Color(0xFFFFE4E2)),
    scheduled = AgendaRole(Color(0xFFADC7FF), Color(0xFF0B4894), Color(0xFFDCE7FF)),
    repeat = AgendaRole(Color(0xFFF5BC63), Color(0xFF6D4500), Color(0xFFFFE9CB)),
    done = AgendaRole(Color(0xFF7ED8A0), Color(0xFF14512F), Color(0xFFCFF3DC)),
    cancelled = AgendaRole(Color(0xFFA29DAF), Color(0xFF3A3745), Color(0xFFDFDAEA)),
    onSolid = Color(0xFF1B1620),
    collections = CollectionsOverDark,
    collectionsOnSolid = CollectionsOverLight,
)

val LocalAgendaColors = staticCompositionLocalOf { LightAgendaColors }
