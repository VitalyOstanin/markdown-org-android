package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the measurements of the interface live.
 *
 * Written against the sources rather than against a running screen, for the
 * same reason as [PipelineTest]: a padding of 11 dp next to one of 12 dp is
 * not a failure any test can observe, it is a drift that only shows when the
 * two are read side by side. Twenty-three distinct values had accumulated,
 * nine of them off the 4 dp grid, and rows that play the same part in
 * different layouts had stopped lining up.
 */
class SpacingScaleTest {

    private val root = File(System.getProperty("repo.root") ?: "..")

    private val ui = root.resolve("app/src/main/kotlin/io/github/vitalyostanin/markdownorg/ui")

    @Test
    fun everyMeasurementComesFromTheScale() {
        val offenders = sources()
            .filter { it.name != TOKENS }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) -> LITERAL.containsMatchIn(line) }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }

        assertTrue(
            "these carry a measurement of their own instead of one from $TOKENS:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun theSpacingScaleStaysOnTheFourPointGrid() {
        // Only the spacing: a hairline is one pixel of a line and the marker
        // over the axis is two, and rounding either to the grid would draw
        // something other than a line.
        val offGrid = LITERAL.findAll(spacingScale())
            .map { it.groupValues[1].toInt() }
            .filter { it % 4 != 0 }
            .toList()

        assertTrue("these are off the 4 dp grid: $offGrid", offGrid.isEmpty())
    }

    /** The body of `object Spacing`, which is where the grid applies. */
    private fun spacingScale(): String {
        val tokens = ui.resolve("theme/$TOKENS").readText()
        val start = tokens.indexOf("object Spacing {")
        val end = tokens.indexOf("\n}", start)
        assertTrue("no object Spacing in $TOKENS", start >= 0 && end > start)
        return tokens.substring(start, end)
    }

    private fun sources(): List<File> = ui.walkTopDown().filter { it.extension == "kt" }.toList()

    private companion object {
        const val TOKENS = "Dimens.kt"

        /** A bare `12.dp` written into a layout, as opposed to a named token. */
        val LITERAL = Regex("""\b(\d+)\.dp\b""")
    }
}
