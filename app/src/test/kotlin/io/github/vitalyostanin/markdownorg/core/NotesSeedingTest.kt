package io.github.vitalyostanin.markdownorg.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * What the notes directory answers when it cannot be written to.
 *
 * Every one of these used to be a boolean nobody read: `mkdirs` and
 * `deleteRecursively` report failure by returning false, and the code carried
 * on either way — the seed then failed with a message about `sample.md`, and
 * the wipe was followed by a clone that refused a directory it said nothing
 * about.
 */
class NotesSeedingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val today = LocalDate.of(2026, 7, 30)

    @Test
    fun aDirectoryThatCannotBeCreatedIsReportedRatherThanWrittenInto() = runBlocking {
        // The path is a plain file, so mkdirs creates nothing and everything
        // after it fails over a name inside something that is not a directory.
        val store = NotesStore(folder.newFile("in-the-way"))

        val seeded = store.ensureSeeded(today) { false }

        assertTrue("a file was written under a plain file", seeded.isFailure)
        assertTrue(seeded.exceptionOrNull()?.message.orEmpty().contains("could not be created"))
    }

    @Test
    fun aFreshDirectoryIsSeededAndSaysSo() = runBlocking {
        val root = File(folder.newFolder(), "notes")
        val store = NotesStore(root)

        val seeded = store.ensureSeeded(today) { false }

        assertTrue(seeded.isSuccess)
        assertTrue(File(root, "sample.md").isFile)
    }

    @Test
    fun aWipeOfADirectoryThatIsNotThereIsNotAFailure() = runBlocking {
        // Nothing to empty is the state the wipe was after; the clone that
        // follows it needs an empty directory, not a wipe that happened.
        val store = NotesStore(File(folder.newFolder(), "never-created"))

        assertTrue(store.reset().isSuccess)
    }

    @Test
    fun aWipeThatWentThroughLeavesRoomForAClone() = runBlocking {
        val root = folder.newFolder()
        File(root, "sample.md").writeText("# notes\n")
        val store = NotesStore(root)

        assertTrue(store.reset().isSuccess)
        assertFalse(root.exists())
    }
}
