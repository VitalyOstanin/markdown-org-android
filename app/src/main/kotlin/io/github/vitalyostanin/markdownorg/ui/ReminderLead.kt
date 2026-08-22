package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R

/**
 * How long before a timed entry it is announced, as the settings offer it.
 *
 * A fixed set rather than a field: the answer is a habit — a quarter of an
 * hour to put the kettle on, an hour to get across town — and typing a number
 * of minutes into a keyboard is a worse way of saying that than picking one of
 * five. [NONE] is not the absence of a reminder but the reminder arriving at
 * the moment itself, which is why it is one of the five.
 *
 * The stored value is the number of minutes, so a set widened later reads back
 * whatever was stored; a value no chip carries falls to the nearest one below
 * it rather than to nothing.
 */
enum class ReminderLead(val minutes: Int) {
    NONE(0),
    FIVE(5),
    FIFTEEN(15),
    THIRTY(30),
    HOUR(60),
    ;

    companion object {

        /** The chip a stored number of minutes belongs to. */
        fun of(minutes: Int): ReminderLead = entries.lastOrNull { it.minutes <= minutes } ?: NONE
    }
}

/** What the choice is called where it is made. */
@get:StringRes
internal val ReminderLead.labelRes: Int
    get() = when (this) {
        ReminderLead.NONE -> R.string.settings_reminders_lead_none
        ReminderLead.FIVE -> R.string.settings_reminders_lead_five
        ReminderLead.FIFTEEN -> R.string.settings_reminders_lead_fifteen
        ReminderLead.THIRTY -> R.string.settings_reminders_lead_thirty
        ReminderLead.HOUR -> R.string.settings_reminders_lead_hour
    }

/** Handle for the instrumented tests, as the week switch has one. */
internal val ReminderLead.testTag: String get() = "settings-reminders-lead-$name"
