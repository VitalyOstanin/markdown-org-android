package io.github.vitalyostanin.markdownorg.core

import uniffi.markdown_org_ffi.AgendaQuery
import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.NotesIndex
import uniffi.markdown_org_ffi.Options
import uniffi.markdown_org_ffi.Scope
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Reads the agenda out of the notes directory. */
interface AgendaLoader {

    /**
     * The agenda for [scope], drawn around [shown] and dated from [today].
     *
     * Two dates rather than one: [today] is what the plan is late or early
     * against, and [shown] is what is on screen. They part as soon as the
     * reader steps off today — and passed as one value the arrears of the
     * whole collection followed the reader from month to month, landing in
     * whichever day was being looked at. `null` draws the window around
     * [today], which is where a reader who has not stepped away is.
     *
     * [weekStart] is the weekday a week begins on, for the spans drawn in
     * weeks. The core reads no locale of its own and falls back to Monday, so
     * a client that knows better — the phone does, from its own settings —
     * says so.
     */
    suspend fun load(
        scope: Scope,
        today: LocalDate,
        shown: LocalDate? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        includeDone: Boolean = false,
        weekStart: DayOfWeek? = null,
    ): Result<AgendaResult>

    /**
     * Re-read one note, named as the task names it.
     *
     * For after an edit: the file that changed is known, and the rest of the
     * collection has not. What it saves is the whole of the walk — on a phone
     * with a thousand notes, seconds of it.
     *
     * Both halves of the name, because the same relative path occurs in more
     * than one collection: [root] is the directory the task came from, as the
     * task carries it, and dropping by [file] alone would take the tasks of a
     * note in another collection out of the agenda.
     */
    suspend fun reread(root: String, file: String): Result<Unit>

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
 * The core keeps the notes of the directories between calls (`NotesIndex`), so
 * an agenda after an edit costs the file that changed rather than the whole
 * collection. Everything the agenda means stays over there: this decides when
 * the held notes are stale, and nothing else.
 *
 * One index over every collection rather than one each, because the agenda
 * over them is one agenda: the task cap is a budget for the whole of it, the
 * scan statistics are one report, and the order of tasks falling on the same
 * minute belongs to the walk that produced them. Each task carries the root it
 * came from, which is how an edit reaches the collection it belongs to.
 *
 * The calls are synchronous and touch the filesystem, so they run off the main
 * thread and under the lock on every notes directory: a scan overlapping a
 * fast-forward would read a mixture of the files before and after it. It is
 * not a subprocess: Android forbids spawning the CLI, and the bindings call
 * the same code in-process.
 */
class AgendaSource(private val notes: NotesAreas) : AgendaLoader {

    /** The held notes, and the directories they were read from. */
    private var index: NotesIndex? = null
    private var indexed: List<File>? = null

    override suspend fun load(
        scope: Scope,
        today: LocalDate,
        shown: LocalDate?,
        zone: ZoneId,
        includeDone: Boolean,
        weekStart: DayOfWeek?,
    ): Result<AgendaResult> = notes.exclusive { areas ->
        // Failures arrive as ExtractException from the core and as
        // UnsatisfiedLinkError when the native library is missing for the
        // device's ABI; both have to reach the screen rather than turn into
        // an empty agenda.
        runCatching {
            held(roots(areas)).agenda(
                AgendaQuery(
                    scope = scope,
                    // The core never reads the clock; the caller decides what
                    // "today" is, so the same files always render the same
                    // agenda.
                    currentDate = today.toString(),
                    // And separately, which day the window is drawn around.
                    // Left out, it is drawn around today.
                    date = shown?.toString(),
                    timezone = zone.id,
                    includeDone = includeDone,
                    // Named as the core names its weekdays -- lower case, in
                    // English, whatever the phone's own language is.
                    weekStart = weekStart?.name?.lowercase(Locale.ROOT),
                ),
            )
        }
    }

    override suspend fun reread(root: String, file: String): Result<Unit> =
        notes.exclusive { areas ->
            runCatching {
                // Nothing held means nothing to bring up to date: the next
                // agenda walks the directories and reads this file along with
                // the rest.
                index?.takeIf { indexed == roots(areas) }?.refreshFile(root, file) ?: Unit
            }
        }

    override suspend fun invalidate() {
        notes.exclusive { drop() }
    }

    /**
     * The index over [roots], built if there is none over exactly those.
     *
     * The directories are compared rather than assumed: they change under a
     * running application — one added, one removed, one pointed elsewhere —
     * and an index over the previous set would answer with a collection that
     * is no longer read, or without one that now is.
     */
    private fun held(roots: List<File>): NotesIndex {
        index?.takeIf { indexed == roots }?.let { return it }

        drop()
        // Left at what the core fills in: the file glob, the locales for
        // weekday names and the task cap are stated there, and repeating them
        // here as three nulls would only be a second place to keep them.
        return NotesIndex.open(roots.map(File::getAbsolutePath), Options()).also {
            index = it
            indexed = roots
        }
    }

    /**
     * The directories of [areas], in the order the collections are shown.
     *
     * Named from the areas the lock was taken over rather than from the set as
     * it is now: those are the directories this walk is allowed to read.
     */
    private fun roots(areas: List<NotesArea>): List<File> = areas.map(NotesArea::root)

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
