package io.github.vitalyostanin.markdownorg.core

import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The directory the notes live in, which is also the git working copy.
 *
 * What matters here is when it may hold the sample and when it must be empty:
 * a clone only lands in an empty directory, and an untracked file left behind
 * would make the checkout dirty and block the next sync.
 */
class NotesStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = NotesStore(context)
    private val today = LocalDate.of(2026, 7, 28)

    @Before
    @After
    fun clean() {
        store.reset()
    }

    @Test
    fun aFreshInstallHasSomethingToShow() {
        store.ensureSeeded(today, synced = false)

        assertTrue(store.root.resolve("sample.md").exists())
    }

    @Test
    fun theSampleIsWrittenOnlyOnce() {
        store.ensureSeeded(today, synced = false)
        val first = store.root.resolve("sample.md").readText()
        store.root.resolve("sample.md").writeText("# Edited\n")
        store.ensureSeeded(today, synced = false)

        // Seeding again would overwrite whatever is there; the second call has
        // to leave the directory alone.
        assertTrue(store.root.resolve("sample.md").readText() == "# Edited\n")
        assertTrue(first.contains("Sample notes"))
    }

    @Test
    fun aConfiguredRemoteIsNeverSeeded() {
        store.ensureSeeded(today, synced = true)

        // The directory belongs to the repository from here on: a sample file
        // would show up as an untracked change.
        assertFalse(store.root.resolve("sample.md").exists())
    }

    @Test
    fun resetLeavesRoomForAClone() {
        store.ensureSeeded(today, synced = false)
        store.reset()

        // The core clones into an empty directory, and the very first setup
        // has the sample sitting in the way.
        assertFalse(store.root.exists())
    }
}
