package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.EditReport
import io.github.vitalyostanin.markdownorg.core.FIRST_ID
import io.github.vitalyostanin.markdownorg.core.GroupReport
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.SyncRun
import io.github.vitalyostanin.markdownorg.core.UndoReport
import io.github.vitalyostanin.markdownorg.core.testWording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.markdown_org_ffi.Adoption
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.BulkOutcome
import uniffi.markdown_org_ffi.BulkRefusal
import uniffi.markdown_org_ffi.FileRollback
import uniffi.markdown_org_ffi.RefusalReason
import uniffi.markdown_org_ffi.RevertOutcome
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.SyncException
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

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

    /**
     * The working copy of the one collection, replaced by the tests that start
     * from a directory other than the default: the collection is built around
     * this area, so its path and this one are the same path by construction.
     */
    private var notes = FakeNotesArea()
    private val loader = FakeAgendaLoader()
    private val settings = FakePreferences()
    private val ui = FakeUiPreferences()
    private val writer = FakeWriter()

    /**
     * The stored set of collections, holding the one the stand-ins are over.
     *
     * Kept beside the model rather than derived from it, so a test can read
     * back what saving a directory wrote: the set the walk reads and the set
     * the next launch reads are the same set.
     */
    private val store = FakeCollectionsStore(
        listOf(NotesCollection(id = FIRST_ID, name = "Notes", path = "/notes")),
    )

    /** What an empty choice of directory falls back to, as on a device. */
    private val own = File("/data/data/markdown-org/files/notes")

    /** Whether the platform lets a directory outside [own] be read. */
    private var granted = true

    /** The wall clock the model reads, moved by hand where a test needs it to. */
    private var moment = NOON

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
        val held = CompletableDeferred<Result<SyncRun>>()
        val syncer = FakeSyncer { held.await() }
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)

        model.syncNow()
        model.syncNow()
        advanceUntilIdle()

        assertEquals(1, syncer.requested.size)
        held.complete(Result.success(FakeSyncer.run()))
        advanceUntilIdle()
    }

    /**
     * Saving a form used to empty the notes directory whenever the address
     * changed, and a commit that had not been pushed went with it. Now the
     * directory is left alone and the user is told what stands in the way.
     */
    @Test
    fun changingTheRemoteOverSomebodyElsesCheckoutEmptiesNothing() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        syncer.statusResult = Result.success(FakeSyncer.status(REMOTE))
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(OTHER_REMOTE, branch = "", token = "")
        advanceUntilIdle()

        assertEquals(R.string.settings_other_checkout, model.syncState.value.message?.text)
    }

    /**
     * The directory has been holding notes with no git at all — the state a
     * fresh install starts in and one a user can stay in for good. Naming a
     * remote takes those notes into git; it does not throw them away and
     * clone over the top, which is what saving used to do.
     */
    @Test
    fun namingARemoteOverADirectoryOfNotesAdoptsIt() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(REMOTE, branch = "", token = "")
        advanceUntilIdle()

        assertEquals(listOf(REMOTE), syncer.adopted)
        assertTrue(syncer.requested.isEmpty())
    }

    @Test
    fun bothSidesHoldingNotesIsAQuestionRatherThanAFailure() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        syncer.adoptResult = Result.success(Adoption.Unrelated("main"))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(REMOTE, branch = "", token = "")
        advanceUntilIdle()

        val state = model.syncState.value
        assertEquals("main", state.unrelated)
        assertEquals(R.string.sync_unrelated, state.message?.text)
        // Not a failure: nothing was lost, nothing was sent, and the screen
        // shows an answer to press rather than a red line to read.
        assertFalse(state.message?.failed == true)
    }

    /**
     * SSH has no certificate authorities: the first sync with a server has
     * nothing to check its key against, and believing whatever answers is how
     * the key and the notes reach the wrong machine. The key it offered goes
     * on screen instead, with the press that vouches for it.
     */
    @Test
    fun aServerNobodyHasVouchedForStopsTheSyncAndSaysWithWhatKey() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        syncer.result = Result.failure(SyncException.UnknownHost("git.example.test", FINGERPRINT))
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.syncNow()
        advanceUntilIdle()

        val state = model.syncState.value
        assertEquals(FINGERPRINT, state.pendingHost)
        assertNull(state.pendingHostReplaces)
        assertEquals(R.string.sync_host_unknown, state.message?.text)
        // Nothing is stored on the strength of the server having answered.
        assertNull(settings.knownHost)
    }

    @Test
    fun vouchingForTheServerStoresItsKeyAndTriesAgain() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        syncer.result = Result.failure(SyncException.UnknownHost("git.example.test", FINGERPRINT))
        syncer.checkout = true
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()
        model.syncNow()
        advanceUntilIdle()

        syncer.result = Result.success(FakeSyncer.run(cloned = false))
        model.trustHost()
        advanceUntilIdle()

        assertEquals(FINGERPRINT, settings.knownHost)
        assertNull(model.syncState.value.pendingHost)
        // The attempt that was interrupted, made again: a checkout fetches.
        assertEquals(2, syncer.requested.size)
    }

    /**
     * A stored key contradicted is the graver question of the two, and the
     * screen has to word it as one — the same press, different wording.
     */
    @Test
    fun aServerKeyThatDisagreesWithTheStoredOneIsNamedAsAReplacement() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        syncer.result = Result.failure(
            SyncException.HostChanged("git.example.test", FINGERPRINT, "SHA256:what-was-known"),
        )
        settings.remoteUrl = REMOTE
        settings.knownHost = "SHA256:what-was-known"
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.syncNow()
        advanceUntilIdle()

        val state = model.syncState.value
        assertEquals(FINGERPRINT, state.pendingHost)
        assertEquals("SHA256:what-was-known", state.pendingHostReplaces)
        assertEquals(R.string.sync_host_changed, state.message?.text)
        assertTrue(state.message?.failed == true)
        // Still the stored one until somebody says otherwise.
        assertEquals("SHA256:what-was-known", settings.knownHost)
    }

    @Test
    fun takingTheRemotesNotesAnswersTheQuestionAndRebuildsTheAgenda() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        syncer.adoptResult = Result.success(Adoption.Unrelated("main"))
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()
        model.saveSettings(REMOTE, branch = "", token = "")
        advanceUntilIdle()
        val invalidationsBefore = loader.invalidations

        model.takeRemoteNotes()
        advanceUntilIdle()

        assertEquals(1, syncer.remotesTaken)
        assertNull(model.syncState.value.unrelated)
        assertEquals(R.string.sync_took_remote, model.syncState.value.message?.text)
        assertTrue(loader.invalidations > invalidationsBefore)
    }

    /**
     * The state was reachable by accident — a directory with no address is a
     * plain directory — and read as "not set up yet" on every launch. Said
     * outright it is a way to use the application.
     */
    @Test
    fun keepingTheNotesOnTheDeviceIsAConfiguredStateOfItsOwn() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.keepNotesLocal()
        advanceUntilIdle()

        assertTrue(settings.storesLocally)
        assertTrue(model.syncState.value.local)
        assertTrue(settings.isSettled)
        assertFalse(settings.isConfigured)
    }

    @Test
    fun namingARemoteAfterwardsLeavesTheLocalStoreBehind() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()
        model.keepNotesLocal()

        model.saveSettings(REMOTE, branch = "", token = "")
        advanceUntilIdle()

        assertFalse(settings.storesLocally)
        assertEquals(listOf(REMOTE), syncer.adopted)
    }

    @Test
    fun anUnreadableCheckoutIsLeftAloneAndSaidToBeUnreadable() = runTest(dispatcher) {
        // `status()` failing means the directory holds a repository that could
        // not be read — not that there is none. An edit made offline is
        // committed here and nowhere else until a sync gets through, so this
        // is said out loud rather than treated as an empty directory.
        val syncer = FakeSyncer()
        syncer.statusResult = Result.failure(IllegalStateException("broken .git/config"))
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(REMOTE, branch = "", token = "")
        advanceUntilIdle()

        assertEquals(R.string.sync_status_unreadable, model.syncState.value.message?.text)
    }

    @Test
    fun anAddressWithAnUnsupportedSchemeIsRefusedBeforeAnythingIsTouched() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings("http://example.test/notes.git", branch = "", token = "")
        advanceUntilIdle()

        assertNull(settings.remoteUrl)
        assertEquals(R.string.settings_url_scheme, model.syncState.value.message?.text)
    }

    /**
     * Saving settings is work on the working copy like a sync is, and the two
     * must not run beside each other: saving stops the sync in flight, moves
     * the directory, stores the address and starts a sync of its own. It ran
     * outside the job that stands for "a sync is under way", so a tap on the
     * sync icon in that window passed the check and fetched into a directory
     * being pointed somewhere else.
     */
    @Test
    fun aSyncAskedForWhileSettingsAreBeingSavedDoesNotRunBesideThem() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        syncer.statusResult = Result.success(FakeSyncer.status(REMOTE))
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        // The save has stopped the sync that was running and is now reading
        // the checkout. The tap lands in that window.
        val reading = CompletableDeferred<Unit>()
        syncer.statusGate = reading
        model.saveSettings(REMOTE, branch = "notes", token = "")
        advanceUntilIdle()

        model.syncNow()
        advanceUntilIdle()
        reading.complete(Unit)
        advanceUntilIdle()

        // One fetch, the one the save started. Two means the tap got its own,
        // beside a save that was still moving the directory under it.
        assertEquals(listOf(REMOTE), syncer.requested)
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

    /**
     * A scan dropped for a newer one ends by being cancelled, and cancellation
     * is not an outcome the screen has anything to say about: the agenda the
     * user is waiting for is the newer scan. It was reported as a failed
     * agenda — briefly, between the two scans, and for good if the newer one
     * was itself replaced by an edit that never triggered a third.
     */
    @Test
    fun aScanDroppedForANewerOneIsNeverShownAsAFailure() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        val seen = mutableListOf<AgendaUiState>()
        // Unconfined, so the collector resumes at the moment of the write
        // rather than on the next turn of the scheduler: `state` is a
        // StateFlow, and a value replaced before the collector runs again is
        // one it never sees — which is exactly the value under test here.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.toList(seen)
        }
        advanceUntilIdle()

        // The load from `init` is still open; a second one drops it.
        model.refresh()
        advanceUntilIdle()
        loader.pending.last().complete(Result.success(agenda(day())))
        advanceUntilIdle()

        assertTrue(
            "the newer scan did not reach the screen",
            model.state.value is AgendaUiState.Ready,
        )
        val failed = seen.filterIsInstance<AgendaUiState.Failed>()
        assertTrue("a cancelled scan was shown as a failure: $failed", failed.isEmpty())
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
    fun steppingMovesThePlanBySpansRatherThanByDays() = runTest(dispatcher) {
        // A week stepped by a day answers with six of the same seven days,
        // which reads on screen as the step having done nothing.
        ui.span = AgendaSpan.WEEK
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.stepBy(1)
        advanceUntilIdle()

        assertEquals(listOf(NOON.toLocalDate(), NOON.toLocalDate().plusWeeks(1)), loader.dates)
    }

    @Test
    fun steppingBackAndForthLandsOnTheDayItStartedFrom() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.stepBy(1)
        advanceUntilIdle()
        model.stepBy(-1)
        advanceUntilIdle()

        val today = NOON.toLocalDate()
        assertEquals(listOf(today, today.plusDays(1), today), loader.dates)
        // Back to following the clock rather than pinned to a date that
        // happens to be today's: the phone is left running over midnight.
        assertNull(model.anchor.value)
    }

    @Test
    fun theFlatListOfTasksHasNoDatesToStepThrough() = runTest(dispatcher) {
        ui.span = AgendaSpan.TASKS
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.stepBy(1)
        advanceUntilIdle()

        assertEquals(1, loader.dates.size)
        assertNull(model.anchor.value)
    }

    @Test
    fun todayBringsThePlanBackFromWhereverItWasStepped() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.stepBy(4)
        advanceUntilIdle()
        model.showToday()
        advanceUntilIdle()

        val today = NOON.toLocalDate()
        assertEquals(listOf(today, today.plusDays(4), today), loader.dates)
    }

    @Test
    fun steppingMovesTheWindowAndLeavesTodayWhereItIs() = runTest(dispatcher) {
        // The two used to be one date, and the buckets that only exist
        // relative to today went with the reader: paged a month forward, the
        // arrears of the whole collection were reported under the day being
        // looked at — and counted a second time in the days they belong to.
        ui.span = AgendaSpan.MONTH
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.stepBy(1)
        advanceUntilIdle()

        val today = NOON.toLocalDate()
        assertEquals(listOf(today, today.plusMonths(1)), loader.dates)
        assertEquals(listOf(today, today), loader.todays)
    }

    /**
     * The check for the day turning over used to compare the date on screen
     * with the clock. Once the plan can be stepped away from today, those two
     * differ every minute, and the check fired a scan on every tick of the
     * clock — one directory walk a minute, over an agenda nobody was changing.
     */
    @Test
    fun aPlanSteppedAwayFromTodayIsNotRescannedEveryMinute() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        model.stepBy(1)
        advanceUntilIdle()
        loader.pending[1].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        val watching = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.now.collect { }
        }
        moment = NOON.plusMinutes(2)
        advanceTimeBy(2 * MINUTE_MS)
        runCurrent()
        watching.cancel()

        assertEquals(2, loader.dates.size)
    }

    @Test
    fun switchingTheLayoutIsRemembered() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.setLayout(AgendaLayout.LIST)

        assertEquals(AgendaLayout.LIST, ui.layout)
    }

    @Test
    fun droppingTheSectionHeadingsIsRememberedAndCostsNoScan() = runTest(dispatcher) {
        // No scan on purpose, unlike the span: the sections are already in
        // hand, and the setting only decides whether they announce themselves.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val scansBefore = loader.pending.size

        model.setGrouped(false)
        advanceUntilIdle()

        assertEquals(false, ui.grouped)
        assertEquals(false, model.grouped.value)
        assertEquals("the agenda was rebuilt for nothing", scansBefore, loader.pending.size)
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
    fun anEditNamesTheNoteItChangedSoTheAgendaCostsThatNote() = runTest(dispatcher) {
        // Without this the agenda after every tap re-read the whole
        // collection: on a device with a thousand notes, seconds of it for a
        // change to one line.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.apply(task(file = "projects/plan.md"), TaskAction.Complete)
        advanceUntilIdle()

        assertEquals(listOf("projects/plan.md"), loader.reread)
        assertEquals(0, loader.invalidations)
    }

    @Test
    fun aNoteThatCouldNotBeReReadStillRebuildsTheAgenda() = runTest(dispatcher) {
        // The re-read is an optimisation, and a failed one is not the user's
        // business: the scan that follows reads the file along with the rest.
        loader.rereadResult = Result.failure(IllegalStateException("gone"))
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val scansBefore = loader.pending.size

        model.apply(task(), TaskAction.Complete)
        advanceUntilIdle()

        assertNull(model.editIssue.value)
        assertTrue("the agenda was not rebuilt", loader.pending.size > scansBefore)
    }

    @Test
    fun aSyncDropsWhatWasHeldBecauseTheFetchRewroteItUnseen() = runTest(dispatcher) {
        // A fast-forward changes files without naming them, so nothing held
        // about the directory can be trusted afterwards.
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.syncNow()
        advanceUntilIdle()

        assertEquals(1, loader.invalidations)
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
            val syncer = FakeSyncer { Result.success(FakeSyncer.run(cloned = true)) }
            settings.remoteUrl = REMOTE
            val model = viewModel(syncer)
            advanceUntilIdle()
            val readsBefore = syncer.statusReads

            model.syncNow()
            advanceUntilIdle()

            assertEquals(readsBefore, syncer.statusReads)
            assertEquals(head, model.syncState.value.repository)
        }

    /**
     * The fetch went through and the push after it was refused. What came down
     * is on disk and belongs on screen; the refusal belongs in the banner. An
     * earlier shape of this reported the whole run as a failure, which left the
     * agenda showing notes the application had already replaced.
     */
    @Test
    fun aRefusedPushIsSaidWithoutHoldingBackWhatTheFetchBrought() = runTest(dispatcher) {
        val syncer = FakeSyncer {
            Result.success(
                FakeSyncer.run(
                    cloned = false,
                    commits = 1u,
                    pushFailure = SyncException.Rejected("main", "fetch first"),
                ),
            )
        }
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.syncNow()
        advanceUntilIdle()

        assertEquals(R.string.sync_failed_rejected, model.syncState.value.message?.text)
        assertTrue(model.syncState.value.message?.failed == true)
        // The fetch rewrote files nobody named, so what is held for them is
        // stale and the agenda is built again from disk.
        assertEquals(1, loader.invalidations)
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

    /**
     * The server key is what an `ssh://` remote is pinned by, and it is about
     * that server and no other: kept across a change of address, it would
     * vouch for whatever answers at the new one.
     */
    @Test
    fun pointingTheApplicationElsewhereForgetsTheServerItVouchedFor() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        settings.knownHost = "SHA256:the-old-server"
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = OTHER_REMOTE, branch = "main", token = "")
        advanceUntilIdle()

        assertNull(settings.knownHost)
    }

    /**
     * The key is not the token: it belongs to the device rather than to one
     * server, and its owner adds it to as many as they like. Dropping it on a
     * change of address would leave the new remote unreachable for a reason
     * nothing on screen states.
     */
    @Test
    fun theKeyOfTheDeviceOutlivesAChangeOfAddress() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        settings.sshKey = "-----BEGIN PRIVATE KEY-----"
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = OTHER_REMOTE, branch = "main", token = "")
        advanceUntilIdle()

        assertEquals("-----BEGIN PRIVATE KEY-----", settings.sshKey)
    }

    @Test
    fun aPassphraseIsForgottenWithTheKeyItOpens() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        settings.sshKey = "-----BEGIN PRIVATE KEY-----"
        settings.sshPassphrase = "let me in"
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "main", token = "", dropKey = true)
        advanceUntilIdle()

        assertNull(settings.sshKey)
        assertNull(settings.sshPassphrase)
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
        // The core checks the branch out itself: the checkout stays where it
        // is and is fetched into, rather than being taken into git afresh.
        val syncer = FakeSyncer()
        syncer.statusResult = Result.success(FakeSyncer.status(REMOTE))
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "notes", token = "")
        advanceUntilIdle()

        assertEquals(listOf(REMOTE), syncer.requested)
        assertTrue(syncer.adopted.isEmpty())
    }

    @Test
    fun theChosenDirectoryBecomesTheWorkingCopy() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertEquals(File(SHARED), notes.root)
        assertEquals(SHARED, storedPath)
    }

    @Test
    fun aChangeOfDirectoryDropsTheNotesHeldFromTheOldOne() = runTest(dispatcher) {
        // They describe files the new directory does not have, and an agenda
        // built over them would be someone else's.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.saveSettings(url = "", branch = "", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertEquals(1, loader.invalidations)
    }

    @Test
    fun anEmptyDirectoryFieldPutsTheNotesBackInTheOwnStorage() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        notes = FakeNotesArea(File(SHARED))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = "")
        advanceUntilIdle()

        assertEquals(own, notes.root)
        assertEquals(own.absolutePath, storedPath)
    }

    @Test
    fun aDirectoryThatCannotBeUsedIsNeitherStoredNorMovedInto() = runTest(dispatcher) {
        // The move is what says whether the directory can hold the notes:
        // storing the choice regardless would have the application open on a
        // directory it cannot read, with no way back but the same form.
        val syncer = FakeSyncer()
        notes.moveResult = Result.failure(IllegalStateException("read-only"))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertEquals(DEFAULT_NOTES, storedPath)
        assertEquals(R.string.settings_notes_failed, model.syncState.value.message?.text)
    }

    @Test
    fun aFailedMoveLeavesTheRemoteAlone() = runTest(dispatcher) {
        // Everything after the move is about the directory the notes are in.
        // Storing a remote against a directory that was refused would clone
        // into the previous one on the next sync.
        val syncer = FakeSyncer()
        notes.moveResult = Result.failure(IllegalStateException("read-only"))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertNull(settings.remoteUrl)
        assertTrue(syncer.requested.isEmpty())
    }

    @Test
    fun aDirectoryOutsideTheOwnStorageIsRefusedUntilTheAccessIsGranted() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        granted = false
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertEquals(DEFAULT_NOTES, storedPath)
        assertFalse("the directory was moved into anyway", notes.trace.contains("move"))
        assertEquals(R.string.settings_notes_denied, model.syncState.value.message?.text)
    }

    @Test
    fun theDirectoryCanBeSavedWithoutARemoteAtAll() = runTest(dispatcher) {
        // Notes already on the device need no repository. The form used to
        // refuse an empty address outright, which left no way to point the
        // application at them.
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = "", branch = "", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertEquals(SHARED, storedPath)
        assertNull(settings.remoteUrl)
        assertTrue("a sync was attempted without a remote", syncer.requested.isEmpty())
    }

    @Test
    fun anEmptyAddressLeavesTheRemoteThatWasConfiguredBefore() = runTest(dispatcher) {
        // Saving a directory is not a way to forget a repository: the address
        // field is empty on a form that was opened for something else.
        val syncer = FakeSyncer()
        settings.remoteUrl = REMOTE
        settings.token = "the-old-token"
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = "", branch = "", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertEquals(REMOTE, settings.remoteUrl)
        assertEquals("the-old-token", settings.token)
    }

    @Test
    fun aSaveThatLeavesTheDirectoryAloneDoesNotTouchTheWorkingCopy() = runTest(dispatcher) {
        // A move under a directory that did not change is still a move: it
        // takes the lock the sync is waiting for and rebuilds the agenda for
        // nothing.
        val syncer = FakeSyncer()
        notes = FakeNotesArea(File(SHARED))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertFalse("the directory was moved into for nothing", notes.trace.contains("move"))
    }

    @Test
    fun theClockOnScreenFollowsTheOneOnTheWall() = runTest(dispatcher) {
        // It used to be read once, when the agenda was built, so the marker
        // line stood where the last scan had left it — an hour behind the
        // phone after an hour in the background.
        val model = viewModel(FakeSyncer())
        val seen = mutableListOf<LocalDateTime>()
        // In the background scope, which is what a collector with no end of
        // its own belongs in: `runTest` cancels it rather than waiting on it.
        // Time is stepped by hand from here on for the same reason the scope
        // differs — a ticker never runs out of work, so waiting for idle over
        // one would never return.
        backgroundScope.launch { model.now.toList(seen) }
        runCurrent()

        moment = moment.plusMinutes(1)
        advanceTimeBy(MINUTE_MS)
        runCurrent()

        assertEquals(listOf(NOON, NOON.plusMinutes(1)), seen)
    }

    @Test
    fun aDayTurningOverRebuildsTheAgenda() = runTest(dispatcher) {
        // What was due today is overdue tomorrow, and the sections were built
        // against the old date: nothing short of another scan fixes that.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()
        backgroundScope.launch { model.now.collect { } }
        runCurrent()
        val scansBefore = loader.pending.size

        moment = NOON.plusDays(1)
        advanceTimeBy(MINUTE_MS)
        runCurrent()

        assertTrue("the new day was not scanned for", loader.pending.size > scansBefore)
        loader.pending.last().complete(Result.success(agenda(day())))
        runCurrent()
        assertEquals(
            NOON.toLocalDate().plusDays(1),
            (model.state.value as AgendaUiState.Ready).date,
        )
    }

    @Test
    fun aMinutePassingLeavesTheNotesAlone() = runTest(dispatcher) {
        // The ticker sits beside the agenda rather than inside it precisely so
        // that a minute does not cost a walk of the notes directory.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()
        backgroundScope.launch { model.now.collect { } }
        runCurrent()
        val scansBefore = loader.pending.size

        moment = moment.plusMinutes(2)
        advanceTimeBy(2 * MINUTE_MS)
        runCurrent()

        assertEquals("the notes were walked for a passing minute", scansBefore, loader.pending.size)
        assertEquals(NOON.plusMinutes(2), model.now.value)
    }

    @Test
    fun awholeBandGoesToTheWriterAsOneRequest() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val rows = listOf(
            task(heading = "Pay the tax", line = 1u).toAgendaRow(),
            task(heading = "Service the car", line = 2u).toAgendaRow(),
        )

        model.applyToGroup(rows, BulkAction.MOVE_TO_TODAY)
        advanceUntilIdle()

        // One call for the band rather than one per task: twenty taps would
        // be twenty rewrites of the file and twenty commits.
        assertEquals(1, writer.calls)
        assertEquals(BulkAction.MOVE_TO_TODAY, writer.group?.first)
        assertEquals(
            listOf("Pay the tax", "Service the car"),
            writer.group?.second?.map { it.heading },
        )
    }

    @Test
    fun whatTheBandDidIsOfferedWithTheUndoThatPutsItBack() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        writer.groupOutcome = Result.success(
            GroupReport(
                outcome = BulkOutcome(
                    changed = 2u,
                    refused = listOf(refusal("Eye clinic")),
                    rollback = listOf(rollback("notes.md")),
                ),
                report = EditReport(committed = true),
            ),
        )

        model.applyToGroup(listOf(task().toAgendaRow()), BulkAction.CANCEL)
        advanceUntilIdle()

        val result = model.groupResult.value
        assertEquals(BulkAction.CANCEL, result?.action)
        assertEquals(2, result?.changed)
        // The refusal is counted rather than swallowed: a group that left one
        // task alone has to say so, or the user believes it did all of them.
        assertEquals(1, result?.refused)
        assertTrue("the move cannot be undone", result?.canUndo == true)
    }

    @Test
    fun aBandThatChangedNothingIsNotOfferedAnUndo() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.applyToGroup(listOf(task().toAgendaRow()), BulkAction.DROP_PLANNING)
        advanceUntilIdle()

        assertFalse("an undo was offered for nothing", model.groupResult.value?.canUndo == true)
    }

    @Test
    fun theUndoHandsBackWhatTheGroupOverwrote() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val written = rollback("notes.md")
        writer.groupOutcome = Result.success(
            GroupReport(
                outcome = BulkOutcome(
                    changed = 1u,
                    refused = emptyList(),
                    rollback = listOf(written),
                ),
                report = EditReport(committed = true),
            ),
        )
        model.applyToGroup(listOf(task().toAgendaRow()), BulkAction.MOVE_TO_TODAY)
        advanceUntilIdle()

        model.undoGroup()
        advanceUntilIdle()

        assertEquals(listOf(written), writer.undone)
        // The offer is gone with it: pressing undo twice would put the notes
        // back over an edit made in between.
        assertNull(model.groupResult.value)
    }

    @Test
    fun anUndoThatCouldNotPutEverythingBackSaysSo() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        writer.groupOutcome = Result.success(
            GroupReport(
                outcome = BulkOutcome(
                    changed = 2u,
                    refused = emptyList(),
                    rollback = listOf(rollback("work.md"), rollback("home.md")),
                ),
                report = EditReport(committed = true),
            ),
        )
        writer.undoOutcome = Result.success(
            UndoReport(
                outcome = RevertOutcome(
                    restored = listOf("work.md"),
                    skipped = listOf("home.md"),
                    failed = emptyList(),
                ),
                report = EditReport(committed = true),
            ),
        )
        model.applyToGroup(listOf(task().toAgendaRow()), BulkAction.MOVE_TO_TODAY)
        advanceUntilIdle()

        model.undoGroup()
        advanceUntilIdle()

        assertEquals(
            R.string.agenda_group_undo_partial,
            model.editIssue.value?.text,
        )
    }

    @Test
    fun aBandOfNotesWithUnnamedFilesIsRefusedBeforeItReachesTheCore() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        // A filename that is not UTF-8 arrives with U+FFFD and names nothing
        // on disk; every edit aimed at it would come back as "file not found".
        val rows = listOf(task(file = "notes�.md").toAgendaRow())

        model.applyToGroup(rows, BulkAction.CANCEL)
        advanceUntilIdle()

        assertEquals(0, writer.calls)
        assertEquals(R.string.edit_failed_unnamed, model.editIssue.value?.text)
    }

    @Test
    fun theSpanThatWasChosenIsTheSpanTheCoreIsAskedFor() = runTest(dispatcher) {
        // The grouping is the core's: a week is the same notes read against
        // seven dates, and nothing on this side can regroup a day into one.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        model.setSpan(AgendaSpan.WEEK)
        advanceUntilIdle()

        assertEquals(listOf(Scope.DAY, Scope.WEEK), loader.scopes)
    }

    @Test
    fun theAgendaOpensOnTheSpanItWasClosedIn() = runTest(dispatcher) {
        // Stored beside the layout and for the same reason: a choice that
        // survives a rotation but not the process being killed is one the user
        // makes again on every launch.
        ui.span = AgendaSpan.MONTH

        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        assertEquals(listOf(Scope.MONTH), loader.scopes)
        assertEquals(AgendaSpan.MONTH, model.span.value)
    }

    @Test
    fun aWeekArrivesWithItsDaysStillApart() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        model.setSpan(AgendaSpan.WEEK)
        advanceUntilIdle()

        loader.pending.last().complete(
            Result.success(
                agenda(
                    day(date = "2026-07-27", scheduledNoTime = listOf(task(heading = "Monday"))),
                    day(date = "2026-07-28", scheduledNoTime = listOf(task(heading = "Tuesday"))),
                ),
            ),
        )
        advanceUntilIdle()

        val ready = model.state.value as AgendaUiState.Ready
        assertEquals(
            listOf(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 28)),
            ready.days.map(AgendaDay::date),
        )
        assertEquals(
            listOf("Monday"),
            ready.days.first().sections.untimed.map { it.task.heading },
        )
    }

    @Test
    fun theFlatListOfTasksArrivesAsOneDayWithNoDate() = runTest(dispatcher) {
        // The core fills `tasks` rather than `days` for that scope, and the
        // entries in it carry no date to sit under: a screen that dropped
        // them would show nothing at all for the one span that answers "what
        // is left".
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        model.setSpan(AgendaSpan.TASKS)
        advanceUntilIdle()

        loader.pending.last().complete(
            Result.success(flatAgenda(task(heading = "Someday", daysOffset = null))),
        )
        advanceUntilIdle()

        val ready = model.state.value as AgendaUiState.Ready
        assertNull(ready.days.single().date)
        assertEquals(listOf("Someday"), ready.sections.untimed.map { it.task.heading })
    }

    /**
     * The model over one collection, which is what a device that has not been
     * set up past the first directory works with.
     *
     * The stand-ins are the ones the assertions read, so the collection is
     * built around them rather than the other way round.
     */
    private fun viewModel(syncer: FakeSyncer): AgendaViewModel {
        // Stored to match the area the assertions read, so a test that starts
        // from another directory says so in one place.
        store.collections = listOf(
            NotesCollection(id = FIRST_ID, name = "Notes", path = notes.root.absolutePath),
        )

        return AgendaViewModel(
            collections = FakeCollections.one(
                area = notes,
                settings = settings,
                editor = writer,
                syncer = syncer,
            ),
            stored = store,
            agenda = loader,
            ui = ui,
            ownNotes = own,
            sample = testWording,
            storageGranted = { granted },
            clock = { moment },
        )
    }

    /** Where the one collection is, as the settings hold it. */
    private val storedPath: String? get() = store.collections.singleOrNull()?.path

    /** A task the core would not change, as it comes back from a group. */
    private fun refusal(heading: String) = BulkRefusal(
        file = "notes.md",
        line = 9u,
        heading = heading,
        reason = RefusalReason.MOVED,
        detail = "notes.md:9 is not a heading",
    )

    /** What the core hands back to undo one file's share of a group. */
    private fun rollback(file: String) = FileRollback(
        file = file,
        before = "# TODO Pay the tax\n",
        after = "# CANCELLED Pay the tax\n",
    )

    private companion object {
        const val REMOTE = "https://example.test/notes.git"
        const val OTHER_REMOTE = "https://example.test/other.git"

        /** A server key, spelled the way OpenSSH spells one. */
        const val FINGERPRINT = "SHA256:2sJ8mQBz1TeQ5iTGH7t7zZ0hqRk3sB0Xk8v0FhK0aBc"

        /** A directory on the shared storage, which is what needs the access. */
        const val SHARED = "/storage/emulated/0/Documents/notes"

        /** Where the one collection starts, which a refused move leaves it at. */
        const val DEFAULT_NOTES = "/notes"

        /** Where the hand-held clock starts, on a whole minute. */
        val NOON: LocalDateTime = LocalDate.of(2026, 7, 28).atTime(12, 0)

        const val MINUTE_MS = 60_000L
    }
}
