package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val restored = OverdueCollapse.Saver.restore(save(state))!!

        assertEquals(setOf(OverdueBand.EARLIER, OverdueBand.LONG_AGO), restored.collapsed)
    }

    @Test
    fun `a saved band that no longer exists unfolds rather than failing`() {
        val restored = OverdueCollapse.Saver.restore(listOf("LONG_AGO", "SOMETHING_ELSE"))!!

        assertEquals(setOf(OverdueBand.LONG_AGO), restored.collapsed)
    }

    @Test
    fun `bands are saved by name, so an added band cannot shift the rest`() {
        val saved = save(OverdueCollapse(setOf(OverdueBand.MISSED_REPEAT)))

        assertEquals(listOf("MISSED_REPEAT"), saved)
    }
}

/**
 * Everything is saveable here, so the scope answers for anything it is asked.
 * `Saver.save` is typed as `Any?` — what comes back is narrowed rather than
 * cast, so a saver that started returning something else fails as an empty
 * list instead of a class cast.
 */
private fun save(state: OverdueCollapse): List<String> =
    (with(OverdueCollapse.Saver) { SaverScope { true }.save(state) } as List<*>)
        .filterIsInstance<String>()
