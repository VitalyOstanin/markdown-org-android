package io.github.vitalyostanin.markdownorg.core

import java.io.File
import java.time.LocalDate

/**
 * The notes directory, and the lock that serialises access to it.
 *
 * Every operation that touches the working copy goes through [exclusive]: the
 * scan behind the agenda, the seeding of the sample, the clone, an edit with
 * its commit, and the wipe that precedes a change of remote. The directory is
 * a git working copy, and none of those tolerate a neighbour: two libgit2
 * operations over one repository contend for `index.lock`, a wipe running
 * under a clone leaves half a checkout behind, and a scan running under a
 * fast-forward reads a mixture of the old files and the new.
 *
 * `Dispatchers.IO` is a pool of up to 64 threads, so without a lock these do
 * run at the same time rather than queue up behind each other.
 */
interface NotesArea {

    /** Where the markdown lives. */
    val root: File

    /**
     * Runs [block] with sole access to [root], off the main thread.
     *
     * Not reentrant: a block must not call another operation that takes the
     * same lock.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T

    /**
     * Writes the sample unless the directory already holds notes.
     *
     * [synced] is read under the lock rather than passed as a value, so a
     * remote saved while this was queued still counts: seeding a directory
     * that is about to be cloned into would make the clone fail.
     *
     * Answers with a [Result] rather than throwing: this runs in the same
     * coroutine as the scan that follows it, and an exception out of that
     * coroutine takes the process down — over a directory that cannot be
     * written to, which is something a screen can say.
     */
    suspend fun ensureSeeded(today: LocalDate, synced: () -> Boolean): Result<Unit>

    /**
     * Clears the checkout so a different remote can be cloned into it.
     *
     * A wipe that only half happened is a failure of its own: the clone that
     * follows refuses a directory that is not empty, and reports it as a
     * repository failure that says nothing about the directory.
     */
    suspend fun reset(): Result<Unit>
}
