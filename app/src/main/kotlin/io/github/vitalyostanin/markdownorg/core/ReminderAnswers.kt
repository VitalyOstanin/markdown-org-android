package io.github.vitalyostanin.markdownorg.core

import io.github.vitalyostanin.markdownorg.ReminderActions
import uniffi.markdown_org_ffi.Day
import uniffi.markdown_org_ffi.Task
import java.time.ZonedDateTime

/** What pressing a button on a reminder comes to. */
internal sealed interface ReminderAnswer {

    /** Say this one again shortly, worded and timed as it will be said. */
    data class HoldAside(val reminder: TimedReminder) : ReminderAnswer

    /** Close the entry the reminder is about, through the core. */
    data class Close(val reminder: TimedReminder) : ReminderAnswer
}

/**
 * The answer [action] amounts to, or nothing for a broadcast that is neither.
 *
 * Nothing rather than an exception: a broadcast to the receiver can come from
 * anywhere on the device, and one naming an action this application does not
 * offer is one to ignore rather than to end the process over.
 */
internal fun answerTo(
    action: String?,
    reminder: TimedReminder,
    now: ZonedDateTime,
): ReminderAnswer? = when (action) {
    // The entry keeps the hour it starts at; only the moment it is announced
    // moves. What the reader gets in a quarter of an hour is the same
    // reminder, worded the same way.
    ReminderActions.SNOOZE ->
        ReminderAnswer.HoldAside(reminder.copy(at = now.plus(ReminderActions.LATER)))

    ReminderActions.DONE -> ReminderAnswer.Close(reminder)

    else -> null
}

/**
 * The task the entry names, wherever in the day it sits.
 *
 * By note and line rather than by heading, for the reason the reminder is
 * addressed that way: headings repeat, and a repeating entry repeats its own.
 * Nothing found is the entry closed or moved between the plan and the press,
 * which is a case the caller answers for -- there is no screen here to ask.
 */
internal fun Day.entryNamed(entry: ReminderEntry): Task? =
    (overdue + scheduledTimed + scheduledNoTime + upcoming).firstOrNull { task ->
        task.line == entry.line && task.file == entry.file && task.root == entry.root
    }
