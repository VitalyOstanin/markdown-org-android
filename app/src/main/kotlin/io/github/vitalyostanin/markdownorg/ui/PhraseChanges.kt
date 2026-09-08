package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.core.written
import uniffi.markdown_org_ffi.PhraseDraft
import uniffi.markdown_org_ffi.PhraseField
import uniffi.markdown_org_ffi.PlanningKeyword
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType

/**
 * What a phrase changed about an entry, field by field, with the values.
 *
 * A phrase says several things at once -- "перенеси на пятницу в 16:00 и
 * сделай срочной" is three fields -- and "the note has been written" leaves
 * the reader to open the note to find out whether it heard all three. The
 * fields are worked out here, from the entry as the agenda held it and from
 * the draft the rules read, and the line under the screen names them.
 *
 * Worked out before the write rather than read back after it: the entry the
 * agenda carries is the entry the phrase was said about, and re-reading the
 * note would name what a sync from another device changed as well.
 *
 * Free of Compose and of Android, so the rule is tested on the JVM; the
 * wording of each field is a resource, and lives with the screen.
 */
enum class PhraseChangedField {
    /** The entry's own keyword: TODO, DONE, CANCELLED. */
    STATUS,

    /** The priority cookie on the heading. */
    PRIORITY,

    /** Which line the date stands on: SCHEDULED or DEADLINE. */
    PLANNING,

    /** The planning date. */
    DATE,

    /** The hour inside the planning timestamp. */
    TIME,

    /** The repeater inside the planning timestamp. */
    REPEATER,

    /** The entry's own reminder lead time. */
    REMINDER,
}

/**
 * One field the phrase changed.
 *
 * [before] is `null` where the entry carried nothing, [after] where the
 * phrase emptied the field. Both are the values as the notes spell them --
 * `TODO`, `A`, `2026-09-04`, `15:00`, `+1w` -- and the screen writes the dates
 * and the hours the way the reader's locale writes them.
 */
data class PhraseChange(val field: PhraseChangedField, val before: String?, val after: String?)

/**
 * The fields [draft] changes about [task], in the order they are announced.
 *
 * A field the phrase named to the value the entry already carried is not a
 * change and is left out: the point of the line is what is different now.
 */
fun phraseChanges(task: Task, draft: PhraseDraft): List<PhraseChange> {
    val cleared = draft.cleared.toSet()
    // Where the phrase writes the other planning line, the row's own values
    // are not what it changed, so nothing of the row is named as the "before".
    val elsewhere = writesTheOtherLine(task, draft)
    val had = { value: String? -> value.takeUnless { elsewhere } }

    return listOfNotNull(
        change(PhraseChangedField.STATUS, task.taskType?.keyword(), draft.status?.keyword(), false),
        change(
            PhraseChangedField.PRIORITY,
            task.priority,
            draft.priority,
            PhraseField.PRIORITY in cleared,
        ),
        change(
            PhraseChangedField.PLANNING,
            had(task.timestampType?.planningKeyword()),
            // The keyword travels with the date: a phrase that named no date
            // says nothing about which line it stands on either.
            draft.keyword?.keyword()?.takeIf { draft.date != null },
            false,
        ),
        change(
            PhraseChangedField.DATE,
            had(task.timestampDate),
            draft.date,
            PhraseField.DATE in cleared,
        ),
        change(
            PhraseChangedField.TIME,
            had(task.timestampTime),
            draft.time,
            PhraseField.TIME in cleared,
        ),
        change(
            PhraseChangedField.REPEATER,
            had(task.timestampRepeater),
            draft.repeater,
            PhraseField.REPEATER in cleared,
        ),
        // The lead time stands in a property of its own rather than in the
        // planning line, so the other line the phrase may write says nothing
        // about it and the entry's own value is always the "before".
        change(
            PhraseChangedField.REMINDER,
            task.reminder?.written(),
            draft.reminder?.written(),
            PhraseField.REMINDER in cleared,
        ),
    )
}

/**
 * Whether the phrase writes a planning line other than the one the row shows.
 *
 * A phrase naming the other kind -- "перенеси к пятнице" on an entry that is
 * scheduled -- does not carry the date across: the core writes a `DEADLINE`
 * line and leaves the `SCHEDULED` one where it is, so the entry keeps the day
 * the agenda drew it on and gains a second one. The row's date, hour and
 * repeater belong to the line that stayed, and naming them as what the phrase
 * changed would report a move that did not happen.
 *
 * What the other line carried before is unknown here -- the agenda row carries
 * one timestamp, the one it is drawn by -- so the fields are named with their
 * new values alone. That is true whether the line was written or rewritten,
 * which is the other thing this cannot tell apart.
 *
 * The date is what settles it: the keyword travels with the date in the rules,
 * and a phrase naming no date names no line.
 */
private fun writesTheOtherLine(task: Task, draft: PhraseDraft): Boolean {
    val said = draft.keyword?.keyword()?.takeIf { draft.date != null } ?: return false

    return said != task.timestampType?.planningKeyword()
}

/**
 * The change to one field, or `null` when there is none.
 *
 * Three cases, in this order: the phrase named a value, the phrase emptied the
 * field, the phrase said nothing about it. Only the third is silence -- a
 * field emptied that was already empty, and a value that matches what the
 * entry carries, are both "no change" as well.
 */
private fun change(
    field: PhraseChangedField,
    before: String?,
    said: String?,
    cleared: Boolean,
): PhraseChange? {
    val after = when {
        said != null -> said
        cleared -> null
        else -> return null
    }

    return if (after == before) null else PhraseChange(field, before, after)
}

private fun TaskType.keyword(): String = when (this) {
    TaskType.TODO -> "TODO"
    TaskType.DONE -> "DONE"
    TaskType.CANCELLED -> "CANCELLED"
}

private fun PlanningKeyword.keyword(): String = when (this) {
    PlanningKeyword.SCHEDULED -> "SCHEDULED"
    PlanningKeyword.DEADLINE -> "DEADLINE"
}

/**
 * The planning line a timestamp stands on, or `null` for one that is not
 * planning at all -- a plain date, or the CLOSED line of a finished task.
 */
private fun TimestampType.planningKeyword(): String? = when (this) {
    TimestampType.SCHEDULED -> "SCHEDULED"
    TimestampType.DEADLINE -> "DEADLINE"
    TimestampType.CLOSED, TimestampType.PLAIN -> null
}
