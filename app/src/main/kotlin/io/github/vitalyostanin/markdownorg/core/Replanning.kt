package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Replanning the reminders, off the screen that asked for it.
 *
 * A settings screen writes the choice down and asks for the plan to be made
 * again. The plan is a walk of the notes, so it cannot be made where the
 * switch was pressed: a scope remembered by the composition is cancelled the
 * moment the reader leaves the screen, and the walk stops at its first
 * suspension point -- the preference saved, the alarms still the old ones.
 * What the screen states is the opposite ("a lead time changed and forgotten
 * about would take effect at the next fetch instead of at once"), and this is
 * where that is made true.
 *
 * One run at a time, the last request winning: the plan is made whole from the
 * choices as they stand, so an earlier run has nothing to contribute and two
 * of them at once are two walks of the notes racing to replace the same
 * alarms. The same rule the agenda applies to its sync.
 */
object Replanning {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var running: Job? = null

    /**
     * Plan the reminders again, by the scheduler the caller already has.
     *
     * Returns at once: the walk outlives the screen that asked for it, and
     * nothing on screen waits for it.
     *
     * A scheduler rather than a context, because building one opens an index
     * of the notes of its own — the walk of every collection that the screen
     * asking for this has already paid for once. A caller holding one passes
     * it; the callers with nothing to hold use the overload below.
     */
    @Synchronized
    fun request(scheduler: ReminderScheduler) {
        val previous = running

        running = scope.launch {
            // Cancelled rather than queued: what it was about to hold is what
            // this run will hold, and both writing at once is the interleaving
            // `PlanSlots` exists to prevent.
            previous?.cancelAndJoinQuietly()
            scheduler.replan()
                .onFailure { failure -> Log.w(TAG, "the reminders could not be planned", failure) }
        }
    }

    /**
     * The same, for a caller with no scheduler of its own.
     *
     * A receiver woken by the platform is a few milliseconds of process with
     * nothing held in it, so the index this opens is the only one there is.
     */
    fun request(context: Context) = request(ReminderScheduler.of(context.applicationContext))

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        join()
    }

    private const val TAG = "markdown-org"
}
