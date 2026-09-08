package io.github.vitalyostanin.markdownorg.core

import uniffi.markdown_org_ffi.ReminderLead
import uniffi.markdown_org_ffi.ReminderUnit
import java.time.ZonedDateTime

/**
 * An entry's own lead time, applied where the occurrence is known.
 *
 * The core reads the `REMINDER` key of a note and hands back what it says --
 * a count and a unit -- without turning it into minutes, because a month and
 * a year have no fixed length. Subtracting them is calendar arithmetic and
 * belongs here, where the occurrence being counted back from is in hand.
 */
internal fun ZonedDateTime.before(lead: ReminderLead): ZonedDateTime {
    val count = lead.value.toLong()

    return when (lead.unit) {
        ReminderUnit.MINUTE -> minusMinutes(count)
        ReminderUnit.HOUR -> minusHours(count)
        ReminderUnit.DAY -> minusDays(count)
        ReminderUnit.WEEK -> minusWeeks(count)
        ReminderUnit.MONTH -> minusMonths(count)
        ReminderUnit.YEAR -> minusYears(count)
    }
}

/**
 * The lead time as a note writes it: the count and the unit's suffix.
 *
 * Minutes are `min` because `m` is a calendar month, in a repeater and in
 * this key alike. What is written here is read back by the core, so the two
 * spellings have to be the one spelling.
 */
internal fun ReminderLead.written(): String = "$value${unit.suffix()}"

private fun ReminderUnit.suffix(): String = when (this) {
    ReminderUnit.MINUTE -> "min"
    ReminderUnit.HOUR -> "h"
    ReminderUnit.DAY -> "d"
    ReminderUnit.WEEK -> "w"
    ReminderUnit.MONTH -> "m"
    ReminderUnit.YEAR -> "y"
}
