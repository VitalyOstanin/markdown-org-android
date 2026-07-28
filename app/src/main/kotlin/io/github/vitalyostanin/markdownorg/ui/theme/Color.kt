package io.github.vitalyostanin.markdownorg.ui.theme

import androidx.compose.ui.graphics.Color

// The palette is drawn, not derived: dynamic colour would tie the application
// to whatever wallpaper the device has and collapse the roles onto one hue,
// which is exactly what the agenda needs to keep apart. The dark set is drawn
// separately rather than inverted — the tone lightens while the container
// darkens. Contrast of every pair was measured against WCAG 2.1: text pairs
// clear 4.5, the rails and glyphs clear 3.0.

internal val LightPrimary = Color(0xFF6A3FD1)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFE7DEFF)
internal val LightOnPrimaryContainer = Color(0xFF23005C)

internal val LightSecondary = Color(0xFF00757F)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFABEEF5)
internal val LightOnSecondaryContainer = Color(0xFF002022)

internal val LightTertiary = Color(0xFFB3305E)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFFFD9E3)
internal val LightOnTertiaryContainer = Color(0xFF3F001B)

internal val LightBackground = Color(0xFFFCFAFF)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightOnSurface = Color(0xFF16141F)
internal val LightOnSurfaceVariant = Color(0xFF4A4658)
internal val LightOutline = Color(0xFF6F6B7E)
internal val LightOutlineVariant = Color(0xFFE4E0EE)

internal val DarkPrimary = Color(0xFFCBBCFF)
internal val DarkOnPrimary = Color(0xFF2A0E6B)
internal val DarkPrimaryContainer = Color(0xFF4F2AAF)
internal val DarkOnPrimaryContainer = Color(0xFFEDE4FF)

internal val DarkSecondary = Color(0xFF66D9E3)
internal val DarkOnSecondary = Color(0xFF00363B)
internal val DarkSecondaryContainer = Color(0xFF005159)
internal val DarkOnSecondaryContainer = Color(0xFFC6F3F8)

internal val DarkTertiary = Color(0xFFFFB0C6)
internal val DarkOnTertiary = Color(0xFF5E0730)
internal val DarkTertiaryContainer = Color(0xFF8C1746)
internal val DarkOnTertiaryContainer = Color(0xFFFFE0E8)

internal val DarkBackground = Color(0xFF14121B)
internal val DarkSurface = Color(0xFF201E29)
internal val DarkOnSurface = Color(0xFFEFEBF7)
internal val DarkOnSurfaceVariant = Color(0xFFBDB8CB)
internal val DarkOutline = Color(0xFF918CA1)
internal val DarkOutlineVariant = Color(0xFF34313F)
