package io.github.vitalyostanin.markdownorg.core

import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One lock per notes directory, shared by every area over it.
 *
 * The areas are not one object: the agenda holds one, the reminder scheduler
 * builds its own, the service that closes an entry from a notification builds
 * a third, and each of them is a separate [NotesStore] over the same
 * directory. A lock held inside one of them serialises that one alone, which
 * is not what the directory needs -- it is a git working copy, and two libgit2
 * operations over one repository contend for `index.lock` whichever object
 * started them.
 *
 * Keyed by the canonical path, so the same directory reached by another
 * spelling -- through a link, or with a `.` in the path -- is the same lock.
 * A path that cannot be canonicalised (a directory that is not there yet, on a
 * filesystem that refuses to resolve it) falls back to the absolute one: two
 * spellings would then take two locks, which is the behaviour there was before
 * this existed rather than a new failure.
 *
 * The map is never emptied. An entry is a `Mutex` and a path string per notes
 * directory the process has touched, and a device holds a handful of
 * collections at most; forgetting one while an area still held it would hand
 * the next caller a different lock over the same directory.
 */
internal object NotesLocks {

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The lock for [directory], the same one for every area over it. */
    fun of(directory: File): Mutex = locks.computeIfAbsent(key(directory)) { Mutex() }

    private fun key(directory: File): String =
        runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
}
