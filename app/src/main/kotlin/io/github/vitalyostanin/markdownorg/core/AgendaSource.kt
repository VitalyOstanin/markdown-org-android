package io.github.vitalyostanin.markdownorg.core

import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.NotesIndex
import uniffi.markdown_org_ffi.Options
import uniffi.markdown_org_ffi.Scope
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Reads the agenda out of the notes directory. */
interface AgendaLoader {

    suspend fun load(
        scope: Scope,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        includeDone: Boolean = false,
    ): Result<AgendaResult>

    /**
     * Re-read one note, named as the task names it.
     *
     * For after an edit: the file that changed is known, and the rest of the
     * collection has not. What it saves is the whole of the walk — on a phone
     * with a thousand notes, seconds of it.
     */
    suspend fun reread(file: String): Result<Unit>

    /**
     * Forget what is held, so the next agenda walks the directory again.
     *
     * For the changes that are not one file: a fetch that fast-forwarded the
     * checkout, a clone, a wipe, a change of directory.
     */
    suspend fun invalidate()
}

/**
 * The agenda, as the Rust core returns it.
 *
 * The core keeps the notes of one directory between calls (`NotesIndex`), so
 * an agenda after an edit costs the file that changed rather than the whole
 * collection. Everything the agenda means stays over there: this decides when
 * the held notes are stale, and nothing else.
 *
 * The calls are synchronous and touch the filesystem, so they run off the main
 * thread and under the lock on the notes directory: a scan overlapping a
 * fast-forward would read a mixture of the files before and after it. It is
 * not a subprocess: Android forbids spawning the CLI, and the bindings call
 * the same code in-process.
 */
class AgendaSource(private val notes: NotesArea) : AgendaLoader {

    /** The held notes, and the directory they were read from. */
    private var index: NotesIndex? = null
    private var indexed: File? = null

    override suspend fun load(
        scope: Scope,
        today: LocalDate,
        zone: ZoneId,
        includeDone: Boolean,
    ): Result<AgendaResult> = notes.exclusive {
        // Failures arrive as ExtractException from the core and as
        // UnsatisfiedLinkError when the native library is missing for the
        // device's ABI; both have to reach the screen rather than turn into
        // an empty agenda.
        runCatching {
            held().agenda(
                scope = scope,
                // The core never reads the clock; the caller decides what
                // "today" is, so the same files always render the same agenda.
                currentDate = today.toString(),
                timezone = zone.id,
                includeDone = includeDone,
            )
        }
    }

    override suspend fun reread(file: String): Result<Unit> = notes.exclusive {
        runCatching {
            // Nothing held means nothing to bring up to date: the next agenda
            // walks the directory and reads this file along with the rest.
            index?.takeIf { indexed == notes.root }?.refreshFile(file) ?: Unit
        }
    }

    override suspend fun invalidate() {
        notes.exclusive { drop() }
    }

    /**
     * The index over the current directory, built if there is none.
     *
     * The directory is compared rather than assumed: it changes under a
     * running application, and an index over the previous one would answer
     * with somebody else's notes.
     */
    private fun held(): NotesIndex {
        index?.takeIf { indexed == notes.root }?.let { return it }

        drop()
        // Left at what the core fills in: the file glob, the locales for
        // weekday names and the task cap are stated there, and repeating them
        // here as three nulls would only be a second place to keep them.
        return NotesIndex.open(notes.root.absolutePath, Options()).also {
            index = it
            indexed = notes.root
        }
    }

    /**
     * Release the held notes.
     *
     * The object owns memory on the Rust side, freed when it is destroyed or
     * when the collector eventually gets to it. Dropped explicitly because the
     * held tasks of a large collection are the largest thing this application
     * keeps, and a directory that was left behind has no claim on them.
     */
    private fun drop() {
        index?.destroy()
        index = null
        indexed = null
    }
}
