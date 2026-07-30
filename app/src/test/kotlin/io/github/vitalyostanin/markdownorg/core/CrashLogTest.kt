package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What survives a run that ended in a crash.
 *
 * An exception out of a composition or out of a coroutine takes the process
 * down, and logcat is gone by the time anyone thinks to look. The trace is
 * written where the next run can find it — the point being that it is
 * readable after the process that produced it is no longer there.
 */
class CrashLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aRunThatEndedWellLeavesNothingBehind() {
        assertNull(CrashLog(folder.newFolder()).read())
    }

    @Test
    fun theTraceOfTheCrashIsReadableAfterwards() {
        val log = CrashLog(folder.newFolder())

        log.record(Thread.currentThread(), IllegalStateException("the list was empty"))
        val kept = log.read()

        assertTrue("nothing was written", kept != null)
        assertTrue("the message is missing", kept!!.contains("the list was empty"))
        assertTrue("the class of the failure is missing", kept.contains("IllegalStateException"))
        assertTrue("the frames are missing", kept.contains("CrashLogTest"))
    }

    @Test
    fun theCauseTravelsWithIt() {
        // The exception a screen sees is rarely the one worth reading: what
        // failed is under it.
        val log = CrashLog(folder.newFolder())
        val cause = java.io.IOException("no space left on device")

        log.record(Thread.currentThread(), IllegalStateException("could not seed", cause))

        assertTrue(log.read()!!.contains("no space left on device"))
    }

    @Test
    fun aTraceThatHasBeenReadIsNotShownAgain() {
        val log = CrashLog(folder.newFolder())
        log.record(Thread.currentThread(), IllegalStateException("boom"))

        log.clear()

        assertNull(log.read())
    }

    @Test
    fun theSecondCrashReplacesTheFirstRatherThanGrowingWithoutBound() {
        val log = CrashLog(folder.newFolder())

        log.record(Thread.currentThread(), IllegalStateException("the first one"))
        log.record(Thread.currentThread(), IllegalStateException("the second one"))
        val kept = log.read()!!

        assertTrue(kept.contains("the second one"))
        assertEquals(1, kept.split("IllegalStateException").size - 1)
    }

    @Test
    fun adirectoryThatCannotBeWrittenToIsNotWorthAnotherCrash() {
        // This runs from the handler of an exception that is already taking
        // the process down; throwing here would replace the trace with one
        // about the writing of it.
        val log = CrashLog(folder.newFile())

        log.record(Thread.currentThread(), IllegalStateException("boom"))

        assertNull(log.read())
    }
}
