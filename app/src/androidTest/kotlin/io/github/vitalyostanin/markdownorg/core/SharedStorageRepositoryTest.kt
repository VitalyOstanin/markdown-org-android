package io.github.vitalyostanin.markdownorg.core

import android.os.Build
import android.os.Environment
import android.system.Os
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uniffi.markdown_org_ffi.CommitAuthor
import uniffi.markdown_org_ffi.commitChanges
import uniffi.markdown_org_ffi.holdsRepository
import uniffi.markdown_org_ffi.repositoryStatus
import java.io.File

/**
 * A checkout in a directory the application does not own.
 *
 * The shared part of the storage — where `Documents` is, and where a notes
 * directory chosen in the settings lives (ADR-0013) — hands its files out
 * under an owner of its own rather than under the uid of the application
 * reading them, and libgit2 refuses to open a repository whose directory
 * belongs to somebody else. Notes there were read and written while every git
 * operation on them was turned away. This is the test that the refusal is
 * gone.
 *
 * Not `getExternalFilesDir`: that one is on the same storage and is reported
 * as owned by the application, so it never produced the refusal in the first
 * place.
 *
 * Both of the things this needs — all files access, and a storage that
 * reports somebody else as the owner — are properties of the device rather
 * than of this project, so they are stated as assumptions: a run without them
 * skips the test instead of passing while checking nothing. The permission is
 * not taken by force either; granting it from the test through `appops`
 * restarts the application under the instrumentation, and the run that tried
 * lost the device part-way through the suite. On a phone where the permission
 * is granted — which is the only way the directory is usable at all — this
 * runs for real.
 *
 * The refusal itself is covered on the host by
 * `rust/markdown-org-ffi/tests/owner.rs`, which produces the ownership by hand.
 */
class SharedStorageRepositoryTest {

    private val directory
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "markdown-org-owner-check",
        )

    /**
     * Whether the shared storage can be written to at all. Before Android 11
     * the ordinary storage permission covers it, and the instrumentation
     * grants that one itself.
     */
    private val granted
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

    @After
    fun clean() {
        directory.deleteRecursively()
    }

    @Test
    fun aCheckoutOnTheSharedStorageOpensAndCommits() {
        assumeTrue(
            "all files access was not granted, so the shared storage cannot be written to",
            granted,
        )

        val notes = directory
        // The parts of a repository libgit2 asks for. Written by hand because
        // the core exposes no way to create one: it clones, and a clone needs
        // a remote this test has no business needing.
        assertTrue("the directory could not be created", File(notes, ".git/objects").mkdirs())
        File(notes, ".git/refs").mkdirs()
        File(notes, ".git/HEAD").writeText("ref: refs/heads/main\n")
        File(notes, ".git/config").writeText(
            """
            [core]
            ${'\t'}repositoryformatversion = 0
            ${'\t'}bare = false
            """.trimIndent() + "\n",
        )

        assumeTrue(
            "this storage reports the test process as the owner, so there is no refusal to lift",
            Os.stat(notes.path).st_uid != Os.getuid(),
        )

        assertTrue("the directory holds no repository", holdsRepository(notes.path))
        assertNotNull("no status for a repository that is there", repositoryStatus(notes.path))

        File(notes, "note.md").writeText("# Notes\n\n## TODO Write one\n")
        val commit = commitChanges(
            notes.path,
            "note",
            CommitAuthor(name = "markdown-org", email = "markdown-org@localhost"),
        )

        assertNotNull("nothing was committed", commit)
        assertEquals("note", repositoryStatus(notes.path)?.headSummary)
    }
}
