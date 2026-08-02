package io.github.vitalyostanin.markdownorg.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.markdown_org_ffi.Scope
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

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

        val result = AgendaSource(areasOf(NotesStore(folder.root)))
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

        val result = AgendaSource(areasOf(NotesStore(folder.root)))
            .load(Scope.TASKS, today, zone)
            .getOrThrow()
        val task = result.tasks.single()

        assertEquals("Daily standup", task.heading)
        assertEquals(null, task.timestampType)
        assertEquals(null, task.timestampDate)
    }

    @Test
    fun anEditedNoteIsPickedUpWhenItIsNamed() = runBlocking {
        // What an edit does: one file rewritten, and the source told which.
        // This is the whole point of holding the notes — the agenda that
        // follows costs that file rather than the collection.
        write(SCHEDULED_TODAY)
        val source = AgendaSource(areasOf(NotesStore(folder.root)))
        assertEquals(1, source.load(Scope.DAY, today, zone).getOrThrow().days.single().count())

        write(SCHEDULED_TODAY.replace("2026-07-28", "2026-08-28"))
        source.reread(folder.root.canonicalPath, "notes.md").getOrThrow()

        assertEquals(0, source.load(Scope.DAY, today, zone).getOrThrow().days.single().count())
    }

    @Test
    fun anEditNobodyNamedIsNotNoticed() = runBlocking {
        // The contract of what is held, stated as a test: this is a cache with
        // an explicit invalidation, not a watcher. A caller reading it as one
        // would be surprised by a stale agenda later, in a case harder to see.
        write(SCHEDULED_TODAY)
        val source = AgendaSource(areasOf(NotesStore(folder.root)))
        source.load(Scope.DAY, today, zone).getOrThrow()

        write(SCHEDULED_TODAY.replace("2026-07-28", "2026-08-28"))

        assertEquals(1, source.load(Scope.DAY, today, zone).getOrThrow().days.single().count())
    }

    @Test
    fun invalidatingSendsTheNextAgendaBackToTheDirectory() = runBlocking {
        // What follows a fetch: files changed, and which ones is not known.
        write(SCHEDULED_TODAY)
        val source = AgendaSource(areasOf(NotesStore(folder.root)))
        source.load(Scope.DAY, today, zone).getOrThrow()

        write(SCHEDULED_TODAY.replace("2026-07-28", "2026-08-28"))
        source.invalidate()

        assertEquals(0, source.load(Scope.DAY, today, zone).getOrThrow().days.single().count())
    }

    @Test
    fun theNotesOfTheDirectoryInUseAreTheOnesRead() = runBlocking {
        // The directory changes under a running application, and the notes held
        // from the previous one describe files this one does not have.
        write(SCHEDULED_TODAY)
        val store = NotesStore(folder.root)
        val source = AgendaSource(areasOf(store))
        assertEquals(1, source.load(Scope.DAY, today, zone).getOrThrow().days.single().count())

        val other = folder.newFolder("elsewhere")
        File(other, "other.md")
            .writeText(SCHEDULED_TODAY.replace("standup", "review").trimIndent() + "\n")
        store.useDirectory(other).getOrThrow()

        val day = source.load(Scope.DAY, today, zone).getOrThrow().days.single()
        assertEquals(listOf("Daily review"), day.scheduledTimed.map { it.heading })
    }

    @Test
    fun aMissingDirectoryFails() = runBlocking {
        val missing = File(folder.root, "nowhere")

        val result = AgendaSource(areasOf(NotesStore(missing))).load(Scope.DAY, today, zone)

        assertTrue(result.isFailure)
    }

    /**
     * Two collections at once: the agenda over them is one agenda, and an edit
     * still reaches the directory the task came from.
     *
     * Both hold a file of the same name, which is the case a walk over one
     * directory could never produce and the one a re-read by path alone gets
     * wrong.
     */
    @Test
    fun theAgendaCoversEveryCollectionAndEachTaskNamesItsOwn() = runBlocking {
        val work = folder.newFolder("work")
        val home = folder.newFolder("home")
        File(work, "notes.md").writeText(SCHEDULED_TODAY.trimIndent() + "\n")
        File(home, "notes.md")
            .writeText(SCHEDULED_TODAY.replace("standup", "review").trimIndent() + "\n")

        val source = AgendaSource(areasOf(NotesStore(work), NotesStore(home)))
        val day = source.load(Scope.DAY, today, zone).getOrThrow().days.single()

        assertEquals(
            listOf("Daily review", "Daily standup"),
            day.scheduledTimed.map { it.heading }.sorted(),
        )
        assertEquals(
            listOf(home.canonicalPath, work.canonicalPath).sorted(),
            day.scheduledTimed.mapNotNull { it.root }.sorted(),
        )
    }

    @Test
    fun aReReadTouchesOnlyTheCollectionItNames() = runBlocking {
        val work = folder.newFolder("work")
        val home = folder.newFolder("home")
        File(work, "notes.md").writeText(SCHEDULED_TODAY.trimIndent() + "\n")
        File(home, "notes.md")
            .writeText(SCHEDULED_TODAY.replace("standup", "review").trimIndent() + "\n")
        val source = AgendaSource(areasOf(NotesStore(work), NotesStore(home)))
        source.load(Scope.DAY, today, zone).getOrThrow()

        // The note in `work` moves out of today; the one in `home`, which has
        // the same relative path, must stay where it is.
        File(work, "notes.md")
            .writeText(SCHEDULED_TODAY.replace("2026-07-28", "2026-08-28").trimIndent() + "\n")
        source.reread(work.canonicalPath, "notes.md").getOrThrow()

        val day = source.load(Scope.DAY, today, zone).getOrThrow().days.single()
        assertEquals(listOf("Daily review"), day.scheduledTimed.map { it.heading })
    }

    private fun write(markdown: String) {
        File(folder.root, "notes.md").writeText(markdown.trimIndent() + "\n")
    }

    /**
     * The working copies as the source reads them.
     *
     * The lock is taken over all of them in the order given, which is what the
     * application does: the walk behind one agenda holds every directory it
     * reads.
     */
    private fun areasOf(vararg areas: NotesArea): NotesAreas = object : NotesAreas {

        override val areas: List<NotesArea> = areas.toList()

        override suspend fun <T> exclusive(block: suspend () -> T): T =
            holdingAll(this.areas, block)
    }

    /** Everything a day agenda puts on screen, however it is bucketed. */
    private fun uniffi.markdown_org_ffi.Day.count(): Int =
        overdue.size + scheduledTimed.size + scheduledNoTime.size + upcoming.size

    private companion object {
        /** One task, on the day these tests call today. */
        const val SCHEDULED_TODAY = """
            # Notes

            ## TODO Daily standup
            `SCHEDULED: <2026-07-28 09:30>`
        """
    }
}
