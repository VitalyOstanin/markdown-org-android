package io.github.vitalyostanin.markdownorg.core

import android.content.Intent
import java.time.LocalDate

/** The day a notification points at, and the entry within it when it names one. */
data class AgendaTarget(val day: LocalDate, val entry: ReminderEntry?)

/**
 * Where a tapped notification asks the agenda to open.
 *
 * Extras of the launch intent rather than a link of its own: the application
 * is one activity, and a link would publish an address to the whole device for
 * a screen only this notification opens. The entry is named by file and line
 * for the reason an edit is — headings repeat, and a repeating entry repeats
 * its own.
 */
object AgendaAddress {

    /** The target as extras of an intent that starts the activity. */
    fun pack(intent: Intent, target: AgendaTarget): Intent {
        intent.putExtra(EXTRA_DAY, target.day.toString())
        target.entry?.let { entry ->
            intent
                .putExtra(EXTRA_ROOT, entry.root)
                .putExtra(EXTRA_FILE, entry.file)
                .putExtra(EXTRA_LINE, entry.line.toInt())
                .putExtra(EXTRA_HEADING, entry.heading)
        }

        return intent
    }

    /**
     * What was packed, or `null` from an intent that carries none of it.
     *
     * Null rather than an exception: the activity is started by the launcher
     * as well, and a launch that names no day is the ordinary one.
     */
    fun unpack(intent: Intent?): AgendaTarget? {
        val day = intent?.getStringExtra(EXTRA_DAY)
            ?.let(::statedDate)
            ?: return null
        val file = intent.getStringExtra(EXTRA_FILE)
        val heading = intent.getStringExtra(EXTRA_HEADING)

        if (file == null || heading == null) {
            return AgendaTarget(day = day, entry = null)
        }

        return AgendaTarget(
            day = day,
            entry = ReminderEntry(
                root = intent.getStringExtra(EXTRA_ROOT),
                file = file,
                line = intent.getIntExtra(EXTRA_LINE, 0).coerceAtLeast(0).toUInt(),
                heading = heading,
            ),
        )
    }

    /**
     * Take the address back out, so a later launch does not reopen it.
     *
     * The activity keeps the intent it was started with, and the platform
     * hands the same one back after a rotation or a return from the recents
     * list. Left in place, the sheet the reader has just closed would open
     * again on every one of those.
     */
    fun clear(intent: Intent?) {
        intent?.removeExtra(EXTRA_DAY)
        intent?.removeExtra(EXTRA_ROOT)
        intent?.removeExtra(EXTRA_FILE)
        intent?.removeExtra(EXTRA_LINE)
        intent?.removeExtra(EXTRA_HEADING)
    }

    private const val EXTRA_DAY = "agenda-day"
    private const val EXTRA_ROOT = "agenda-root"
    private const val EXTRA_FILE = "agenda-file"
    private const val EXTRA_LINE = "agenda-line"
    private const val EXTRA_HEADING = "agenda-heading"
}
