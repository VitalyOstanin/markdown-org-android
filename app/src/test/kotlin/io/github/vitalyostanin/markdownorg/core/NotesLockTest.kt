package io.github.vitalyostanin.markdownorg.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Two ways into one notes directory, and the lock that has to hold across
 * both.
 *
 * The directory is a git working copy, and the areas over it are built in more
 * than one place: the agenda holds one, the reminder scheduler builds its own,
 * and so does the service that closes an entry from a notification. Two
 * libgit2 operations over one repository contend for `index.lock`, and a scan
 * running under a fast-forward reads a mixture of the old files and the new,
 * so serialising per instance serialises nothing.
 */
class NotesLockTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun twoAreasOverOneDirectoryTakeTurns() = runBlocking {
        val root = folder.newFolder("notes")
        val first = NotesStore(root)
        val second = NotesStore(root)
        val held = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()

        val holder = launch(Dispatchers.IO) {
            first.exclusive {
                entered.complete(Unit)
                held.await()
            }
        }
        entered.await()

        val waiting = async(Dispatchers.IO) { second.exclusive { "in" } }
        assertNull(
            "the second area entered while the first was holding the directory",
            withTimeoutOrNull(300) { waiting.await() },
        )

        held.complete(Unit)
        holder.join()
        assertEquals("in", waiting.await())
    }

    @Test
    fun areasOverDifferentDirectoriesDoNotWaitForEachOther() = runBlocking {
        // The lock is the directory's, not the application's: two collections
        // are two working copies, and a scan of one must not queue behind a
        // sync of the other.
        val first = NotesStore(folder.newFolder("one"))
        val second = NotesStore(folder.newFolder("two"))
        val held = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()

        val holder = launch(Dispatchers.IO) {
            first.exclusive {
                entered.complete(Unit)
                held.await()
            }
        }
        entered.await()

        assertEquals("in", withTimeoutOrNull(2_000) { second.exclusive { "in" } })

        held.complete(Unit)
        holder.join()
    }

    @Test
    fun aDirectoryNamedTwoWaysIsOneDirectory() = runBlocking {
        // The paths a collection is built from come from settings and from
        // the platform, and the same directory reaches them spelled more than
        // one way -- with a `.` in it, or through a link.
        val root = folder.newFolder("notes")
        val first = NotesStore(root)
        val second = NotesStore(File(root, "./."))
        val held = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()

        val holder = launch(Dispatchers.IO) {
            first.exclusive {
                entered.complete(Unit)
                held.await()
            }
        }
        entered.await()

        val waiting = async(Dispatchers.IO) { second.exclusive { "in" } }
        assertNull(
            "the same directory spelled another way took another lock",
            withTimeoutOrNull(300) { waiting.await() },
        )

        held.complete(Unit)
        holder.join()
        assertEquals("in", waiting.await())
    }
}
