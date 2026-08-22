package io.github.vitalyostanin.markdownorg.core

import io.github.vitalyostanin.markdownorg.ui.agenda
import io.github.vitalyostanin.markdownorg.ui.day
import io.github.vitalyostanin.markdownorg.ui.task
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.Day
import uniffi.markdown_org_ffi.Scope
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Which days the plan is made from, and what is held when it cannot be made.
 *
 * The wording of a reminder and the moment it fires belong to
 * [ReminderPlanTest]; what is exercised here is the part around that decision
 * — the days asked of the agenda, the alarms handed to the platform, and what
 * happens to the ones already held when the notes cannot be read.
 */
class ReminderSchedulerTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private val now = ZonedDateTime.of(2026, 7, 28, 8, 0, 0, 0, zone)

    @Test
    fun `the horizon decides how many days are read`() = runTest {
        val agenda = StandingAgenda()

        scheduler(agenda).replan()

        // Today and the two days the horizon reaches, each asked for as
        // itself: a day left out is a day whose entries nothing announces.
        assertEquals(
            listOf("2026-07-28", "2026-07-29", "2026-07-30"),
            agenda.asked.map(LocalDate::toString),
        )
    }

    @Test
    fun `every day is dated against today rather than against itself`() = runTest {
        val agenda = StandingAgenda()

        scheduler(agenda).replan()

        // Otherwise what is late would be late against the day being read,
        // and tomorrow's digest would count today's arrears a second time.
        assertEquals(setOf(LocalDate.of(2026, 7, 28)), agenda.dated.toSet())
    }

    @Test
    fun `the plan is what the alarms are replaced with`() = runTest {
        val agenda = StandingAgenda(
            days = mapOf(
                "2026-07-28" to day(
                    date = "2026-07-28",
                    scheduledTimed = listOf(task(heading = "Stand-up", time = "10:00")),
                ),
            ),
        )
        val alarms = HeldAlarms()

        scheduler(agenda, alarms).replan()

        val timed = alarms.held.orEmpty().filterIsInstance<TimedReminder>()
        assertEquals(listOf("Stand-up"), timed.map { it.entry.heading })
        // A lead time of a quarter of an hour, as the choices below say.
        assertEquals("2026-07-28T09:45+03:00[Europe/Moscow]", timed.single().at.toString())
    }

    @Test
    fun `switching reminders off drops the alarms without reading the notes`() = runTest {
        val agenda = StandingAgenda()
        val alarms = HeldAlarms()

        scheduler(agenda, alarms, choices(enabled = false)).replan()

        assertEquals(1, alarms.cancelled)
        assertNull("nothing is held", alarms.held)
        assertTrue("the notes were walked for a plan nobody asked for", agenda.asked.isEmpty())
    }

    @Test
    fun `notes that cannot be read leave the alarms alone`() = runTest {
        val alarms = HeldAlarms()

        val outcome = scheduler(StandingAgenda(failure = IOException("gone")), alarms).replan()

        assertTrue(outcome.isFailure)
        // The reader keeps whatever was planned before the directory went
        // away, rather than losing the day's reminders to a passing failure.
        assertEquals(0, alarms.cancelled)
        assertNull(alarms.held)
    }

    @Test
    fun `a day is read as it stands when its digest is about to be raised`() = runTest {
        val agenda = StandingAgenda(
            days = mapOf(
                "2026-07-29" to day(
                    date = "2026-07-29",
                    scheduledNoTime = listOf(task(heading = "Renew the pass")),
                ),
            ),
        )

        val read = scheduler(agenda).read(LocalDate.of(2026, 7, 29))

        assertEquals("2026-07-29", read.getOrNull()?.date)
        assertEquals(
            listOf("Renew the pass"),
            read.getOrNull()?.scheduledNoTime?.map { it.heading },
        )
    }

    @Test
    fun `a digest for a day the agenda does not return is a failure and not a crash`() = runTest {
        val read = scheduler(StandingAgenda(days = emptyMap())).read(LocalDate.of(2026, 7, 29))

        assertTrue(read.isFailure)
        assertFalse("an empty agenda is not an answer about that day", read.isSuccess)
    }

    private fun scheduler(
        agenda: StandingAgenda,
        alarms: AlarmHolder = HeldAlarms(),
        choices: ReminderChoices = choices(),
    ) = ReminderScheduler(
        agenda = agenda,
        preferences = Chosen(choices),
        alarms = alarms,
        horizon = Duration.ofDays(2),
        clock = { now },
    )

    private fun choices(enabled: Boolean = true) = ReminderChoices(
        enabled = enabled,
        leadMinutes = 15,
        alsoAtStart = false,
    )

    /** The preferences as a value, which is all the scheduler reads of them. */
    private class Chosen(private val held: ReminderChoices) : ReminderPreferences {
        override var enabled: Boolean
            get() = held.enabled
            set(_) = Unit
        override var leadMinutes: Int
            get() = held.leadMinutes
            set(_) = Unit
        override var alsoAtStart: Boolean
            get() = held.alsoAtStart
            set(_) = Unit
        override var digestAt: LocalTime
            get() = held.digestAt
            set(_) = Unit
    }

    /**
     * An agenda that answers out of a table, and remembers what it was asked.
     *
     * A day with nothing in the table comes back empty rather than missing:
     * that is what the core answers for a day nothing is planned on, and a
     * scheduler that treated it as a failure would stop planning over the
     * first quiet day.
     */
    private class StandingAgenda(
        private val days: Map<String, Day> = emptyMap(),
        private val failure: Throwable? = null,
    ) : AgendaLoader {

        val asked = mutableListOf<LocalDate>()

        val dated = mutableListOf<LocalDate>()

        override suspend fun load(
            scope: Scope,
            today: LocalDate,
            shown: LocalDate?,
            zone: ZoneId,
            includeDone: Boolean,
            weekStart: DayOfWeek?,
        ): Result<AgendaResult> {
            val date = shown ?: today
            asked += date
            dated += today
            failure?.let { return Result.failure(it) }

            return Result.success(
                days[date.toString()]
                    ?.let { agenda(it) }
                    ?: AgendaResult(
                        days = emptyList(),
                        tasks = emptyList(),
                        stats = agenda().stats,
                    ),
            )
        }

        override suspend fun reread(root: String, file: String): Result<Unit> = Result.success(Unit)

        override suspend fun invalidate() = Unit
    }

    /** The platform's alarms, as a list. */
    private class HeldAlarms : AlarmHolder {

        var held: List<PlannedReminder>? = null

        var cancelled = 0

        override fun replace(plan: List<PlannedReminder>) {
            held = plan
        }

        override fun cancelAll() {
            cancelled += 1
        }
    }
}
