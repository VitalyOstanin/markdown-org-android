package io.github.vitalyostanin.markdownorg.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * That a write and the commit that follows it answer separately.
 *
 * The write goes through the native library, which is not here; the commit is
 * behind [Committer] precisely so it can fail on its own in a test. What is
 * pinned down is the shape of the answer: a failed write is a failure, a
 * failed commit over a written file is not, and both are visible from the
 * outside rather than merged into one verdict.
 */
class NotesEditorTest {

    private val notes = RecordingArea()

    private var failCommit: Throwable? = null

    private var commits = mutableListOf<String>()

    private val editor = NotesEditor(notes, FakeSettings()) { _, message, _ ->
        failCommit?.let { throw it }
        commits += message
        true
    }

    @Test
    fun aCommitThatFailedOverAWrittenFileIsNotAFailedEdit() = runBlocking {
        failCommit = IllegalStateException("index.lock exists")

        val report = editor.write { "Mark \"Task\" as done" }

        assertTrue("the edit was reported as failed", report.isSuccess)
        assertFalse(report.getOrThrow().committed)
        assertEquals("index.lock exists", report.getOrThrow().commitFailure?.message)
    }

    @Test
    fun aWriteThatFailedIsAFailureAndNothingIsCommitted() = runBlocking {
        val report = editor.write { throw IllegalStateException("the file has changed") }

        assertTrue(report.isFailure)
        assertEquals(emptyList<String>(), commits)
    }

    @Test
    fun anEditThatWentThroughReportsTheCommitItMade() = runBlocking {
        val report = editor.write { "Set the priority of \"Task\" to A" }

        assertTrue(report.getOrThrow().committed)
        assertNull(report.getOrThrow().commitFailure)
        assertEquals(listOf("Set the priority of \"Task\" to A"), commits)
    }

    @Test
    fun theLeftoversOfAnEarlierEditAreCommittedOnTheirOwn() = runBlocking {
        // What the sync calls before fetching: the core refuses to
        // fast-forward a checkout an earlier edit left dirty.
        val committed = editor.commitPending()

        assertEquals(true, committed.getOrThrow())
        assertEquals(1, commits.size)
    }

    @Test
    fun aCommitOfTheLeftoversThatFailedIsReportedRatherThanThrown() = runBlocking {
        failCommit = IllegalStateException("index.lock exists")

        val committed = editor.commitPending()

        assertTrue(committed.isFailure)
    }

    /** The lock and the directory, without a device to hold either. */
    private class RecordingArea : NotesArea {

        override val root: File = File("/notes")

        override suspend fun <T> exclusive(block: suspend () -> T): T = block()

        override suspend fun ensureSeeded(
            today: LocalDate,
            wording: SampleWording,
            synced: () -> Boolean,
        ) = Result.success(Unit)

        /** Nothing here moves the notes; the editor never asks for it. */
        override suspend fun useDirectory(directory: File) = Result.success(Unit)

        /** Nor makes them: the directory of these tests is a temporary folder. */
        override suspend fun prepareDirectory() = Result.success(Unit)
    }

    private class FakeSettings(
        override var remoteUrl: String? = null,
        override var branch: String? = null,
        override var token: String? = null,
        override var authorName: String = "markdown-org",
        override var authorEmail: String = "markdown-org@localhost",
        override var lastSyncedAt: Long = 0,
        override var storesLocally: Boolean = false,
        override var sshKey: String? = null,
        override var sshPassphrase: String? = null,
        override var sshPublicKey: String? = null,
        override var knownHost: String? = null,
    ) : SyncPreferences
}
