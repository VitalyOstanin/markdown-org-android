package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.core.AgendaLoader
import io.github.vitalyostanin.markdownorg.core.EditReport
import io.github.vitalyostanin.markdownorg.core.NotesArea
import io.github.vitalyostanin.markdownorg.core.NotesLocationPreferences
import io.github.vitalyostanin.markdownorg.core.NotesSyncer
import io.github.vitalyostanin.markdownorg.core.NotesWriter
import io.github.vitalyostanin.markdownorg.core.SyncPreferences
import io.github.vitalyostanin.markdownorg.core.UiPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.RepoStatus
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.SyncOutcome
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stand-ins for the core, so what the view model does with concurrent
 * requests can be pinned down on the JVM.
 *
 * The real implementations call the native library through UniFFI and touch a
 * directory on the device; neither is available here, and neither is what
 * these tests are about — the order in which the view model asks is.
 */
class FakeNotesArea : NotesArea {

    override var root: File = File("/notes")
        private set

    /** What pointing the working copy elsewhere answers, so a refusal can be played. */
    var moveResult: Result<Unit> = Result.success(Unit)

    private val lock = Mutex()

    override suspend fun useDirectory(directory: File): Result<Unit> = exclusive {
        trace += "move"
        moveResult.onSuccess { root = directory }
    }

    /** What happened to the directory, in order, with markers for the overlaps. */
    val trace = mutableListOf<String>()

    var seeded: Int = 0
        private set

    var wiped: Int = 0
        private set

    /** Run at the moment of the wipe, so a test can see what else was going on. */
    var onWipe: () -> Unit = {}

    /** What seeding answers, so a directory that cannot be written to can be played. */
    var seedResult: Result<Unit> = Result.success(Unit)

    /** What the wipe answers: a directory emptied only in part fails here. */
    var resetResult: Result<Unit> = Result.success(Unit)

    override suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    override suspend fun ensureSeeded(today: LocalDate, synced: () -> Boolean) = exclusive {
        if (!synced()) {
            seeded++
            trace += "seed"
        }
        seedResult
    }

    override suspend fun reset() = exclusive {
        wiped++
        trace += "wipe"
        onWipe()
        resetResult
    }
}

/** A syncer that reports what it was asked and can be held mid-flight. */
class FakeSyncer(
    private val onSync: suspend (String?) -> Result<SyncOutcome> = { Result.success(outcome()) },
) : NotesSyncer {

    val requested = mutableListOf<String?>()

    /** Set to `false` to make the checkout unreadable rather than absent. */
    var statusResult: Result<RepoStatus?> = Result.success(null)

    /** How many times the checkout was read, to catch a read nobody needed. */
    var statusReads = 0
        private set

    /** Raised while a sync is in flight, so a test can assert on the overlap. */
    var running: Boolean = false
        private set

    override suspend fun sync(settings: SyncPreferences): Result<SyncOutcome> {
        requested += settings.remoteUrl
        running = true
        try {
            return onSync(settings.remoteUrl)
        } finally {
            running = false
        }
    }

    override suspend fun status(): Result<RepoStatus?> {
        statusReads += 1
        return statusResult
    }

    companion object {

        fun outcome(cloned: Boolean = true, commits: UInt = 0u) = SyncOutcome(
            cloned = cloned,
            commitsApplied = commits,
            head = status("https://example.test/notes.git"),
        )

        fun status(url: String) = RepoStatus(
            url = url,
            branch = "main",
            headId = "0123456789abcdef0123456789abcdef01234567",
            headSummary = "Initial commit",
            headTime = 0,
            dirty = false,
        )
    }
}

/** An agenda source whose answers a test decides on, one call at a time. */
class FakeAgendaLoader : AgendaLoader {

    /** Held open until the test releases them, in the order they were asked for. */
    val pending = mutableListOf<CompletableDeferred<Result<AgendaResult>>>()

    /** Files re-read, in order — one per edit that named the note it changed. */
    val reread = mutableListOf<String>()

    /** How many times everything held was dropped. */
    var invalidations = 0
        private set

    /** What a re-read answers, for the test that makes one fail. */
    var rereadResult: Result<Unit> = Result.success(Unit)

    override suspend fun load(
        scope: Scope,
        today: LocalDate,
        zone: ZoneId,
        includeDone: Boolean,
    ): Result<AgendaResult> {
        val answer = CompletableDeferred<Result<AgendaResult>>()
        pending += answer
        return answer.await()
    }

    override suspend fun reread(file: String): Result<Unit> {
        reread += file
        return rereadResult
    }

    override suspend fun invalidate() {
        invalidations++
    }
}

/** An editor whose outcome the test sets. */
class FakeWriter(var outcome: Result<EditReport> = Result.success(EditReport(committed = true))) :
    NotesWriter {

    /** How many edits reached the writer, for asserting that one did not. */
    var calls = 0
        private set

    /** How many times the leftovers of an earlier edit were committed. */
    var pendingCommits = 0
        private set

    override suspend fun complete(task: Task, today: LocalDate): Result<EditReport> = record()

    override suspend fun setStatus(task: Task, status: TaskType?): Result<EditReport> = record()

    override suspend fun setPriority(task: Task, priority: String?): Result<EditReport> = record()

    override suspend fun shift(
        task: Task,
        keyword: PlanningKeyword,
        days: Int,
    ): Result<EditReport> = record()

    override suspend fun commitPending(): Result<Boolean> {
        pendingCommits += 1
        return Result.success(false)
    }

    private fun record(): Result<EditReport> {
        calls += 1
        return outcome
    }
}

/** What the user chose about the interface, in memory. */
class FakeUiPreferences(override var layout: AgendaLayout = AgendaLayout.TIME) : UiPreferences

/** Where the notes are kept, in memory. */
class FakeNotesLocation(override var path: String? = null) : NotesLocationPreferences

/** Settings in memory, with the same defaults the stored ones fall back to. */
class FakePreferences(
    override var remoteUrl: String? = null,
    override var branch: String? = null,
    override var token: String? = null,
    override var authorName: String = "markdown-org",
    override var authorEmail: String = "markdown-org@localhost",
    override var lastSyncedAt: Long = 0,
) : SyncPreferences
