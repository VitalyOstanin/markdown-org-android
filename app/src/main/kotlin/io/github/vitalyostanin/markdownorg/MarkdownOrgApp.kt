package io.github.vitalyostanin.markdownorg

import android.app.Application
import io.github.vitalyostanin.markdownorg.core.CrashLog
import io.github.vitalyostanin.markdownorg.core.SyncSettings
import io.github.vitalyostanin.markdownorg.core.UiSettings

/**
 * Keeps the trace of a run that ended in a crash.
 *
 * Without this, an exception out of a composition or out of a coroutine ends
 * the process with a system dialog and leaves nothing behind: there is no
 * console on a phone, and logcat is cleared long before a report about the
 * crash is written. The trace goes to the application's own storage, and the
 * settings screen offers it on the next launch.
 */
class MarkdownOrgApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val log = CrashLog(this)
        // Chained rather than replaced: the platform handler is what ends the
        // process. Left out, the thread that failed would stay up with
        // nothing running on it and the application would appear to hang.
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            log.record(thread, failure)
            platform?.uncaughtException(thread, failure)
        }

        // After the handler above is in place, so that a failure on that
        // thread is written down like any other.
        warmPreferences()
    }

    /**
     * Read both preference files before the first screen asks for them.
     *
     * Reading a preference reads its file off storage, and the agenda asks for
     * three of them — the remote, the layout, the time of the last sync —
     * while the main thread is building the first frame. Doing it here, on a
     * thread of its own, moves that read out of the way of the frame.
     *
     * An attempt rather than a guarantee: nothing waits on this thread. Should
     * the agenda get there first, the read happens where it used to — and a
     * file that cannot be read fails there too, on the screen that asked for
     * it, rather than ending the process from here before there is a screen.
     */
    private fun warmPreferences() {
        Thread(
            {
                runCatching {
                    SyncSettings(this).isConfigured
                    UiSettings(this).layout
                }
            },
            "preferences-warmup",
        ).start()
    }
}
