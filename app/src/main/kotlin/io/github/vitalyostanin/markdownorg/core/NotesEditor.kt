package io.github.vitalyostanin.markdownorg.core

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.markdown_org_ffi.CommitAuthor
import uniffi.markdown_org_ffi.EditTarget
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.commitChanges
import uniffi.markdown_org_ffi.completeTask as coreComplete
import uniffi.markdown_org_ffi.repositoryStatus
import uniffi.markdown_org_ffi.setPriority as coreSetPriority
import uniffi.markdown_org_ffi.setStatus as coreSetStatus
import uniffi.markdown_org_ffi.shiftPlanning as coreShiftPlanning

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
class NotesEditor(private val root: File, private val settings: SyncSettings) {

    /** Mark done, or move a repeating task to its next occurrence. */
    suspend fun complete(task: Task, today: LocalDate): Result<Unit> = write(task) {
        val outcome = coreComplete(task.target(), today.toString())
        if (outcome.repeated) {
            "Move \"${task.heading}\" to its next occurrence"
        } else {
            "Mark \"${task.heading}\" as done"
        }
    }

    /** Set or clear the keyword outright, without the repeater semantics. */
    suspend fun setStatus(task: Task, status: TaskType?): Result<Unit> = write(task) {
        coreSetStatus(task.target(), status)
        when (status) {
            null -> "Clear the keyword on \"${task.heading}\""
            else -> "Set \"${task.heading}\" to ${status.keyword()}"
        }
    }

    /** Set or clear the priority cookie. */
    suspend fun setPriority(task: Task, priority: String?): Result<Unit> = write(task) {
        coreSetPriority(task.target(), priority)
        when (priority) {
            null -> "Drop the priority of \"${task.heading}\""
            else -> "Set the priority of \"${task.heading}\" to $priority"
        }
    }

    /** Move a planning date by whole days. */
    suspend fun shift(task: Task, keyword: PlanningKeyword, days: Int): Result<Unit> = write(task) {
        coreShiftPlanning(task.target(), keyword, days)
        "Move \"${task.heading}\" by $days day(s)"
    }

    /**
     * Run [edit] off the main thread and commit what it changed.
     *
     * [edit] returns the commit message, so the message describes what
     * actually happened — a completion that turned out to be a repeat says
     * so.
     */
    private suspend fun write(task: Task, edit: () -> String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val message = edit()
                // No repository yet: the sample notes are edited too, they
                // just have no history to write to.
                if (repositoryStatus(root.absolutePath) != null) {
                    commitChanges(root.absolutePath, message, author())
                }
                Unit
            }
        }

    private fun Task.target() = EditTarget(
        dir = root.absolutePath,
        file = file,
        line = line,
        heading = heading,
    )

    private fun author() = CommitAuthor(
        name = settings.authorName,
        email = settings.authorEmail,
    )

    private fun TaskType.keyword() = when (this) {
        TaskType.TODO -> "TODO"
        TaskType.DONE -> "DONE"
        TaskType.CANCELLED -> "CANCELLED"
    }
}
