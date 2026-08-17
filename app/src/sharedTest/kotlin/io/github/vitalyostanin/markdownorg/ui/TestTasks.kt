package io.github.vitalyostanin.markdownorg.ui

import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.Day
import uniffi.markdown_org_ffi.ScanStats
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType

/**
 * A task with everything defaulted, so a test names only the field it is
 * about. The generated record has no default arguments of its own — it is a
 * projection of a Rust struct, and every field is positional there.
 */
internal fun task(
    heading: String = "Task",
    line: UInt = 1u,
    file: String = "notes.md",
    /** The collection the task came from; the one a stand-in area reports. */
    root: String? = "/notes",
    taskType: TaskType? = TaskType.TODO,
    priority: String? = null,
    timestampType: TimestampType? = TimestampType.SCHEDULED,
    date: String? = "2026-07-28",
    time: String? = null,
    repeater: String? = null,
    next: String? = null,
    /** The occurrence after this row's own day; only a dated row carries one. */
    nextAfter: String? = null,
    daysOffset: Long? = 0,
): Task = Task(
    file = file,
    root = root,
    line = line,
    heading = heading,
    taskType = taskType,
    priority = priority,
    timestampType = timestampType,
    timestampDate = date,
    timestampTime = time,
    timestampRepeater = repeater,
    timestampNext = next,
    timestampNextAfter = nextAfter,
    daysOffset = daysOffset,
)

internal fun day(
    date: String = "2026-07-28",
    overdue: List<Task> = emptyList(),
    scheduledTimed: List<Task> = emptyList(),
    scheduledNoTime: List<Task> = emptyList(),
    upcoming: List<Task> = emptyList(),
): Day = Day(date, overdue, scheduledTimed, scheduledNoTime, upcoming)

/** A walk that ran into nothing worth reporting. */
internal fun cleanScan(
    filesProcessed: UInt = 1u,
    filesFailed: UInt = 0u,
    filesNotUtf8: UInt = 0u,
    filesTooLarge: UInt = 0u,
    nonutf8Paths: UInt = 0u,
    truncated: Boolean = false,
): ScanStats = ScanStats(
    filesProcessed = filesProcessed,
    filesFailed = filesFailed,
    filesNotUtf8 = filesNotUtf8,
    filesTooLarge = filesTooLarge,
    nonutf8Paths = nonutf8Paths,
    truncated = truncated,
    hasWarnings = filesFailed > 0u ||
        filesNotUtf8 > 0u ||
        filesTooLarge > 0u ||
        nonutf8Paths > 0u ||
        truncated,
)

internal fun agenda(vararg days: Day, stats: ScanStats = cleanScan()): AgendaResult =
    AgendaResult(days = days.toList(), tasks = emptyList(), stats = stats)

/** Tasks with no day buckets at all, as the `Tasks` scope returns them. */
internal fun flatAgenda(vararg tasks: Task): AgendaResult =
    AgendaResult(days = emptyList(), tasks = tasks.toList(), stats = cleanScan())

/** Rows at the given hour, in order, for asserting on an axis. */
internal fun List<AxisEntry>.headingsAt(hour: Int): List<String> =
    filterIsInstance<AxisEntry.Hour>()
        .firstOrNull { it.hour == hour }
        ?.entries
        ?.map { it.task.heading }
        .orEmpty()
