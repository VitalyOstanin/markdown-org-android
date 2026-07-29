package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.markdown_org_ffi.SyncOutcome

/**
 * How the view model orders work that lands on the same directory.
 *
 * The notes directory is a git working copy, and the requests that reach it
 * come from independent coroutines: a scan, a clone, an edit with its commit,
 * and the wipe that precedes a change of remote. What is asserted here is the
 * order of those, not what the core does with them — the core is stood in for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val notes = FakeNotesArea()
    private val loader = FakeAgendaLoader()
    private val settings = FakePreferences()
    private val writer = FakeWriter()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aSecondSyncDoesNotStartWhileTheFirstIsRunning() = runTest(dispatcher) {
        // The guard used to be a flag read before `launch` and written inside
        // it: on a dispatcher that does not run the body eagerly, both calls
        // pass the check and two clones run over one repository.
        val held = CompletableDeferred<Result<SyncOutcome>>()
        val syncer = FakeSyncer { held.await() }
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)

        model.syncNow()
        model.syncNow()
        advanceUntilIdle()

        assertEquals(1, syncer.requested.size)
        held.complete(Result.success(FakeSyncer.outcome()))
        advanceUntilIdle()
    }

    @Test
    fun changingTheRemoteWaitsForTheSyncInFlightBeforeWipingTheDirectory() = runTest(dispatcher) {
        val held = CompletableDeferred<Result<SyncOutcome>>()
        val syncer = FakeSyncer { held.await() }
        var syncingWhenWiped: Boolean? = null
        notes.onWipe = { syncingWhenWiped = syncer.running }
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)

        model.syncNow()
        advanceUntilIdle()
        assertTrue(syncer.running)

        model.saveSettings(OTHER_REMOTE, branch = "", token = "")
        advanceUntilIdle()

        // The wipe is `deleteRecursively` over the directory a clone is
        // writing into. It may only happen once that clone is out of the way.
        assertEquals(1, notes.wiped)
        assertFalse(syncingWhenWiped!!)
    }

    @Test
    fun changingTheRemoteMidSyncStillSyncsTheNewOne() = runTest(dispatcher) {
        // The old code ended `saveSettings` with the same `syncNow()` that
        // skips when a sync is running, so the directory was emptied and
        // nothing was ever fetched into it.
        val held = CompletableDeferred<Result<SyncOutcome>>()
        val syncer = FakeSyncer { url ->
            if (url == REMOTE) held.await() else Result.success(FakeSyncer.outcome())
        }
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)

        model.syncNow()
        advanceUntilIdle()
        model.saveSettings(OTHER_REMOTE, branch = "", token = "")
        advanceUntilIdle()

        assertEquals(listOf(REMOTE, OTHER_REMOTE), syncer.requested)
        assertFalse(model.syncState.value.running)
    }

    @Test
    fun anUnreadableCheckoutIsNotWiped() = runTest(dispatcher) {
        // `status()` failing means the directory holds a repository that could
        // not be read — not that there is none. Edits are committed locally
        // and never pushed, so wiping here destroys the only copy of them.
        val syncer = FakeSyncer()
        syncer.statusResult = Result.failure(IllegalStateException("broken .git/config"))
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(REMOTE, branch = "", token = "")
        advanceUntilIdle()

        assertEquals(0, notes.wiped)
        assertEquals(R.string.sync_status_unreadable, model.syncState.value.message?.text)
    }

    @Test
    fun anAddressWithAnUnsupportedSchemeIsRefusedBeforeAnythingIsTouched() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings("http://example.test/notes.git", branch = "", token = "")
        advanceUntilIdle()

        assertEquals(0, notes.wiped)
        assertNull(settings.remoteUrl)
        assertEquals(R.string.settings_url_scheme, model.syncState.value.message?.text)
    }

    @Test
    fun aSlowRefreshDoesNotOverwriteTheOneThatFollowedIt() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        // The load from `init` is still open; a second one starts on top of it.
        model.refresh()
        advanceUntilIdle()

        // The newer load answers first, the older one after it. Answering the
        // older one must not put its result on screen: the agenda it describes
        // is the one from before whatever triggered the refresh.
        loader.pending[1].complete(Result.failure(IllegalStateException("newer")))
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda()))
        advanceUntilIdle()

        assertTrue(model.state.value is AgendaUiState.Failed)
    }

    @Test
    fun aSyncResultDoesNotHideAFailedEdit() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        writer.outcome = Result.failure(IllegalStateException("the file moved on"))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.apply(task(), TaskAction.Complete)
        advanceUntilIdle()
        val failure = model.syncState.value.message
        assertEquals(MessageSource.EDIT, failure?.source)

        model.syncNow()
        advanceUntilIdle()

        // "Already up to date" over "the task could not be changed" would read
        // as the edit having landed.
        assertEquals(failure, model.syncState.value.message)
    }

    @Test
    fun aTaskWhoseFileNameIsNotUtf8IsRefusedWithoutReachingTheCore() = runTest(dispatcher) {
        // The path arrived with U+FFFD in place of bytes that are not UTF-8,
        // so it names nothing on disk. Going through the core would come back
        // as "file not found", which reads as the note having been deleted.
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.apply(task(file = "bad�name.md"), TaskAction.Complete)
        advanceUntilIdle()

        assertEquals(0, writer.calls)
        val message = model.syncState.value.message
        assertEquals(R.string.edit_failed_unnamed, message?.text)
        assertEquals(MessageSource.EDIT, message?.source)
    }

    private fun viewModel(syncer: FakeSyncer) = AgendaViewModel(
        notes = notes,
        agenda = loader,
        sync = syncer,
        settings = settings,
        editor = writer,
    )

    private companion object {
        const val REMOTE = "https://example.test/notes.git"
        const val OTHER_REMOTE = "https://example.test/other.git"
    }
}
