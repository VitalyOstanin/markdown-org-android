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
     *
     * A declaration carrying no body at all — a method of an interface — is
     * left out, because the first closing brace at its depth is the one that
     * ends the interface: the first method of a growing interface was
     * otherwise reported as being as long as everything declared after it.
     */
    private fun File.longFunctions(): List<Pair<String, Int>> {
        val lines = readLines()

        return lines.indices.mapNotNull { start ->
            val indent = declaration.find(lines[start])
                ?.groupValues
                ?.get(1)
                ?: return@mapNotNull null
            val signature = lines.signatureEnd(start) ?: return@mapNotNull null
            val end = when {
                lines.opensBlock(signature) ->
                    (start + 1..lines.lastIndex).firstOrNull { lines[it] == "$indent}" }

                lines.opensExpression(signature) -> lines.expressionEnd(signature)

                else -> null
            } ?: return@mapNotNull null
            val length = end - start + 1

            ("$name:${start + 1}" to length).takeIf { length > LIMIT }
        }
    }

    /**
     * Where the parameter list of the declaration at [start] closes, which may
     * be several lines down.
     */
    private fun List<String>.signatureEnd(start: Int): Int? {
        var depth = 0

        for (index in start..lastIndex) {
            val line = this[index]
            depth += line.count { it == '(' } - line.count { it == ')' }
            if (depth <= 0) return index
        }

        return null
    }

    /** What stands after the parameter list: a body opens with `{` or with `=`. */
    private fun List<String>.tailOf(signature: Int): String {
        val line = this[signature]
        return line.substring(line.lastIndexOf(')') + 1)
    }

    private fun List<String>.opensBlock(signature: Int) = tailOf(signature).trimEnd().endsWith("{")

    /**
     * Looking past the closing bracket rather than at the whole line keeps a
     * default value (`a: Int = 1`) from reading as an expression body.
     */
    private fun List<String>.opensExpression(signature: Int) = tailOf(signature).contains('=')

    /**
     * Where the expression a function is written as comes to an end: the line
     * on which every bracket opened after the `=` has closed again.
     *
     * Counted by brackets rather than by looking for a closing brace at the
     * declaration's own depth. An expression body closes no brace of its own,
     * so that search runs on to whatever brace comes next in the file, and a
     * two-line function reads as being as long as everything declared after
     * it.
     */
    private fun List<String>.expressionEnd(signature: Int): Int? {
        var depth = 0

        for (index in signature..lastIndex) {
            val line = if (index == signature) this[index].substringAfter('=') else this[index]
            depth += line.count { it in "({[" } - line.count { it in ")}]" }
            if (depth <= 0) return index
        }

        return null
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
