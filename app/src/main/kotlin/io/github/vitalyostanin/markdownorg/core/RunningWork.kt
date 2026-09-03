package io.github.vitalyostanin.markdownorg.core

/**
 * How many pieces of work a service has in flight.
 *
 * A notification carries a button per entry, so two presses a second apart
 * start the same service twice and it runs both at once. Each of them used to
 * take the foreground state down and call `stopSelf` when it finished, which
 * is the wrong answer twice over: the first to finish leaves the second
 * running in the background with nothing protecting the process, and a
 * `stopSelf` for the newest start id destroys the service under work that has
 * not finished -- taking with it the replan that follows the write.
 *
 * The count says whether the piece that just finished was the last one. Which
 * start id to stop under is the newest seen: `stopSelf(startId)` is refused
 * unless the id names the newest start, which is exactly the check that keeps
 * a service alive when a command arrived after the work began.
 */
internal class RunningWork {

    private var running = 0

    private var newest = 0

    /** One more piece of work, started under [startId]. */
    @Synchronized
    fun started(startId: Int) {
        running++
        newest = maxOf(newest, startId)
    }

    /**
     * One piece finished. The start id to stop under, or `null` while others
     * are still running.
     */
    @Synchronized
    fun finished(): Int? {
        running--

        return newest.takeIf { running <= 0 }
    }
}
