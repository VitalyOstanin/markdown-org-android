package io.github.vitalyostanin.markdownorg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * The time a broadcast has, and what running out of it is told apart from.
 *
 * Nothing here has a screen: reminders that stop arriving look the same
 * whatever ended the work, and the two lines written down are all there is to
 * tell a collection that could not be read from a budget that ran out.
 */
class ReminderBudgetTest {

    @Test
    fun `work that outstays the budget is not work that finished`() = runTest {
        assertFalse(withinTheBudget { delay(Duration.ofMinutes(1).toMillis()) })
    }

    @Test
    fun `work that fails inside the budget is work that finished`() = runTest {
        assertTrue(withinTheBudget { error("the notes could not be read") })
    }

    @Test
    fun `work dropped by the caller ends instead of being reported as finished`() = runTest {
        var ended = false

        try {
            withinTheBudget { throw CancellationException("the process is going away") }
        } catch (dropped: CancellationException) {
            ended = dropped.message == "the process is going away"
        }

        assertTrue("the cancellation was swallowed and read as work that finished", ended)
    }
}
