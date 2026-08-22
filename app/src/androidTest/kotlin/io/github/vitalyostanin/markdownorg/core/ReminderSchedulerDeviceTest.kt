package io.github.vitalyostanin.markdownorg.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The plan made after a restart is the plan the running application had.
 *
 * A reminder is scheduled from a walk of the notes, and the two occasions that
 * walk differ in everything except the notes: one runs inside the application
 * with an index already warm, the other inside a broadcast receiver in a
 * process that has just started. What must not differ is the answer — a phone
 * restarted overnight is a phone whose morning was planned by a receiver, and
 * nothing else would say so until the reminder failed to arrive.
 *
 * Instrumented because it walks the notes through the core: the scheduler over
 * a stand-in loader is [ReminderSchedulerTest], and this is the same decision
 * over real files.
 */
class ReminderSchedulerDeviceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Moscow")

    private val now = ZonedDateTime.of(2026, 7, 28, 8, 0, 0, 0, zone)

    @Test
    fun aPlanMadeFromColdMatchesTheOneMadeWhileRunning() = runBlocking {
        write(
            """
            # Notes

            ## TODO Daily standup
            `SCHEDULED: <2026-07-28 09:30>`

            ## TODO Renew the certificate
            `DEADLINE: <2026-07-29>`

            ## TODO Board meeting
            `SCHEDULED: <2026-07-29 14:00>`
            """,
        )

        // The application's own: one source, walked twice over the run.
        val running = HeldPlan()
        val warm = scheduler(AgendaSource(areasOf(NotesStore(folder.root))), running)
        warm.replan()
        warm.replan()

        // The receiver's: a source that has never read anything.
        val restarted = HeldPlan()
        scheduler(AgendaSource(areasOf(NotesStore(folder.root))), restarted).replan()

        assertEquals(describe(running), describe(restarted))
        assertTrue("nothing was planned at all", running.held.orEmpty().isNotEmpty())
    }

    /**
     * The three counts a digest is made of come out of one day of the agenda,
     * and each of them is a different bucket of it.
     *
     * A deadline falling on the day being read is not in `upcoming`: the core
     * files it as the day's own entry, and only the days before it — inside
     * the warning window — carry it as one coming up. The digest counts both
     * either way, which is why this is asserted rather than assumed.
     */
    @Test
    fun theDayADigestNamesIsReadThroughTheCore() = runBlocking {
        write(
            """
            # Notes

            ## TODO Renew the certificate
            `DEADLINE: <2026-07-30>`

            ## TODO Pay the invoice
            `DEADLINE: <2026-07-28>`

            ## TODO Water the plants
            `SCHEDULED: <2026-07-28>`
            """,
        )

        val day = scheduler(AgendaSource(areasOf(NotesStore(folder.root))), HeldPlan())
            .read(now.toLocalDate())
            .getOrThrow()

        assertEquals("2026-07-28", day.date)
        assertEquals(
            listOf("Pay the invoice", "Water the plants"),
            day.scheduledNoTime.map { it.heading }.sorted(),
        )
        assertEquals(listOf("Renew the certificate"), day.upcoming.map { it.heading })
    }

    /** What a plan is, for a comparison that says what differs when it does. */
    private fun describe(alarms: HeldPlan): List<String> = alarms.held.orEmpty().map { reminder ->
        when (reminder) {
            is TimedReminder -> "${reminder.at} ${reminder.entry.heading}"
            is DigestReminder -> "${reminder.at} digest of ${reminder.day}"
        }
    }

    private fun scheduler(agenda: AgendaLoader, alarms: AlarmHolder) = ReminderScheduler(
        agenda = agenda,
        preferences = Chosen,
        alarms = alarms,
        clock = { now },
    )

    private fun write(markdown: String) {
        File(folder.root, "notes.md").writeText(markdown.trimIndent() + "\n")
    }

    /** The working copies as the source reads them, as [AgendaSourceTest] builds them. */
    private fun areasOf(vararg areas: NotesArea): NotesAreas = object : NotesAreas {

        override val areas: List<NotesArea> = areas.toList()

        override suspend fun <T> exclusive(block: suspend (List<NotesArea>) -> T): T =
            holdingAll(this.areas) { block(this.areas) }
    }

    /** Reminders on, at the defaults, with nothing to write them back to. */
    private object Chosen : ReminderPreferences {
        override var enabled: Boolean
            get() = true
            set(_) = Unit
        override var leadMinutes: Int
            get() = DEFAULT_LEAD_MINUTES
            set(_) = Unit
        override var alsoAtStart: Boolean
            get() = false
            set(_) = Unit
        override var digestAt: LocalTime
            get() = DEFAULT_DIGEST_TIME
            set(_) = Unit
    }

    private class HeldPlan : AlarmHolder {

        var held: List<PlannedReminder>? = null

        override fun replace(plan: List<PlannedReminder>) {
            held = plan
        }

        override fun cancelAll() = Unit
    }
}
