package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import java.io.File
import java.time.LocalDate

/**
 * Where the markdown lives on the device.
 *
 * For now a directory inside the application's own storage, seeded with a
 * sample on first run so there is something to render. Once git
 * synchronisation lands this becomes the working copy and the seeding goes
 * away.
 */
class NotesStore(context: Context) {

    val root: File = File(context.filesDir, "notes")

    fun ensureSeeded(today: LocalDate) {
        if (!root.exists()) {
            root.mkdirs()
        }
        if (root.listFiles { file -> file.extension == "md" }?.isNotEmpty() == true) {
            return
        }
        File(root, "sample.md").writeText(sampleNotes(today))
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
