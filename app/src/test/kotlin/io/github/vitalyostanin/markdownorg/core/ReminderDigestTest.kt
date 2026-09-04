package io.github.vitalyostanin.markdownorg.core

import io.github.vitalyostanin.markdownorg.ui.day
import io.github.vitalyostanin.markdownorg.ui.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the morning digest counts, and the morning it says nothing.
 *
 * The wording is the platform's -- plurals out of the resources, read on a
 * device -- but which buckets are counted and which day is not worth a
 * notification is decided here, and a day counted wrong reaches the reader as
 * a summary that disagrees with the screen.
 */
class ReminderDigestTest {

    /**
     * A digest of three zeroes is a notification saying there is nothing to
     * say, raised every morning. The alarm still fired and the next plan was
     * still made, which is all that firing was for.
     */
    @Test
    fun `a day holding nothing is not worth a notification`() {
        assertTrue(digestCounts(day()).silent)
    }

    /**
     * Entries held at an hour are left out: each of them is announced ahead
     * of its own hour, and counting them here would say everything twice.
     */
    @Test
    fun `a day holding only timed entries is not worth one either`() {
        val holding = day(scheduledTimed = listOf(task(time = "15:00")))

        assertTrue(digestCounts(holding).silent)
    }

    @Test
    fun `each kind is counted where the digest says it`() {
        val holding = day(
            overdue = listOf(task(heading = "Renew the certificate")),
            scheduledTimed = listOf(task(heading = "Team sync", time = "15:00")),
            scheduledNoTime = listOf(task(heading = "Review the notes"), task(heading = "Pay")),
            upcoming = listOf(task(heading = "Quarterly report")),
        )

        assertEquals(DigestCounts(dated = 2, deadlines = 1, overdue = 1), digestCounts(holding))
        assertFalse(digestCounts(holding).silent)
    }

    /**
     * The order is the agenda's, which is the order the screen shows them in:
     * a drawer that lists them otherwise reads as a different day.
     */
    @Test
    fun `the expanded digest names the headings in the agenda's order`() {
        val holding = day(
            overdue = listOf(task(heading = "Renew the certificate")),
            scheduledNoTime = listOf(task(heading = "Review the notes")),
            upcoming = listOf(task(heading = "Quarterly report")),
        )

        assertEquals(
            listOf("Review the notes", "Quarterly report", "Renew the certificate"),
            digestHeadings(holding),
        )
    }

    /**
     * A few rather than all of them: the platform truncates a long text, and
     * the counts already say how much the day holds.
     */
    @Test
    fun `a day of many entries names only the first few`() {
        val holding = day(
            scheduledNoTime = (1..20).map { number -> task(heading = "Entry $number") },
        )

        assertEquals(
            listOf("Entry 1", "Entry 2", "Entry 3", "Entry 4", "Entry 5"),
            digestHeadings(holding),
        )
    }
}
