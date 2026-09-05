package io.github.vitalyostanin.markdownorg.core

import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.BulkOutcome
import uniffi.markdown_org_ffi.BulkTarget
import uniffi.markdown_org_ffi.CommitAuthor
import uniffi.markdown_org_ffi.EditTarget
import uniffi.markdown_org_ffi.EntryText
import uniffi.markdown_org_ffi.FileRollback
import uniffi.markdown_org_ffi.MoveOutcome
import uniffi.markdown_org_ffi.NewPlanning
import uniffi.markdown_org_ffi.NewTask
import uniffi.markdown_org_ffi.PhraseDraft
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.RevertOutcome
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType
import uniffi.markdown_org_ffi.WritePosition
import uniffi.markdown_org_ffi.commitChanges
import uniffi.markdown_org_ffi.holdsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import uniffi.markdown_org_ffi.applyPhrase as coreApplyPhrase
import uniffi.markdown_org_ffi.applyToGroup as coreApplyToGroup
import uniffi.markdown_org_ffi.cancelOccurrence as coreCancelOccurrence
import uniffi.markdown_org_ffi.completeTask as coreComplete
import uniffi.markdown_org_ffi.createTask as coreCreateTask
import uniffi.markdown_org_ffi.moveEntry as coreMoveEntry
import uniffi.markdown_org_ffi.moveOccurrence as coreMoveOccurrence
import uniffi.markdown_org_ffi.readEntry as coreReadEntry
import uniffi.markdown_org_ffi.revertFiles as coreRevertFiles
import uniffi.markdown_org_ffi.setEntry as coreSetEntry
import uniffi.markdown_org_ffi.setPlanning as coreSetPlanning
import uniffi.markdown_org_ffi.setPlanningTime as coreSetPlanningTime
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
data class EditReport(
    val committed: Boolean,
    val commitFailure: Throwable? = null,
    /**
     * What each note held before this edit and holds after it, empty where
     * nothing was written.
     *
     * Carried out of every edit rather than asked for by the ones that offer
     * an undo: which taps are worth offering it for is a decision for the
     * screen, and an edit that did not bring the pairs back could not be given
     * one later.
     *
     * A list because a move is an edit to two notes — the file the entry left
     * and the file it reached — and putting it back means putting both back.
     * Every other edit brings back one.
     */
    val rollback: List<FileRollback> = emptyList(),
)

/**
 * A task to write, as the screen composed it.
 *
 * Not the core's own record, which also carries the directory and the file:
 * those are the writer's to fill in — the directory is the collection it
 * belongs to, and the file is the one that collection receives new tasks in.
 * What is here is what somebody typed.
 */
data class TaskDraft(
    val title: String,
    val body: String = "",
    /** The keyword to write, or `null` for a heading that carries none. */
    val status: TaskType? = TaskType.TODO,
    /** The bare priority (`A`), without the `[#` `]` framing. */
    val priority: String? = null,
    /**
     * Which kind of date [date] is, when there is one.
     *
     * Always answered, even where no date was chosen: a date has to say
     * whether it is the day work starts or the day the task is due, and the
     * two are not interchangeable. No date at all is [date] being `null`.
     */
    val keyword: PlanningKeyword = PlanningKeyword.SCHEDULED,
    val date: LocalDate? = null,
    /**
     * The hour the entry is held at, or `null` for one that takes the whole
     * day. Belongs to [date]: there is no hour to be held at on a task that
     * is not planned for a day.
     */
    val time: LocalTime? = null,
    /**
     * The repeater that makes the date a series (`++1w`), or `null` for a
     * task that happens once. Belongs to [date] for the same reason [time]
     * does, and is checked by the core rather than here.
     */
    val repeater: String? = null,
)

/**
 * What acting on a whole group did: to the notes, and to the history.
 *
 * [outcome] carries what the core changed and what it refused, task by task;
 * [report] is the commit half, which fails apart from the write exactly as it
 * does for a single edit.
 */
data class GroupReport(val outcome: BulkOutcome, val report: EditReport)

/**
 * What moving an entry did: to the two files, and to the history.
 *
 * Apart from [EditReport] because a move is the one write that changes two
 * notes, and taking it back means putting both of them back. [outcome] carries
 * that pair and the file the entry now sits in; [report] is the commit half,
 * which fails apart from the write as it does for a single edit.
 */
data class MoveReport(val outcome: MoveOutcome, val report: EditReport)

/** What undoing a group did: which files went back, and whether it committed. */
data class UndoReport(val outcome: RevertOutcome, val report: EditReport)

/** Point edits to the notes. */
interface NotesWriter {

    suspend fun complete(task: Task, today: LocalDate): Result<EditReport>

    suspend fun setStatus(task: Task, status: TaskType?): Result<EditReport>

    suspend fun setPriority(task: Task, priority: String?): Result<EditReport>

    suspend fun shift(task: Task, keyword: PlanningKeyword, days: Int): Result<EditReport>

    /**
     * Put a planning date on a task, or take one off with `null`.
     *
     * A day rather than a number of days, which is what the calendar answers
     * with and what a task carrying no date at all can be given: there is
     * nothing for a shift to count from until one is written.
     */
    suspend fun setPlanning(
        task: Task,
        keyword: PlanningKeyword,
        date: LocalDate?,
    ): Result<EditReport>

    /**
     * Apply what a phrase said to a task that already exists.
     *
     * One operation rather than one per field: a sentence naming a day, an
     * hour and a priority is one edit, one commit and one undo, where three
     * calls would be three of each.
     */
    suspend fun applyPhrase(task: Task, draft: PhraseDraft): Result<EditReport>

    /**
     * Put an hour on a planning date, or take one off with `null`.
     *
     * Separate from [setPlanning] because an hour is written into a date that
     * already exists: a task with no planning line has no timestamp to carry
     * one, and the core refuses that rather than inventing a day for it.
     */
    suspend fun setPlanningTime(
        task: Task,
        keyword: PlanningKeyword,
        time: LocalTime?,
    ): Result<EditReport>

    /**
     * Take one occurrence out of a repeating entry.
     *
     * [date] is the day the row stands on, which the core writes into the
     * series' own list of occurrences it does not have. The series is left
     * repeating: what is excluded is the one day.
     */
    suspend fun cancelOccurrence(task: Task, date: LocalDate): Result<EditReport>

    /**
     * Move one occurrence of a repeating entry, leaving the series where it is.
     *
     * [occurrence] is the day it stands on now and [date] the day it moves to;
     * both are needed, because the line written into the series has to name
     * the occurrence it is about, and the new date cannot say that. [time] is
     * `null` for a series not held at an hour, and for one that keeps the hour
     * it already has.
     */
    suspend fun moveOccurrence(
        task: Task,
        occurrence: LocalDate,
        date: LocalDate,
        time: LocalTime?,
    ): Result<EditReport>

    /**
     * The title and the body of a task's entry, as the file holds them.
     *
     * Read through the core rather than off the task the agenda produced: what
     * the agenda carries is the display text of the heading, with the markup
     * taken off, and handing that to an editor would lose it on the first save.
     */
    suspend fun readEntry(task: Task): Result<EntryText>

    /**
     * Write the title and the body back, in one write and one commit.
     *
     * Both at once because they are one edit to the reader, and because two
     * calls could not work: the first changes the heading, and the second
     * would arrive naming a heading the file no longer holds.
     */
    suspend fun setEntry(task: Task, title: String, body: String): Result<EditReport>

    /**
     * Write a task that was not in the notes before into [file], at [at].
     *
     * Both are named by the collection rather than worked out from the task:
     * where a new entry belongs is a question this application cannot answer
     * from a title and a date, and the settings are where it is answered once.
     *
     * [now] is marked under the heading as the moment the entry was written
     * at, to the minute, and is taken here for the reason [complete] takes the
     * day: the clock belongs to the caller, so that a test can say what time
     * it is.
     */
    suspend fun createTask(
        file: String,
        at: WritePosition,
        draft: TaskDraft,
        now: LocalDateTime,
    ): Result<EditReport>

    /**
     * Carry a task's whole entry into another file of the same collection.
     *
     * [file] is relative to the collection's directory, as every file this
     * application names is, and is created when it is not there yet — the file
     * a collection calls its main one need not exist before something is filed
     * into it. [at] is where in that file the entry lands, the setting new
     * tasks are written by.
     *
     * Within one collection, which is what makes it one edit and one commit:
     * between two of them it would be two checkouts, and a second step that
     * failed would leave the entry in both or in neither.
     */
    suspend fun moveEntry(task: Task, file: String, at: WritePosition): Result<MoveReport>

    /**
     * Apply one action to every task of a group.
     *
     * One pass over the notes and one commit, however many tasks are named:
     * twenty single edits would rewrite the files twenty times and leave
     * twenty commits describing one move.
     */
    suspend fun applyToGroup(
        tasks: List<Task>,
        action: BulkAction,
        today: LocalDate,
    ): Result<GroupReport>

    /**
     * Put back what a group action overwrote.
     *
     * The core restores only the files that still hold what the group wrote,
     * so an undo cannot take away an edit or a sync that landed after it.
     */
    suspend fun undoGroup(rollback: List<FileRollback>): Result<UndoReport>

    /**
     * Put back what a single edit overwrote, naming the task in the commit.
     *
     * The same restore the group undo makes -- the notes of one edit instead
     * of those of twenty -- and it is refused on the same terms: a note written
     * to since the edit is left as it stands. A list rather than one file
     * because a move is an edit to two of them.
     */
    suspend fun undoEdit(rollback: List<FileRollback>, heading: String): Result<UndoReport>

    /**
     * Take back a task that was just created, naming it in the commit.
     *
     * The same restore as any other undo, and the only way this application
     * removes an entry: what it puts back is the file as it stood before the
     * task was written into it.
     */
    suspend fun undoCreation(rollback: List<FileRollback>, heading: String): Result<UndoReport>

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
        outcome.rollback to completionMessage(task.heading, outcome.repeated)
    }

    /** Set or clear the keyword outright, without the repeater semantics. */
    override suspend fun setStatus(task: Task, status: TaskType?): Result<EditReport> = write {
        val outcome = coreSetStatus(task.target(), status)
        outcome.rollback to statusMessage(task.heading, status)
    }

    /** Set or clear the priority cookie. */
    override suspend fun setPriority(task: Task, priority: String?): Result<EditReport> = write {
        val outcome = coreSetPriority(task.target(), priority)
        outcome.rollback to priorityMessage(task.heading, priority)
    }

    /** Apply the fields a phrase named, in one write and one commit. */
    override suspend fun applyPhrase(task: Task, draft: PhraseDraft): Result<EditReport> = write {
        val outcome = coreApplyPhrase(task.target(), draft)
        outcome.rollback to phraseMessage(task.heading)
    }

    /** Read the text of an entry for editing. */
    override suspend fun readEntry(task: Task): Result<EntryText> = notes.exclusive {
        runCatching { coreReadEntry(task.target()) }
    }

    /** Write an edited title and body back. */
    override suspend fun setEntry(task: Task, title: String, body: String): Result<EditReport> =
        write {
            val outcome = coreSetEntry(task.target(), title, body)
            outcome.rollback to entryMessage(task.heading, title)
        }

    /** Write a task the notes did not hold, into the file that receives them. */
    override suspend fun createTask(
        file: String,
        at: WritePosition,
        draft: TaskDraft,
        now: LocalDateTime,
    ): Result<EditReport> = write {
        val outcome = coreCreateTask(draft.asNewTask(notes.root.absolutePath, file, at, now))
        outcome.rollback to creationMessage(draft.title)
    }

    /** Carry the whole entry into another file of this collection. */
    override suspend fun moveEntry(
        task: Task,
        file: String,
        at: WritePosition,
    ): Result<MoveReport> = writing {
        val outcome = coreMoveEntry(task.target(), file, at)
        outcome to moveMessage(task.heading, file)
    }.map { (outcome, report) -> MoveReport(outcome, report) }

    /** Move a planning date by whole days. */
    override suspend fun shift(
        task: Task,
        keyword: PlanningKeyword,
        days: Int,
    ): Result<EditReport> = write {
        val outcome = coreShiftPlanning(task.target(), keyword, days)
        outcome.rollback to shiftMessage(task.heading, keyword, days)
    }

    /** Put a planning date on a task, or take one off. */
    override suspend fun setPlanning(
        task: Task,
        keyword: PlanningKeyword,
        date: LocalDate?,
    ): Result<EditReport> = write {
        val outcome = coreSetPlanning(task.target(), keyword, date?.toString())
        outcome.rollback to planningMessage(task.heading, keyword, date)
    }

    /** Put an hour on a planning date, or take one off. */
    override suspend fun setPlanningTime(
        task: Task,
        keyword: PlanningKeyword,
        time: LocalTime?,
    ): Result<EditReport> = write {
        val outcome = coreSetPlanningTime(task.target(), keyword, time?.toString())
        outcome.rollback to planningTimeMessage(task.heading, keyword, time)
    }

    /** Take one occurrence out of a repeating entry. */
    override suspend fun cancelOccurrence(task: Task, date: LocalDate): Result<EditReport> = write {
        val outcome = coreCancelOccurrence(task.target(), date.toString())
        outcome.rollback to occurrenceCancelMessage(task.heading, date)
    }

    /** Move one occurrence, writing the `MOVED` line that holds it elsewhere. */
    override suspend fun moveOccurrence(
        task: Task,
        occurrence: LocalDate,
        date: LocalDate,
        time: LocalTime?,
    ): Result<EditReport> = write {
        val outcome = coreMoveOccurrence(
            task.target(),
            occurrence.toString(),
            date.toString(),
            time?.toString(),
        )
        outcome.rollback to occurrenceMoveMessage(task.heading, occurrence, date, time)
    }

    /**
     * Apply one action to a whole group, in one pass and one commit.
     *
     * Which planning line each task is acted on is decided here, from the kind
     * the extractor reported for it — the same rule the sheet of one task
     * follows. A task the agenda placed by neither kind is passed on with no
     * keyword, and the core names it as refused rather than guessing.
     */
    override suspend fun applyToGroup(
        tasks: List<Task>,
        action: BulkAction,
        today: LocalDate,
    ): Result<GroupReport> = writing {
        val outcome = coreApplyToGroup(
            notes.root.absolutePath,
            tasks.map(Task::bulkTarget),
            action,
            today.toString(),
        )
        outcome to groupMessage(action, outcome.changed.toInt())
    }.map { (outcome, report) -> GroupReport(outcome, report) }

    override suspend fun undoGroup(rollback: List<FileRollback>): Result<UndoReport> =
        undo(rollback) { outcome -> undoMessage(outcome.restored.size) }

    override suspend fun undoEdit(
        rollback: List<FileRollback>,
        heading: String,
    ): Result<UndoReport> = undo(rollback) { undoEditMessage(heading) }

    override suspend fun undoCreation(
        rollback: List<FileRollback>,
        heading: String,
    ): Result<UndoReport> = undo(rollback) { undoCreationMessage(heading) }

    /** Restore [rollback], committing what [message] calls it. */
    private suspend fun undo(
        rollback: List<FileRollback>,
        message: (RevertOutcome) -> String,
    ): Result<UndoReport> = writing {
        val outcome = coreRevertFiles(notes.root.absolutePath, rollback)
        outcome to message(outcome)
    }.map { (outcome, report) -> UndoReport(outcome, report) }

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
     * so — and what the note held on either side of it, which is what an undo
     * of that one tap works from.
     *
     * The two halves answer separately. A write that failed is a failure: the
     * file is as it was. A commit that failed over a written file is not —
     * saying so would send the user to repeat an edit that has already
     * happened, and the second attempt would come back as "the file has
     * changed". The uncommitted change is picked up by the next edit and by
     * the commit that precedes the next sync.
     */
    internal suspend fun write(edit: () -> Pair<FileRollback?, String>): Result<EditReport> =
        writing(edit).map { (rollback, report) ->
            report.copy(rollback = listOfNotNull(rollback))
        }

    /**
     * [write] for an edit that has something to say beyond its message.
     *
     * The group actions answer with what the core did to each task, and that
     * has to come back out of the lock along with the commit.
     */
    private suspend fun <T> writing(edit: () -> Pair<T, String>): Result<Pair<T, EditReport>> =
        notes.exclusive {
            runCatching(edit).map { (value, message) ->
                value to runCatching {
                    committer.commit(notes.root.absolutePath, message, author())
                }.fold(
                    onSuccess = { committed -> EditReport(committed = committed) },
                    onFailure = { failure ->
                        EditReport(committed = false, commitFailure = failure)
                    },
                )
            }
        }

    /**
     * The draft as the core takes it: with the collection it goes to and the
     * file that receives it, and with the date paired to the kind it is.
     */
    private fun TaskDraft.asNewTask(
        dir: String,
        file: String,
        at: WritePosition,
        now: LocalDateTime,
    ) = NewTask(
        dir = dir,
        file = file,
        at = at,
        // To the minute, without the seconds `toString` spells out where the
        // clock has any: what the core parses is `YYYY-MM-DDTHH:MM`.
        created = now.truncatedTo(ChronoUnit.MINUTES).toString(),
        title = title,
        body = body,
        status = status,
        priority = priority,
        planning = date?.let {
            NewPlanning(
                keyword = keyword,
                date = it.toString(),
                // `toString` writes HH:mm, which is what a timestamp holds;
                // the picker the hour comes from names no seconds for it to
                // spell out.
                time = time?.toString(),
                repeater = repeater,
            )
        },
    )

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

/**
 * The task as a member of a group, carrying the planning line it was placed
 * by.
 *
 * The kind comes from the extractor rather than from a guess: a closing date
 * and a bare timestamp are neither `SCHEDULED` nor `DEADLINE`, and a group
 * action aimed at one of those has nothing to move. Spelled out rather than
 * left to `else`, so a kind added to the core has to be answered for here.
 */
internal fun Task.bulkTarget(): BulkTarget = BulkTarget(
    file = file,
    line = line,
    heading = heading,
    keyword = when (timestampType) {
        TimestampType.DEADLINE -> PlanningKeyword.DEADLINE
        TimestampType.SCHEDULED -> PlanningKeyword.SCHEDULED
        TimestampType.CLOSED, TimestampType.PLAIN, null -> null
    },
)

/** What a group action did, as one line of history. */
internal fun groupMessage(action: BulkAction, changed: Int): String {
    val tasks = if (changed == 1) "task" else "tasks"

    return when (action) {
        BulkAction.MOVE_TO_TODAY -> "Move $changed overdue $tasks to today"
        BulkAction.DROP_PLANNING -> "Drop the date of $changed $tasks"
        BulkAction.CANCEL -> "Cancel $changed $tasks"
    }
}

/** What undoing a group did, as one line of history. */
internal fun undoMessage(restored: Int): String {
    val notes = if (restored == 1) "note" else "notes"

    return "Undo the group edit of $restored $notes"
}

/**
 * What undoing one edit did, as one line of history.
 *
 * Named after the task rather than after the edit: what was done to it is the
 * commit above this one, and the pair reads as a move and its reversal.
 */
internal fun undoEditMessage(heading: String): String = "Undo the edit of \"$heading\""

/** What a task written from nothing says it did. */
internal fun creationMessage(title: String): String = "Create \"${title.trim()}\""

/**
 * What moving an entry did, as one line of history.
 *
 * Names the file it arrived in and not the one it left: the commit shows both
 * halves of the move as a removal and an addition, and what a reader of the
 * history wants from the message is where the entry is now.
 */
internal fun moveMessage(heading: String, file: String): String =
    "Move \"${heading.trim()}\" to $file"

/**
 * What taking a new task back did, as one line of history.
 *
 * Named apart from an undone edit because the two undo different things: an
 * edit put a note back the way it was, and this one takes an entry out of it
 * again.
 */
internal fun undoCreationMessage(heading: String): String =
    "Undo the creation of \"${heading.trim()}\""

/** A completion that turned out to be a repeat says so. */
internal fun completionMessage(heading: String, repeated: Boolean): String = when {
    repeated -> "Move \"$heading\" to its next occurrence"
    else -> "Mark \"$heading\" as done"
}

internal fun statusMessage(heading: String, status: TaskType?): String = when (status) {
    null -> "Clear the keyword on \"$heading\""
    else -> "Set \"$heading\" to ${status.keyword()}"
}

/**
 * What an edit made by phrase says it did.
 *
 * The sentence itself is not in the message: it is the user's own words, and a
 * commit history is not where they were meant to end up. What changed is
 * visible in the diff, which is where a reader of the history looks anyway.
 */
internal fun phraseMessage(heading: String): String = "Change \"$heading\" by phrase"

internal fun priorityMessage(heading: String, priority: String?): String = when (priority) {
    null -> "Drop the priority of \"$heading\""
    else -> "Set the priority of \"$heading\" to $priority"
}

/**
 * What an edited entry says it did.
 *
 * A retitled entry names both titles, because that is the change a reader of
 * the history would look for; an entry whose title stayed put says only that
 * its text was rewritten. The old title is the display text the agenda
 * carried, so a heading holding markup reads in the message without it.
 */
internal fun entryMessage(heading: String, title: String): String = when (title.trim()) {
    heading -> "Rewrite the text of \"$heading\""
    else -> "Rewrite \"$heading\", now \"${title.trim()}\""
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

/**
 * Which date was written, and to when -- or that it was taken off.
 *
 * The day is spelled the way the notes spell it, `YYYY-MM-DD`, rather than the
 * way the phone's locale would: the history is read beside the files.
 */
internal fun planningMessage(heading: String, keyword: PlanningKeyword, date: LocalDate?): String =
    when (date) {
        null -> "Take the ${keyword.keyword()} off \"$heading\""
        else -> "Set the ${keyword.keyword()} of \"$heading\" to $date"
    }

/**
 * Which hour was written, and on which date -- or that it was taken off.
 *
 * The hour is spelled the way the notes spell it, `HH:MM`, as every date in
 * these messages is: the history is read beside the files.
 */
internal fun planningTimeMessage(
    heading: String,
    keyword: PlanningKeyword,
    time: LocalTime?,
): String = when (time) {
    null -> "Take the hour off the ${keyword.keyword()} of \"$heading\""
    else -> "Set the ${keyword.keyword()} of \"$heading\" to $time"
}

/**
 * Which occurrence left the series, and which day it stood on.
 *
 * The day is spelled the way the notes spell it, as every other date in these
 * messages is: the history is read beside the files.
 */
internal fun occurrenceCancelMessage(heading: String, date: LocalDate): String =
    "Take the occurrence of \"$heading\" on $date out of the series"

/**
 * Which occurrence moved, and to when.
 *
 * Both days are named even where they are the same one: a class moved from
 * three to six is still the occurrence of that day, and a message naming only
 * the new date would read as though something else had happened.
 */
internal fun occurrenceMoveMessage(
    heading: String,
    occurrence: LocalDate,
    date: LocalDate,
    time: LocalTime?,
): String {
    // `toString` rather than a formatter: it writes HH:mm and leaves a zero
    // second out, where ISO_LOCAL_TIME spells the seconds every time, and it
    // is not the locale's digits either.
    val moved = when (time) {
        null -> "$date"
        else -> "$date $time"
    }

    return "Move the occurrence of \"$heading\" on $occurrence to $moved"
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
