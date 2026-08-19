package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import java.io.File

/**
 * One place the notes live: a directory, the name it is shown under, and an
 * identifier the rest of its settings hang off.
 *
 * There is more than one because notes are kept in more than one place — a
 * work repository and a private one, a shared collection and a personal one —
 * and the agenda over them is one agenda. The merging itself belongs to the
 * extractor, which walks every directory in one pass and marks each task with
 * the root it came from; this is the application's side of that: which
 * directories, under what names, synced with what.
 *
 * [id] is a number handed out once and never again. It names the preferences
 * file holding the remote, the branch and the token of this collection, so
 * giving one back out after a deletion would hand a new collection the
 * credentials of the old one.
 */
data class NotesCollection(

    val id: String,

    /** What the agenda labels the tasks of this collection with. */
    val name: String,

    /** The directory, absolute, as [notesPathProblem] has already allowed it. */
    val path: String,

    /**
     * The file a task created here is written to, relative to [path].
     *
     * A file rather than a rule about which file: a new task has no date, no
     * tag and no section to be filed by, and every guess this application
     * could make about where it belongs would be wrong in someone's notes.
     * The entry is appended to the end of this one — see the core's `create`
     * module for why the end is the one place a write cannot collide with an
     * edit made on another device.
     *
     * [DEFAULT_INBOX] until the collection says otherwise, and created by the
     * first task written to it.
     */
    val inbox: String = DEFAULT_INBOX,
)

/**
 * The file a collection receives new tasks in until one is chosen for it.
 *
 * A name of its own rather than one of the notes already there: writing into
 * a file this application picked out of someone's collection would put its
 * entries among notes kept for another purpose, and a file that is not there
 * yet says plainly where the tasks made here have gone.
 */
const val DEFAULT_INBOX = "inbox.md"

/** Why a file cannot be the one a collection receives new tasks in. */
enum class InboxProblem {

    /** Nothing to write to, and nothing to show as the answer. */
    EMPTY,

    /** An absolute path, or one climbing out of the collection. */
    OUTSIDE,

    /**
     * Not a markdown file.
     *
     * The walk behind the agenda reads `*.md` and nothing else, so a task
     * written elsewhere would appear on the agenda — the note it went into is
     * re-read by name after the write — and be gone at the next full scan,
     * with the file still holding it.
     */
    NOT_MARKDOWN,
}

/**
 * Checks the file a collection is to receive new tasks in.
 *
 * Checked here rather than left to the core, which refuses a path leaving the
 * notes directory when it is asked to write: the settings are where the file
 * is named, and a name that cannot work has to be answered there rather than
 * on the first task somebody tries to create.
 *
 * Returns `null` when the file can be written to.
 */
fun inboxProblem(file: String): InboxProblem? {
    val named = file.trim()

    return when {
        named.isEmpty() -> InboxProblem.EMPTY

        // The same two ways out of the directory the core refuses: an absolute
        // path, and a step above the root of the collection.
        named.startsWith('/') || named.split('/', '\\').any { it == ".." } ->
            InboxProblem.OUTSIDE

        !named.endsWith(MARKDOWN, ignoreCase = true) -> InboxProblem.NOT_MARKDOWN

        else -> null
    }
}

/** What the walk behind the agenda reads, and therefore what a task can go into. */
private const val MARKDOWN = ".md"

/** Why a collection cannot be added, beyond what [notesPathProblem] refuses. */
enum class CollectionProblem {

    /** Nothing to label the tasks with, and nothing to filter the agenda by. */
    NAME_EMPTY,

    /** Another collection is already that directory. */
    PATH_TAKEN,

    /** One of the two directories sits inside the other. */
    PATH_NESTED,
}

/**
 * Checks a collection against the ones already set up.
 *
 * The two failures are the two ways one set of notes ends up read twice. The
 * same directory entered again is the visible one: the walk merges roots that
 * are equal, so the second collection adds a name and nothing else. Nesting is
 * the one the walk cannot see — the roots differ, both are scanned, and every
 * note under the inner directory appears in the agenda twice, each copy naming
 * a different collection. An edit then acts on one of the two, and the other
 * stays on the screen as it was.
 *
 * [others] is the collections this one is judged against, which on an edit is
 * every collection but itself.
 *
 * Returns `null` when the collection can be added.
 */
fun collectionProblem(
    name: String,
    path: String,
    others: List<NotesCollection>,
): CollectionProblem? {
    if (name.isBlank()) {
        return CollectionProblem.NAME_EMPTY
    }

    val target = File(path.trim())

    return when {
        others.any { File(it.path).absolutePath == target.absolutePath } ->
            CollectionProblem.PATH_TAKEN

        others.any { isInside(target, File(it.path)) || isInside(File(it.path), target) } ->
            CollectionProblem.PATH_NESTED

        else -> null
    }
}

/**
 * The next identifier, clear of every one already given out.
 *
 * Counted past the largest rather than off the length: a collection deleted
 * from the middle would otherwise hand its identifier — and the preferences
 * file of its remote and token — to the next one added.
 */
fun nextCollectionId(existing: List<NotesCollection>): String {
    val largest = existing.mapNotNull { it.id.toLongOrNull() }.maxOrNull() ?: 0

    return (largest + 1).toString()
}

/**
 * The collections to work with, starting the first one where a single
 * directory used to be.
 *
 * A device upgrading from a version that knew one directory has that
 * directory, its remote, its branch and its token, and no collections at all.
 * It becomes the first collection over the same path: nothing is moved and
 * nothing is cloned again, so a checkout with commits that exist nowhere else
 * stays where it is.
 *
 * [own] is the directory inside the application's own storage, which is where
 * the notes are when none was ever chosen.
 */
fun migratedCollections(
    stored: List<NotesCollection>,
    legacyPath: String?,
    own: File,
    defaultName: String,
): List<NotesCollection> {
    if (stored.isNotEmpty()) {
        return stored
    }

    val path = legacyPath?.takeUnless(String::isBlank) ?: own.absolutePath

    return listOf(NotesCollection(id = FIRST_ID, name = defaultName, path = path))
}

/** The identifier the collection made out of an earlier version's directory gets. */
const val FIRST_ID = "1"

/**
 * The collections set up on this device, as everything above the storage sees
 * them.
 *
 * An interface for the same reason as [SyncPreferences]: the orchestration is
 * exercised without a device, and the stored form is Android preferences.
 */
interface NotesCollectionsPreferences {

    /**
     * In the order they are shown, which is also the order the walk takes
     * them in and therefore the order equal tasks come back in.
     *
     * Empty only before [migratedCollections] has been applied — the
     * application always works with at least one.
     */
    var collections: List<NotesCollection>
}

/**
 * The collections, in a file of its own.
 *
 * Not in `sync`: that file belongs to one collection and holds its token. Not
 * in `notes` either, which is the single directory of an earlier version and
 * is read once, to make the first collection out of it.
 */
class NotesCollectionsStore(context: Context) : NotesCollectionsPreferences {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var collections: List<NotesCollection>
        get() {
            val order = preferences.getString(KEY_ORDER, null).orEmpty()

            return order.split(SEPARATOR)
                .filter(String::isNotBlank)
                .mapNotNull { id ->
                    // A collection without a path is not one: an entry left
                    // half written by a process that died is skipped rather
                    // than turned into a directory named by the empty string,
                    // which the walk would refuse for the whole agenda.
                    preferences.getString(KEY_PATH + id, null)?.let { path ->
                        NotesCollection(
                            id = id,
                            name = preferences.getString(KEY_NAME + id, null).orEmpty(),
                            path = path,
                            // A collection stored by a version that had no
                            // receiving file gets the default one, which is
                            // the whole of that migration: nothing is written
                            // back until the settings are saved, and the file
                            // itself is made by the first task that goes into
                            // it.
                            inbox = preferences.getString(KEY_INBOX + id, null)
                                ?.takeUnless(String::isBlank)
                                ?: DEFAULT_INBOX,
                        )
                    }
                }
        }
        set(value) {
            // Rewritten whole rather than edited in place, so the name and the
            // path of a removed collection go with it: left behind, they would
            // come back under a reused identifier.
            val edit = preferences.edit().clear()
            edit.putString(KEY_ORDER, value.joinToString(SEPARATOR.toString()) { it.id })
            value.forEach { collection ->
                edit.putString(KEY_NAME + collection.id, collection.name)
                edit.putString(KEY_PATH + collection.id, collection.path)
                edit.putString(KEY_INBOX + collection.id, collection.inbox)
            }
            edit.apply()
        }

    private companion object {
        const val FILE = "collections"
        const val KEY_ORDER = "order"
        const val KEY_NAME = "name."
        const val KEY_PATH = "path."
        const val KEY_INBOX = "inbox."

        /** Not part of an identifier, which is a number. */
        const val SEPARATOR = ','
    }
}
