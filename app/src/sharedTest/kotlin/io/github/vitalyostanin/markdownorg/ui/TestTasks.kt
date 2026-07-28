package io.github.vitalyostanin.markdownorg.ui

import uniffi.markdown_org_ffi.AgendaResult
import uniffi.markdown_org_ffi.Day
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType

/**
 * A task with everything defaulted, so a test names only the field it is
 * about. The generated record has no default arguments of its own — it is a
 * projection of a Rust struct, and every field is positional there.
 */
internal fun task(
    heading: String = "Task",
    line: UInt = 1u,
    file: String = "notes.md",
    taskType: TaskType? = TaskType.TODO,
    priority: String? = null,
    timestampType: String? = "SCHEDULED",
    date: String? = "2026-07-28",
    time: String? = null,
    repeater: String? = null,
    daysOffset: Long? = 0,
): Task = Task(
    file = file,
    line = line,
    heading = heading,
    taskType = taskType,
    priority = priority,
    timestampType = timestampType,
    timestampDate = date,
    timestampTime = time,
    timestampRepeater = repeater,
    timestampNext = null,
    daysOffset = daysOffset,
)

internal fun day(
    date: String = "2026-07-28",
    overdue: List<Task> = emptyList(),
    scheduledTimed: List<Task> = emptyList(),
    scheduledNoTime: List<Task> = emptyList(),
    upcoming: List<Task> = emptyList(),
): Day = Day(date, overdue, scheduledTimed, scheduledNoTime, upcoming)

internal fun agenda(vararg days: Day): AgendaResult =
    AgendaResult(days = days.toList(), tasks = emptyList())

/** Tasks with no day buckets at all, as the `Tasks` scope returns them. */
internal fun flatAgenda(vararg tasks: Task): AgendaResult =
    AgendaResult(days = emptyList(), tasks = tasks.toList())

/** Rows at the given hour, in order, for asserting on an axis. */
internal fun List<AxisEntry>.headingsAt(hour: Int): List<String> =
    filterIsInstance<AxisEntry.Hour>()
        .firstOrNull { it.hour == hour }
        ?.entries
        ?.map { it.task.heading }
        .orEmpty()
