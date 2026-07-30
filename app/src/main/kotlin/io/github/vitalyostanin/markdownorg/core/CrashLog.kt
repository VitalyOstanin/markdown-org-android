package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The trace of the run that did not end well, kept for the run after it.
 *
 * An exception out of a composition or out of a coroutine takes the process
 * down with a system dialog, and logcat is gone by the time anyone thinks to
 * look — on a phone there is no console it was printed to. Written to the
 * application's own storage, so a report about a crash can carry what
 * actually failed.
 *
 * One file, overwritten: the last crash is the one worth reading, and a log
 * that grows on its own is a second problem.
 */
class CrashLog(private val directory: File) {

    constructor(context: Context) : this(context.filesDir)

    private val file: File get() = File(directory, NAME)

    /** Write the trace, doing nothing that could fail in its own right. */
    fun record(thread: Thread, failure: Throwable) {
        // Called from the handler of an exception that is already ending the
        // process. Anything thrown here would replace the trace with one
        // about the writing of it, so nothing is thrown.
        runCatching {
            val trace = StringWriter().also { text ->
                PrintWriter(text).use { failure.printStackTrace(it) }
            }
            file.writeText("thread: ${thread.name}\n\n$trace")
        }
    }

    /** The trace of the last crash, or `null` if the last run ended well. */
    fun read(): String? = runCatching { file.takeIf(File::isFile)?.readText() }.getOrNull()

    /** Forget it, so it is shown once rather than on every launch. */
    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val NAME = "last-crash.txt"
    }
}
