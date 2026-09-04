package io.github.vitalyostanin.markdownorg.core

/**
 * The numbers reminders are addressed by.
 *
 * A notification is replaced by whatever is raised under its number; a pending
 * intent is told apart by its request code and by the intent, and extras count
 * for neither; an alarm is cancelled by the number it was scheduled under.
 * Two of anything sharing a number is one of them taking the other's place
 * without a word, which reaches the reader as a reminder that never arrived.
 *
 * The arithmetic is kept here, away from the platform, for the reason
 * [PlanSlots] is: what goes wrong in it is a number out of its range or two
 * ranges that meet, and both are worth a unit test.
 */
internal object ReminderNumbering {

    /** The digest is one notification, replaced daily. */
    const val DIGEST_NOTIFICATION = 1

    /** The one the closing service stands behind while it writes. */
    const val WORKING_NOTIFICATION = 2

    /**
     * A notification of its own per entry, and the same one when the entry is
     * announced twice.
     *
     * Keyed by the note and the line rather than by the heading, so the
     * reminder at the hour replaces the one that came before it instead of
     * standing beside it. Two entries whose keys collide share a notification,
     * which costs the earlier of the two -- rare enough, and the alternative is
     * a table of identifiers to be kept as long as the alarms are.
     */
    fun notification(entry: ReminderEntry): Int =
        TIMED_FIRST + slot("${entry.root} ${entry.file} ${entry.line}".hashCode(), TIMED_COUNT)

    /**
     * The request code of one of the two buttons a reminder carries.
     *
     * Spaced by the number of buttons: one code for all of them would leave
     * every button holding whichever entry was packed last. Buttons and alarms
     * may share a code, and do -- they are broadcasts to different receivers,
     * which the platform compares as part of the intent.
     */
    fun button(notification: Int, second: Boolean): Int =
        notification * BUTTONS + if (second) 1 else 0

    /** One alarm of the plan, by its place in it. */
    fun planAlarm(index: Int): Int = PLAN_FIRST + index

    /**
     * The alarm the reader asked to have again later.
     *
     * Numbered above everything the plan uses, so that replacing the plan --
     * which cancels by number, from the first of them to the count held --
     * leaves it alone. [key] tells one held-aside alarm from another; the same
     * key twice replaces the earlier one, which is what a reader pressing the
     * button twice means.
     */
    fun alarmHeldAside(key: Int): Int = ASIDE_FIRST + slot(key, ASIDE_COUNT)

    /**
     * Where [key] falls among [count] places, from a key of any sign.
     *
     * `mod` rather than a remainder of the absolute value: the absolute value
     * of the smallest integer is itself, still negative, and a hash is as
     * likely to be that as any other number. What came of it was a number
     * outside the range set aside for it -- reached by one entry in four
     * billion, and by that entry every time.
     */
    fun slot(key: Int, count: Int): Int = key.mod(count)

    /** Where the numbering of the notifications about entries starts. */
    const val TIMED_FIRST = 100

    /** How many of them there are before the numbering comes round again. */
    const val TIMED_COUNT = 100_000

    /** Where the numbering of the plan's alarms starts. */
    const val PLAN_FIRST = 1_000

    /** How many alarms one plan holds: the timed ones and the digest. */
    const val PLAN_COUNT = PLAN_LIMIT + 1

    /** Where the numbering of the alarms held aside starts. */
    const val ASIDE_FIRST = 2_000

    /** How many of them there are before the numbering comes round again. */
    const val ASIDE_COUNT = 100_000

    /** How many buttons a reminder carries, which is what spaces the codes out. */
    private const val BUTTONS = 2
}
