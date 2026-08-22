package io.github.vitalyostanin.markdownorg.core

import io.github.vitalyostanin.markdownorg.ui.day
import io.github.vitalyostanin.markdownorg.ui.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.TaskType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What the reader is told about and when, decided without a device.
 *
 * The alarms themselves are the platform's; what is worth asserting is the
 * plan handed to them — which occurrences it names, at what moment, and what
 * it leaves out.
 */
class ReminderPlanTest {

    @Test
    fun `nothing is planned while reminders are switched off`() {
        val plan = planReminders(
            days = listOf(day(date = TODAY, scheduledTimed = listOf(task(time = "15:00")))),
            choices = ReminderChoices(enabled = false),
            now = at("08:00"),
        )

        assertEquals(emptyList<PlannedReminder>(), plan)
    }

    @Test
    fun `a timed entry is announced its lead time before it starts`() {
        val plan = planReminders(
            days = listOf(day(date = TODAY, scheduledTimed = listOf(task(time = "15:00")))),
            choices = on(),
            now = at("08:00"),
        )

        val timed = plan.filterIsInstance<TimedReminder>()
        assertEquals(1, timed.size)
        assertEquals(at("14:45"), timed.single().at)
        assertEquals(at("15:00"), timed.single().starts)
    }

    /**
     * The moment itself is a switch, not the rule: a reader who wants it sets
     * a lead time of zero, and one who wants both turns this on.
     */
    @Test
    fun `the switch adds a second signal at the moment itself`() {
        val plan = planReminders(
            days = listOf(day(date = TODAY, scheduledTimed = listOf(task(time = "15:00")))),
            choices = on(alsoAtStart = true),
            now = at("08:00"),
        )

        assertEquals(
            listOf(at("14:45"), at("15:00")),
            plan.filterIsInstance<TimedReminder>().map { it.at },
        )
    }

    /**
     * A lead time of zero puts both signals on the same second, and two
     * notifications about one entry at one moment is one overwriting the
     * other.
     */
    @Test
    fun `a lead time of zero with the switch on is still one signal`() {
        val plan = planReminders(
            days = listOf(day(date = TODAY, scheduledTimed = listOf(task(time = "15:00")))),
            choices = on(leadMinutes = 0, alsoAtStart = true),
            now = at("08:00"),
        )

        assertEquals(listOf(at("15:00")), plan.filterIsInstance<TimedReminder>().map { it.at })
    }

    @Test
    fun `a signal whose moment has passed is not planned`() {
        val plan = planReminders(
            days = listOf(day(date = TODAY, scheduledTimed = listOf(task(time = "07:00")))),
            choices = on(),
            now = at("08:00"),
        )

        assertEquals(emptyList<TimedReminder>(), plan.filterIsInstance<TimedReminder>())
    }

    @Test
    fun `an entry beyond the horizon waits for a later plan`() {
        val plan = planReminders(
            days = listOf(
                day(date = TODAY, scheduledTimed = listOf(task(heading = "near", time = "15:00"))),
                day(
                    date = "2026-08-27",
                    scheduledTimed = listOf(task(heading = "far", time = "15:00")),
                ),
            ),
            choices = on(),
            now = at("08:00"),
        )

        assertEquals(
            listOf("near"),
            plan.filterIsInstance<TimedReminder>().map { it.entry.heading },
        )
    }

    @Test
    fun `a closed entry is not announced`() {
        val plan = planReminders(
            days = listOf(
                day(
                    date = TODAY,
                    scheduledTimed = listOf(
                        task(heading = "done", time = "15:00", taskType = TaskType.DONE),
                        task(heading = "cancelled", time = "16:00", taskType = TaskType.CANCELLED),
                        task(heading = "open", time = "17:00"),
                    ),
                ),
            ),
            choices = on(),
            now = at("08:00"),
        )

        assertEquals(
            listOf("open"),
            plan.filterIsInstance<TimedReminder>().map { it.entry.heading },
        )
    }

    /**
     * The day a row is drawn under, not the date the entry carries: a
     * repeating entry is announced for the occurrence on screen, and its own
     * date answers for the anchor.
     */
    @Test
    fun `an occurrence is announced for the day it is drawn under`() {
        val plan = planReminders(
            days = listOf(
                day(
                    date = "2026-08-23",
                    scheduledTimed = listOf(task(date = TODAY, time = "15:00", repeater = "+1w")),
                ),
            ),
            choices = on(),
            now = at("08:00"),
        )

        assertEquals(
            ZonedDateTime.of(LocalDate.parse("2026-08-23"), LocalTime.of(14, 45), ZONE),
            plan.filterIsInstance<TimedReminder>().single().at,
        )
    }

    @Test
    fun `the entry carries the collection it came from`() {
        val plan = planReminders(
            days = listOf(
                day(
                    date = TODAY,
                    scheduledTimed = listOf(task(root = "/work", file = "plan.md", time = "15:00")),
                ),
            ),
            choices = on(),
            now = at("08:00"),
        )

        val entry = plan.filterIsInstance<TimedReminder>().single().entry
        assertEquals("/work", entry.root)
        assertEquals("plan.md", entry.file)
    }

    @Test
    fun `the digest is today's while its hour is still ahead`() {
        val plan = planReminders(days = emptyList(), choices = on(), now = at("08:00"))

        val digest = plan.filterIsInstance<DigestReminder>().single()
        assertEquals(at("09:00"), digest.at)
        assertEquals(LocalDate.parse(TODAY), digest.day)
    }

    @Test
    fun `the digest is tomorrow's once its hour has passed`() {
        val plan = planReminders(days = emptyList(), choices = on(), now = at("09:30"))

        val digest = plan.filterIsInstance<DigestReminder>().single()
        assertEquals(
            ZonedDateTime.of(LocalDate.parse("2026-08-23"), LocalTime.of(9, 0), ZONE),
            digest.at,
        )
    }

    /** One, so that the digest that fires is the one that plans the next. */
    @Test
    fun `only the next digest is held`() {
        val plan = planReminders(days = emptyList(), choices = on(), now = at("08:00"))

        assertEquals(1, plan.filterIsInstance<DigestReminder>().size)
    }

    @Test
    fun `the plan is in the order it will fire`() {
        val plan = planReminders(
            days = listOf(
                day(
                    date = TODAY,
                    scheduledTimed = listOf(
                        task(heading = "evening", time = "18:00"),
                        task(heading = "morning", time = "08:30"),
                    ),
                ),
            ),
            choices = on(),
            now = at("08:00"),
        )

        assertTrue("$plan", plan.map { it.at } == plan.map { it.at }.sorted())
        assertEquals(at("08:15"), plan.first().at)
    }

    private fun on(
        leadMinutes: Int = DEFAULT_LEAD_MINUTES,
        alsoAtStart: Boolean = false,
    ): ReminderChoices = ReminderChoices(
        enabled = true,
        leadMinutes = leadMinutes,
        alsoAtStart = alsoAtStart,
    )

    private fun at(time: String): ZonedDateTime =
        ZonedDateTime.of(LocalDate.parse(TODAY), LocalTime.parse(time), ZONE)

    private companion object {
        const val TODAY = "2026-08-22"
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
