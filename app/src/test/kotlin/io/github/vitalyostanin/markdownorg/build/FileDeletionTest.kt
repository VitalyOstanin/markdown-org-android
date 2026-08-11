package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the application removes nothing it did not write itself.
 *
 * The notes directory may be any directory of the device — the setting takes a
 * path, and with access to the shared storage that path can be somebody's
 * documents. Files there were not put there by this application and are not
 * its to remove: what stands in the way of an operation is a reason to refuse
 * the operation and say so, never a reason to empty the directory. The rule
 * held once and was then broken by a single call, so it is guarded here rather
 * than left to a reading of the code.
 */
class FileDeletionTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    /**
     * A file the application made for itself, in its own storage, is its own
     * to remove. Named per file rather than per call: a list of allowed lines
     * would go stale on the first edit above them.
     */
    private val ownFiles = setOf("CrashLog.kt")

    /** Anything that takes a file off the disk, whole tree or single entry. */
    private val removal = Regex("""\.delete(Recursively|OnExit)?\s*\(""")

    @Test
    fun nothingRemovesAFileTheApplicationDidNotWrite() {
        val offenders = sources()
            .filterNot { it.name in ownFiles }
            .flatMap { file -> file.code().filter { (_, line) -> removal.containsMatchIn(line) } }

        assertTrue(
            "the notes directory can be any directory of the device, so a file there is " +
                "not this application's to remove — refuse the operation and say why:\n" +
                offenders.joinToString("\n") { (where, line) -> "  $where: ${line.trim()}" },
            offenders.isEmpty(),
        )
    }

    /** Every Kotlin source of the application itself, tests aside. */
    private fun sources(): List<File> = root.resolve("app/src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sorted()
        .toList()

    /**
     * The lines that run, paired with where they are. Comments are dropped:
     * this file's subject is discussed in several of them, and a rule that
     * fires on prose about itself would be unwritable.
     */
    private fun File.code(): List<Pair<String, String>> = readLines()
        .mapIndexed { index, line -> "${this.name}:${index + 1}" to line }
        .filterNot { (_, line) -> line.trimStart().startsWith("//") }
        .filterNot { (_, line) -> line.trimStart().startsWith("*") }
        .filterNot { (_, line) -> line.trimStart().startsWith("/*") }
}
