package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.EditException
import java.io.IOException

/**
 * A failed edit has to say which kind of failure it was: the answer differs
 * per variant, and "stale" in particular is the one the user can act on by
 * syncing and looking again.
 */
class EditMessageTest {

    @Test
    fun eachFailureGetsItsOwnWording() {
        val wordings = listOf(
            EditException.Stale("notes.md:4 holds something else") to R.string.edit_failed_stale,
            EditException.NoPlanningLine("no DEADLINE:") to R.string.edit_failed_no_planning,
            EditException.Unsupported("hourly repeater") to R.string.edit_failed_unsupported,
            EditException.NotFound("gone.md") to R.string.edit_failed_missing,
        )

        for ((error, expected) in wordings) {
            val message = error.toEditMessage()
            assertEquals(expected, message.text)
            assertTrue(message.failed)
        }
    }

    @Test
    fun theRemainingVariantsShareTheGeneralWording() {
        // Nothing the user can do differently about a broken priority or a
        // write that failed, so both land on the same line.
        val message = EditException.InvalidPriority("65").toEditMessage()

        assertEquals(R.string.edit_failed, message.text)
    }

    @Test
    fun anUnexpectedFailureStillSaysSomething() {
        val message = IOException("permission denied").toEditMessage()

        assertEquals(R.string.edit_failed, message.text)
        assertTrue(message.failed)
    }

    /**
     * The core words its details as English sentences — `HEAD does not point
     * at a branch`, `line 9 is past the end of notes.md` — because that is
     * what a log entry is read as. Putting one under a Russian heading makes
     * a message that is half in each language and explains no more than the
     * heading alone; the wording per variant is what carries the meaning, and
     * the detail belongs in logcat.
     */
    @Test
    fun theDetailOfTheCoreDoesNotReachTheScreen() {
        val failures = listOf(
            EditException.Stale("notes.md:4 is not a heading"),
            EditException.NoPlanningLine("Renew certificate has no SCHEDULED:"),
            EditException.Unsupported("an hourly repeater cannot be advanced yet"),
            EditException.NotFound("gone.md"),
            EditException.InvalidPriority("65"),
            IOException("permission denied"),
        )

        for (error in failures) {
            assertEquals("detail of $error", null, error.toEditMessage().detail)
        }
    }
}
