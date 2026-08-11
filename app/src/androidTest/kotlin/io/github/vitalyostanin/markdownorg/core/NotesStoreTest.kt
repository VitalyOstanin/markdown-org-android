package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vitalyostanin.markdownorg.ui.sampleWording
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.markdown_org_ffi.TimestampType
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The directory the notes live in, which is also the git working copy.
 *
 * What matters here is when it may hold the sample and when it must be empty:
 * a clone only lands in an empty directory, and an untracked file left behind
 * would make the checkout dirty and block the next sync. And that only one
 * operation is inside it at a time.
 */
class NotesStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = NotesStore(context)
    private val today = LocalDate.of(2026, 7, 28)

    /**
     * The real strings rather than words invented here: the sample is written
     * in the language of the device, and a translation that broke the format
     * would only show up against the resources the application ships.
     */
    private val wording = sampleWording(context)

    /**
     * The directory of this test run, emptied here rather than through the
     * store: the application removes nothing of its own accord, and a test
     * clearing the directory it made itself is not that.
     */
    @Before
    @After
    fun clean() {
        store.root.deleteRecursively()
    }

    @Test
    fun aFreshInstallHasSomethingToShow() = runBlocking {
        store.ensureSeeded(today, wording) { false }

        assertTrue(store.root.resolve("sample.md").exists())
    }

    @Test
    fun theSampleIsWrittenOnlyOnce() = runBlocking {
        store.ensureSeeded(today, wording) { false }
        val first = store.root.resolve("sample.md").readText()
        store.root.resolve("sample.md").writeText("# Edited\n")
        store.ensureSeeded(today, wording) { false }

        // Seeding again would overwrite whatever is there; the second call has
        // to leave the directory alone.
        assertTrue(store.root.resolve("sample.md").readText() == "# Edited\n")
        assertTrue(first.contains(wording.heading))
    }

    @Test
    fun notesOneFolderDownAreStillNotes() = runBlocking {
        // How a collection someone keeps actually looks: markdown in folders,
        // nothing at the top. Read as an empty directory, it got a sample of
        // ours written into it — among their notes, and into their next commit.
        val folder = store.root.resolve("projects").apply { mkdirs() }
        folder.resolve("plan.md").writeText("# Plan\n")

        store.ensureSeeded(today, wording) { false }

        assertFalse(store.root.resolve("sample.md").exists())
    }

    @Test
    fun aCheckoutWithoutMarkdownYetIsNotSeededEither() = runBlocking {
        // A clone that brought no markdown is still someone's checkout: the
        // sample would be an untracked file in it, and the next sync refuses a
        // dirty working copy.
        store.root.resolve(".git").mkdirs()

        store.ensureSeeded(today, wording) { false }

        assertFalse(store.root.resolve("sample.md").exists())
    }

    @Test
    fun everyTimestampInTheSampleIsOneTheCoreReadsBack() = runBlocking {
        // The sample is the only example of the format the application ever
        // shows, so a line it cannot read back teaches the wrong form. `CLOSED:`
        // takes the inactive brackets, the others the active ones.
        store.ensureSeeded(today, wording) { false }

        val tasks = uniffi.markdown_org_ffi.scan(
            store.root.absolutePath,
            uniffi.markdown_org_ffi.Options(),
        ).tasks
        val archived = tasks.single { it.heading == wording.archivedBranch }

        assertEquals(TimestampType.CLOSED, archived.timestampType)
        assertTrue(tasks.all { it.timestampDate != null })
    }

    @Test
    fun theSampleReadsBackInEveryLanguageItIsTranslatedInto() = runBlocking {
        // Only the wording is translated, and a translation is where a heading
        // gains a character the grammar stops at — the file then opens to an
        // agenda missing the task it names, on that language alone. Read here
        // rather than on a device set to each language, which no test can do.
        for (language in listOf("en", "ru")) {
            val translated = sampleWording(context.forLanguage(language))
            val store = NotesStore(File(context.cacheDir, "sample-$language"))
            store.root.deleteRecursively()

            store.ensureSeeded(today, translated) { false }
            val tasks = uniffi.markdown_org_ffi.scan(
                store.root.absolutePath,
                uniffi.markdown_org_ffi.Options(),
            ).tasks

            assertEquals("$language: every heading of the sample", 7, tasks.size)
            assertTrue("$language: every task is dated", tasks.all { it.timestampDate != null })
            assertTrue(
                "$language: the closing date is inactive",
                tasks.single { it.heading == translated.archivedBranch }
                    .timestampType == TimestampType.CLOSED,
            )
            store.root.deleteRecursively()
        }
    }

    /** The same resources as a device set to [language] would read. */
    private fun Context.forLanguage(language: String): Context = createConfigurationContext(
        Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        },
    )

    @Test
    fun aConfiguredRemoteIsNeverSeeded() = runBlocking {
        store.ensureSeeded(today, wording) { true }

        // The directory belongs to the repository from here on: a sample file
        // would show up as an untracked change.
        assertFalse(store.root.resolve("sample.md").exists())
    }

    @Test
    fun aRemoteSavedWhileSeedingWasQueuedStopsTheSeed() = runBlocking {
        // The flag is read inside the lock, not captured at the call: a clone
        // is about to land in this directory, and a sample file dropped into
        // it now would make that clone fail.
        var configured = false
        val seeding = async { store.ensureSeeded(today, wording) { configured } }
        configured = true
        seeding.await()

        assertFalse(store.root.resolve("sample.md").exists())
    }

    @Test
    fun theNotesCanBeMovedToAnotherDirectory() = runBlocking {
        store.ensureSeeded(today, wording) { false }
        val before = store.root
        val elsewhere = context.filesDir.resolve("notes-elsewhere")

        store.useDirectory(elsewhere).getOrThrow()

        assertEquals(elsewhere, store.root)
        assertTrue("the directory was not created", elsewhere.isDirectory)
        // What was in the previous one may be a checkout with commits that
        // exist nowhere else; pointing elsewhere is not a reason to lose it.
        assertTrue("the previous directory was emptied", before.resolve("sample.md").exists())

        store.useDirectory(before).getOrThrow()
        assertTrue(elsewhere.deleteRecursively())
    }

    @Test
    fun aDirectoryThatCannotHoldTheNotesIsRefusedAndChangesNothing() = runBlocking {
        val inTheWay = context.filesDir.resolve("not-a-directory")
        inTheWay.writeText("occupied\n")
        val before = store.root

        val outcome = store.useDirectory(inTheWay)

        assertTrue("a plain file was accepted as the notes directory", outcome.isFailure)
        assertEquals(before, store.root)
        assertTrue(inTheWay.delete())
    }

    @Test
    fun onlyOneOperationIsInsideTheDirectoryAtATime() = runBlocking {
        // Everything that touches the working copy goes through the same lock:
        // a wipe running under a clone leaves half a checkout, and two libgit2
        // operations over one repository contend for index.lock.
        val inside = AtomicInteger()
        val overlapped = AtomicInteger()

        (1..8).map {
            async {
                store.exclusive {
                    if (inside.incrementAndGet() > 1) {
                        overlapped.incrementAndGet()
                    }
                    delay(20)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(0, overlapped.get())
    }
}
