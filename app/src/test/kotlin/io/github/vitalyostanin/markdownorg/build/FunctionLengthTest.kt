package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That no function of the application grows past a screenful and a half.
 *
 * A composable is a tree written as nested calls, so length accumulates
 * quietly: the settings form reached 450 lines one control at a time, and the
 * activity's `onCreate` held three screens' worth of wiring. What such a
 * function costs is not the reading of it — it is that nothing inside can be
 * tested, named or moved on its own, and every edit to one control reopens the
 * whole of it.
 *
 * The limit is 120 lines from the declaration to the closing brace, the
 * parameter list included. It is above the 100 the review named, because a
 * composable spends a dozen lines on parameters before its body starts; the
 * three functions the limit was written for stood at 450, 208 and 143.
 */
class FunctionLengthTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    /** A declaration, whatever modifiers and annotations stand before it. */
    private val declaration = Regex(
        """^(\s*)(?:@\w+(?:\([^)]*\))?\s+)*""" +
            """(?:(?:private|internal|public|protected|override|suspend|inline|open|""" +
            """operator|tailrec|external|abstract)\s+)*fun\s""",
    )

    @Test
    fun noFunctionRunsPastAScreenfulAndAHalf() {
        val long = sources().flatMap { file -> file.longFunctions() }

        assertTrue(
            "these functions are longer than $LIMIT lines — a body nothing can be named, " +
                "tested or moved out of:\n" +
                long.joinToString("\n") { (where, length) -> "  $where — $length lines" },
            long.isEmpty(),
        )
    }

    /**
     * Measured by the indentation the declaration stands at: the body ends at
     * the first line that is a closing brace at exactly that depth, since
     * everything inside a function is indented further. A declaration with no
     * such line is an expression body, which is short by construction.
     */
    private fun File.longFunctions(): List<Pair<String, Int>> {
        val lines = readLines()

        return lines.indices.mapNotNull { start ->
            val indent = declaration.find(lines[start])
                ?.groupValues
                ?.get(1)
                ?: return@mapNotNull null
            val end = (start + 1..lines.lastIndex).firstOrNull { lines[it] == "$indent}" }
                ?: return@mapNotNull null
            val length = end - start + 1

            ("$name:${start + 1}" to length).takeIf { length > LIMIT }
        }
    }

    private fun sources(): List<File> = root.resolve("app/src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sorted()
        .toList()

    private companion object {
        const val LIMIT = 120
    }
}
