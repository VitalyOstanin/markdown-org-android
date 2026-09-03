package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two presses of "done", a second apart, in one service.
 *
 * What has to hold: the foreground state stays up while anything is still
 * running, and the service is stopped under the newest start id -- the
 * platform refuses `stopSelf` for an older one, which is what keeps a service
 * alive when a command arrived while it was working.
 */
class RunningWorkTest {

    @Test
    fun `the first of two to finish stops nothing`() {
        val work = RunningWork()
        work.started(1)
        work.started(2)

        assertNull("the foreground state went down under work still running", work.finished())
        assertEquals(2, work.finished())
    }

    @Test
    fun `one piece of work stops under its own id`() {
        val work = RunningWork()
        work.started(7)

        assertEquals(7, work.finished())
    }

    @Test
    fun `a command that arrives while the work runs raises the id to stop under`() {
        // The order the two finish in is not the order they started: a quick
        // second press finishes first, and stopping under its predecessor's
        // id would leave the service running with nothing to end it.
        val work = RunningWork()
        work.started(1)
        work.started(2)

        assertNull(work.finished())
        assertEquals(2, work.finished())
    }
}
