package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Where the markdown lives on the device.
 *
 * A directory inside the application's own storage, which is also the git
 * working copy once a remote is configured. Until then it is seeded with a
 * sample, so a fresh install has something to show.
 *
 * This is also the single point where access to that directory is serialised
 * — see [NotesArea.exclusive].
 */
class NotesStore(override val root: File) : NotesArea {

    /** Where it lives on a device: a directory of the application's own storage. */
    constructor(context: Context) : this(File(context.filesDir, "notes"))

    private val lock = Mutex()

    override suspend fun <T> exclusive(block: suspend () -> T): T =
        lock.withLock { withContext(Dispatchers.IO) { block() } }

    /**
     * Writes the sample unless the directory already holds notes.
     *
     * Skipped once a remote is configured: the directory is then a checkout,
     * and dropping an untracked file into it would show up as a dirty working
     * copy and block the next sync.
     */
    override suspend fun ensureSeeded(today: LocalDate, synced: () -> Boolean) = exclusive {
        if (!synced() && !hasNotes()) {
            File(root, "sample.md").writeText(sampleNotes(today))
        }
    }

    /**
     * Clears the checkout so a different remote can be cloned into it.
     *
     * The core clones into an empty directory; pointing the application at
     * another repository has to start from one.
     */
    override suspend fun reset() = exclusive {
        root.deleteRecursively()
        Unit
    }

    private fun hasNotes(): Boolean {
        if (!root.exists()) {
            root.mkdirs()
        }
        return root.listFiles { file -> file.extension == "md" }?.isNotEmpty() == true
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
    private fun sampleNotes(today: LocalDate): String {
        fun day(offset: Long): String = today.plusDays(offset).toString()

        return """
            # Sample notes

            ## TODO [#A] Renew the TLS certificate
            `DEADLINE: <${day(-3)}>`

            ## TODO [#B] Review the release notes
            `SCHEDULED: <${day(0)} 09:30>`

            ## TODO Team sync
            `SCHEDULED: <${day(0)} 14:00 ++7d>`

            ## TODO [#C] Update the dependency pins
            `SCHEDULED: <${day(0)}>`

            ## TODO Quarterly report
            `DEADLINE: <${day(5)}>`

            ## DONE Archive the old branch
            `CLOSED: [${day(-1)}]`

            ## CANCELLED Migrate the staging host
            `SCHEDULED: <${day(2)}>`
        """.trimIndent() + "\n"
    }
}
