package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import io.github.vitalyostanin.markdownorg.ui.AgendaLayout
import io.github.vitalyostanin.markdownorg.ui.AgendaSpan

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

    /** How much of the plan the agenda opens on: a day, a week, a month, the tasks. */
    var span: AgendaSpan
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
        get() = stored(KEY_LAYOUT, AgendaLayout.entries, AgendaLayout.TIME)
        set(value) = store(KEY_LAYOUT, value.name)

    override var span: AgendaSpan
        // The day, because that is what a phone is opened to look at: what is
        // on today. The wider spans are a question asked deliberately, and one
        // asked once should not become the answer on every launch after it.
        get() = stored(KEY_SPAN, AgendaSpan.entries, AgendaSpan.DAY)
        set(value) = store(KEY_SPAN, value.name)

    /**
     * The stored choice among [options], or [fallback].
     *
     * A value written by a newer version, or a name that has since been
     * dropped, reads as the default rather than as a crash on startup.
     */
    private fun <T : Enum<T>> stored(key: String, options: List<T>, fallback: T): T =
        preferences.getString(key, null)
            ?.let { name -> options.firstOrNull { it.name == name } }
            ?: fallback

    private fun store(key: String, name: String) = preferences.edit().putString(key, name).apply()

    private companion object {
        const val FILE = "ui"
        const val KEY_LAYOUT = "agenda_layout"
        const val KEY_SPAN = "agenda_span"
    }
}
