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
)

internal val DarkAgendaColors = AgendaColors(
    deadline = AgendaRole(Color(0xFFFFB3AE), Color(0xFF7B1116), Color(0xFFFFE4E2)),
    scheduled = AgendaRole(Color(0xFFADC7FF), Color(0xFF0B4894), Color(0xFFDCE7FF)),
    repeat = AgendaRole(Color(0xFFF5BC63), Color(0xFF6D4500), Color(0xFFFFE9CB)),
    done = AgendaRole(Color(0xFF7ED8A0), Color(0xFF14512F), Color(0xFFCFF3DC)),
    cancelled = AgendaRole(Color(0xFFA29DAF), Color(0xFF3A3745), Color(0xFFDFDAEA)),
    onSolid = Color(0xFF1B1620),
)

val LocalAgendaColors = staticCompositionLocalOf { LightAgendaColors }
