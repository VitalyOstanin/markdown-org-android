package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.SyncException

/**
 * Every failure the core raises has to land on wording of its own — that is
 * the whole reason the error is split into variants rather than carrying a
 * string.
 */
class SyncMessageTest {

    @Test
    fun eachFailureGetsItsOwnWording() {
        val wordings = listOf(
            SyncException.Auth("401") to R.string.sync_failed_auth,
            SyncException.Network("no route to host") to R.string.sync_failed_network,
            SyncException.Diverged("2 commits ahead") to R.string.sync_failed_diverged,
            SyncException.Dirty("notes/today.md") to R.string.sync_failed_dirty,
            SyncException.Repository("not a repository") to R.string.sync_failed_repository,
        )

        for ((error, expected) in wordings) {
            val message = error.toSyncMessage()
            assertEquals(expected, message.text)
            assertTrue(message.failed)
        }
    }

    @Test
    fun theDetailFromTheCoreIsCarriedThrough() {
        // What the interface shows under the wording: the core's own words,
        // which are the only thing that says which file or which host.
        assertEquals("notes/today.md", SyncException.Dirty("notes/today.md").toSyncMessage().detail)
    }

    @Test
    fun anUnexpectedFailureStillSaysSomething() {
        // Nothing but the core throws SyncException, yet an IO failure on the
        // way there must not leave the banner blank.
        val message = IOException("permission denied").toSyncMessage()

        assertEquals(R.string.sync_failed_repository, message.text)
        assertEquals("permission denied", message.detail)
        assertTrue(message.failed)
    }
}
