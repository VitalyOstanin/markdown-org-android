package io.github.vitalyostanin.markdownorg.core

import uniffi.markdown_org_ffi.CommitAuthor
import uniffi.markdown_org_ffi.EditTarget
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.commitChanges
import uniffi.markdown_org_ffi.holdsRepository
import java.time.LocalDate
import kotlin.math.abs
import uniffi.markdown_org_ffi.completeTask as coreComplete
import uniffi.markdown_org_ffi.setPriority as coreSetPriority
import uniffi.markdown_org_ffi.setStatus as coreSetStatus
import uniffi.markdown_org_ffi.shiftPlanning as coreShiftPlanning

/**
 * What an edit did: to the file, and to the history.
 *
 * The two are separate outcomes with separate consequences. A failure to write
 * means nothing happened and the tap can be repeated. A failure to commit
 * means the note has already been changed — repeating the tap would edit an
 * edited file, and the answer is to commit again, which the next edit and the
 * next sync both do.
 */
data class EditReport(val committed: Boolean, val commitFailure: Throwable? = null)

/** Point edits to the notes. */
interface NotesWriter {

    suspend fun complete(task: Task, today: LocalDate): Result<EditReport>

    suspend fun setStatus(task: Task, status: TaskType?): Result<EditReport>

    suspend fun setPriority(task: Task, priority: String?): Result<EditReport>

    suspend fun shift(task: Task, keyword: PlanningKeyword, days: Int): Result<EditReport>

    /**
     * Commit whatever earlier edits left uncommitted.
     *
     * The core's commit is idempotent — a working copy that matches HEAD
     * produces no commit — so this is safe to call whenever an uncommitted
     * edit would get in the way, which is before every sync: the core refuses
     * to fast-forward a dirty checkout.
     */
    suspend fun commitPending(): Result<Boolean>
}

/**
 * The commit half of an edit.
 *
 * Behind an interface so the two halves can fail apart from each other in a
 * test: the write goes through the native library, and a library call cannot
 * be made to fail on the JVM.
 */
internal fun interface Committer {

    /** Commit the working copy, answering whether there was anything to commit. */
    fun commit(dir: String, message: String, author: CommitAuthor): Boolean
}

/**
 * What the core does, which is what runs on a device.
 *
 * A directory holding no repository is not a failure: the sample notes are
 * edited before a remote is set up, and there is simply nothing to commit.
 * Asked with the cheap question rather than by reading the state — the state
 * is a walk of the whole working copy.
 */
internal val CoreCommitter = Committer { dir, message, author ->
    holdsRepository(dir) && commitChanges(dir, message, author) != null
}

/**
 * Point edits to the notes, each one committed as it is made.
 *
 * The commit is not a separate step the interface could postpone. An edited
 * but uncommitted file leaves the checkout dirty, and the core refuses to
 * fast-forward a dirty checkout — so an edit without a commit would break
 * syncing until something else committed.
 *
 * A checkout that is not a git repository at all (the sample notes before
 * the remote is set up) is edited all the same; there is simply nothing to
 * commit.
 */
class NotesEditor internal constructor(
    private val notes: NotesArea,
    private val settings: SyncPreferences,
    private val committer: Committer,
) : NotesWriter {

    constructor(notes: NotesArea, settings: SyncPreferences) :
        this(notes, settings, CoreCommitter)

    /** Mark done, or move a repeating task to its next occurrence. */
    override suspend fun complete(task: Task, today: LocalDate): Result<EditReport> = write {
        val outcome = coreComplete(task.target(), today.toString())
        completionMessage(task.heading, outcome.repeated)
    }

    /** Set or clear the keyword outright, without the repeater semantics. */
    override suspend fun setStatus(task: Task, status: TaskType?): Result<EditReport> = write {
        coreSetStatus(task.target(), status)
        statusMessage(task.heading, status)
    }

    /** Set or clear the priority cookie. */
    override suspend fun setPriority(task: Task, priority: String?): Result<EditReport> = write {
        coreSetPriority(task.target(), priority)
        priorityMessage(task.heading, priority)
    }

    /** Move a planning date by whole days. */
    override suspend fun shift(
        task: Task,
        keyword: PlanningKeyword,
        days: Int,
    ): Result<EditReport> = write {
        coreShiftPlanning(task.target(), keyword, days)
        shiftMessage(task.heading, keyword, days)
    }

    override suspend fun commitPending(): Result<Boolean> = notes.exclusive {
        runCatching { committer.commit(notes.root.absolutePath, PENDING_MESSAGE, author()) }
    }

    /**
     * Run [edit] off the main thread and commit what it changed.
     *
     * Held under the lock on the notes directory for the whole of it: the
     * write and the commit are one step, and a fast-forward landing between
     * them would commit a tree nobody asked for.
     *
     * [edit] returns the commit message, so the message describes what
     * actually happened — a completion that turned out to be a repeat says
     * so.
     *
     * The two halves answer separately. A write that failed is a failure: the
     * file is as it was. A commit that failed over a written file is not —
     * saying so would send the user to repeat an edit that has already
     * happened, and the second attempt would come back as "the file has
     * changed". The uncommitted change is picked up by the next edit and by
     * the commit that precedes the next sync.
     */
    internal suspend fun write(edit: () -> String): Result<EditReport> = notes.exclusive {
        runCatching(edit).map { message ->
            runCatching { committer.commit(notes.root.absolutePath, message, author()) }.fold(
                onSuccess = { committed -> EditReport(committed = committed) },
                onFailure = { failure -> EditReport(committed = false, commitFailure = failure) },
            )
        }
    }

    private fun Task.target() = EditTarget(
        dir = notes.root.absolutePath,
        file = file,
        line = line,
        heading = heading,
    )

    private fun author() = CommitAuthor(
        name = settings.authorName,
        email = settings.authorEmail,
    )

    private companion object {
        /** What an edit could not commit at the time, committed later. */
        const val PENDING_MESSAGE = "Commit the edits left uncommitted"
    }
}

/*
 * The commit messages, apart from the edits that produce them.
 *
 * They are what the history of someone's notes will read like, and they are
 * the one part of an edit that can be pinned down without a device: the calls
 * around them go through the native library, these do not.
 */

/** A completion that turned out to be a repeat says so. */
internal fun completionMessage(heading: String, repeated: Boolean): String = when {
    repeated -> "Move \"$heading\" to its next occurrence"
    else -> "Mark \"$heading\" as done"
}

internal fun statusMessage(heading: String, status: TaskType?): String = when (status) {
    null -> "Clear the keyword on \"$heading\""
    else -> "Set \"$heading\" to ${status.keyword()}"
}

internal fun priorityMessage(heading: String, priority: String?): String = when (priority) {
    null -> "Drop the priority of \"$heading\""
    else -> "Set the priority of \"$heading\" to $priority"
}

/** Which date moved, which way and by how much. */
internal fun shiftMessage(heading: String, keyword: PlanningKeyword, days: Int): String {
    val date = keyword.keyword()
    val count = abs(days)
    val unit = if (count == 1) "day" else "days"

    return when {
        days > 0 -> "Move the $date of \"$heading\" forward by $count $unit"
        days < 0 -> "Move the $date of \"$heading\" back by $count $unit"
        else -> "Leave the $date of \"$heading\" where it is"
    }
}

private fun TaskType.keyword() = when (this) {
    TaskType.TODO -> "TODO"
    TaskType.DONE -> "DONE"
    TaskType.CANCELLED -> "CANCELLED"
}

private fun PlanningKeyword.keyword() = when (this) {
    PlanningKeyword.SCHEDULED -> "SCHEDULED"
    PlanningKeyword.DEADLINE -> "DEADLINE"
}
