package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the short names keep meaning the collection the settings screen edits.
 *
 * `notes`, `settings`, `editor` and `sync` are properties of the view model,
 * and each of them reads the collection being edited. A local of the same name
 * means a different collection — the one a task came from, or the one a run is
 * syncing — and the two are the whole of what several collections changed:
 * writing to the wrong one edits whatever note happens to sit at the same
 * relative path there.
 *
 * The compiler says nothing about the shadowing. Worse, it says nothing about
 * losing it either: pull a few lines of `runSync` out into a helper that does
 * not take the collection, and `settings` there quietly becomes the edited
 * collection's. Hence the guard: a local that means another collection carries
 * another name.
 */
class CollectionNamesTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    /** What the view model's own properties are called. */
    private val reserved = listOf("notes", "settings", "editor", "sync")

    /** A local binding of one of those names, wherever it stands. */
    private val local = Regex("""^\s+val (${reserved.joinToString("|")})\s*[:=]""")

    @Test
    fun noLocalTakesTheNameOfTheEditedCollection() {
        val model = root.resolve(
            "app/src/main/kotlin/io/github/vitalyostanin/markdownorg/ui/AgendaViewModel.kt",
        )
        val offenders = model.readLines()
            .mapIndexed { index, line -> index + 1 to line }
            .filter { (_, line) -> local.containsMatchIn(line) }

        assertTrue(
            "these locals take a name that means the collection the settings screen edits, " +
                "while standing for another one — name them apart:\n" +
                offenders.joinToString("\n") { (line, text) ->
                    "  AgendaViewModel.kt:$line: ${text.trim()}"
                },
            offenders.isEmpty(),
        )
    }
}
