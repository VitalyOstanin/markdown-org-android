package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That a screen does not plan the reminders out of an index of its own.
 *
 * Planning walks the notes, and the walk is what the held index exists to
 * avoid: `ReminderScheduler.of` opens the collections again — seconds of it on
 * a phone with a thousand notes — while the agenda already holds them. The
 * screens ask the model they are drawn from, which plans by the scheduler
 * built over that index; only the parts with no screen at all — the
 * receivers, the service — build one of their own.
 *
 * Read off the source, because what is being kept is the wiring: no test can
 * observe an index that was opened and thrown away.
 */
class ReminderPlanningTest {

    private val root = File(System.getProperty("repo.root") ?: "..")

    private val screens =
        root.resolve("app/src/main/kotlin/io/github/vitalyostanin/markdownorg/ui")

    @Test
    fun noScreenBuildsAPlannerOfItsOwn() {
        val building = screens.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().contains("ReminderScheduler.of(") }
            .map { it.name }
            .toList()

        assertTrue(
            "these screens reach for the scheduler themselves, which opens the collections a " +
                "second time — the scheduler over the held index is passed in instead:\n" +
                building.joinToString("\n") { "  $it" },
            building.isEmpty(),
        )
    }

    @Test
    fun noScreenAsksForAPlanWithoutOne() {
        val asking = screens.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().contains("Replanning.request(context") }
            .map { it.name }
            .toList()

        assertTrue(
            "these screens ask for a plan by context, and a plan asked for by context builds " +
                "its own index of the notes:\n" + asking.joinToString("\n") { "  $it" },
            asking.isEmpty(),
        )
    }
}
