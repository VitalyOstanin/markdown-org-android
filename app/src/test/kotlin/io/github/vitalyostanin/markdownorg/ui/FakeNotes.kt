package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.core.AgendaLoader
import io.github.vitalyostanin.markdownorg.core.CollectionInUse
import io.github.vitalyostanin.markdownorg.core.CollectionsInUse
import io.github.vitalyostanin.markdownorg.core.EditReport
import io.github.vitalyostanin.markdownorg.core.FIRST_ID
import io.github.vitalyostanin.markdownorg.core.GroupReport
import io.github.vitalyostanin.markdownorg.core.NotesArea
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.NotesCollectionsPreferences
import io.github.vitalyostanin.markdownorg.core.NotesLocationPreferences
import io.github.vitalyostanin.markdownorg.core.NotesSyncer
import io.github.vitalyostanin.markdownorg.core.NotesWriter
import io.github.vitalyostanin.markdownorg.core.SampleWording
import io.github.vitalyostanin.markdownorg.core.SyncPreferences
import io.github.vitalyostanin.markdownorg.core.SyncRun
import io.github.vitalyostanin.markdownorg.core.UiPreferences
import io.github.vitalyostanin.markdownorg.core.UndoReport
import io.github.vitalyostanin.markdownorg.core.holdingAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.markdown_org_ffi.Adoption
import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.BulkOutcome
import uniffi.markdown_org_ffi.EntryText
import uniffi.markdown_org_ffi.FileRollback
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.RepoStatus
import uniffi.markdown_org_ffi.RevertOutcome
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.SyncOutcome
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import java.io.File
import java.time.DayOfWeek
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
class FakeNotesArea(root: File = File("/notes")) : NotesArea {

    override var root: File = root
        private set

    /** What pointing the working copy elsewhere answers, so a refusal can be played. */
    var moveResult: Result<Unit> = Result.success(Unit)

    private val lock = Mutex()

    override suspend fun useDirectory(directory: File): Result<Unit> = exclusive {
        trace += "move"
        moveResult.onSuccess { root = directory }
    }

    /** What making the directory answers, so a storage that is gone can be played. */
    var prepareResult: Result<Unit> = Result.success(Unit)

    override suspend fun prepareDirectory(): Result<Unit> = exclusive {
        // Its own marker rather than "move": the tests about the working copy
        // are about being pointed elsewhere, which this is not.
        trace += "prepare"
        prepareResult
    }

    /** What happened to the directory, in order, with markers for the overlaps. */
    val trace = mutableListOf<String>()

    var seeded: Int = 0
        private set

    /** What seeding answers, so a directory that cannot be written to can be played. */
    var seedResult: Result<Unit> = Result.success(Unit)

    override suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    override suspend fun ensureSeeded(
        today: LocalDate,
        wording: SampleWording,
        synced: () -> Boolean,
    ) = exclusive {
        if (!synced()) {
            seeded++
            trace += "seed"
        }
        seedResult
    }
}

/** A syncer that reports what it was asked and can be held mid-flight. */
class FakeSyncer(
    private val onSync: suspend (String?) -> Result<SyncRun> = { Result.success(run()) },
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

    /**
     * What the next sync answers, when a test decides that between attempts.
     *
     * Beside the constructor's own answer rather than instead of it: a test
     * that only cares about the outcome sets this and changes it as it goes,
     * while one that has to hold a sync mid-flight passes a function.
     */
    var result: Result<SyncRun>? = null

    override suspend fun sync(settings: SyncPreferences): Result<SyncRun> {
        requested += settings.remoteUrl
        running = true
        try {
            return result ?: onSync(settings.remoteUrl)
        } finally {
            running = false
        }
    }

    /**
     * Held open by the test that needs the checkout read to take a while:
     * saving settings reads it, and what a tap on the sync icon does in that
     * window is what such a test is about.
     */
    var statusGate: CompletableDeferred<Unit>? = null

    override suspend fun status(): Result<RepoStatus?> {
        statusReads += 1
        statusGate?.await()
        return statusResult
    }

    /** What taking the directory into git answers with, for the test that asks. */
    var adoptResult: Result<Adoption> = Result.success(Adoption.Took)

    /** Remotes the directory was asked to start tracking, in order. */
    val adopted = mutableListOf<String?>()

    /** How many times the remote's notes were taken over the local ones. */
    var remotesTaken = 0
        private set

    /** Whether the notes directory answers as a checkout. */
    var checkout: Boolean = false

    override suspend fun adopt(settings: SyncPreferences): Result<Adoption> {
        adopted += settings.remoteUrl
        return adoptResult
    }

    override suspend fun takeRemote(settings: SyncPreferences): Result<SyncOutcome> {
        remotesTaken += 1
        return Result.success(outcome(cloned = false))
    }

    override suspend fun holdsRepository(): Boolean = checkout

    companion object {

        fun outcome(cloned: Boolean = true, commits: UInt = 0u) = SyncOutcome(
            cloned = cloned,
            commitsApplied = commits,
            head = status("https://example.test/notes.git"),
        )

        /** A whole sync: what the fetch did, and what went back afterwards. */
        fun run(
            cloned: Boolean = true,
            commits: UInt = 0u,
            pushed: UInt = 0u,
            pushFailure: Throwable? = null,
        ) = SyncRun(
            fetched = outcome(cloned, commits),
            pushed = pushed,
            pushFailure = pushFailure,
        )

        fun status(url: String, unpushed: UInt = 0u) = RepoStatus(
            url = url,
            branch = "main",
            headId = "0123456789abcdef0123456789abcdef01234567",
            headSummary = "Initial commit",
            headTime = 0,
            dirty = false,
            unpushed = unpushed,
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

    /** Which span each agenda was asked for, in the order the calls came. */
    val scopes = mutableListOf<Scope>()

    /** Which date each of them was asked around, in the same order. */
    val dates = mutableListOf<LocalDate>()

    /** And what each was dated from — today, wherever the window sits. */
    val todays = mutableListOf<LocalDate>()

    /** The weekday each was asked to begin its weeks on, `null` for none. */
    val weekStarts = mutableListOf<DayOfWeek?>()

    override suspend fun load(
        scope: Scope,
        today: LocalDate,
        shown: LocalDate?,
        zone: ZoneId,
        includeDone: Boolean,
        weekStart: DayOfWeek?,
    ): Result<AgendaResult> {
        scopes += scope
        dates += shown ?: today
        todays += today
        weekStarts += weekStart
        val answer = CompletableDeferred<Result<AgendaResult>>()
        pending += answer
        return answer.await()
    }

    /** Roots re-read, beside [reread] — the pair is what names a note. */
    val rereadRoots = mutableListOf<String>()

    override suspend fun reread(root: String, file: String): Result<Unit> {
        rereadRoots += root
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

    /** Which date the last call wrote, and to when -- `null` for one taken off. */
    var planned: Pair<PlanningKeyword, LocalDate?>? = null
        private set

    override suspend fun setPlanning(
        task: Task,
        keyword: PlanningKeyword,
        date: LocalDate?,
    ): Result<EditReport> {
        planned = keyword to date
        return record()
    }

    /** What an entry reads as, and what the last save was handed. */
    var entry: Result<EntryText> = Result.success(EntryText(title = "", body = ""))

    /** The title and the body of the last save, so a test can see them. */
    var saved: Pair<String, String>? = null
        private set

    override suspend fun readEntry(task: Task): Result<EntryText> = entry

    override suspend fun setEntry(task: Task, title: String, body: String): Result<EditReport> {
        saved = title to body
        return record()
    }

    /** What the last group action was asked to do, and to how many tasks. */
    var group: Pair<BulkAction, List<Task>>? = null
        private set

    /** What acting on a group answers with. */
    var groupOutcome: Result<GroupReport> = Result.success(
        GroupReport(
            outcome = BulkOutcome(changed = 0u, refused = emptyList(), rollback = emptyList()),
            report = EditReport(committed = true),
        ),
    )

    /** What the last undo was handed, so a test can see it was the rollback. */
    var undone: List<FileRollback>? = null
        private set

    /** What undoing a group answers with. */
    var undoOutcome: Result<UndoReport> = Result.success(
        UndoReport(
            outcome = RevertOutcome(
                restored = emptyList(),
                skipped = emptyList(),
                failed = emptyList(),
            ),
            report = EditReport(committed = true),
        ),
    )

    override suspend fun applyToGroup(
        tasks: List<Task>,
        action: BulkAction,
        today: LocalDate,
    ): Result<GroupReport> {
        calls += 1
        group = action to tasks
        return groupOutcome
    }

    override suspend fun undoGroup(rollback: List<FileRollback>): Result<UndoReport> {
        undone = rollback
        return undoOutcome
    }

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
class FakeUiPreferences(
    override var layout: AgendaLayout = AgendaLayout.TIME,
    override var span: AgendaSpan = AgendaSpan.DAY,
    override var grouped: Boolean = true,
    override var monthAsGrid: Boolean = true,
    override var weekStart: WeekStart = WeekStart.AUTO,
) : UiPreferences

/** Where the notes are kept, in memory. */
class FakeNotesLocation(override var path: String? = null) : NotesLocationPreferences

/** The stored set of collections, in memory. */
class FakeCollectionsStore(override var collections: List<NotesCollection> = emptyList()) :
    NotesCollectionsPreferences

/**
 * The collections in use, built out of the stand-ins above.
 *
 * A test names the directories and gets one entry per directory, each with its
 * own area, settings and writer — which is what makes "the edit went to the
 * collection the task came from" something that can be asserted.
 */
class FakeCollections(override var entries: List<CollectionInUse>) : CollectionsInUse {

    override val areas: List<NotesArea> get() = entries.map(CollectionInUse::area)

    /** The sets this was told to work with, in order. */
    val used = mutableListOf<List<NotesCollection>>()

    /** The collections whose settings were erased, in order. */
    val forgotten = mutableListOf<String>()

    override fun forget(collection: CollectionInUse) {
        forgotten += collection.collection.id
    }

    override suspend fun <T> exclusive(block: suspend (List<NotesArea>) -> T): T {
        val held = areas

        return holdingAll(held) { block(held) }
    }

    override fun use(collections: List<NotesCollection>) {
        used += collections
        val known = entries.associateBy { it.collection.id }
        entries = collections.map { collection ->
            known[collection.id]?.copy(collection = collection)
                ?: entry(collection.id, collection.name, collection.path)
        }
    }

    companion object {

        /** One collection over [path], with stand-ins for everything that acts on it. */
        fun entry(
            id: String = FIRST_ID,
            name: String = "Notes",
            path: String = "/notes",
            area: FakeNotesArea = FakeNotesArea(File(path)),
            settings: FakePreferences = FakePreferences(),
            editor: FakeWriter = FakeWriter(),
            syncer: FakeSyncer = FakeSyncer(),
        ) = CollectionInUse(
            collection = NotesCollection(id = id, name = name, path = path),
            // The stand-in area names a directory that is not on disk, so the
            // path is taken as it is rather than resolved: what matters here
            // is that a task from it carries the same string.
            root = path,
            area = area,
            settings = settings,
            editor = editor,
            syncer = syncer,
        )

        /** The single collection a device that has not been set up works with. */
        fun one(
            area: FakeNotesArea = FakeNotesArea(),
            settings: FakePreferences = FakePreferences(),
            editor: FakeWriter = FakeWriter(),
            syncer: FakeSyncer = FakeSyncer(),
        ) = FakeCollections(
            listOf(
                entry(
                    path = area.root.absolutePath,
                    area = area,
                    settings = settings,
                    editor = editor,
                    syncer = syncer,
                ),
            ),
        )
    }
}

/** Settings in memory, with the same defaults the stored ones fall back to. */
class FakePreferences(
    override var remoteUrl: String? = null,
    override var branch: String? = null,
    override var token: String? = null,
    override var authorName: String = "markdown-org",
    override var authorEmail: String = "markdown-org@localhost",
    override var lastSyncedAt: Long = 0,
    override var storesLocally: Boolean = false,
    override var sshKey: String? = null,
    override var sshPassphrase: String? = null,
    override var sshPublicKey: String? = null,
    override var knownHost: String? = null,
) : SyncPreferences
