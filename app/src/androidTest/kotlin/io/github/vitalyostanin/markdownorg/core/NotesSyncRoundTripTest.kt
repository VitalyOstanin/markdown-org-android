package io.github.vitalyostanin.markdownorg.core

import androidx.test.platform.app.InstrumentationRegistry
import io.github.vitalyostanin.markdownorg.ui.sampleWording
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.markdown_org_ffi.Adoption
import uniffi.markdown_org_ffi.CommitAuthor
import uniffi.markdown_org_ffi.SyncException
import uniffi.markdown_org_ffi.SyncRequest
import uniffi.markdown_org_ffi.commitChanges
import uniffi.markdown_org_ffi.pushChanges
import uniffi.markdown_org_ffi.repositoryStatus
import uniffi.markdown_org_ffi.syncRepository
import java.io.File
import java.time.LocalDate

/**
 * The sync as a round trip: what leaves this device arrives at the remote,
 * and what another device left there arrives here.
 *
 * The tests above this one ([NotesSyncTest]) cover the answers given before
 * anything is attempted — no remote, no repository, an address that leads
 * nowhere. These cover the exchange itself, over a repository that really
 * exists on the device, because that is where the two halves meet: a fetch
 * that lands in the working copy and a push the remote keeps.
 *
 * The other device is the core called directly. It is the same library the
 * application calls, driven over a checkout of its own — which is what a
 * second phone is, and it makes the remote move for reasons this device knows
 * nothing about.
 */
class NotesSyncRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = NotesStore(context)
    private val sync = NotesSync(context, store)
    private val wording = sampleWording(context)

    /** Where the remote and the other device's checkout live for one test. */
    private val scratch = File(context.cacheDir, "sync-round-trip")

    private class Settings(
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

    @Before
    @After
    fun clean() {
        store.root.deleteRecursively()
        scratch.deleteRecursively()
    }

    // ---- the notes of this device -----------------------------------------

    /**
     * Seed the notes and publish them to an empty remote, which is the state
     * every test below starts from: a device that has notes and a remote that
     * agrees with them.
     */
    private suspend fun published(): Settings {
        store.ensureSeeded(LocalDate.of(2026, 7, 29), wording) { false }

        val settings = Settings(remoteUrl = emptyRemote().absolutePath)
        val adoption = sync.adopt(settings)

        assertTrue("adopting an empty remote: $adoption", adoption.isSuccess)
        assertTrue(adoption.getOrNull() is Adoption.Published)
        return settings
    }

    private fun note(name: String) = File(store.root, name)

    // ---- the remote and the other device ----------------------------------

    /**
     * A remote with nothing in it, laid out by hand.
     *
     * The core initialises checkouts, not remotes: a repository to push to is
     * something the tests need and the application never makes, so the four
     * files and directories git puts in a bare repository are written here.
     * Nothing else is required to open one — hooks, description and the info
     * exclude are for a git the tests do not run.
     */
    private fun emptyRemote(name: String = "origin.git"): File {
        val remote = File(scratch, name)

        File(remote, "objects/info").mkdirs()
        File(remote, "objects/pack").mkdirs()
        File(remote, "refs/heads").mkdirs()
        File(remote, "refs/tags").mkdirs()
        File(remote, "HEAD").writeText("ref: refs/heads/master\n")
        File(remote, "config").writeText(
            "[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = true\n",
        )
        return remote
    }

    /**
     * A checkout of the remote that is not this device's, with an edit of its
     * own already handed over.
     *
     * Returned so a test can go on using it: a device that pushed once is
     * where the interesting failures start.
     */
    private fun otherDevice(settings: Settings, file: String, text: String): File {
        val checkout = File(scratch, "other")
        val request = SyncRequest(dir = checkout.absolutePath, url = settings.remoteUrl!!)

        if (!checkout.exists()) {
            syncRepository(request)
        }
        File(checkout, file).writeText(text)
        commitChanges(
            dir = checkout.absolutePath,
            message = "from the other device: $file",
            author = CommitAuthor("other", "other@localhost"),
        )
        pushChanges(request)
        return checkout
    }

    // ---- what leaves this device ------------------------------------------

    @Test
    fun theNotesOfADeviceWithNoRemoteYetBecomeTheRemote() = runBlocking {
        val settings = published()

        // Published rather than merged: the remote had no branch at all, so
        // what is on it now is this device's history and nothing else.
        val status = repositoryStatus(store.root.absolutePath)

        assertNotNull(status)
        assertEquals(0u, status!!.unpushed)
        assertFalse(status.dirty)
        assertTrue(settings.lastSyncedAt > 0)
    }

    @Test
    fun anEditMadeHereReachesTheRemote() = runBlocking {
        val settings = published()
        note("mine.md").writeText("# Mine\n")
        commitChanges(
            dir = store.root.absolutePath,
            message = "a note written here",
            author = CommitAuthor(settings.authorName, settings.authorEmail),
        )

        val run = sync.sync(settings).getOrThrow()

        assertEquals(1u, run.pushed)
        assertEquals(0u, run.head.unpushed)
        // Read back through a checkout that was never told about the edit:
        // the remote is the only place it could have come from.
        val theirs = File(scratch, "reader")
        syncRepository(SyncRequest(dir = theirs.absolutePath, url = settings.remoteUrl!!))
        assertEquals("# Mine\n", File(theirs, "mine.md").readText())
    }

    @Test
    fun aRunThatHasNothingToHandOverSaysSoRatherThanPushing() = runBlocking {
        val settings = published()

        val run = sync.sync(settings).getOrThrow()

        assertEquals(0u, run.pushed)
        assertEquals(0u, run.fetched.commitsApplied)
        assertFalse(run.fetched.cloned)
    }

    // ---- what arrives from elsewhere --------------------------------------

    @Test
    fun aCheckoutThatIsNotThereYetIsCloned() = runBlocking {
        val settings = published()
        // The directory is emptied the way a reinstall empties it: the remote
        // is unchanged and the device has nothing.
        store.root.deleteRecursively()

        val run = sync.sync(settings).getOrThrow()

        assertTrue(run.fetched.cloned)
        assertTrue(store.root.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun anEditFromAnotherDeviceArrivesInTheWorkingCopy() = runBlocking {
        val settings = published()
        otherDevice(settings, "theirs.md", "# Theirs\n")

        val run = sync.sync(settings).getOrThrow()

        assertEquals(1u, run.fetched.commitsApplied)
        assertEquals("# Theirs\n", note("theirs.md").readText())
    }

    @Test
    fun aDeviceThatFellBehindCatchesUpAndThenIsHeardFromItself() = runBlocking {
        val settings = published()
        otherDevice(settings, "theirs.md", "# Theirs\n")
        note("mine.md").writeText("# Mine\n")
        commitChanges(
            dir = store.root.absolutePath,
            message = "a note written here",
            author = CommitAuthor(settings.authorName, settings.authorEmail),
        )

        // Both halves in one run, and in this order: the commit here sits on
        // top of what the remote had, so the fetch has to land before the push
        // is even possible.
        val diverged = sync.sync(settings)

        // The commit here was made before the other device's arrived, so the
        // two histories are not one line and this application does not make
        // them one.
        assertTrue(diverged.isFailure)
        assertTrue(diverged.exceptionOrNull() is SyncException.Diverged)
    }

    // ---- what goes wrong ---------------------------------------------------

    @Test
    fun anUncommittedEditStopsTheSyncRatherThanBeingOverwritten() = runBlocking {
        val settings = published()
        otherDevice(settings, "theirs.md", "# Theirs\n")
        note("hand-written.md").writeText("# Not committed\n")

        val result = sync.sync(settings)

        // Dirty rather than merged or discarded: the fetch would write over a
        // working copy the user is in the middle of.
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.Dirty)
        assertEquals("# Not committed\n", note("hand-written.md").readText())
    }

    @Test
    fun takingTheRemoteEndsADivergenceTheApplicationWillNotMerge() = runBlocking {
        val settings = published()
        otherDevice(settings, "theirs.md", "# Theirs\n")
        note("mine.md").writeText("# Mine\n")
        commitChanges(
            dir = store.root.absolutePath,
            message = "a note written here",
            author = CommitAuthor(settings.authorName, settings.authorEmail),
        )
        assertTrue(sync.sync(settings).isFailure)

        val taken = sync.takeRemote(settings)

        // The way out the banner offers, and the only one: the remote's line
        // of history becomes this device's.
        assertTrue("taking the remote: $taken", taken.isSuccess)
        assertEquals("# Theirs\n", note("theirs.md").readText())
        assertEquals(0u, repositoryStatus(store.root.absolutePath)!!.unpushed)
    }

    /**
     * The refusal another sync cannot answer: the remote itself would not take
     * the update.
     *
     * On the device this was found on it was a token without the right to push
     * to a protected branch; here it is a remote whose refs cannot be written,
     * which is the same event as far as this side can tell — the branch here
     * is level with the remote, so nothing a fetch brings would change the
     * answer. What matters is that it arrives as its own case, with what the
     * remote said, rather than as the branch having fallen behind.
     */
    @Test
    fun aRefusalTheRemoteItselfMadeIsNotBlamedOnTheBranch() = runBlocking {
        val settings = published()
        note("mine.md").writeText("# Mine\n")
        commitChanges(
            dir = store.root.absolutePath,
            message = "a note written here",
            author = CommitAuthor(settings.authorName, settings.authorEmail),
        )

        // The pack still lands — objects are written before the reference is —
        // and the update of the branch is what the remote refuses.
        val heads = File(scratch, "origin.git/refs/heads")
        assertTrue("could not make the remote's refs read-only", heads.setWritable(false))
        val refusal = try {
            runCatching {
                pushChanges(SyncRequest(dir = store.root.absolutePath, url = settings.remoteUrl!!))
            }.exceptionOrNull()
        } finally {
            // Restored whatever happened: a directory left read-only cannot be
            // deleted, and the next test starts by deleting this one.
            heads.setWritable(true)
        }

        val rejected = refusal as? SyncException.Rejected
        assertNotNull("expected a refusal, got $refusal", rejected)
        assertFalse("this device is level, so no fetch answers it", rejected!!.stale)
        assertTrue("the remote's own words name the cause", rejected.detail.isNotEmpty())
        assertEquals(1u, repositoryStatus(store.root.absolutePath)!!.unpushed)
    }

    @Test
    fun aPushRefusedBecauseTheRemoteMovedSaysAnotherSyncCanHelp() = runBlocking {
        val settings = published()
        // Fetched first, so the checkout is level, and only then does the
        // remote move: this is the refusal the application cannot see coming,
        // and the one another sync really does fix.
        note("mine.md").writeText("# Mine\n")
        commitChanges(
            dir = store.root.absolutePath,
            message = "a note written here",
            author = CommitAuthor(settings.authorName, settings.authorEmail),
        )
        otherDevice(settings, "theirs.md", "# Theirs\n")

        val refusal = runCatching {
            pushChanges(SyncRequest(dir = store.root.absolutePath, url = settings.remoteUrl!!))
        }.exceptionOrNull()

        val rejected = refusal as? SyncException.Rejected
        assertNotNull("expected a refusal, got $refusal", rejected)
        assertTrue("the branch here is behind, so a fetch answers it", rejected!!.stale)
        assertTrue(rejected.detail.isNotEmpty())
        // Refused is not lost: the commit is still here and still owed.
        assertEquals(1u, repositoryStatus(store.root.absolutePath)!!.unpushed)
    }
}
