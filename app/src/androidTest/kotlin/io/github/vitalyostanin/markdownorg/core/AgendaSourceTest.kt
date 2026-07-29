package io.github.vitalyostanin.markdownorg.core

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.markdown_org_ffi.Scope

/**
 * The core itself, on the device.
 *
 * This is the only test that loads the native library, and it is the reason
 * it is instrumented rather than local: it proves the UniFFI bindings, JNA
 * and the packaged `.so` line up on a real ABI. The projections above it run
 * on the JVM without any of that.
 */
class AgendaSourceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val today = LocalDate.of(2026, 7, 28)
    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun readsAnAgendaOutOfADirectory() = runBlocking {
        write(
            """
            # Notes

            ## TODO [#A] Renew the certificate
            `DEADLINE: <2026-07-25>`

            ## TODO Daily standup
            `SCHEDULED: <2026-07-28 09:30 ++7d>`
            """,
        )

        val result = AgendaSource(NotesStore(folder.root))
            .load(Scope.DAY, today, zone)
            .getOrThrow()
        val day = result.days.single()

        assertEquals("2026-07-28", day.date)
        assertEquals(listOf("Renew the certificate"), day.overdue.map { it.heading })
        assertEquals(listOf("Daily standup"), day.scheduledTimed.map { it.heading })
        assertEquals("09:30", day.scheduledTimed.single().timestampTime)
        assertEquals("++7d", day.scheduledTimed.single().timestampRepeater)
        assertEquals("A", day.overdue.single().priority)
        assertEquals(-3L, day.overdue.single().daysOffset)
    }

    @Test
    fun aTimestampWithoutBackticksCarriesNoDate() = runBlocking {
        // The failure this guards against is silent: the task is still found,
        // but with no timestamp fields, so it lands in no day at all and the
        // agenda looks empty over a file that is not.
        write(
            """
            # Notes

            ## TODO Daily standup
            SCHEDULED: <2026-07-28 09:30>
            """,
        )

        val result = AgendaSource(NotesStore(folder.root))
            .load(Scope.TASKS, today, zone)
            .getOrThrow()
        val task = result.tasks.single()

        assertEquals("Daily standup", task.heading)
        assertEquals(null, task.timestampType)
        assertEquals(null, task.timestampDate)
    }

    @Test
    fun aMissingDirectoryFails() = runBlocking {
        val missing = File(folder.root, "nowhere")

        val result = AgendaSource(NotesStore(missing)).load(Scope.DAY, today, zone)

        assertTrue(result.isFailure)
    }

    private fun write(markdown: String) {
        File(folder.root, "notes.md").writeText(markdown.trimIndent() + "\n")
    }
}
