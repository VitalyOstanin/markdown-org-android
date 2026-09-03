package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Where the markdown lives on the device.
 *
 * A directory inside the application's own storage unless another one was
 * chosen, and the git working copy once a remote is configured. Until then it
 * is seeded with a sample, so a fresh install has something to show.
 *
 * This is also the single point where access to that directory is serialised
 * — see [NotesArea.exclusive]. The lock itself is not held here but in
 * [NotesLocks], keyed by the directory: several areas are built over one
 * directory, and a lock held inside one of them would serialise that object
 * rather than the working copy.
 */
class NotesStore(root: File) : NotesArea {

    /** Where it lives on a device: the chosen directory, or the default one. */
    constructor(context: Context) : this(notesRoot(context, NotesLocation(context)))

    /**
     * Read without the lock by every operation, written only under it.
     *
     * Volatile because those reads happen on the IO pool while the write
     * comes from whichever thread called [useDirectory]: without it a scan
     * could keep reading the previous directory out of a cached field long
     * after the move.
     */
    @Volatile
    private var current: File = root

    override val root: File get() = current

    override suspend fun <T> exclusive(block: suspend () -> T): T =
        NotesLocks.of(current).withLock { withContext(Dispatchers.IO) { block() } }

    /**
     * Creating it here rather than leaving it to the first scan: a directory
     * that cannot be made is the answer to "can the notes live here", and the
     * screen that asked has somewhere to put that answer. Later it would
     * surface as a failed agenda, which says nothing about the choice that
     * caused it.
     */
    override suspend fun useDirectory(directory: File): Result<Unit> = exclusive {
        runCatching {
            makeUsable(directory)
            current = directory
        }
    }

    override suspend fun prepareDirectory(): Result<Unit> = exclusive {
        // Nothing is pointed anywhere: the directory is the one this area
        // already names, and the answer is only whether it is there and can
        // be worked with.
        runCatching { makeUsable(current) }
    }

    private fun makeUsable(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) {
            "the notes directory could not be created: $directory"
        }
        // Both, because the notes are not only read: seeding writes the
        // sample, an edit rewrites a file, and a clone fills the whole of
        // it. A directory that only reads would fail at the first of those.
        check(directory.canRead() && directory.canWrite()) {
            "the notes directory cannot be read and written: $directory"
        }
    }

    /**
     * Writes the sample unless the directory already holds notes.
     *
     * Skipped once a remote is configured: the directory is then a checkout,
     * and dropping an untracked file into it would show up as a dirty working
     * copy and block the next sync.
     */
    override suspend fun ensureSeeded(
        today: LocalDate,
        wording: SampleWording,
        synced: () -> Boolean,
    ): Result<Unit> = exclusive {
        runCatching {
            if (!synced() && !hasNotes()) {
                File(root, "sample.md").writeText(sampleNotes(today, wording))
            }
        }
    }

    private fun hasNotes(): Boolean {
        // mkdirs answers false when it created nothing, which over a path
        // that is a plain file, or one that cannot be written to, is the
        // whole of the report: everything after it would fail with a message
        // about sample.md rather than about the directory. Asked as
        // isDirectory rather than exists, because a plain file of that name
        // exists and is still not somewhere notes can be written.
        check(root.isDirectory || root.mkdirs()) {
            "the notes directory could not be created: $root"
        }
        // The whole tree, not the top of it: notes are kept in folders, and a
        // directory whose markdown all sits one level down was read as empty —
        // which put a sample of ours among someone's own notes, and into their
        // next commit. Stopped at the first hit, so the cost is the first few
        // entries rather than a walk of the collection.
        return root.walkTopDown().any { it.isFile && it.extension == "md" } ||
            // A checkout with no markdown in it yet is still not a directory
            // to seed: the clone that filled it is the one thing that says so.
            File(root, ".git").exists()
    }

    /**
     * Dates are written relative to [today] rather than fixed, so the sample
     * still fills the agenda whenever it is first opened.
     *
     * The timestamp line is wrapped in backticks. That is the format the
     * extractor recognises and the extension writes: a markdown renderer
     * treats a bare `<2026-07-28>` as an HTML tag and swallows it.
     *
     * One form of brackets per keyword, as the extractor reads them: angle
     * ones for `SCHEDULED:` and `DEADLINE:`, square ones for `CLOSED:` — a
     * closing date is a record of what happened, not something the agenda
     * schedules. The sample is the only example of the format the application
     * shows, so a line written the other way teaches a form it cannot read.
     */
    private fun sampleNotes(today: LocalDate, wording: SampleWording): String {
        fun day(offset: Long): String = today.plusDays(offset).toString()

        return """
            # ${wording.heading}

            ## TODO [#A] ${wording.certificate}
            `DEADLINE: <${day(-3)}>`

            ## TODO [#B] ${wording.releaseNotes}
            `SCHEDULED: <${day(0)} 09:30>`

            ## TODO ${wording.teamSync}
            `SCHEDULED: <${day(0)} 14:00 ++7d>`

            ## TODO [#C] ${wording.dependencyPins}
            `SCHEDULED: <${day(0)}>`

            ## TODO ${wording.quarterlyReport}
            `DEADLINE: <${day(5)}>`

            ## DONE ${wording.archivedBranch}
            `CLOSED: [${day(-1)}]`

            ## CANCELLED ${wording.stagingHost}
            `SCHEDULED: <${day(2)}>`
        """.trimIndent() + "\n"
    }
}
