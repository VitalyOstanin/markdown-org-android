package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.EditReport
import io.github.vitalyostanin.markdownorg.core.GroupReport
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.testWording
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.BulkOutcome
import uniffi.markdown_org_ffi.FileRollback
import uniffi.markdown_org_ffi.SyncException
import java.io.File
import java.time.LocalDateTime

/**
 * What the agenda does once there is more than one collection.
 *
 * The single-collection behaviour is asserted in [AgendaViewModelTest]; this
 * covers what only two of them can go wrong at — an edit landing in the wrong
 * directory, a group rewriting one collection instead of both, and the filter
 * that decides how much of the agenda is on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaCollectionsTest {

    private val dispatcher = StandardTestDispatcher()

    private val loader = FakeAgendaLoader()
    private val ui = FakeUiPreferences()

    /** The working copy of the personal notes, and what acts on it. */
    private val personalWriter = FakeWriter()
    private val personal = FakeCollections.entry(
        id = "1",
        name = "Personal",
        path = PERSONAL,
        editor = personalWriter,
    )

    /** The working copy of the work notes, kept apart from [personal]. */
    private val workWriter = FakeWriter()
    private val work = FakeCollections.entry(
        id = "2",
        name = "Work",
        path = WORK,
        editor = workWriter,
    )

    private val collections = FakeCollections(listOf(personal, work))

    private val store = FakeCollectionsStore(
        listOf(
            NotesCollection(id = "1", name = "Personal", path = PERSONAL),
            NotesCollection(id = "2", name = "Work", path = WORK),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anEditGoesToTheCollectionTheTaskCameFrom() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.apply(task(heading = "Write the report", root = WORK), TaskAction.Complete)
        advanceUntilIdle()

        // The same relative path exists in both directories, so an edit that
        // went by path alone would strike whatever note sits at `notes.md` in
        // the collection the settings happen to be about.
        assertEquals(0, personalWriter.calls)
        assertEquals(1, workWriter.calls)
    }

    @Test
    fun aReReadNamesBothTheCollectionAndTheFile() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.apply(task(heading = "Write the report", root = WORK), TaskAction.Complete)
        advanceUntilIdle()

        assertEquals(listOf(WORK), loader.rereadRoots)
        assertEquals(listOf("notes.md"), loader.reread)
    }

    @Test
    fun aTaskFromACollectionThatIsGoneIsRefusedRatherThanMisdirected() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.apply(task(heading = "Orphan", root = "/gone"), TaskAction.Complete)
        advanceUntilIdle()

        assertEquals(0, personalWriter.calls)
        assertEquals(0, workWriter.calls)
        assertEquals(R.string.edit_failed_no_collection, model.editIssue.value?.text)
    }

    @Test
    fun aBandSpanningTwoCollectionsIsOnePassOverEach() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.applyToGroup(
            listOf(
                task(heading = "Pay the tax", root = PERSONAL).toAgendaRow(),
                task(heading = "Renew the licence", line = 2u, root = PERSONAL).toAgendaRow(),
                task(heading = "Write the report", root = WORK).toAgendaRow(),
            ),
            BulkAction.MOVE_TO_TODAY,
        )
        advanceUntilIdle()

        // One request per collection, not one per task and not one for the lot:
        // each directory is rewritten once and committed once.
        assertEquals(1, personalWriter.calls)
        assertEquals(1, workWriter.calls)
        assertEquals(
            listOf("Pay the tax", "Renew the licence"),
            personalWriter.group?.second?.map { it.heading },
        )
        assertEquals(listOf("Write the report"), workWriter.group?.second?.map { it.heading })
    }

    @Test
    fun theUndoOfferedForABandCarriesTheCollectionOfEachFile() = runTest(dispatcher) {
        personalWriter.groupOutcome = groupReport(rollback("todo.md"))
        workWriter.groupOutcome = groupReport(rollback("todo.md"))
        val model = viewModel()
        advanceUntilIdle()

        model.applyToGroup(
            listOf(
                task(heading = "Pay the tax", root = PERSONAL).toAgendaRow(),
                task(heading = "Write the report", root = WORK).toAgendaRow(),
            ),
            BulkAction.MOVE_TO_TODAY,
        )
        advanceUntilIdle()

        // Both hold a file called `todo.md`, and the rollback is applied by
        // whoever owns the directory: without the root beside it, one
        // collection's text would be written over the other's file.
        val result = model.groupResult.value!!
        assertEquals(listOf(PERSONAL, WORK), result.rollback.map { it.root })
        assertTrue(result.canUndo)
    }

    @Test
    fun eachCollectionIsOfferedAsAFilter() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        assertEquals(
            listOf("Personal", "Work"),
            model.collectionFilter.value.map { it.label.name },
        )
        assertTrue(model.collectionFilter.value.all { it.shown })
        // A colour apiece, so the mark on a row and the chip above it agree.
        assertEquals(listOf(0, 1), model.collectionFilter.value.map { it.label.tone })
    }

    @Test
    fun hidingACollectionTakesItsRowsOffTheAgendaWithoutAnotherScan() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        loader.pending[0].complete(
            Result.success(
                agenda(
                    day(
                        scheduledNoTime = listOf(
                            task(heading = "Pay the tax", root = PERSONAL),
                            task(heading = "Write the report", root = WORK),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        val scansBefore = loader.pending.size

        model.setCollectionShown("2", shown = false)

        assertEquals(
            listOf("Pay the tax"),
            (model.state.value as AgendaUiState.Ready).sections.untimed.map { it.task.heading },
        )
        // The notes have not changed — only how much of them is shown. A walk
        // of the directories for a tap on a chip is the whole cost this
        // avoids.
        assertEquals(scansBefore, loader.pending.size)
        assertFalse(model.collectionFilter.value.single { it.label.id == "2" }.shown)
    }

    @Test
    fun showingACollectionAgainBringsItsRowsBack() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        loader.pending[0].complete(
            Result.success(
                agenda(
                    day(
                        scheduledNoTime = listOf(
                            task(heading = "Pay the tax", root = PERSONAL),
                            task(heading = "Write the report", root = WORK),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        model.setCollectionShown("2", shown = false)
        model.setCollectionShown("2", shown = true)

        assertEquals(
            listOf("Pay the tax", "Write the report"),
            (model.state.value as AgendaUiState.Ready).sections.untimed.map { it.task.heading },
        )
    }

    @Test
    fun aHiddenCollectionStaysHiddenAcrossTheNextScan() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()

        model.setCollectionShown("2", shown = false)
        model.refresh()
        advanceUntilIdle()
        loader.pending.last().complete(
            Result.success(
                agenda(
                    day(
                        scheduledNoTime = listOf(
                            task(heading = "Pay the tax", root = PERSONAL),
                            task(heading = "Write the report", root = WORK),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        // Every edit ends in a scan, and a filter undone by the agenda
        // rebuilding itself would last until the next tap.
        assertEquals(
            listOf("Pay the tax"),
            (model.state.value as AgendaUiState.Ready).sections.untimed.map { it.task.heading },
        )
    }

    @Test
    fun aRunOverTwoCollectionsSaysWhatEachOfThemAnswered() = runTest(dispatcher) {
        val personalSync = FakeSyncer()
        val workSync = FakeSyncer { Result.failure(SyncException.Network("no route")) }
        val model = viewModel(
            FakeCollections(
                listOf(
                    personal.copy(settings = configured(), syncer = personalSync),
                    work.copy(settings = configured(), syncer = workSync),
                ),
            ),
        )
        advanceUntilIdle()

        model.syncNow()
        advanceUntilIdle()

        // The banner ends up describing the last collection of the run, so
        // without a line apiece a failure in the middle would leave no trace
        // on screen.
        assertEquals(
            listOf("Personal", "Work"),
            model.syncState.value.runs.map { it.name },
        )
        assertEquals(
            listOf(false, true),
            model.syncState.value.runs.map { it.message.failed },
        )
    }

    @Test
    fun anAddedCollectionIsStoredAndBecomesTheOneBeingEdited() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.addCollection("Archive")
        advanceUntilIdle()

        // Stored as well as put to use: a set the next launch does not read is
        // a collection that disappears when the process does.
        assertEquals(listOf("Personal", "Work", "Archive"), store.collections.map { it.name })
        assertEquals(
            listOf("Personal", "Work", "Archive"),
            model.collectionSet.value.map { it.name },
        )
        assertEquals("3", model.editingId.value)
        // A directory inside the application's own storage, which is there to
        // be written to whatever the platform grants.
        assertEquals("/data/data/markdown-org/files/notes-3", store.collections.last().path)
    }

    /**
     * The directory of a collection is made before anything walks it.
     *
     * A new collection names a directory nobody has created, and the walk
     * refuses a root it cannot open — with the whole agenda, so the other
     * collections go dark too. On a device this read as "the agenda could not
     * be built: directory does not exist" the moment a second collection was
     * added.
     */
    @Test
    fun theDirectoryOfAnAddedCollectionIsMadeBeforeTheWalkReachesIt() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.addCollection("Archive")
        advanceUntilIdle()

        val added = collections.entries.last().area as FakeNotesArea

        assertEquals("/data/data/markdown-org/files/notes-3", added.root.absolutePath)
        assertTrue("the directory was never made", added.trace.contains("prepare"))
    }

    /**
     * And on a launch, not only when the set changes.
     *
     * The set a launch reads was stored earlier, and a directory in it may be
     * gone by then — deleted by hand, or on storage that is not mounted. Left
     * to the scan, that is again the whole agenda failing rather than the one
     * collection.
     */
    @Test
    fun theDirectoriesOfTheStoredSetAreMadeOnALaunch() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()

        assertEquals(
            listOf(true, true),
            collections.entries.map { (it.area as FakeNotesArea).trace.contains("prepare") },
        )
    }

    /**
     * A directory that cannot be made is said so, and the rest goes on.
     *
     * The storage a collection sits on may be unplugged, or the permission to
     * write it withdrawn. Stopping there would leave a device with no agenda
     * at all over one directory it cannot reach, so the scan is asked for
     * anyway and the failure is carried by the banner.
     */
    @Test
    fun aDirectoryThatCannotBeMadeIsReportedRatherThanStoppingTheScan() = runTest(dispatcher) {
        (work.area as FakeNotesArea).prepareResult =
            Result.failure(IllegalStateException("no such volume"))

        val model = viewModel()
        advanceUntilIdle()

        assertEquals(R.string.settings_notes_failed, model.syncState.value.message?.text)
        assertTrue("the scan was never asked for", loader.pending.isNotEmpty())
    }

    @Test
    fun aRemovedCollectionLosesItsSettingsButKeepsItsDirectory() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.removeCollection("2")
        advanceUntilIdle()

        assertEquals(listOf("Personal"), store.collections.map { it.name })
        // The token that reached that server has no one left to belong to;
        // the directory is not this screen's to delete.
        assertEquals(listOf("2"), collections.forgotten)
        assertEquals("1", model.editingId.value)
    }

    @Test
    fun theLastCollectionIsNotRemoved() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.removeCollection("2")
        advanceUntilIdle()

        model.removeCollection("1")
        advanceUntilIdle()

        // An agenda over nothing has no way back except a reinstall.
        assertEquals(listOf("Personal"), store.collections.map { it.name })
        assertEquals(listOf("2"), collections.forgotten)
    }

    @Test
    fun savingTheFormRenamesTheCollectionItIsAbout() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.editCollection("2")

        model.saveSettings(url = "", branch = "", token = "", name = "Office")
        advanceUntilIdle()

        assertEquals(listOf("Personal", "Office"), store.collections.map { it.name })
    }

    @Test
    fun aCollectionIsNotLeftWithoutAName() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.saveSettings(url = "", branch = "", token = "", name = "  ")
        advanceUntilIdle()

        assertEquals(listOf("Personal", "Work"), store.collections.map { it.name })
        assertEquals(R.string.collection_name_empty, model.syncState.value.message?.text)
    }

    @Test
    fun aSingleCollectionIsOfferedNoFilterAtAll() = runTest(dispatcher) {
        val model = AgendaViewModel(
            collections = FakeCollections(listOf(personal)),
            stored = FakeCollectionsStore(
                listOf(NotesCollection(id = "1", name = "Personal", path = PERSONAL)),
            ),
            agenda = loader,
            ui = ui,
            ownNotes = OWN,
            sample = testWording,
            storageGranted = { true },
            clock = { NOON },
        )
        advanceUntilIdle()

        // One chip either shows everything or nothing, and a mark on every row
        // would be a column of the same word.
        assertTrue(model.collectionFilter.value.isEmpty())
    }

    /** A group action that changed one file and can be put back. */
    private fun groupReport(rollback: FileRollback) = Result.success(
        GroupReport(
            outcome = BulkOutcome(
                changed = 1u,
                refused = emptyList(),
                rollback = listOf(rollback),
            ),
            report = EditReport(committed = true),
        ),
    )

    private fun rollback(file: String) = FileRollback(
        file = file,
        before = "# TODO Pay the tax\n",
        after = "# DONE Pay the tax\n",
    )

    /** Settings with a remote, so the collection takes part in a sync run. */
    private fun configured() = FakePreferences(remoteUrl = "https://example.test/notes.git")

    private fun viewModel(inUse: FakeCollections = collections): AgendaViewModel = AgendaViewModel(
        collections = inUse,
        stored = store,
        agenda = loader,
        ui = ui,
        ownNotes = OWN,
        sample = testWording,
        storageGranted = { true },
        clock = { NOON },
    )

    private companion object {
        const val PERSONAL = "/notes/personal"
        const val WORK = "/notes/work"

        /** The directory the application owns, as on a device. */
        val OWN = File("/data/data/markdown-org/files/notes")
        val NOON: LocalDateTime = LocalDateTime.parse("2026-07-28T12:00")
    }
}
