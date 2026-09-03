package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * What the button on a reminder came to, and what is said about it.
 *
 * The notification is taken down the moment the button is pressed — a
 * reminder left standing reads as a press that did nothing — so from there on
 * the only sign of what happened is what is raised in its place. Every way of
 * failing has to have one, or an entry that is still open looks exactly like
 * one that was closed.
 */
class ReminderClosingTest {

    @Test
    fun `notes that could not be read are their own outcome`() {
        val outcome = closingOutcome(
            read = Result.failure<Unit>(IOException("the directory is not there")),
            found = false,
            inACollection = false,
            written = null,
        )

        assertEquals(ClosingOutcome.UNREADABLE, outcome)
    }

    @Test
    fun `an entry no longer where it was announced is told apart from a failure`() {
        val outcome = closingOutcome(
            read = Result.success(Unit),
            found = false,
            inACollection = true,
            written = null,
        )

        assertEquals(ClosingOutcome.GONE, outcome)
    }

    @Test
    fun `a collection removed since the reminder was planned is named as such`() {
        val outcome = closingOutcome(
            read = Result.success(Unit),
            found = true,
            inACollection = false,
            written = null,
        )

        assertEquals(ClosingOutcome.COLLECTION_GONE, outcome)
    }

    @Test
    fun `an entry that was found and not written is a failure of its own`() {
        val outcome = closingOutcome(
            read = Result.success(Unit),
            found = true,
            inACollection = true,
            written = Result.failure<Unit>(IOException("read-only")),
        )

        assertEquals(ClosingOutcome.NOT_WRITTEN, outcome)
    }

    @Test
    fun `the entry written is the one outcome nothing is raised about`() {
        val outcome = closingOutcome(
            read = Result.success(Unit),
            found = true,
            inACollection = true,
            written = Result.success(Unit),
        )

        assertEquals(ClosingOutcome.CLOSED, outcome)
        assertNull("a reminder answered says nothing further", outcome.said)
    }

    @Test
    fun `every way of failing has something of its own to say`() {
        val failures = ClosingOutcome.entries.filter { it != ClosingOutcome.CLOSED }

        failures.forEach { outcome ->
            assertNotNull("$outcome says nothing", outcome.said)
        }
        assertEquals(
            "two outcomes share a wording, so the reader cannot tell them apart",
            failures.size,
            failures.mapNotNull { it.said }.toSet().size,
        )
    }
}
