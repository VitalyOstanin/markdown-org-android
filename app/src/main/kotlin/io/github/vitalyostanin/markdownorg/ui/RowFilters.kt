package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.core.MergedTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which of the rows a scan produced are on screen.
 *
 * Two filters, and neither of them costs a scan: the collections decide which
 * rows exist at all, the tag selects among them, and both read what the rows
 * already carry. That is why they are held apart from the agenda itself --
 * answering a chip is redrawing what is in hand, not reading the notes again.
 *
 * Neither filter is stored between launches: they hide tasks, and a device
 * opening on an agenda missing half of them with no memory of why is worse off
 * than one that starts with everything shown.
 */
class RowFilters {

    /** The collections whose rows the filter is keeping off the screen. */
    private var hidden: Set<String> = emptySet()

    /**
     * The tags the collections declare, merged, as of the last scan.
     *
     * Read with the notes rather than watched: the file arrives with a sync
     * like the notes around it, and a scan is what follows a sync.
     */
    private val _tags = MutableStateFlow<List<MergedTag>>(emptyList())
    val tags: StateFlow<List<MergedTag>> = _tags.asStateFlow()

    /** The tag the agenda is narrowed to, or null while it is not narrowed. */
    private val _currentTag = MutableStateFlow<String?>(null)
    val currentTag: StateFlow<String?> = _currentTag.asStateFlow()

    /** The filter over the collections, empty while there is one of them. */
    private val _collectionFilter = MutableStateFlow<List<CollectionChoice>>(emptyList())
    val collectionFilter: StateFlow<List<CollectionChoice>> = _collectionFilter.asStateFlow()

    /**
     * Show or hide the rows of one collection.
     *
     * Turning the last collection off is allowed and leaves the agenda empty --
     * the chips stay up, and the way back is the same tap.
     */
    fun setCollectionShown(id: String, shown: Boolean) {
        hidden = if (shown) hidden - id else hidden + id
        _collectionFilter.update { choices ->
            choices.map { choice ->
                if (choice.label.id == id) choice.copy(shown = shown) else choice
            }
        }
    }

    /** Narrow the agenda to one tag, or to none when [tag] is null. */
    fun setTag(tag: String?) {
        _currentTag.value = tag
    }

    /**
     * The days as the filters leave them.
     *
     * Both run here and in this order: the collections decide which rows exist
     * at all, the tag selects among them. Order matters for what the reader
     * sees, not for the outcome -- neither can bring back a row the other
     * removed -- but keeping it in one place is what stops the two from
     * drifting apart.
     */
    fun apply(days: List<AgendaDay>): List<AgendaDay> =
        days.showing(hidden).tagged(_currentTag.value, _tags.value)

    /**
     * Rebuild the row of filter chips for the collections now in use.
     *
     * The hidden set is narrowed to what is still there: a collection that has
     * been removed must not go on hiding rows through an identifier its
     * successor could be given.
     */
    fun offerCollections(labels: List<CollectionLabel>) {
        hidden = hidden.intersect(labels.map(CollectionLabel::id).toSet())
        _collectionFilter.value = labels.map { label ->
            CollectionChoice(label = label, shown = label.id !in hidden)
        }
    }

    /**
     * Take the merged tag dictionary as the last scan read it.
     *
     * The chosen tag survives only while the dictionary still holds it. A file
     * edited elsewhere and arriving with a sync can retire a tag, and going on
     * filtering by a name nothing declares would leave the agenda narrowed with
     * nothing on screen to say by what.
     */
    fun offerTags(merged: List<MergedTag>) {
        _tags.value = merged
        if (_currentTag.value !in merged.map(MergedTag::name)) {
            _currentTag.value = null
        }
    }
}
