package io.github.vitalyostanin.markdownorg.core

import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.Options
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.scanAgenda
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
}

/**
 * The agenda, as the Rust core returns it.
 *
 * The call is synchronous and walks the filesystem, so it runs off the main
 * thread and under the lock on the notes directory: a scan overlapping a
 * fast-forward would read a mixture of the files before and after it. It is
 * not a subprocess: Android forbids spawning the CLI, and the bindings call
 * the same code in-process.
 */
class AgendaSource(private val notes: NotesArea) : AgendaLoader {

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
            scanAgenda(
                dir = notes.root.absolutePath,
                scope = scope,
                // The core never reads the clock; the caller decides what
                // "today" is, so the same files always render the same agenda.
                currentDate = today.toString(),
                timezone = zone.id,
                includeDone = includeDone,
                options = Options(glob = null, locale = null, maxTasks = null),
            )
        }
    }
}
