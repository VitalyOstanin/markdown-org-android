package io.github.vitalyostanin.markdownorg.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colours a collection is marked with, in both themes.
 *
 * The mark is a dot and nothing else, so a collection is only as legible as
 * its colour: a tone that disappears into the row it sits on, or that reads
 * as the tone of the collection beside it, leaves the row saying nothing
 * about where it came from.
 */
class AgendaPaletteTest {

    /** Both, since a tone can be right in one theme and wrong in the other. */
    private val themes = listOf("light" to LightAgendaColors, "dark" to DarkAgendaColors)

    @Test
    fun aCollectionKeepsItsOwnTone() {
        for ((theme, colors) in themes) {
            assertEquals(
                "$theme, over the background",
                colors.collections.size,
                colors.collections.distinct().size,
            )
            assertEquals(
                "$theme, over a dense fill",
                colors.collectionsOnSolid.size,
                colors.collectionsOnSolid.distinct().size,
            )
        }
    }

    @Test
    fun theTwoPalettesHoldTheSameNumberOfCollections() {
        // The mark takes a collection by its position in the set, and a set
        // longer than the palette wraps round it. Two lists of different
        // lengths would wrap at different points, so the same collection
        // would be one colour on a plain row and another on a filled one.
        for ((theme, colors) in themes) {
            assertEquals(theme, colors.collections.size, colors.collectionsOnSolid.size)
        }
    }

    @Test
    fun aMarkOnADenseFillStaysAgainstIt() {
        // The fill of an overdue row is the deadline tone at full strength,
        // which inverts the lightness of the surface: the palette of the
        // theme goes flat on it, and the other theme's is what reads. 3:1 is
        // what WCAG asks of a graphic this small.
        for ((theme, colors) in themes) {
            for ((index, tone) in colors.collectionsOnSolid.withIndex()) {
                val ratio = contrast(tone, colors.deadline.tone)

                assertTrue(
                    "$theme, collection $index: $ratio against the fill",
                    ratio >= MIN_CONTRAST,
                )
            }
        }
    }

    @Test
    fun aMarkOnAPlainRowStaysAgainstIt() {
        // A row that is not overdue is a tile in the container of its role,
        // and every role is a surface the mark has to be seen on.
        for ((theme, colors) in themes) {
            val surfaces = listOf(
                "surface" to if (colors === DarkAgendaColors) DarkSurface else LightSurface,
                "deadline" to colors.deadline.container,
                "scheduled" to colors.scheduled.container,
                "repeat" to colors.repeat.container,
                "done" to colors.done.container,
                "cancelled" to colors.cancelled.container,
            )

            for ((index, tone) in colors.collections.withIndex()) {
                for ((where, surface) in surfaces) {
                    val ratio = contrast(tone, surface)

                    assertTrue(
                        "$theme, collection $index on $where: $ratio",
                        ratio >= MIN_CONTRAST,
                    )
                }
            }
        }
    }

    /** The WCAG ratio between two opaque colours, the lighter one first or not. */
    private fun contrast(one: Color, other: Color): Double {
        val a = luminance(one)
        val b = luminance(other)

        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** Relative luminance, as WCAG defines it over linearised sRGB. */
    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(channel: Float): Double {
        val value = channel.toDouble()

        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private companion object {
        /** What WCAG asks of a graphic, rather than the 4.5 it asks of text. */
        const val MIN_CONTRAST = 3.0
    }
}
