package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which form of the date and time questions a window has room for.
 *
 * The dialogs themselves are drawn in a window of their own, which a test
 * cannot resize from the composition around it, so what is asserted here is
 * the decision rather than the drawing: the sizes of real phones, upright and
 * sideways, against the two answers.
 */
class PickerRoomTest {

    @Test
    fun anUprightPhoneHasRoomForBoth() {
        assertTrue(aCalendarFits(891.dp))
        assertTrue(aClockFits(411.dp, 891.dp))
    }

    @Test
    fun aPhoneOnItsSideHasRoomForNeither() {
        assertFalse(aCalendarFits(411.dp))
        assertFalse(aClockFits(891.dp, 411.dp))
    }

    @Test
    fun theNarrowestPhoneUprightStillFitsACalendar() {
        assertTrue(aCalendarFits(640.dp))
        assertTrue(aClockFits(320.dp, 640.dp))
    }

    @Test
    fun aWindowShorterThanTheDialogItselfDoesNot() {
        assertTrue(aCalendarFits(568.dp))
        assertFalse(aCalendarFits(567.dp))
    }

    @Test
    fun aSquareWindowCountsAsRoomForAClock() {
        assertTrue(aClockFits(600.dp, 600.dp))
    }
}
