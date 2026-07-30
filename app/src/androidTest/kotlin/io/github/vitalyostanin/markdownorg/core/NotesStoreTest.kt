package io.github.vitalyostanin.markdownorg.core

import androidx.test.platform.app.InstrumentationRegistry
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
import java.time.LocalDate
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

    @Before
    @After
    fun clean(): Unit = runBlocking {
        store.reset().getOrThrow()
    }

    @Test
    fun aFreshInstallHasSomethingToShow() = runBlocking {
        store.ensureSeeded(today) { false }

        assertTrue(store.root.resolve("sample.md").exists())
    }

    @Test
    fun theSampleIsWrittenOnlyOnce() = runBlocking {
        store.ensureSeeded(today) { false }
        val first = store.root.resolve("sample.md").readText()
        store.root.resolve("sample.md").writeText("# Edited\n")
        store.ensureSeeded(today) { false }

        // Seeding again would overwrite whatever is there; the second call has
        // to leave the directory alone.
        assertTrue(store.root.resolve("sample.md").readText() == "# Edited\n")
        assertTrue(first.contains("Sample notes"))
    }

    @Test
    fun everyTimestampInTheSampleIsOneTheCoreReadsBack() = runBlocking {
        // The sample is the only example of the format the application ever
        // shows, so a line it cannot read back teaches the wrong form. `CLOSED:`
        // takes the inactive brackets, the others the active ones.
        store.ensureSeeded(today) { false }

        val tasks = uniffi.markdown_org_ffi.scan(
            store.root.absolutePath,
            uniffi.markdown_org_ffi.Options(glob = null, locale = null, maxTasks = null),
        ).tasks
        val archived = tasks.single { it.heading == "Archive the old branch" }

        assertEquals("CLOSED", archived.timestampType)
        assertTrue(tasks.all { it.timestampDate != null })
    }

    @Test
    fun aConfiguredRemoteIsNeverSeeded() = runBlocking {
        store.ensureSeeded(today) { true }

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
        val seeding = async { store.ensureSeeded(today) { configured } }
        configured = true
        seeding.await()

        assertFalse(store.root.resolve("sample.md").exists())
    }

    @Test
    fun resetLeavesRoomForAClone() = runBlocking {
        store.ensureSeeded(today) { false }
        store.reset()

        // The core clones into an empty directory, and the very first setup
        // has the sample sitting in the way.
        assertFalse(store.root.exists())
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
