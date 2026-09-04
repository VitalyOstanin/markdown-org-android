package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.EditReport
import io.github.vitalyostanin.markdownorg.core.FIRST_ID
import io.github.vitalyostanin.markdownorg.core.GroupReport
import io.github.vitalyostanin.markdownorg.core.MoveReport
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.SyncRun
import io.github.vitalyostanin.markdownorg.core.TaskDraft
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
import uniffi.markdown_org_ffi.EntryText
import uniffi.markdown_org_ffi.FileRollback
import uniffi.markdown_org_ffi.MoveOutcome
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.RefusalReason
import uniffi.markdown_org_ffi.RevertOutcome
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.SyncException
import uniffi.markdown_org_ffi.WritePosition
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.WeekFields
import java.util.Locale

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

        model.settings.syncNow()
        model.settings.syncNow()
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

        model.settings.saveSettings(OTHER_REMOTE, branch = "", token = "")
        advanceUntilIdle()

        assertEquals(R.string.settings_other_checkout, model.settings.syncState.value.message?.text)
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

        model.settings.saveSettings(REMOTE, branch = "", token = "")
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

        model.settings.saveSettings(REMOTE, branch = "", token = "")
        advanceUntilIdle()

        val state = model.settings.syncState.value
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

        model.settings.syncNow()
        advanceUntilIdle()

        val state = model.settings.syncState.value
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
        model.settings.syncNow()
        advanceUntilIdle()

        syncer.result = Result.success(FakeSyncer.run(cloned = false))
        model.settings.trustHost()
        advanceUntilIdle()

        assertEquals(FINGERPRINT, settings.knownHost)
        assertNull(model.settings.syncState.value.pendingHost)
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

        model.settings.syncNow()
        advanceUntilIdle()

        val state = model.settings.syncState.value
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
        model.settings.saveSettings(REMOTE, branch = "", token = "")
        advanceUntilIdle()
        val invalidationsBefore = loader.invalidations

        model.settings.takeRemoteNotes()
        advanceUntilIdle()

        assertEquals(1, syncer.remotesTaken)
        assertNull(model.settings.syncState.value.unrelated)
        assertEquals(R.string.sync_took_remote, model.settings.syncState.value.message?.text)
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

        model.settings.keepNotesLocal()
        advanceUntilIdle()

        assertTrue(settings.storesLocally)
        assertTrue(model.settings.syncState.value.local)
        assertTrue(settings.isSettled)
        assertFalse(settings.isConfigured)
    }

    @Test
    fun namingARemoteAfterwardsLeavesTheLocalStoreBehind() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()
        model.settings.keepNotesLocal()

        model.settings.saveSettings(REMOTE, branch = "", token = "")
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

        model.settings.saveSettings(REMOTE, branch = "", token = "")
        advanceUntilIdle()

        assertEquals(R.string.sync_status_unreadable, model.settings.syncState.value.message?.text)
    }

    @Test
    fun anAddressWithAnUnsupportedSchemeIsRefusedBeforeAnythingIsTouched() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.settings.saveSettings("http://example.test/notes.git", branch = "", token = "")
        advanceUntilIdle()

        assertNull(settings.remoteUrl)
        assertEquals(R.string.settings_url_scheme, model.settings.syncState.value.message?.text)
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
        model.settings.saveSettings(REMOTE, branch = "notes", token = "")
        advanceUntilIdle()

        model.settings.syncNow()
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

        assertEquals(AgendaLayout.LIST, model.view.layout.value)
    }

    @Test
    fun everyChoiceAboutTheViewIsWrittenDownAsItIsMade() = runTest(dispatcher) {
        // The other half of "the agenda opens the way it was left": that test
        // reads a stored choice, this one checks that making a choice stores
        // it. Without this, a setter that only moved the flow would keep the
        // screen right for as long as the process lived and lose the choice on
        // the next launch -- and every test above would still pass.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.view.setLayout(AgendaLayout.LIST)
        model.view.setSpan(AgendaSpan.WEEK)
        model.view.setGrouped(false)
        model.view.setMonthAsGrid(false)
        model.view.setWeekStart(WeekStart.MONDAY)
        advanceUntilIdle()

        assertEquals(AgendaLayout.LIST, ui.layout)
        assertEquals(AgendaSpan.WEEK, ui.span)
        assertFalse(ui.grouped)
        assertFalse(ui.monthAsGrid)
        assertEquals(WeekStart.MONDAY, ui.weekStart)
    }

    @Test
    fun theDayACellAsksForIsTheSpanTheScreenIsLeftIn() = runTest(dispatcher) {
        // A tap on a cell of the month calendar moves the span as well as the
        // date, and the span it moves to is stored like any other: the next
        // launch opens on the day, not back on the month.
        ui.span = AgendaSpan.MONTH
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.view.showDay(NOON.toLocalDate().plusDays(1))
        advanceUntilIdle()

        assertEquals(AgendaSpan.DAY, model.view.span.value)
        assertEquals(AgendaSpan.DAY, ui.span)
    }

    @Test
    fun steppingMovesThePlanBySpansRatherThanByDays() = runTest(dispatcher) {
        // A week stepped by a day answers with six of the same seven days,
        // which reads on screen as the step having done nothing.
        ui.span = AgendaSpan.WEEK
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.view.stepBy(1)
        advanceUntilIdle()

        assertEquals(listOf(NOON.toLocalDate(), NOON.toLocalDate().plusWeeks(1)), loader.dates)
    }

    @Test
    fun steppingBackAndForthLandsOnTheDayItStartedFrom() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.view.stepBy(1)
        advanceUntilIdle()
        model.view.stepBy(-1)
        advanceUntilIdle()

        val today = NOON.toLocalDate()
        assertEquals(listOf(today, today.plusDays(1), today), loader.dates)
        // Back to following the clock rather than pinned to a date that
        // happens to be today's: the phone is left running over midnight.
        assertNull(model.view.anchor.value)
    }

    @Test
    fun theFlatListOfTasksHasNoDatesToStepThrough() = runTest(dispatcher) {
        ui.span = AgendaSpan.TASKS
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.view.stepBy(1)
        advanceUntilIdle()

        assertEquals(1, loader.dates.size)
        assertNull(model.view.anchor.value)
    }

    @Test
    fun todayBringsThePlanBackFromWhereverItWasStepped() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.view.stepBy(4)
        advanceUntilIdle()
        model.view.showToday()
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

        model.view.stepBy(1)
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

        model.view.stepBy(1)
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

        model.view.setLayout(AgendaLayout.LIST)

        assertEquals(AgendaLayout.LIST, ui.layout)
    }

    @Test
    fun droppingTheSectionHeadingsIsRememberedAndCostsNoScan() = runTest(dispatcher) {
        // No scan on purpose, unlike the span: the sections are already in
        // hand, and the setting only decides whether they announce themselves.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val scansBefore = loader.pending.size

        model.view.setGrouped(false)
        advanceUntilIdle()

        assertEquals(false, ui.grouped)
        assertEquals(false, model.view.grouped.value)
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

        model.edits.apply(task(), TaskAction.Complete)
        advanceUntilIdle()
        val failure = model.edits.editIssue.value
        assertEquals(R.string.edit_failed, failure?.text)
        assertNull(model.settings.syncState.value.message)

        model.settings.syncNow()
        advanceUntilIdle()

        assertEquals(failure, model.edits.editIssue.value)
        assertEquals(R.string.sync_cloned, model.settings.syncState.value.message?.text)
    }

    @Test
    fun anEntryOpensOnTheTextTheCoreRead() = runTest(dispatcher) {
        // The heading the agenda carries has had its markup taken off, so an
        // editor filled from it would drop the markup on the first save.
        writer.entry = Result.success(
            EntryText(title = "Write **the** report", body = "The figures are in the drive."),
        )
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        model.edits.select(task())

        model.edits.edit(task())
        advanceUntilIdle()

        assertEquals("Write **the** report", model.edits.editedEntry.value?.title)
        assertEquals("The figures are in the drive.", model.edits.editedEntry.value?.body)
        assertNull("the sheet stayed open over the editor", model.edits.selected.value)
    }

    @Test
    fun theTargetsOfAMoveAreReadWithinTheTest() = runTest(dispatcher) {
        // The walk over the collection used to run on `Dispatchers.IO`, which
        // the test scheduler does not drive: `advanceUntilIdle` came back with
        // the coroutine still suspended, and it resumed onto a main dispatcher
        // that had been reset by then. The exception that raised surfaced as a
        // failure of whichever test happened to run next.
        val model = viewModel(FakeSyncer(), mainFile = "main.md")
        advanceUntilIdle()

        model.edits.select(task())
        advanceUntilIdle()

        assertEquals("main.md", model.edits.moveTargets.value.mainFile)
    }

    @Test
    fun anEntryLongerThanTheScreenCanHoldIsNotOpened() = runTest(dispatcher) {
        // A note whose whole content sits under one heading: a field of that
        // size answers a keystroke in seconds, which is not an editor.
        writer.entry = Result.success(EntryText(title = "A note", body = "x".repeat(20_001)))
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.edit(task())
        advanceUntilIdle()

        assertNull(model.edits.editedEntry.value)
        assertEquals(R.string.entry_too_long, model.edits.editIssue.value?.text)
    }

    @Test
    fun anEntryThatCannotBeReadDoesNotOpenAnEmptyEditor() = runTest(dispatcher) {
        // An editor opened over text the core refused to hand over would save
        // an empty entry onto a file that has moved on.
        writer.entry = Result.failure(IllegalStateException("the file moved on"))
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.edit(task())
        advanceUntilIdle()

        assertNull(model.edits.editedEntry.value)
        assertEquals(R.string.edit_failed, model.edits.editIssue.value?.text)
    }

    @Test
    fun savingAnEntryWritesBothHalvesAndRebuildsTheAgenda() = runTest(dispatcher) {
        writer.entry = Result.success(EntryText(title = "A note", body = "One line."))
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        model.edits.edit(task())
        advanceUntilIdle()
        val scansBefore = loader.pending.size

        model.edits.saveEntry("A note, retitled", "Two\nlines.")
        advanceUntilIdle()

        assertEquals("A note, retitled" to "Two\nlines.", writer.saved)
        assertNull(model.edits.editedEntry.value)
        assertTrue("the agenda was not rebuilt", loader.pending.size > scansBefore)
    }

    @Test
    fun leavingTheEditorWritesNothing() = runTest(dispatcher) {
        writer.entry = Result.success(EntryText(title = "A note", body = "One line."))
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        model.edits.edit(task())
        advanceUntilIdle()

        model.edits.cancelEdit()
        advanceUntilIdle()

        assertNull(model.edits.editedEntry.value)
        assertEquals(0, writer.calls)
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

        model.edits.apply(task(), TaskAction.Complete)
        advanceUntilIdle()

        assertEquals(R.string.edit_not_committed, model.edits.editIssue.value?.text)
        assertTrue("the agenda was not rebuilt", loader.pending.size > scansBefore)
    }

    @Test
    fun anEditNamesTheNoteItChangedSoTheAgendaCostsThatNote() = runTest(dispatcher) {
        // Without this the agenda after every tap re-read the whole
        // collection: on a device with a thousand notes, seconds of it for a
        // change to one line.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.apply(task(file = "projects/plan.md"), TaskAction.Complete)
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

        model.edits.apply(task(), TaskAction.Complete)
        advanceUntilIdle()

        assertNull(model.edits.editIssue.value)
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

        model.settings.syncNow()
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

        model.settings.syncNow()
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

        model.edits.apply(task(), TaskAction.Complete)
        advanceUntilIdle()
        model.edits.editIssueShown()

        assertNull(model.edits.editIssue.value)
    }

    @Test
    fun aTaskWhoseFileNameIsNotUtf8IsRefusedWithoutReachingTheCore() = runTest(dispatcher) {
        // The path arrived with U+FFFD in place of bytes that are not UTF-8,
        // so it names nothing on disk. Going through the core would come back
        // as "file not found", which reads as the note having been deleted.
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.edits.apply(task(file = "bad�name.md"), TaskAction.Complete)
        advanceUntilIdle()

        assertEquals(0, writer.calls)
        assertEquals(R.string.edit_failed_unnamed, model.edits.editIssue.value?.text)
        assertNull(model.settings.syncState.value.message)
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

            model.settings.syncNow()
            advanceUntilIdle()

            assertEquals(readsBefore, syncer.statusReads)
            assertEquals(head, model.settings.syncState.value.repository)
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
                    pushFailure = SyncException.Rejected("main", "fetch first", stale = true),
                ),
            )
        }
        settings.remoteUrl = REMOTE
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.settings.syncNow()
        advanceUntilIdle()

        assertEquals(R.string.sync_failed_rejected, model.settings.syncState.value.message?.text)
        assertTrue(model.settings.syncState.value.message?.failed == true)
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

        model.settings.syncNow()
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

        model.settings.saveSettings(url = OTHER_REMOTE, branch = "main", token = "")
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

        model.settings.saveSettings(url = REMOTE, branch = "notes", token = "")
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

        model.settings.saveSettings(url = OTHER_REMOTE, branch = "main", token = "")
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

        model.settings.saveSettings(url = OTHER_REMOTE, branch = "main", token = "")
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

        model.settings.saveSettings(url = REMOTE, branch = "main", token = "", dropKey = true)
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

        model.settings.saveSettings(url = REMOTE, branch = "main", token = "", dropToken = true)
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

        model.settings.saveSettings(
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

        model.settings.saveSettings(url = REMOTE, branch = "notes", token = "")
        advanceUntilIdle()

        assertEquals(listOf(REMOTE), syncer.requested)
        assertTrue(syncer.adopted.isEmpty())
    }

    @Test
    fun theChosenDirectoryBecomesTheWorkingCopy() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.settings.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
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

        model.settings.saveSettings(url = "", branch = "", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertEquals(1, loader.invalidations)
    }

    @Test
    fun anEmptyDirectoryFieldPutsTheNotesBackInTheOwnStorage() = runTest(dispatcher) {
        val syncer = FakeSyncer()
        notes = FakeNotesArea(File(SHARED))
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.settings.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = "")
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

        model.settings.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertEquals(DEFAULT_NOTES, storedPath)
        assertEquals(R.string.settings_notes_failed, model.settings.syncState.value.message?.text)
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

        model.settings.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
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

        model.settings.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
        advanceUntilIdle()

        assertEquals(DEFAULT_NOTES, storedPath)
        assertFalse("the directory was moved into anyway", notes.trace.contains("move"))
        assertEquals(R.string.settings_notes_denied, model.settings.syncState.value.message?.text)
    }

    @Test
    fun theDirectoryCanBeSavedWithoutARemoteAtAll() = runTest(dispatcher) {
        // Notes already on the device need no repository. The form used to
        // refuse an empty address outright, which left no way to point the
        // application at them.
        val syncer = FakeSyncer()
        val model = viewModel(syncer)
        advanceUntilIdle()

        model.settings.saveSettings(url = "", branch = "", token = "", notesPath = SHARED)
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

        model.settings.saveSettings(url = "", branch = "", token = "", notesPath = SHARED)
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

        model.settings.saveSettings(url = REMOTE, branch = "main", token = "", notesPath = SHARED)
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

        model.edits.applyToGroup(rows, BulkAction.MOVE_TO_TODAY)
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

        model.edits.applyToGroup(listOf(task().toAgendaRow()), BulkAction.CANCEL)
        advanceUntilIdle()

        val result = model.edits.groupResult.value
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

        model.edits.applyToGroup(listOf(task().toAgendaRow()), BulkAction.DROP_PLANNING)
        advanceUntilIdle()

        assertFalse(
            "an undo was offered for nothing",
            model.edits.groupResult.value?.canUndo == true,
        )
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
        model.edits.applyToGroup(listOf(task().toAgendaRow()), BulkAction.MOVE_TO_TODAY)
        advanceUntilIdle()

        model.edits.undoGroup()
        advanceUntilIdle()

        assertEquals(listOf(written), writer.undone)
        // The offer is gone with it: pressing undo twice would put the notes
        // back over an edit made in between.
        assertNull(model.edits.groupResult.value)
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
        model.edits.applyToGroup(listOf(task().toAgendaRow()), BulkAction.MOVE_TO_TODAY)
        advanceUntilIdle()

        model.edits.undoGroup()
        advanceUntilIdle()

        assertEquals(
            R.string.agenda_group_undo_partial,
            model.edits.editIssue.value?.text,
        )
    }

    @Test
    fun aTapThatWroteToANoteOffersToPutItBack() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val written = rollback("notes.md")
        writer.outcome = Result.success(EditReport(committed = true, rollback = listOf(written)))

        model.edits.apply(task(heading = "Pay the tax"), TaskAction.Complete)
        advanceUntilIdle()

        val result = model.edits.editResult.value
        assertEquals(listOf(written), result?.rollback)
        // Both halves of the address travel with it: the same relative path
        // occurs in more than one collection, and the undo commits by name.
        assertEquals("/notes", result?.root)
        assertEquals("Pay the tax", result?.heading)
    }

    @Test
    fun aTapThatChangedNothingIsNotOfferedAnUndo() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        // The task already stood that way, so the core wrote nothing and
        // handed back no pair to put back.
        writer.outcome = Result.success(EditReport(committed = true))

        model.edits.apply(task(), TaskAction.Complete)
        advanceUntilIdle()

        assertNull(model.edits.editResult.value)
    }

    @Test
    fun theUndoOfOneTapHandsBackWhatThatTapOverwrote() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val written = rollback("notes.md")
        writer.outcome = Result.success(EditReport(committed = true, rollback = listOf(written)))
        writer.undoOutcome = Result.success(
            UndoReport(
                outcome = RevertOutcome(
                    restored = listOf("notes.md"),
                    skipped = emptyList(),
                    failed = emptyList(),
                ),
                report = EditReport(committed = true),
            ),
        )
        model.edits.apply(task(heading = "Pay the tax"), TaskAction.Complete)
        advanceUntilIdle()

        model.edits.undoEdit()
        advanceUntilIdle()

        assertEquals(listOf(written), writer.undone)
        assertEquals("Pay the tax", writer.undoneEdit)
        // The offer goes with it, for the reason the group's does: pressing it
        // twice would put the note back over whatever came after.
        assertNull(model.edits.editResult.value)
    }

    @Test
    fun aTaskWrittenFromNothingIsOfferedToBeTakenOutAgain() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val written = rollback("inbox.md")
        writer.outcome = Result.success(EditReport(committed = true, rollback = listOf(written)))

        model.edits.createTask(FIRST_ID, TaskDraft(title = "Pay the tax"))
        advanceUntilIdle()

        val result = model.edits.editResult.value
        assertEquals(listOf(written), result?.rollback)
        assertEquals("Pay the tax", result?.heading)
        // Marked as a creation, because what the offer takes back is the whole
        // entry rather than a line of one that stays either way -- and the
        // line on screen says so.
        assertEquals(true, result?.created)
        // The note it went into is re-read by name, so the agenda that follows
        // costs one file rather than a walk of the collection.
        assertEquals(listOf("inbox.md"), loader.reread)
    }

    /**
     * The rules of the phrase set an hour without a day, and a planning line
     * cannot hold one. The hour used to be dropped between the draft and the
     * file; it is given the next day that hour comes round on instead.
     */
    @Test
    fun anHourSaidWithNoDayIsWrittenForTheDayItStillComesRoundOn() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.createTask(
            FIRST_ID,
            TaskDraft(title = "Позвонить врачу", time = LocalTime.of(15, 0)),
        )
        advanceUntilIdle()

        // Noon on this clock, so three in the afternoon is still ahead.
        assertEquals(NOON.toLocalDate(), writer.created?.second?.date)
        assertEquals(LocalTime.of(15, 0), writer.created?.second?.time)
    }

    @Test
    fun anHourAlreadyPastIsWrittenForTomorrow() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.createTask(
            FIRST_ID,
            TaskDraft(title = "Позвонить врачу", time = LocalTime.of(9, 0)),
        )
        advanceUntilIdle()

        assertEquals(NOON.toLocalDate().plusDays(1), writer.created?.second?.date)
    }

    /**
     * The day was chosen rather than asked for, so the line that says the task
     * was written says which day and on what grounds -- an entry on tomorrow
     * with no word about it reads as the hour having been misheard.
     */
    @Test
    fun theLineOnScreenSaysWhichDayWasChosenAndWhy() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        writer.outcome = Result.success(
            EditReport(committed = true, rollback = listOf(rollback("inbox.md"))),
        )

        model.edits.createTask(
            FIRST_ID,
            TaskDraft(title = "Позвонить врачу", time = LocalTime.of(9, 0)),
        )
        advanceUntilIdle()

        val assumed = model.edits.editResult.value?.assumedDay
        assertEquals(NOON.toLocalDate().plusDays(1), assumed?.date)
        assertEquals(LocalTime.of(9, 0), assumed?.hour)
        assertEquals(true, assumed?.passed)
    }

    /** A task that named its own day is written for it, and nothing is said. */
    @Test
    fun aDayThatWasNamedIsLeftAloneAndNotExplained() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        writer.outcome = Result.success(
            EditReport(committed = true, rollback = listOf(rollback("inbox.md"))),
        )

        model.edits.createTask(
            FIRST_ID,
            TaskDraft(
                title = "Позвонить врачу",
                date = LocalDate.of(2026, 8, 1),
                time = LocalTime.of(9, 0),
            ),
        )
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 8, 1), writer.created?.second?.date)
        assertNull(model.edits.editResult.value?.assumedDay)
    }

    @Test
    fun aTaskIsWrittenWhereItsCollectionSaysEntriesGo() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        store.collections = store.collections.map { it.copy(writeAt = WritePosition.END) }
        model.settings.saveSettings(url = "", branch = "", token = "", writeAt = WritePosition.END)
        advanceUntilIdle()

        model.edits.createTask(FIRST_ID, TaskDraft(title = "Pay the tax"))
        advanceUntilIdle()

        // The setting travels with the write rather than being read inside the
        // core: the core is given a directory and a file, and where in that
        // file an entry goes is what the collection was set to.
        assertEquals(WritePosition.END, writer.createdAt)
    }

    @Test
    fun theMomentTheEntryIsWrittenAtTravelsWithIt() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.createTask(FIRST_ID, TaskDraft(title = "Pay the tax"))
        advanceUntilIdle()

        // From this model's clock rather than the device's, for the reason
        // every other date here comes from it: a test that could not say what
        // time it is would have nothing to compare against, and a screen shown
        // over midnight would otherwise mark yesterday. To the minute, because
        // that is what tells apart two entries written the same day.
        assertEquals(NOON, writer.createdOn)
    }

    @Test
    fun anEntryIsCarriedIntoTheFileTheSheetNamed() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val left = rollback("notes.md")
        val reached = rollback("main.md")
        writer.moveOutcome = Result.success(
            MoveReport(
                outcome = MoveOutcome(
                    line = "# TODO Pay the tax",
                    file = "main.md",
                    rollback = listOf(left, reached),
                ),
                report = EditReport(committed = true),
            ),
        )

        model.edits.apply(task(heading = "Pay the tax"), TaskAction.MoveToFile("main.md"))
        advanceUntilIdle()

        // The file, and the place in it the collection writes entries at.
        assertEquals("main.md" to WritePosition.START, writer.movedTo)
        // Both notes come back for the undo: the entry has to go out of the
        // one it reached as well as back into the one it left.
        assertEquals(listOf(left, reached), model.edits.editResult.value?.rollback)
        // Both notes are re-read by name: the entry has to appear in the file
        // it reached and stop appearing in the file it left.
        assertEquals(listOf("notes.md", "main.md"), loader.reread)
    }

    @Test
    fun undoingAMoveNamesBothNotesAndReadsThemBack() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val left = rollback("notes.md")
        val reached = rollback("main.md")
        writer.moveOutcome = Result.success(
            MoveReport(
                outcome = MoveOutcome(
                    line = "",
                    file = "main.md",
                    rollback = listOf(left, reached),
                ),
                report = EditReport(committed = true),
            ),
        )
        writer.undoOutcome = Result.success(
            UndoReport(
                outcome = RevertOutcome(
                    restored = listOf("notes.md", "main.md"),
                    skipped = emptyList(),
                    failed = emptyList(),
                ),
                report = EditReport(committed = true),
            ),
        )
        model.edits.apply(task(heading = "Pay the tax"), TaskAction.MoveToFile("main.md"))
        advanceUntilIdle()
        loader.reread.clear()

        model.edits.undoEdit()
        advanceUntilIdle()

        // Not through the undo of a creation: nothing was written from
        // nothing, and both files go back to what they held.
        assertEquals("Pay the tax", writer.undoneEdit)
        assertEquals(listOf(left, reached), writer.undone)
        assertEquals(listOf("notes.md", "main.md"), loader.reread)
    }

    @Test
    fun theUndoOfACreationTakesTheEntryOutRatherThanPuttingALineBack() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        val written = rollback("inbox.md")
        writer.outcome = Result.success(EditReport(committed = true, rollback = listOf(written)))
        writer.undoOutcome = Result.success(
            UndoReport(
                outcome = RevertOutcome(
                    restored = listOf("inbox.md"),
                    skipped = emptyList(),
                    failed = emptyList(),
                ),
                report = EditReport(committed = true),
            ),
        )
        model.edits.createTask(FIRST_ID, TaskDraft(title = "Pay the tax"))
        advanceUntilIdle()

        model.edits.undoEdit()
        advanceUntilIdle()

        // Through the undo that says so in the history: the same file goes
        // back either way, and the commit above it is what a reader sees.
        assertEquals("Pay the tax", writer.undoneCreation)
        assertNull(writer.undoneEdit)
        assertEquals(listOf(written), writer.undone)
    }

    @Test
    fun aNoteThatMovedOnBetweenTheTapAndTheUndoSaysSo() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        writer.outcome = Result.success(
            EditReport(committed = true, rollback = listOf(rollback("notes.md"))),
        )
        writer.undoOutcome = Result.success(
            UndoReport(
                outcome = RevertOutcome(
                    restored = emptyList(),
                    skipped = listOf("notes.md"),
                    failed = emptyList(),
                ),
                report = EditReport(committed = true),
            ),
        )
        model.edits.apply(task(), TaskAction.Complete)
        advanceUntilIdle()

        model.edits.undoEdit()
        advanceUntilIdle()

        assertEquals(R.string.agenda_edit_undo_skipped, model.edits.editIssue.value?.text)
    }

    @Test
    fun anUndoneMoveThatOnlyHalfWentBackSaysSo() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        // A move writes two files — the one the entry left and the one it
        // arrived in — so its undo has two to put back.
        writer.outcome = Result.success(
            EditReport(
                committed = true,
                rollback = listOf(rollback("notes.md"), rollback("inbox.md")),
            ),
        )
        writer.undoOutcome = Result.success(
            UndoReport(
                outcome = RevertOutcome(
                    restored = listOf("notes.md"),
                    skipped = emptyList(),
                    failed = listOf("inbox.md"),
                ),
                report = EditReport(committed = true),
            ),
        )
        model.edits.apply(task(), TaskAction.Complete)
        advanceUntilIdle()

        model.edits.undoEdit()
        advanceUntilIdle()

        // One file back and the other not is the entry standing in both notes
        // or in neither — reported as an undo that worked, it is a state the
        // reader has no reason to go looking for.
        assertEquals(R.string.agenda_edit_undo_partial, model.edits.editIssue.value?.text)
    }

    @Test
    fun aBandOfNotesWithUnnamedFilesIsRefusedBeforeItReachesTheCore() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        // A filename that is not UTF-8 arrives with U+FFFD and names nothing
        // on disk; every edit aimed at it would come back as "file not found".
        val rows = listOf(task(file = "notes�.md").toAgendaRow())

        model.edits.applyToGroup(rows, BulkAction.CANCEL)
        advanceUntilIdle()

        assertEquals(0, writer.calls)
        assertEquals(R.string.edit_failed_unnamed, model.edits.editIssue.value?.text)
    }

    @Test
    fun theSpanThatWasChosenIsTheSpanTheCoreIsAskedFor() = runTest(dispatcher) {
        // The grouping is the core's: a week is the same notes read against
        // seven dates, and nothing on this side can regroup a day into one.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        model.view.setSpan(AgendaSpan.WEEK)
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

        // The calendar is what a month opens as, and the calendar is drawn on
        // whole weeks -- so that is the scope, not the month alone.
        assertEquals(listOf(Scope.MONTH_GRID), loader.scopes)
        assertEquals(AgendaSpan.MONTH, model.view.span.value)
    }

    @Test
    fun theMonthReadAsAListIsAskedForAsTheMonthAlone() = runTest(dispatcher) {
        // The list of a month is that month: the days either side of it belong
        // to the months before and after, and a list that stated them would be
        // answering about a month nobody asked for.
        ui.span = AgendaSpan.MONTH
        ui.monthAsGrid = false

        viewModel(FakeSyncer())
        advanceUntilIdle()

        assertEquals(listOf(Scope.MONTH), loader.scopes)
    }

    @Test
    fun switchingBetweenTheTwoReadingsOfAMonthAsksAgain() = runTest(dispatcher) {
        // The two are no longer one answer read two ways: the grid needs the
        // weeks around the month, and the list does not carry them.
        ui.span = AgendaSpan.MONTH

        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        model.view.setMonthAsGrid(false)
        advanceUntilIdle()

        assertEquals(listOf(Scope.MONTH_GRID, Scope.MONTH), loader.scopes)
    }

    @Test
    fun theWeekdayAWeekBeginsOnIsPassedToTheCore() = runTest(dispatcher) {
        // The core reads no locale and defaults to Monday; the phone knows how
        // its owner reads a calendar, so it says. The grid scope is refused
        // without it.
        ui.span = AgendaSpan.MONTH

        viewModel(FakeSyncer())
        advanceUntilIdle()

        assertEquals(
            listOf(WeekFields.of(Locale.getDefault()).firstDayOfWeek),
            loader.weekStarts,
        )
    }

    @Test
    fun theStoredWeekStartWinsOverTheOneThePhoneWouldGive() = runTest(dispatcher) {
        // The reader whose habit and whose phone disagree: a stated Sunday is
        // what the core is told, whatever the locale says.
        ui.span = AgendaSpan.MONTH
        ui.weekStart = WeekStart.SUNDAY

        viewModel(FakeSyncer())
        advanceUntilIdle()

        assertEquals(listOf(DayOfWeek.SUNDAY), loader.weekStarts)
    }

    @Test
    fun changingWhereAWeekStartsAsksForTheAgendaAgain() = runTest(dispatcher) {
        // The cut into weeks is the core's, so the calendar on screen cannot
        // be redrawn from the answer already in hand.
        ui.span = AgendaSpan.MONTH

        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        model.view.setWeekStart(WeekStart.SUNDAY)
        advanceUntilIdle()

        assertEquals(
            listOf(WeekFields.of(Locale.getDefault()).firstDayOfWeek, DayOfWeek.SUNDAY),
            loader.weekStarts,
        )
        assertEquals(WeekStart.SUNDAY, ui.weekStart)
    }

    @Test
    fun changingWhereAWeekStartsFromTheDayViewCostsNoScan() = runTest(dispatcher) {
        // Nothing on screen is cut into weeks, so there is nothing to ask
        // again for; the choice is stored and the next week or month uses it.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        model.view.setWeekStart(WeekStart.SUNDAY)
        advanceUntilIdle()

        assertEquals(1, loader.scopes.size)
        assertEquals(WeekStart.SUNDAY, ui.weekStart)
    }

    @Test
    fun aWeekArrivesWithItsDaysStillApart() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()
        model.view.setSpan(AgendaSpan.WEEK)
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
        model.view.setSpan(AgendaSpan.TASKS)
        advanceUntilIdle()

        loader.pending.last().complete(
            Result.success(flatAgenda(task(heading = "Someday", daysOffset = null))),
        )
        advanceUntilIdle()

        val ready = model.state.value as AgendaUiState.Ready
        assertNull(ready.days.single().date)
        assertEquals(listOf("Someday"), ready.sections.untimed.map { it.task.heading })
    }

    @Test
    fun `a plan that could not be made is said on the settings screen`() = runTest(dispatcher) {
        val model = viewModel(
            FakeSyncer(),
            notesChanged = { onFailure -> onFailure(IOException("the directory is not there")) },
        )
        advanceUntilIdle()

        model.replanReminders()
        advanceUntilIdle()

        // A switch that planned nothing is otherwise indistinguishable from
        // one that worked: the reminders simply do not arrive, days later.
        val message = model.settings.syncState.value.message
        assertEquals(R.string.reminders_plan_failed, message?.text)
        assertTrue("the message does not read as a failure", message?.failed == true)
    }

    /**
     * The model over one collection, which is what a device that has not been
     * set up past the first directory works with.
     *
     * The stand-ins are the ones the assertions read, so the collection is
     * built around them rather than the other way round.
     */
    private fun viewModel(
        syncer: FakeSyncer,
        mainFile: String = "",
        notesChanged: ((Throwable) -> Unit) -> Unit = {},
    ): AgendaViewModel {
        // Stored to match the area the assertions read, so a test that starts
        // from another directory says so in one place.
        store.collections = listOf(
            NotesCollection(
                id = FIRST_ID,
                name = "Notes",
                path = notes.root.absolutePath,
                mainFile = mainFile,
            ),
        )

        return AgendaViewModel(
            collections = FakeCollections.one(
                area = notes,
                settings = settings,
                editor = writer,
                syncer = syncer,
                mainFile = mainFile,
            ),
            stored = store,
            agenda = loader,
            ui = ui,
            ownNotes = own,
            sample = testWording,
            storageGranted = { granted },
            notesChanged = notesChanged,
            clock = { moment },
            io = dispatcher,
        )
    }

    @Test
    fun aDateChosenInTheCalendarReachesTheNoteAsThatDay() = runTest(dispatcher) {
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.apply(
            task(timestampType = null, date = null),
            TaskAction.Plan(PlanningKeyword.DEADLINE, LocalDate.of(2026, 8, 19)),
        )
        advanceUntilIdle()

        assertEquals(PlanningKeyword.DEADLINE to LocalDate.of(2026, 8, 19), writer.planned)
    }

    @Test
    fun cancellingOneOccurrenceReachesTheWriterWithTheDayTheRowStandsOn() = runTest(dispatcher) {
        // The day a repeating row was drawn on, not the anchor written in the
        // file: the core rewrites the date of the copy it puts in a day.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.apply(
            task(repeater = "+1w", date = "2026-08-20"),
            TaskAction.CancelOccurrence(LocalDate.of(2026, 8, 20)),
        )
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 8, 20), writer.cancelled)
    }

    @Test
    fun movingOneOccurrenceCarriesTheDayItLeftAlongWithTheNewOne() = runTest(dispatcher) {
        // Which occurrence is being replaced is not the new date: an
        // occurrence moved to another day names both, and the core needs the
        // first to say what the entry stands in for.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.apply(
            task(repeater = "+1w", date = "2026-08-20", time = "15:00"),
            TaskAction.MoveOccurrence(
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 21),
                LocalTime.of(18, 0),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            Triple(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 21), LocalTime.of(18, 0)),
            writer.moved,
        )
    }

    @Test
    fun takingADateOffIsTheSameActionWithNoDayInIt() = runTest(dispatcher) {
        // One action for both, because the file is written by one call: a
        // second one for clearing would be a second refusal to translate and a
        // second message in the history.
        val model = viewModel(FakeSyncer())
        advanceUntilIdle()

        model.edits.apply(task(), TaskAction.Plan(PlanningKeyword.SCHEDULED, null))
        advanceUntilIdle()

        assertEquals(PlanningKeyword.SCHEDULED to null, writer.planned)
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
