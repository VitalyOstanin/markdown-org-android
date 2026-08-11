package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverdueCollapseTest {

    @Test
    fun `the oldest band starts folded and the rest open`() {
        val state = OverdueCollapse()

        assertTrue(state.isCollapsed(OverdueBand.LONG_AGO))
        assertFalse(state.isCollapsed(OverdueBand.MISSED_REPEAT))
        assertFalse(state.isCollapsed(OverdueBand.RECENT))
        assertFalse(state.isCollapsed(OverdueBand.EARLIER))
    }

    @Test
    fun `folding a band toggles it and leaves the others alone`() {
        val state = OverdueCollapse()

        state.toggle(OverdueBand.RECENT)
        assertTrue(state.isCollapsed(OverdueBand.RECENT))
        assertTrue(state.isCollapsed(OverdueBand.LONG_AGO))

        state.toggle(OverdueBand.RECENT)
        assertFalse(state.isCollapsed(OverdueBand.RECENT))
        assertTrue(state.isCollapsed(OverdueBand.LONG_AGO))
    }

    @Test
    fun `the fold survives being saved and restored`() {
        val state = OverdueCollapse(setOf(OverdueBand.EARLIER, OverdueBand.LONG_AGO))

        val restored = OverdueCollapse.Saver.restore(save(state)!!)!!

        assertEquals(setOf(OverdueBand.EARLIER, OverdueBand.LONG_AGO), restored.collapsed)
    }

    @Test
    fun `a saved band that no longer exists unfolds rather than failing`() {
        val restored = OverdueCollapse.Saver.restore("LONG_AGO,SOMETHING_ELSE")!!

        assertEquals(setOf(OverdueBand.LONG_AGO), restored.collapsed)
    }

    /**
     * The one state a rotation must not lose is the one it used to lose.
     *
     * Every band opened by hand is what somebody working through an old file
     * arrives at, and coming back to a folded archive after each turn of the
     * phone is exactly what the saved fold is there to prevent.
     */
    @Test
    fun `a screen with nothing folded comes back with nothing folded`() {
        val state = OverdueCollapse(setOf(OverdueBand.LONG_AGO))
        state.toggle(OverdueBand.LONG_AGO)

        val saved = with(OverdueCollapse.Saver) { SaverScope { true }.save(state) }

        assertNotNull("no band folded is a state too, and nothing was saved for it", saved)
        assertEquals(emptySet<OverdueBand>(), OverdueCollapse.Saver.restore(saved!!)!!.collapsed)
    }

    @Test
    fun `bands are saved by name, so an added band cannot shift the rest`() {
        val saved = save(OverdueCollapse(setOf(OverdueBand.MISSED_REPEAT)))

        assertEquals("MISSED_REPEAT", saved)
    }
}

/** Everything is saveable here, so the scope answers for anything it is asked. */
private fun save(state: OverdueCollapse): String? =
    with(OverdueCollapse.Saver) { SaverScope { true }.save(state) }
