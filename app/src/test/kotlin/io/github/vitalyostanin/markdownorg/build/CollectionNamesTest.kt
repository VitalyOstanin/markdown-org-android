package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the short names keep meaning the collection the settings screen edits.
 *
 * `notes`, `settings`, `editor` and `sync` are properties of the settings
 * screen, and each of them reads the collection being edited. A local of the
 * same name means a different collection — the one a task came from, or the
 * one a run is syncing — and the two are the whole of what several collections
 * changed: writing to the wrong one edits whatever note happens to sit at the
 * same relative path there.
 *
 * The compiler says nothing about the shadowing. Worse, it says nothing about
 * losing it either: pull a few lines of `runSync` out into a helper that does
 * not take the collection, and `settings` there quietly becomes the edited
 * collection's. Hence the guard: a local that means another collection carries
 * another name.
 *
 * Where to look is found rather than written down. A path in the test survives
 * the code moving out of the file it names and goes on passing over a file
 * that holds none of this; the property declarations are what the guard is
 * about, so they are what it looks for. The name alone would not do either:
 * `settings` in the agenda's model is the settings screen and in the reminders
 * it is their preferences, and neither stands for a collection.
 */
class CollectionNamesTest {

    private val root = File(System.getProperty("repo.root") ?: "..")

    private val screens =
        root.resolve("app/src/main/kotlin/io/github/vitalyostanin/markdownorg/ui")

    /** The short names, and what each of them is a collaborator of. */
    private val standFor = mapOf(
        "notes" to "NotesArea",
        "settings" to "SyncPreferences",
        "editor" to "NotesWriter",
        "sync" to "NotesSyncer",
    )

    @Test
    fun noLocalTakesTheNameOfTheEditedCollection() {
        val sources = screens.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val declared = standFor.mapValues { (name, type) ->
            sources.filter { file ->
                Regex("""val $name\s*:\s*$type\b""").containsMatchIn(file.readText())
            }
        }

        assertEquals(
            "each of these names is the edited collection's in exactly one file; the guard " +
                "below has nowhere to look otherwise:\n" +
                declared.entries.joinToString("\n") { (name, files) ->
                    "  $name: ${files.map { it.name }}"
                },
            standFor.keys,
            declared.filterValues { it.size == 1 }.keys,
        )

        val shadowed = declared.flatMap { (name, files) ->
            val property = Regex("""val $name\s*:\s*${standFor.getValue(name)}\b""")

            files.flatMap { file ->
                file.readLines()
                    .mapIndexed { index, line -> index + 1 to line }
                    .filter { (_, line) -> Regex("""\bval $name\b""").containsMatchIn(line) }
                    .filterNot { (_, line) -> property.containsMatchIn(line) }
                    .map { (at, line) -> "  ${file.name}:$at: ${line.trim()}" }
            }
        }

        assertTrue(
            "these bindings take a name that means the collection the settings screen edits, " +
                "while standing for another one — name them apart:\n" + shadowed.joinToString("\n"),
            shadowed.isEmpty(),
        )
    }
}
