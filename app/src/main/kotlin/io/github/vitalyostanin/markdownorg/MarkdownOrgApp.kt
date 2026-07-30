package io.github.vitalyostanin.markdownorg

import android.app.Application
import io.github.vitalyostanin.markdownorg.core.CrashLog

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
    }
}
