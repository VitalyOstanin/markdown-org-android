package io.github.vitalyostanin.markdownorg.core

import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R

/**
 * What answering a reminder with its "done" button came to.
 *
 * The notification goes down as the button is pressed, before anything is
 * read or written: one that stayed up while the answer was carried out would
 * read as a press that did nothing. That leaves every way of failing looking
 * like the way of succeeding — the reminder is gone from the drawer, and the
 * entry is still open in the notes. Each of them is named here so that
 * something can be raised in the reminder's place.
 */
enum class ClosingOutcome {

    /** The entry was found and written; the drawer is right to be empty. */
    CLOSED,

    /** The notes could not be read at all, so nothing was even looked for. */
    UNREADABLE,

    /** Read, and the entry was not where the reminder said it was. */
    GONE,

    /** Found, in a collection this device no longer keeps. */
    COLLECTION_GONE,

    /** Found, and writing it back did not work. */
    NOT_WRITTEN,

    /** The platform would not start the service that does the closing. */
    REFUSED,
}

/**
 * What is said about [this] outcome, or `null` when nothing is.
 *
 * A wording each, rather than one for every failure: "the entry is not there
 * any more" and "the entry could not be written" ask different things of the
 * reader — the first is answered by looking at the notes, the second by
 * pressing again.
 */
@get:StringRes
val ClosingOutcome.said: Int?
    get() = when (this) {
        ClosingOutcome.CLOSED -> null
        ClosingOutcome.UNREADABLE -> R.string.reminder_close_unreadable
        ClosingOutcome.GONE -> R.string.reminder_close_gone
        ClosingOutcome.COLLECTION_GONE -> R.string.reminder_close_collection_gone
        ClosingOutcome.NOT_WRITTEN -> R.string.reminder_close_not_written
        ClosingOutcome.REFUSED -> R.string.reminder_close_refused
    }

/**
 * Which outcome the parts of the answer add up to.
 *
 * Taken apart from the work itself so that the decision is one a test can
 * make: what happens around it — reading the day, finding the collection,
 * writing the note — needs a device.
 */
fun closingOutcome(
    read: Result<*>,
    found: Boolean,
    inACollection: Boolean,
    written: Result<*>?,
): ClosingOutcome = when {
    read.isFailure -> ClosingOutcome.UNREADABLE
    !found -> ClosingOutcome.GONE
    !inACollection -> ClosingOutcome.COLLECTION_GONE
    written == null || written.isFailure -> ClosingOutcome.NOT_WRITTEN
    else -> ClosingOutcome.CLOSED
}
