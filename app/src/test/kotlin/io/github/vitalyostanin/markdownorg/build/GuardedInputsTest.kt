package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That every file the guards read is one Gradle knows about.
 *
 * The tests in this package read the tree while they run, which the test task
 * cannot see: unless a path is declared an input, an edit to it leaves the task
 * up to date and the guard never runs again. The mismatch then surfaces on CI,
 * where there is no cache to hit — which is exactly how it surfaced once
 * already. This walks the other way round: it collects what the guards reach
 * for and fails when the build script has not been told about it.
 */
class GuardedInputsTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    private val buildScript = root.resolve("app/build.gradle.kts").readText()

    /**
     * A path taken from the repository root in any test of this package.
     * Anchored on the receiver: a test that resolves further from a directory
     * it already took from the root states the root-relative part once, and it
     * is that part which has to be an input.
     */
    private val resolved = Regex("""\broot\.resolve\("([^"$]+)"\)""")

    /** An entry of `guardedFiles` or `guardedTrees` in the build script. */
    private val declared = Regex("""^\s+"([^"]+)",$""", RegexOption.MULTILINE)

    /**
     * Sources of the app are inputs of the task by definition, and a path
     * built at runtime from a temporary directory is not part of the tree.
     */
    private val alreadyInputs = listOf("app/src/")

    @Test
    fun everyPathTheGuardsReadIsDeclaredAnInput() {
        val declaredPaths = declaredPaths()
        val missing = guardSources()
            .flatMap { file ->
                resolved.findAll(file.readText()).map { match -> file.name to match.groupValues[1] }
            }
            .filterNot { (_, path) -> alreadyInputs.any(path::startsWith) }
            .filterNot { (_, path) -> declaredPaths.any { path == it || path.startsWith("$it/") } }
            .distinct()

        assertTrue(
            "these paths are read while the tests run but are not inputs of the test task " +
                "(add them to guardedFiles or guardedTrees in app/build.gradle.kts):\n" +
                missing.joinToString("\n") { (test, path) -> "  $path — read by $test" },
            missing.isEmpty(),
        )
    }

    /**
     * A path that no longer exists guards nothing, and Gradle says nothing
     * about it: a missing input file is a legitimate state, not an error.
     */
    @Test
    fun everyDeclaredInputExists() {
        val gone = declaredPaths().filterNot { root.resolve(it).exists() }

        assertTrue(
            "declared as inputs of the test task but not in the tree:\n${gone.joinToString("\n")}",
            gone.isEmpty(),
        )
    }

    private fun declaredPaths(): List<String> {
        val lists = buildScript
            .substringAfter("val guardedFiles")
            .substringBefore("// What the APK says")

        return declared.findAll(lists).map { it.groupValues[1] }.toList()
    }

    private fun guardSources(): List<File> =
        root.resolve("app/src/test/kotlin/io/github/vitalyostanin/markdownorg/build")
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .sorted()
}
