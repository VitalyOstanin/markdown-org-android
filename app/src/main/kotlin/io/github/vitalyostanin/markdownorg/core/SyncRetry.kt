package io.github.vitalyostanin.markdownorg.core

import kotlinx.coroutines.delay
import uniffi.markdown_org_ffi.SyncException

/** How many times a sync is attempted before the failure is shown. */
internal const val SYNC_ATTEMPTS = 3

/** Wait before the second attempt; each further one doubles it. */
internal const val SYNC_BACKOFF_MS = 500L

/**
 * Whether another attempt could go differently.
 *
 * Only the network failure is transient. Rejected credentials, a diverged
 * history and a working copy with uncommitted changes all need someone to do
 * something first, and repeating them costs a wait and a mobile connection
 * for nothing.
 */
internal fun Throwable.worthRetrying(): Boolean = this is SyncException.Network

/**
 * How long to wait before attempt number [attempt], counted from one — so the
 * first attempt never waits and the wait doubles from there.
 *
 * No random spread: one phone syncing on a button press is not a fleet of
 * clients hammering a server in step, and a fixed schedule is one a test can
 * assert on.
 */
internal fun backoffMillis(attempt: Int): Long = SYNC_BACKOFF_MS * (1L shl (attempt - 2))

/**
 * Run [body] until it succeeds, gives a failure not worth repeating, or runs
 * out of attempts.
 *
 * [sleep] is a parameter so the wait can be driven by the test scheduler
 * instead of a real clock.
 */
internal suspend fun <T> retryingTransientFailures(
    attempts: Int = SYNC_ATTEMPTS,
    sleep: suspend (Long) -> Unit = { delay(it) },
    body: suspend () -> Result<T>,
): Result<T> {
    var last: Result<T> = body()

    for (attempt in 2..attempts) {
        val error = last.exceptionOrNull() ?: return last
        if (!error.worthRetrying()) {
            return last
        }
        sleep(backoffMillis(attempt))
        last = body()
    }

    return last
}
