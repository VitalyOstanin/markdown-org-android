package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.PhraseRules
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.markdown_org_ffi.PhraseDraft
import uniffi.markdown_org_ffi.PhraseField
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.TaskType
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the model does with a sentence, on either side of the rules that read
 * it.
 *
 * The rules themselves are the core's, and they are asserted where they are
 * written; what only the model answers is which day they are asked about, what
 * becomes of an answer, and how each way of coming back empty-handed is said.
 * That is asserted here because the rules arrive as a parameter — called
 * directly, they would be a native library the JVM these tests run on has not
 * loaded, and every refusal below would go unexercised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaPhraseTest {

    private val dispatcher = StandardTestDispatcher()
    private val loader = FakeAgendaLoader()
    private val ui = FakeUiPreferences()
    private val writer = FakeWriter()
    private val collections = FakeCollections(listOf(FakeCollections.entry(editor = writer)))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theRulesAreAskedAboutTodayWithNothingAlreadyFilledIn() = runTest(dispatcher) {
        val rules = StubRules(read = draft(heading = "позвонить врачу"))
        val model = viewModel(rules)
        advanceUntilIdle()

        model.edits.createFromPhrase("  позвонить врачу завтра  ")
        advanceUntilIdle()

        val asked = rules.asked
        assertEquals("позвонить врачу завтра", asked?.said)
        assertEquals(NOON.toLocalDate(), asked?.today)
        // Read from scratch rather than against a task being composed: this
        // sentence is the whole of what is known about the entry.
        assertEquals("", asked?.draft?.heading)
        assertNull(asked?.draft?.date)
    }

    @Test
    fun whatTheRulesReadIsTheTaskThatGetsWritten() = runTest(dispatcher) {
        val model = viewModel(
            StubRules(
                read = draft(
                    heading = "позвонить врачу",
                    priority = "A",
                    keyword = PlanningKeyword.DEADLINE,
                    date = "2026-07-29",
                    time = "15:00",
                    repeater = "+1w",
                ),
            ),
        )
        advanceUntilIdle()

        model.edits.createFromPhrase("позвонить врачу завтра в 15:00, каждую неделю")
        advanceUntilIdle()

        val written = writer.created?.second
        assertEquals("позвонить врачу", written?.title)
        assertEquals("A", written?.priority)
        assertEquals(PlanningKeyword.DEADLINE, written?.keyword)
        assertEquals(LocalDate.of(2026, 7, 29), written?.date)
        assertEquals(LocalTime.of(15, 0), written?.time)
        assertEquals("+1w", written?.repeater)
    }

    @Test
    fun aSentenceTheRulesConsumedEntirelyIsKeptAsTheHeading() = runTest(dispatcher) {
        // An entry without a heading is not written at all, so what was heard
        // is better than nothing -- even where every word of it was a date.
        val model = viewModel(StubRules(read = draft(heading = "", date = "2026-07-29")))
        advanceUntilIdle()

        model.edits.createFromPhrase("завтра")
        advanceUntilIdle()

        assertEquals("завтра", writer.created?.second?.title)
    }

    @Test
    fun aPhraseWithNoCollectionToWriteIntoIsRefusedBeforeTheRulesAreConsulted() =
        runTest(dispatcher) {
            val rules = StubRules(read = draft())
            val model = viewModel(rules)
            advanceUntilIdle()

            // The button was pressed while the last collection was being
            // removed in the settings.
            collections.entries = emptyList()
            model.edits.createFromPhrase("позвонить врачу")
            advanceUntilIdle()

            assertEquals(R.string.edit_failed_no_collection, model.edits.editIssue.value?.text)
            assertNull("the rules were consulted with nowhere to write", rules.asked)
            assertNull(writer.created)
        }

    @Test
    fun aPhraseTheRulesCouldNotReadWritesNothingAndSaysSo() = runTest(dispatcher) {
        val model = viewModel(StubRules(refusal = IllegalStateException("no grammar")))
        advanceUntilIdle()

        model.edits.createFromPhrase("позвонить врачу")
        advanceUntilIdle()

        assertEquals(R.string.agenda_dictate_failed, model.edits.editIssue.value?.text)
        assertTrue(
            "the message does not read as a failure",
            model.edits.editIssue.value?.failed == true,
        )
        assertNull(writer.created)
    }

    @Test
    fun anEmptyPhraseIsNotACollectionProblemAndNotAskedAbout() = runTest(dispatcher) {
        val rules = StubRules(read = draft())
        val model = viewModel(rules)
        advanceUntilIdle()

        collections.entries = emptyList()
        model.edits.createFromPhrase("   ")
        advanceUntilIdle()

        assertNull(rules.asked)
        assertNull("a phrase nobody said was reported as a failure", model.edits.editIssue.value)
    }

    @Test
    fun whatTheRulesReadAboutAnEntryReachesTheWriter() = runTest(dispatcher) {
        val read = draft(date = "2026-07-29", time = "15:00")
        val model = viewModel(StubRules(read = read))
        advanceUntilIdle()

        model.edits.apply(task(), TaskAction.Phrase("завтра в 15:00"))
        advanceUntilIdle()

        assertEquals(read, writer.phrase)
        assertNull(model.edits.editIssue.value)
    }

    @Test
    fun aPhraseAboutAnEntryTheRulesCouldNotReadChangesNothing() = runTest(dispatcher) {
        val model = viewModel(StubRules(refusal = IllegalStateException("no grammar")))
        advanceUntilIdle()

        model.edits.apply(task(), TaskAction.Phrase("завтра в 15:00"))
        advanceUntilIdle()

        assertEquals(R.string.agenda_dictate_failed, model.edits.editIssue.value?.text)
        assertNull(writer.phrase)
    }

    @Test
    fun aWordLeftOverIsSaidBackAsItWasHeardAndNothingIsWritten() = runTest(dispatcher) {
        // Applying the half that was understood would move a field nobody
        // named, so the whole sentence is refused and quoted back.
        val model = viewModel(StubRules(read = draft(heading = "врчу", date = "2026-07-29")))
        advanceUntilIdle()

        model.edits.apply(task(), TaskAction.Phrase("врчу завтра"))
        advanceUntilIdle()

        val issue = model.edits.editIssue.value
        assertEquals(R.string.agenda_phrase_leftover, issue?.text)
        assertEquals(Detail.Verbatim("врчу"), issue?.detail)
        assertNull(writer.phrase)
    }

    @Test
    fun aPhraseThatNamedNoFieldIsRefusedRatherThanWrittenAsAnEmptyEdit() = runTest(dispatcher) {
        val model = viewModel(StubRules(read = draft()))
        advanceUntilIdle()

        model.edits.apply(task(), TaskAction.Phrase("здравствуйте"))
        advanceUntilIdle()

        assertEquals(R.string.agenda_phrase_nothing, model.edits.editIssue.value?.text)
        assertNull(writer.phrase)
    }

    @Test
    fun aFieldTheSentenceEmptiedCountsAsAFieldItNamed() = runTest(dispatcher) {
        // Nothing is set and everything is still null, but the sentence said
        // to take the hour off -- which is an edit, not an empty one.
        val read = draft(cleared = listOf(PhraseField.TIME))
        val model = viewModel(StubRules(read = read))
        advanceUntilIdle()

        model.edits.apply(task(), TaskAction.Phrase("без времени"))
        advanceUntilIdle()

        assertEquals(read, writer.phrase)
    }

    /** The rules as a test states them: an answer, or the way one is refused. */
    private class StubRules(
        private val read: PhraseDraft? = null,
        private val refusal: Throwable? = null,
    ) : PhraseRules {

        /** What the last reading was handed, `null` while there was none. */
        var asked: Asked? = null
            private set

        override fun refine(draft: PhraseDraft, said: String, today: LocalDate): PhraseDraft {
            asked = Asked(draft, said, today)
            refusal?.let { throw it }
            return read ?: draft
        }

        override fun repeater(typed: String): String? = null
    }

    /** One reading, as the rules were asked for it. */
    private data class Asked(val draft: PhraseDraft, val said: String, val today: LocalDate)

    private fun viewModel(rules: PhraseRules): AgendaViewModel = AgendaViewModel(
        collections = collections,
        stored = FakeCollectionsStore(collections.entries.map { it.collection }),
        agenda = loader,
        ui = ui,
        ownNotes = File("/data/data/markdown-org/files/notes"),
        sample = testWording,
        storageGranted = { true },
        clock = { NOON },
        phrases = rules,
        io = dispatcher,
    )

    private companion object {

        val NOON: LocalDateTime = LocalDateTime.parse("2026-07-28T12:00")

        /** What the rules answer with, named field by field. */
        fun draft(
            heading: String = "",
            priority: String? = null,
            keyword: PlanningKeyword? = null,
            date: String? = null,
            time: String? = null,
            repeater: String? = null,
            status: TaskType? = null,
            cleared: List<PhraseField> = emptyList(),
        ) = PhraseDraft(
            heading = heading,
            priority = priority,
            keyword = keyword,
            date = date,
            time = time,
            repeater = repeater,
            status = status,
            cleared = cleared,
        )
    }
}
