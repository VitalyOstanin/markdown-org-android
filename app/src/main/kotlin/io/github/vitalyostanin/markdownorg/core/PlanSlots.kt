package io.github.vitalyostanin.markdownorg.core

/**
 * The numbers the plan's alarms are held under.
 *
 * Cancelling an alarm needs the number it was scheduled under and nothing
 * else remembers it, so the count is written down and survives the process.
 * Replacing the plan is four steps over that count -- read it, cancel what it
 * covers, place the new alarms, write the new count -- and the four have to be
 * one step: a plan of twelve and a plan of eight interleaved leave alarms 8..11
 * in the platform with the count saying eight, and no later replace, and no
 * switching reminders off, ever reaches them again.
 *
 * Replanning is asked for from four places -- the settings screen on every
 * toggle, the agenda after every edit, the receiver of a fired alarm, and the
 * service that closes an entry from a notification -- and none of them waits
 * for the others, so this is not a theoretical interleaving.
 *
 * The lock is on the class rather than on the instance: `ReminderAlarms` is
 * built per call site, and a lock held inside one of them would serialise that
 * object rather than the alarms of the process.
 */
internal class PlanSlots(private val counter: HeldCount) {

    /** Where the count of held alarms is kept between processes. */
    interface HeldCount {
        fun read(): Int
        fun write(count: Int)
    }

    /**
     * Cancel what is held and hold [planned] alarms instead, as one step.
     *
     * The count is written before the alarms are placed and never below what
     * is about to be placed: a process killed between the two leaves a count
     * that still covers every alarm the platform holds, so the next [clear]
     * reaches all of them. Writing it afterwards would leave alarms nothing
     * remembers.
     */
    fun replace(planned: Int, cancel: (Int) -> Unit, place: (Int) -> Unit) {
        synchronized(GATE) {
            cancelHeld(cancel)
            counter.write(planned)
            for (index in 0 until planned) {
                place(index)
            }
        }
    }

    /** Drop every alarm the count covers, for reminders switched off. */
    fun clear(cancel: (Int) -> Unit) {
        synchronized(GATE) {
            cancelHeld(cancel)
        }
    }

    /** Whatever else is done to the alarms, in turn with the two above. */
    fun <T> inTurn(block: () -> T): T = synchronized(GATE) { block() }

    private fun cancelHeld(cancel: (Int) -> Unit) {
        for (index in 0 until counter.read()) {
            cancel(index)
        }
        counter.write(0)
    }

    private companion object {
        /** One lock for the alarms of the process, not one per holder. */
        val GATE = Any()
    }
}
