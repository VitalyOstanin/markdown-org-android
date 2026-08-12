package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That no function takes more arguments than a reader can keep in order.
 *
 * The screens took 28, 25 and 19, and passed them down one another positionally
 * — eleven arguments in a row, three of them `() -> Unit`. Two swapped there
 * compile, run, and are found by whoever presses the wrong-looking button. The
 * answer is the one the form values already showed: a handful of objects that
 * say what they are, rather than a list of loose fields.
 *
 * The limit is 12, which is where the widest screen stands after the grouping;
 * the guard is here to keep the next parameter from being appended to a list
 * instead of joining an object.
 */
class ParameterListTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    /** A declaration whose parameters go one to a line, as ktlint has them. */
    private val declaration = Regex(
        """^(\s*)(?:@\w+(?:\([^)]*\))?\s+)*""" +
            """(?:(?:private|internal|public|protected|override|suspend|inline|open|""" +
            """operator|tailrec|external|abstract)\s+)*fun\s+[A-Za-z_]\w*\s*\($""",
    )

    @Test
    fun noFunctionTakesMoreThanAHandfulOfArguments() {
        val wide = sources().flatMap { file -> file.wideDeclarations() }

        assertTrue(
            "these take more than $LIMIT arguments — group the ones that belong together " +
                "into an object, the way SyncFormValues does:\n" +
                wide.joinToString("\n") { (where, count) -> "  $where — $count arguments" },
            wide.isEmpty(),
        )
    }

    /**
     * Counted by indentation: a parameter of a declaration written this way
     * stands one level in from it, and the list ends at the closing bracket
     * back at the declaration's own level. Documentation and blank lines are
     * skipped; a one-line declaration takes few enough arguments to fit a line
     * and is not looked at.
     */
    private fun File.wideDeclarations(): List<Pair<String, Int>> {
        val lines = readLines()

        return lines.indices.mapNotNull { start ->
            val indent = declaration.find(lines[start])?.groupValues?.get(1)
                ?: return@mapNotNull null
            val body = (start + 1..lines.lastIndex).takeWhile { !lines[it].startsWith("$indent)") }
            val count = body.count { line ->
                val text = lines[line]
                val own = text.length - text.trimStart().length

                own == indent.length + 4 && !text.trimStart().startsWith("*") &&
                    !text.trimStart().startsWith("/") && text.isNotBlank()
            }

            ("$name:${start + 1}" to count).takeIf { count > LIMIT }
        }
    }

    private fun sources(): List<File> = root.resolve("app/src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sorted()
        .toList()

    private companion object {
        const val LIMIT = 12
    }
}
