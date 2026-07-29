package io.github.vitalyostanin.markdownorg.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.markdown_org_ffi.SyncException

/**
 * Which failures are worth another attempt, and how long the attempts wait.
 *
 * A phone syncs over a connection that comes and goes, so a single failed
 * request is not an answer. The distinction the core already draws — network
 * against credentials, divergence, uncommitted work — is what decides; the
 * waits are driven by the test scheduler rather than a clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncRetryTest {

    @Test
    fun aNetworkFailureIsTriedAgain() = runTest {
        val waits = mutableListOf<Long>()
        var calls = 0

        val result = retryingTransientFailures(sleep = { waits += it }) {
            calls += 1
            if (calls < 3) {
                Result.failure(SyncException.Network("the host went away"))
            } else {
                Result.success("done")
            }
        }

        assertEquals("done", result.getOrNull())
        assertEquals(3, calls)
        assertEquals(listOf(500L, 1000L), waits)
    }

    @Test
    fun aRejectedTokenIsNotTriedAgain() = runTest {
        var calls = 0

        val result = retryingTransientFailures<Unit>(sleep = { }) {
            calls += 1
            Result.failure(SyncException.Auth("bad credentials"))
        }

        // Repeating this costs a wait and a mobile connection to be told the
        // same thing: the token has to be replaced first.
        assertEquals(1, calls)
        assertTrue(result.isFailure)
    }

    @Test
    fun aDivergedCheckoutIsNotTriedAgain() = runTest {
        var calls = 0

        retryingTransientFailures<Unit>(sleep = { }) {
            calls += 1
            Result.failure(SyncException.Diverged("both moved"))
        }

        assertEquals(1, calls)
    }

    @Test
    fun theAttemptsRunOutAndTheLastFailureIsTheAnswer() = runTest {
        var calls = 0

        val result = retryingTransientFailures<Unit>(sleep = { }) {
            calls += 1
            Result.failure(SyncException.Network("attempt $calls"))
        }

        assertEquals(SYNC_ATTEMPTS, calls)
        // The last failure, not the first: it is the one that describes what
        // the network was doing when the attempts ran out.
        val failure = result.exceptionOrNull()
        assertTrue(failure is SyncException.Network)
        assertEquals("attempt $SYNC_ATTEMPTS", (failure as SyncException.Network).detail)
    }

    @Test
    fun aSyncThatWorksFirstTimeWaitsForNothing() = runTest {
        val waits = mutableListOf<Long>()

        val result = retryingTransientFailures(sleep = { waits += it }) { Result.success(1) }

        assertEquals(1, result.getOrNull())
        assertTrue(waits.isEmpty())
    }
}
