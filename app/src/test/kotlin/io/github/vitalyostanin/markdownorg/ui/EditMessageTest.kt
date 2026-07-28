package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.EditException

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
        // write that failed, so both land on the same line with the core's
        // own detail under it.
        val message = EditException.InvalidPriority("65").toEditMessage()

        assertEquals(R.string.edit_failed, message.text)
        assertEquals("65", message.detail)
    }

    @Test
    fun anUnexpectedFailureStillSaysSomething() {
        val message = IOException("permission denied").toEditMessage()

        assertEquals(R.string.edit_failed, message.text)
        assertEquals("permission denied", message.detail)
        assertTrue(message.failed)
    }
}
