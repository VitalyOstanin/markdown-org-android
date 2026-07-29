package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import io.github.vitalyostanin.markdownorg.ui.AgendaLayout

/**
 * What the user chose about the interface itself, as everything above the
 * storage sees it.
 *
 * Kept apart from [SyncPreferences]: that one describes where the notes come
 * from and carries a token, this one describes how they are drawn. An
 * interface for the same reason — the view model is exercised without a device.
 */
interface UiPreferences {

    /** Which of the two layouts the agenda opens in. */
    var layout: AgendaLayout
}

/**
 * The interface preferences, in a file of their own.
 *
 * A separate file from `sync` so that forgetting the repository — which clears
 * that one outright — does not also reset how the agenda is drawn.
 */
class UiSettings(context: Context) : UiPreferences {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var layout: AgendaLayout
        // A value written by a newer version, or a name that has since been
        // dropped, reads as the default rather than as a crash on startup.
        get() = preferences.getString(KEY_LAYOUT, null)
            ?.let { name -> AgendaLayout.entries.firstOrNull { it.name == name } }
            ?: AgendaLayout.TIME
        set(value) = preferences.edit().putString(KEY_LAYOUT, value.name).apply()

    private companion object {
        const val FILE = "ui"
        const val KEY_LAYOUT = "agenda_layout"
    }
}
