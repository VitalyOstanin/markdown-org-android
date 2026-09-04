package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * That a day and an hour are asked for in one dialog each, not in copies.
 *
 * The screens that ask are several — the sheet over a task in the notes, the
 * screen that writes a new one, the hour the daily digest is raised at — and
 * the answer they ask for is the same. A second dialog beside the first drifts
 * from it without anything failing: the digest's own copy confirmed with the
 * platform's "OK" where the shared one says "Set", and offered the dial on a
 * window the shared one had already decided was too short for it.
 *
 * The guard is on the pickers themselves rather than on the dialogs: a
 * composable of Material's is what a copy starts from, and `DateChoice` and
 * `TimeChoice` of `ui/TaskDates.kt` are the only places that are meant to hold
 * one.
 */
class OnePickerTest {
    private val root = File(System.getProperty("repo.root") ?: "..")

    @Test
    fun eachPickerIsStoodUpInOnePlace() {
        val sources = root
            .resolve("app/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }

        val holders = sources
            .filter { file -> PICKER_STATE.containsMatchIn(file.readText()) }
            .map(File::getName)
            .toSortedSet()

        assertEquals(
            "a day and an hour are asked for by one dialog each — put the " +
                "question to DateChoice or TimeChoice of ui/TaskDates.kt " +
                "rather than standing a picker up beside them",
            setOf("TaskDates.kt"),
            holders.toSet(),
        )
    }

    private companion object {
        /**
         * The state a picker of Material's is stood up with, remembered for
         * the composition or built by hand — the calendar needs the second
         * form to be told which weekday a week begins on.
         */
        val PICKER_STATE = Regex("""(remember)?(Date|Time)PickerState\(""")
    }
}
