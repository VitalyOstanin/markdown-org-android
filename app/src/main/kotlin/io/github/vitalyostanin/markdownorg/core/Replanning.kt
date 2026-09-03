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
     * Plan the reminders again, from the choices as they now stand.
     *
     * Returns at once: the walk outlives the screen that asked for it, and
     * nothing on screen waits for it.
     */
    @Synchronized
    fun request(context: Context) {
        val app = context.applicationContext
        val previous = running

        running = scope.launch {
            // Cancelled rather than queued: what it was about to hold is what
            // this run will hold, and both writing at once is the interleaving
            // `PlanSlots` exists to prevent.
            previous?.cancelAndJoinQuietly()
            ReminderScheduler.of(app).replan()
                .onFailure { failure -> Log.w(TAG, "the reminders could not be planned", failure) }
        }
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        join()
    }

    private const val TAG = "markdown-org"
}
