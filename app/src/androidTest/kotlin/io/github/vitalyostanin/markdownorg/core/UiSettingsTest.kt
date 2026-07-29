package io.github.vitalyostanin.markdownorg.core

import androidx.test.platform.app.InstrumentationRegistry
import io.github.vitalyostanin.markdownorg.ui.AgendaLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the interface remembers between runs.
 *
 * The layout switch is in the header as a control meant to be used often, so
 * the choice has to outlive the process rather than only a rotation.
 */
class UiSettingsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theChosenLayoutOutlivesTheObjectThatStoredIt() {
        UiSettings(context).layout = AgendaLayout.LIST

        // A second instance stands in for the next launch: the value has to
        // come off disk, not out of the object that wrote it.
        assertEquals(AgendaLayout.LIST, UiSettings(context).layout)
        UiSettings(context).layout = AgendaLayout.TIME
    }

    @Test
    fun aStoredNameThatNoLongerExistsReadsAsTheDefault() {
        // A layout dropped in a later version, or a file written by a newer
        // one, must not take the application down on startup.
        context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("agenda_layout", "KANBAN")
            .commit()

        assertEquals(AgendaLayout.TIME, UiSettings(context).layout)
    }
}
