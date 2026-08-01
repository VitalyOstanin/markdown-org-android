package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.SyncException
import java.io.IOException

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
            SyncException.Diverged("master") to R.string.sync_failed_diverged,
            SyncException.Dirty(1u) to R.string.sync_failed_dirty,
            SyncException.Repository("not a repository") to R.string.sync_failed_repository,
            SyncException.Address("http:// is not encrypted") to R.string.sync_failed_address,
            SyncException.Rejected("master", "fetch first") to R.string.sync_failed_rejected,
        )

        for ((error, expected) in wordings) {
            val message = error.toSyncMessage()
            assertEquals(expected, message.text)
            assertTrue(message.failed)
        }
    }

    @Test
    fun theDetailFromALibraryIsCarriedThrough() {
        // What the interface shows under the wording: libgit2's own words,
        // which are the only thing that says which host refused what. They
        // stay as they came — a library writes in the language it writes in.
        assertEquals(
            Detail.Verbatim("no route to host"),
            SyncException.Network("no route to host").toSyncMessage().detail,
        )
    }

    @Test
    fun theBranchThatDivergedIsWordedByTheApplication() {
        // The core reports the name, not a sentence about it: the sentence
        // is in the resources and exists in every language the app speaks.
        assertEquals(
            Detail.Worded(R.string.sync_diverged_detail, "master"),
            SyncException.Diverged("master").toSyncMessage().detail,
        )
    }

    @Test
    fun theFilesInTheWayAreCountedRatherThanSpelledOut() {
        // `3 file(s)` is a plural form nobody picked, and Russian has four of
        // them; the number decides the form where the forms are declared.
        assertEquals(
            Detail.Counted(R.plurals.sync_dirty_detail, 3),
            SyncException.Dirty(3u).toSyncMessage().detail,
        )
    }

    /**
     * libgit2 quotes the address it was given, and an address can hold a
     * token. The banner is on the agenda screen, where a passer-by sees it.
     */
    @Test
    fun credentialsInsideTheDetailAreNotPutOnScreen() {
        val message = SyncException.Network(
            "failed to connect to https://x:ghp_secret@git.example.org/notes.git",
        ).toSyncMessage()

        assertEquals(
            Detail.Verbatim("failed to connect to https://***@git.example.org/notes.git"),
            message.detail,
        )
    }

    @Test
    fun anUnexpectedFailureStillSaysSomething() {
        // Nothing but the core throws SyncException, yet an IO failure on the
        // way there must not leave the banner blank.
        val message = IOException("permission denied").toSyncMessage()

        assertEquals(R.string.sync_failed_repository, message.text)
        assertEquals(Detail.Verbatim("permission denied"), message.detail)
        assertTrue(message.failed)
    }

    @Test
    fun aFailedScanShowsWhatTheCoreSaid() {
        val message = IOException("invalid directory: /nowhere").toAgendaMessage()

        assertEquals(R.string.agenda_failed, message.text)
        assertEquals(Detail.Verbatim("invalid directory: /nowhere"), message.detail)
    }

    /**
     * A failure with no message of its own used to be reported by the name of
     * its Java class — `UnknownHostException` on the screen, in the middle of
     * a translated interface, as the whole explanation.
     */
    @Test
    fun aFailureWithNothingToSayDoesNotNameItsJavaClass() {
        val message = IOException().toAgendaMessage()

        assertEquals(Detail.Worded(R.string.agenda_failed_unknown), message.detail)
    }

    @Test
    fun theBranchTheServerRefusedIsWordedByTheApplication() {
        assertEquals(
            Detail.Worded(R.string.sync_rejected_detail, "master"),
            SyncException.Rejected("master", "fetch first").toSyncMessage().detail,
        )
    }

    @Test
    fun aRunThatSentSomethingSaysSoOverWhatItFetched() {
        val message = FakeSyncer.run(cloned = false, commits = 2u, pushed = 3u).toMessage()

        assertEquals(R.string.sync_pushed, message.text)
        assertEquals(Detail.Counted(R.plurals.sync_pushed_detail, 3), message.detail)
        assertFalse(message.failed)
    }

    /**
     * The fetch went through and the push did not. What the user needs to know
     * is the half that still needs them — the notes are already up to date, and
     * saying "notes updated" would bury the refusal.
     */
    @Test
    fun aRefusedPushIsReportedOverASuccessfulFetch() {
        val message = FakeSyncer.run(
            cloned = false,
            commits = 1u,
            pushFailure = SyncException.Rejected("master", "fetch first"),
        ).toMessage()

        assertEquals(R.string.sync_failed_rejected, message.text)
        assertTrue(message.failed)
    }

    @Test
    fun aRunWithNothingToSendReadsAsItAlwaysDid() {
        assertEquals(R.string.sync_cloned, FakeSyncer.run(cloned = true).toMessage().text)
        assertEquals(
            R.string.sync_updated,
            FakeSyncer.run(cloned = false, commits = 1u).toMessage().text,
        )
        assertEquals(
            R.string.sync_already_current,
            FakeSyncer.run(cloned = false).toMessage().text,
        )
    }
}
