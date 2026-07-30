package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.EditReport
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
    private val ui = FakeUiPreferences()
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
    fun anAgendaOnScreenStaysThereWhileTheNextOneIsBuilt() = runTest(dispatcher) {
        // Going back through Loading takes the header with it — the layout
        // switch included — and drops the scroll position of a list that is
        // about to come back with one line changed.
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()
        val shown = model.state.value as AgendaUiState.Ready

        model.refresh()
        advanceUntilIdle()

        val during = model.state.value as AgendaUiState.Ready
        assertEquals(shown.sections, during.sections)
        assertTrue(during.refreshing)
    }

    @Test
    fun theFirstAgendaOfAllIsStillLoading() = runTest(dispatcher) {
        // Nothing to keep on screen before the first scan answers, so that one
        // does get the spinner.
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        assertEquals(AgendaUiState.Loading, model.state.value)
    }

    @Test
    fun anAgendaThatArrivedIsNoLongerMarkedAsRefreshing() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        model.refresh()
        advanceUntilIdle()
        loader.pending[1].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        assertFalse((model.state.value as AgendaUiState.Ready).refreshing)
    }

    @Test
    fun theAgendaOpensInTheLayoutItWasClosedIn() = runTest(dispatcher) {
        // The switch sits in the header as a control meant to be used often,
        // and the choice used to live in the view model alone: it survived a
        // rotation but not the process being killed.
        ui.layout = AgendaLayout.LIST
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        assertEquals(AgendaLayout.LIST, model.layout.value)
    }

    @Test
    fun switchingTheLayoutIsRemembered() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.setLayout(AgendaLayout.LIST)

        assertEquals(AgendaLayout.LIST, ui.layout)
    }

    @Test
    fun aFailedEditIsReportedApartFromTheSync() = runTest(dispatcher) {
        // One slot for both meant "the task could not be changed" sat under
        // the header until the next sync, in place of the line describing the
        // checkout, and a sync result could read as the edit having landed.
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        writer.outcome = Result.failure(IllegalStateException("the file moved on"))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.apply(task(), TaskAction.Complete)
        advanceUntilIdle()
        val failure = model.editIssue.value
        assertEquals(R.string.edit_failed, failure?.text)
        assertNull(model.syncState.value.message)

        model.syncNow()
        advanceUntilIdle()

        assertEquals(failure, model.editIssue.value)
        assertEquals(R.string.sync_cloned, model.syncState.value.message?.text)
    }

    @Test
    fun anEditThatWasWrittenButNotCommittedSaysSoAndRebuildsTheAgenda() = runTest(dispatcher) {
        // The file has already changed. Reporting this as a failed edit sends
        // the user to tap again, and the second attempt comes back as "the
        // file has changed" — two answers to one tap, neither of them true.
        val syncer = FakeSyncer()
        writer.outcome = Result.success(
            EditReport(committed = false, commitFailure = IllegalStateException("index.lock")),
        )
        val model = viewModel(syncer)
        advanceUntilIdle()
        val scansBefore = loader.pending.size

        model.apply(task(), TaskAction.Complete)
        advanceUntilIdle()

        assertEquals(R.string.edit_not_committed, model.editIssue.value?.text)
        assertTrue("the agenda was not rebuilt", loader.pending.size > scansBefore)
    }

    @Test
    fun aSyncCommitsWhatAnEarlierEditCouldNot() = runTest(dispatcher) {
        // An uncommitted edit leaves the checkout dirty, and the core refuses
        // to fast-forward a dirty checkout — so the sync would fail over a
        // commit that never happened until something else made it.
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.syncNow()
        advanceUntilIdle()

        assertEquals(1, writer.pendingCommits)
    }

    @Test
    fun aSeedThatCouldNotBeWrittenShowsAsAFailedAgendaRatherThanKillingTheProcess() =
        runTest(dispatcher) {
            // The seeding used to throw straight out of the coroutine, which
            // took the process with it — over the same kind of failure the
            // scan next to it puts on the screen.
            val syncer = FakeSyncer()
            notes.seedResult = Result.failure(IllegalStateException("read-only directory"))
            val model = viewModel(syncer)
            advanceUntilIdle()

            assertTrue(model.state.value is AgendaUiState.Failed)
            assertEquals(0, loader.pending.size)
        }

    @Test
    fun aWipeThatOnlyHalfHappenedIsReportedInsteadOfBeingClonedInto() = runTest(dispatcher) {
        // The clone that follows refuses a directory that is not empty and
        // reports it as a repository failure, which says nothing about the
        // directory it is actually about.
        val syncer = FakeSyncer()
        syncer.statusResult = Result.success(FakeSyncer.status(OTHER_REMOTE))
        notes.resetResult = Result.failure(IllegalStateException("could not be emptied"))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "main", token = "")
        advanceUntilIdle()

        assertEquals(R.string.notes_reset_failed, model.syncState.value.message?.text)
        assertTrue(
            "a clone was started over a directory that is not empty",
            syncer.requested.isEmpty(),
        )
    }

    @Test
    fun anEditFailureIsDroppedOnceItHasBeenShown() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        writer.outcome = Result.failure(IllegalStateException("the file moved on"))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.apply(task(), TaskAction.Complete)
        advanceUntilIdle()
        model.editIssueShown()

        assertNull(model.editIssue.value)
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
        assertEquals(R.string.edit_failed_unnamed, model.editIssue.value?.text)
        assertNull(model.syncState.value.message)
    }

    @Test
    fun aSuccessfulSyncShowsTheStateItAlreadyReturnedRatherThanReadingItAgain() =
        runTest(dispatcher) {
            // The sync answers with the state of the checkout it just wrote.
            // Reading it again walks the whole working copy — every file, and
            // the untracked ones on top — for an answer already in hand.
            val head = FakeSyncer.status(REMOTE)
            val syncer = FakeSyncer { Result.success(FakeSyncer.outcome(cloned = true)) }
            settings.remoteUrl = REMOTE
            val model = viewModel(syncer)
            advanceUntilIdle()
            val readsBefore = syncer.statusReads

            model.syncNow()
            advanceUntilIdle()

            assertEquals(readsBefore, syncer.statusReads)
            assertEquals(head, model.syncState.value.repository)
        }

    @Test
    fun aFailedSyncStillReadsTheCheckoutBecauseItHasNoStateToReport() = runTest(dispatcher) {
        val syncer = FakeSyncer { Result.failure(IllegalStateException("offline")) }
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()
        val readsBefore = syncer.statusReads

        model.syncNow()
        advanceUntilIdle()

        assertTrue(syncer.statusReads > readsBefore)
    }

    @Test
    fun pointingTheApplicationAtAnotherRemoteDoesNotSendItTheOldToken() = runTest(dispatcher) {
        // The saved token belongs to the host it was issued for. Leaving it in
        // place sends it to whoever the new URL points at, and there is no way
        // in the form to clear it: an empty field means "keep what is stored".
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        settings.token = "the-old-token"
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = OTHER_REMOTE, branch = "main", token = "")
        advanceUntilIdle()

        assertNull(settings.token)
    }

    @Test
    fun theSavedTokenSurvivesASaveThatOnlyChangesTheBranch() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        settings.token = "the-old-token"
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "notes", token = "")
        advanceUntilIdle()

        assertEquals("the-old-token", settings.token)
        assertEquals("notes", settings.branch)
    }

    @Test
    fun theTokenCanBeDroppedWithoutChangingTheRemote() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        settings.token = "the-old-token"
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "main", token = "", dropToken = true)
        advanceUntilIdle()

        assertNull(settings.token)
    }

    /**
     * A clone command copied from a repository page carries the token in the
     * address. Stored as typed, it would sit in the one field the settings
     * screen shows in the clear.
     */
    @Test
    fun aTokenPastedInsideTheAddressIsStoredAsATokenInstead() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(
            url = "https://x-access-token:ghp_secret@example.test/notes.git",
            branch = "main",
            token = "",
        )
        advanceUntilIdle()

        assertEquals(REMOTE, settings.remoteUrl)
        assertEquals("ghp_secret", settings.token)
    }

    @Test
    fun changingOnlyTheBranchKeepsTheCheckoutForTheCoreToMoveOver() = runTest(dispatcher) {
        // The core checks the branch out itself. Wiping the directory here
        // would throw away commits made on the device that no remote has.
        val syncer = FakeSyncer()
        syncer.statusResult = Result.success(FakeSyncer.status(REMOTE))
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "notes", token = "")
        advanceUntilIdle()

        assertEquals(0, notes.wiped)
    }

    private fun viewModel(syncer: FakeSyncer) = AgendaViewModel(
        notes = notes,
        agenda = loader,
        sync = syncer,
        settings = settings,
        ui = ui,
        editor = writer,
    )

    private companion object {
        const val REMOTE = "https://example.test/notes.git"
        const val OTHER_REMOTE = "https://example.test/other.git"
    }
}
