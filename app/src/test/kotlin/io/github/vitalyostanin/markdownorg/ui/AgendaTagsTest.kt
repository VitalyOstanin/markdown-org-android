package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.core.MergedTag
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.TAGS_FILE
import io.github.vitalyostanin.markdownorg.core.testWording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/**
 * The second of the two filters over the agenda.
 *
 * Which collections are read decides what is on screen at all; a tag selects
 * among those notes by the name of the file each entry came from. The rules of
 * the merge are asserted in `TagDictionaryTest`; what only the model can answer
 * is whether the files under the collections are read at all, whether the tag
 * survives what a sync brings in, and whether choosing one costs a scan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaTagsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val loader = FakeAgendaLoader()
    private val ui = FakeUiPreferences()

    private lateinit var work: File
    private lateinit var home: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        work = folder.newFolder("work")
        home = folder.newFolder("home")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun declare(directory: File, json: String) {
        File(directory, TAGS_FILE).apply {
            parentFile?.mkdirs()
            writeText(json)
        }
    }

    @Test
    fun theTagsOfEveryCollectionMergeIntoOneVocabulary() = runTest(dispatcher) {
        declare(work, """[{"name":"TASKS","pattern":"task"}]""")
        declare(home, """[{"name":"BILLS","pattern":"bill"}]""")

        val model = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("TASKS", "BILLS"), model.tags.value.map(MergedTag::name))
    }

    @Test
    fun aTagOfOneCollectionSelectsAmongTheNotesOfBoth() = runTest(dispatcher) {
        declare(work, """[{"name":"TASKS","pattern":"task"}]""")

        val model = viewModel()
        advanceUntilIdle()
        loader.pending[0].complete(
            Result.success(
                agenda(
                    day(
                        scheduledNoTime = listOf(
                            task(
                                heading = "Fix the shelf",
                                file = "task-home.md",
                                root = home.path,
                            ),
                            task(
                                heading = "Pay the bill",
                                file = "bill-water.md",
                                root = home.path,
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        model.setTag("TASKS")
        advanceUntilIdle()

        val ready = model.state.value as AgendaUiState.Ready
        assertEquals(listOf("Fix the shelf"), ready.sections.untimed.map { it.task.heading })
    }

    @Test
    fun choosingATagCostsNoScan() = runTest(dispatcher) {
        declare(work, """[{"name":"TASKS","pattern":"task"}]""")

        val model = viewModel()
        advanceUntilIdle()
        loader.pending[0].complete(Result.success(agenda(day())))
        advanceUntilIdle()
        val scansBefore = loader.pending.size

        model.setTag("TASKS")
        advanceUntilIdle()

        // The rows carry the name of the file they came from, so narrowing by
        // one is a regroup of what is already in hand.
        assertEquals(scansBefore, loader.pending.size)
    }

    @Test
    fun theWholeAgendaComesBackWhenTheTagIsDropped() = runTest(dispatcher) {
        declare(work, """[{"name":"TASKS","pattern":"task"}]""")

        val model = viewModel()
        advanceUntilIdle()
        loader.pending[0].complete(
            Result.success(
                agenda(
                    day(
                        scheduledNoTime = listOf(
                            task(
                                heading = "Fix the shelf",
                                file = "task-home.md",
                                root = home.path,
                            ),
                            task(
                                heading = "Pay the bill",
                                file = "bill-water.md",
                                root = home.path,
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        model.setTag("TASKS")
        advanceUntilIdle()
        model.setTag(null)
        advanceUntilIdle()

        val ready = model.state.value as AgendaUiState.Ready
        assertEquals(2, ready.sections.untimed.size)
    }

    @Test
    fun aTagThatTheNotesNoLongerDeclareStopsFiltering() = runTest(dispatcher) {
        declare(work, """[{"name":"TASKS","pattern":"task"}]""")
        val model = viewModel()
        advanceUntilIdle()
        model.setTag("TASKS")

        // A sync brings in a file edited elsewhere, and the tag is gone from
        // it. Filtering by a name nothing declares would leave the agenda
        // narrowed with nothing on screen to say by what.
        declare(work, """[{"name":"OTHER","pattern":"other"}]""")
        model.refresh()
        advanceUntilIdle()

        assertNull(model.currentTag.value)
    }

    @Test
    fun collectionsThatDeclareNothingLeaveTheHeaderWithoutTheControl() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        assertTrue(model.tags.value.isEmpty())
    }

    @Test
    fun aFileThatWillNotParseLeavesTheOtherCollectionReadable() = runTest(dispatcher) {
        declare(work, "{ this is not json")
        declare(home, """[{"name":"BILLS","pattern":"bill"}]""")

        val model = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("BILLS"), model.tags.value.map(MergedTag::name))
    }

    /**
     * The tag file of a collection is read holding that collection's lock.
     *
     * Which is where the step off the main thread is, and what keeps the read
     * away from the fetch rewriting the same file. Asserted by holding the lock
     * and watching the declaration on screen stay as it was: a read that took
     * no lock would answer straight away, off the frame loop it was asked from.
     */
    @Test
    fun theTagsOfACollectionAreReadHoldingItsLock() = runTest(dispatcher) {
        declare(work, """[{"name":"TASKS","pattern":"task"}]""")
        val model = viewModel()
        advanceUntilIdle()

        declare(work, """[{"name":"TASKS","pattern":"task"},{"name":"URGENT","pattern":"now"}]""")
        val opened = CompletableDeferred<Unit>()
        val holder = backgroundScope.launch {
            collections.entries.first().area.exclusive { opened.await() }
        }
        advanceUntilIdle()

        model.refresh()
        advanceUntilIdle()

        assertEquals(
            "the tag file was read without the lock on the working copy it lives in",
            listOf("TASKS"),
            model.tags.value.map(MergedTag::name),
        )

        opened.complete(Unit)
        holder.join()
        advanceUntilIdle()

        assertEquals(listOf("TASKS", "URGENT"), model.tags.value.map(MergedTag::name))
    }

    /** The collections the model under test is working with, for a test that holds a lock. */
    private lateinit var collections: FakeCollections

    private fun viewModel(): AgendaViewModel = AgendaViewModel(
        collections = FakeCollections(
            listOf(
                FakeCollections.entry(id = "1", name = "Work", path = work.path),
                FakeCollections.entry(id = "2", name = "Home", path = home.path),
            ),
        ).also { collections = it },
        stored = FakeCollectionsStore(
            listOf(
                NotesCollection(id = "1", name = "Work", path = work.path),
                NotesCollection(id = "2", name = "Home", path = home.path),
            ),
        ),
        agenda = loader,
        ui = ui,
        ownNotes = File("/data/data/markdown-org/files/notes"),
        sample = testWording,
        storageGranted = { true },
        clock = { NOON },
        io = dispatcher,
    )

    private companion object {
        val NOON: LocalDateTime = LocalDateTime.parse("2026-07-28T12:00")
    }
}
