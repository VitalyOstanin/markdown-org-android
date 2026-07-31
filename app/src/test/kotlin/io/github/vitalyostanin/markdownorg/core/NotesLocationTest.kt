package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Which directories the notes may live in.
 *
 * The rule decides what the settings form refuses before anything is stored,
 * and the one refusal with an action behind it — the missing access to all
 * files — has to be told apart from the two that no permission would fix.
 */
class NotesLocationTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Stands in for the directory inside the application's own storage. */
    private val own: File by lazy { folder.newFolder("files", "notes") }

    @Test
    fun anEmptyPathMeansTheOwnStorageRatherThanAFailure() {
        assertEquals(NotesPathProblem.EMPTY, notesPathProblem("", own, granted = true))
        assertEquals(NotesPathProblem.EMPTY, notesPathProblem("   ", own, granted = true))
    }

    @Test
    fun aRelativePathNamesNothingBecauseThereIsNoWorkingDirectory() {
        assertEquals(NotesPathProblem.RELATIVE, notesPathProblem("notes", own, granted = true))
    }

    @Test
    fun aPlainFileIsRefusedHoweverMuchAccessThereIs() {
        val file = folder.newFile("notes.md")

        assertEquals(
            NotesPathProblem.NOT_A_DIRECTORY,
            notesPathProblem(file.absolutePath, own, granted = true),
        )
    }

    @Test
    fun aDirectoryOutsideTheOwnStorageNeedsTheAccessToAllFiles() {
        val outside = folder.newFolder("shared", "notes")

        assertEquals(
            NotesPathProblem.NEEDS_PERMISSION,
            notesPathProblem(outside.absolutePath, own, granted = false),
        )
        assertNull(notesPathProblem(outside.absolutePath, own, granted = true))
    }

    /**
     * A directory that does not exist yet is not refused: it is created when
     * the notes move into it, and refusing it here would mean the path has to
     * be made elsewhere before it can be typed in.
     */
    @Test
    fun aDirectoryThatIsNotThereYetIsAllowed() {
        val missing = File(folder.root, "not-created-yet")

        assertNull(notesPathProblem(missing.absolutePath, own, granted = true))
    }

    @Test
    fun theOwnStorageNeedsNoPermissionAtAll() {
        assertNull(notesPathProblem(own.absolutePath, own, granted = false))
        assertNull(notesPathProblem(File(own, "inner").absolutePath, own, granted = false))
    }

    /**
     * A sibling whose name merely starts the same way is outside, and a prefix
     * comparison without the separator would have read it as inside — and let
     * it through without the access it needs.
     */
    @Test
    fun aTreeOnTheBuiltInStorageBecomesAPathUnderIt() {
        val primary = File("/storage/emulated/0")

        assertEquals(
            "/storage/emulated/0/Documents/notes",
            documentTreePath("primary:Documents/notes", primary),
        )
        assertEquals("/storage/emulated/0", documentTreePath("primary:", primary))
    }

    /** A card is mounted by its own identifier, not under the built-in one. */
    @Test
    fun aTreeOnACardBecomesAPathUnderItsVolume() {
        assertEquals(
            "/storage/1A2B-3C4D/notes",
            documentTreePath("1A2B-3C4D:notes", File("/storage/emulated/0")),
        )
    }

    /**
     * A provider that is not external storage hands back an identifier of
     * another shape, and a path guessed from it would name nothing.
     */
    @Test
    fun aTreeFromAnotherProviderBecomesNoPathAtAll() {
        val primary = File("/storage/emulated/0")

        assertNull(documentTreePath("document%3A1234", primary))
        assertNull(documentTreePath("primary:/absolute", primary))
    }

    @Test
    fun aSiblingWhoseNameStartsTheSameWayIsOutside() {
        val sibling = File(own.parentFile, own.name + "-old")

        assertEquals(
            NotesPathProblem.NEEDS_PERMISSION,
            notesPathProblem(sibling.absolutePath, own, granted = false),
        )
    }
}
