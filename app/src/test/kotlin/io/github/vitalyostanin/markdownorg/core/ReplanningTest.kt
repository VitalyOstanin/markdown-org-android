package io.github.vitalyostanin.markdownorg.core

import io.github.vitalyostanin.markdownorg.ui.agenda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.Scope
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * That the plan is made by the scheduler handed over, not by one built here.
 *
 * A scheduler built where the plan is asked for reads the collections from
 * scratch: the index of notes it opens is the walk the agenda screen already
 * paid for, and a reader changing three settings in a row paid for it three
 * times over. The screens that have one pass theirs in.
 */
class ReplanningTest {

    @Test
    fun `the scheduler given is the one that plans`() {
        val alarms = CountedAlarms()

        Replanning.request(scheduler(alarms))

        assertEquals(1, alarms.await())
    }

    @Test
    fun `a plan asked for twice is still one scheduler's work`() {
        val alarms = CountedAlarms()
        val scheduler = scheduler(alarms)

        Replanning.request(scheduler)
        Replanning.request(scheduler)

        // The second request replaces the first rather than queueing behind
        // it, and whether the first got far enough to hold anything is a
        // matter of which of the two threads was quicker. What is asked here
        // is that a plan was made at all by the scheduler handed over.
        assertTrue("no plan was made by the scheduler given", alarms.await() >= 1)
    }

    @Test
    fun `a plan that could not be made reaches the caller who asked for it`() {
        val seen = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        Replanning.request(
            ReminderScheduler(
                agenda = UnreadableAgenda(),
                preferences = FixedChoices(),
                alarms = CountedAlarms(),
            ),
        ) { failure -> seen.set(failure) }

        val deadline = System.nanoTime() + WAIT.toNanos()
        while (System.nanoTime() < deadline && seen.get() == null) {
            Thread.sleep(SLICE)
        }

        // The screen that changed a setting is the one place where there is
        // somebody to tell; the log is what the rest of the callers get.
        assertTrue("nothing was said about the notes that could not be read", seen.get() != null)
    }

    private fun scheduler(alarms: AlarmHolder) = ReminderScheduler(
        agenda = EmptyAgenda(),
        preferences = FixedChoices(),
        alarms = alarms,
    )

    /** The alarms as a count, awaited because the plan is made off this thread. */
    private class CountedAlarms : AlarmHolder {

        private var replaced = 0

        @Synchronized
        override fun replace(plan: List<PlannedReminder>) {
            replaced += 1
        }

        override fun cancelAll() = Unit

        override fun holdAside(key: Int, reminder: PlannedReminder) = Unit

        /** How many plans arrived, waited for rather than read at once. */
        @Synchronized
        fun read(): Int = replaced

        fun await(): Int {
            val deadline = System.nanoTime() + WAIT.toNanos()
            while (System.nanoTime() < deadline && read() == 0) {
                Thread.sleep(SLICE)
            }

            return read()
        }
    }

    /** An agenda with nothing in it, which is a plan of no alarms at all. */
    private class EmptyAgenda : AgendaLoader {

        override suspend fun load(
            scope: Scope,
            today: LocalDate,
            shown: LocalDate?,
            zone: ZoneId,
            includeDone: Boolean,
            weekStart: DayOfWeek?,
        ): Result<AgendaResult> = Result.success(
            AgendaResult(days = emptyList(), tasks = emptyList(), stats = agenda().stats),
        )

        override suspend fun reread(root: String, file: String): Result<Unit> = Result.success(Unit)

        override suspend fun invalidate() = Unit
    }

    /** Notes that cannot be read, which is a plan that cannot be made. */
    private class UnreadableAgenda : AgendaLoader {

        override suspend fun load(
            scope: Scope,
            today: LocalDate,
            shown: LocalDate?,
            zone: ZoneId,
            includeDone: Boolean,
            weekStart: DayOfWeek?,
        ): Result<AgendaResult> = Result.failure(java.io.IOException("the directory is not there"))

        override suspend fun reread(root: String, file: String): Result<Unit> = Result.success(Unit)

        override suspend fun invalidate() = Unit
    }

    /** Reminders on, so that a plan is made rather than the alarms dropped. */
    private class FixedChoices : ReminderPreferences {
        override var enabled: Boolean = true
        override var leadMinutes: Int = 15
        override var alsoAtStart: Boolean = false
        override var digestAt: LocalTime = LocalTime.of(9, 0)
    }

    private companion object {

        val WAIT: java.time.Duration = java.time.Duration.ofSeconds(5)

        const val SLICE = 10L
    }
}
