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
     * Points the working copy at [directory], leaving both directories as
     * they are.
     *
     * Under the same lock as everything else, so a scan or a sync that is
     * already running finishes against the directory it started on rather
     * than against half of each. What was in the old directory is not moved
     * and not deleted: it may be a checkout with commits that exist nowhere
     * else, and pointing somewhere else is not a reason to lose them.
     *
     * Fails when the directory cannot be created or cannot be written to —
     * which, outside the application's own storage, is also what a missing
     * permission looks like from here.
     */
    suspend fun useDirectory(directory: File): Result<Unit>

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

/**
 * The working copies in use, and sole access to all of them at once.
 *
 * The agenda is one agenda over several collections, so the walk behind it
 * touches every directory in one pass and has to hold every one of them: a
 * fast-forward landing on the second collection while the first is being read
 * would produce an agenda that never existed on disk.
 *
 * What belongs to one collection — an edit, a commit, a sync — still goes
 * through that collection's own [NotesArea], so a sync of one server does not
 * stop an edit in another directory.
 */
interface NotesAreas {

    /** In the order the collections are shown, which the walk keeps. */
    val areas: List<NotesArea>

    /**
     * Runs [block] with sole access to every working copy, off the main
     * thread.
     *
     * Not reentrant, and not to be called from inside a single area's
     * [NotesArea.exclusive]: the locks are taken in the order of [areas], and
     * a block that already holds one of them out of that order would deadlock
     * against a walk taking them from the start.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T
}

/**
 * Takes the areas one after another, in the order given, and runs [block]
 * holding all of them.
 *
 * The order is what keeps two callers from deadlocking: everything that needs
 * more than one working copy takes them in the order the collections are in,
 * so one caller waiting on the second never holds what another needs first.
 */
internal suspend fun <T> holdingAll(areas: List<NotesArea>, block: suspend () -> T): T =
    when (val first = areas.firstOrNull()) {
        null -> block()
        else -> first.exclusive { holdingAll(areas.drop(1), block) }
    }
