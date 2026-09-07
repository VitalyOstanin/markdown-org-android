package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That answering a reminder is not work done on the main thread.
 *
 * A receiver declared in the manifest runs on the main thread of the process,
 * and what the buttons of a reminder come to is not free: settings read off
 * the disk when the broadcast is what started the process, a lock held by
 * every replan in the application, and a handful of calls through the binder
 * behind it. `inTheBackground` is the one place that hands such work to the IO
 * pool while `goAsync` keeps the broadcast alive.
 *
 * Read off the source rather than measured, because the thread a call ends up
 * on is not something a JVM test can observe — a receiver only runs on a
 * device. Counted rather than parsed: what is being kept is that no receiver
 * is written without it.
 */
class ReceiverThreadTest {

    private val root = File(System.getProperty("repo.root") ?: "..")

    private val sources =
        root.resolve("app/src/main/kotlin/io/github/vitalyostanin/markdownorg")

    @Test
    fun everyReceiverHandsItsWorkToTheBackground() {
        val receivers = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().contains(": BroadcastReceiver()") }
            .toList()

        assertEquals(
            "the receivers of this application are two files; the list this test walks has " +
                "changed:\n" + receivers.joinToString("\n") { "  ${it.name}" },
            2,
            receivers.size,
        )

        val onTheMainThread = receivers
            .filterNot { file -> file.readText().contains("inTheBackground(") }
            .map { it.name }

        assertTrue(
            "these receivers answer on the main thread of the process — the work belongs in " +
                "inTheBackground, which holds the broadcast open while the IO pool carries " +
                "it:\n" + onTheMainThread.joinToString("\n") { "  $it" },
            onTheMainThread.isEmpty(),
        )
    }
}
