package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import java.io.File
import java.time.LocalDate

/**
 * Where the markdown lives on the device.
 *
 * A directory inside the application's own storage, which is also the git
 * working copy once a remote is configured. Until then it is seeded with a
 * sample, so a fresh install has something to show.
 */
class NotesStore(context: Context) {

    val root: File = File(context.filesDir, "notes")

    /**
     * Writes the sample unless the directory already holds notes.
     *
     * Skipped once a remote is configured: the directory is then a checkout,
     * and dropping an untracked file into it would show up as a dirty working
     * copy and block the next sync.
     */
    fun ensureSeeded(today: LocalDate, synced: Boolean) {
        if (synced) {
            return
        }
        if (!root.exists()) {
            root.mkdirs()
        }
        if (root.listFiles { file -> file.extension == "md" }?.isNotEmpty() == true) {
            return
        }
        File(root, "sample.md").writeText(sampleNotes(today))
    }

    /**
     * Clears the checkout so a different remote can be cloned into it.
     *
     * The core clones into an empty directory; pointing the application at
     * another repository has to start from one.
     */
    fun reset() {
        root.deleteRecursively()
    }

    /**
     * Dates are written relative to [today] rather than fixed, so the sample
     * still fills the agenda whenever it is first opened.
     *
     * The timestamp line is wrapped in backticks. That is the format the
     * extractor recognises and the extension writes: a markdown renderer
     * treats a bare `<2026-07-28>` as an HTML tag and swallows it.
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
            `CLOSED: <${day(-1)}>`

            ## CANCELLED Migrate the staging host
            `SCHEDULED: <${day(2)}>`
        """.trimIndent() + "\n"
    }
}
