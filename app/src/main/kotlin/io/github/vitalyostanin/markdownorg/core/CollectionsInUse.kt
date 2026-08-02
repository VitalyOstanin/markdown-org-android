package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import java.io.File

/**
 * One collection, with everything that acts on it.
 *
 * The working copy, the settings that reach its server and the writer that
 * edits it are per collection rather than per device: two collections are two
 * repositories, and a token, a branch or a pinned host key shared between them
 * would be sent to whichever was synced first. The agenda over them is still
 * one agenda — that part belongs to the walk, not here.
 */
data class CollectionInUse(

    val collection: NotesCollection,

    /**
     * The directory as the walk reports it, which is what a task carries.
     *
     * Resolved once, when the collection is put to work, rather than at every
     * comparison: the walk canonicalises what it is given, so a chosen path
     * that goes through a symbolic link comes back as another string, and a
     * task from this collection would match none of it. Resolving it here also
     * keeps the resolution off the path of an edit, where it would be a read
     * of storage on the main thread.
     */
    val root: String,

    /** The working copy, and the lock over it. */
    val area: NotesArea,

    /** The remote, the branch and the credentials of this collection. */
    val settings: SyncPreferences,

    val editor: NotesWriter,

    val syncer: NotesSyncer,
)

/**
 * The collections the application is working with.
 *
 * An interface so the orchestration above it can be exercised without a
 * device: everything below builds a working copy, opens preference files and
 * calls the native library.
 */
interface CollectionsInUse : NotesAreas {

    /** In the order the collections are shown, which the walk keeps. */
    val entries: List<CollectionInUse>

    /**
     * Work with [collections] from now on, keeping what is already set up.
     *
     * A collection whose path is unchanged keeps its working copy — and with
     * it the lock, so a sync or an edit already running against that directory
     * is not left holding a lock nothing else takes.
     */
    fun use(collections: List<NotesCollection>)

    /**
     * Erase what was stored for a collection that has been removed.
     *
     * Its settings and nothing else: the directory is left as it is, because
     * it may be a repository with commits that exist nowhere else. The remote,
     * the branch, the token and the pinned host key do go — they reached a
     * server this device no longer talks to, and an identifier handed to the
     * next collection would otherwise come with them attached.
     */
    fun forget(collection: CollectionInUse)
}

/** Only ever one collection, which is what a device that has not been set up has. */
val CollectionsInUse.single: CollectionInUse get() = entries.first()

/**
 * The collection a task belongs to, found by the root it carries.
 *
 * Compared against [CollectionInUse.root], which was resolved the same way the
 * walk resolves it. Answers `null` for a task from a collection that has since
 * been removed — the agenda on screen outlives the set it was built from by as
 * long as it takes to rebuild it.
 */
fun CollectionsInUse.byRoot(root: String?): CollectionInUse? =
    root?.let { named -> entries.firstOrNull { it.root == named } }

/**
 * The directory as the walk will report it.
 *
 * Falls back to the absolute path when the directory cannot be resolved — one
 * that is not there yet, or that cannot be read: the walk will fail on it and
 * say so, which is a better answer than a collection that silently matches no
 * task.
 */
internal fun resolvedRoot(directory: File): String = runCatching { directory.canonicalPath }
    .getOrElse { directory.absolutePath }

/**
 * The collections as they are on a device.
 *
 * Rebuilt from the stored set rather than watched: the set changes when the
 * settings screen saves, which is also when the agenda is rebuilt.
 */
class DeviceCollections(private val context: Context, collections: List<NotesCollection>) :
    CollectionsInUse {

    /**
     * Read without the lock by every caller, replaced only from the main
     * thread. Volatile for the same reason as the directory inside a working
     * copy: a walk on the IO pool must not keep reading a set that has been
     * replaced.
     */
    @Volatile
    override var entries: List<CollectionInUse> = collections.map(::build)
        private set

    override val areas: List<NotesArea> get() = entries.map(CollectionInUse::area)

    override suspend fun <T> exclusive(block: suspend () -> T): T = holdingAll(areas, block)

    override fun use(collections: List<NotesCollection>) {
        val known = entries.associateBy { it.collection.id }

        entries = collections.map { collection ->
            // Kept when the directory is the same one, so that the lock over
            // it stays the lock everything already running is queued behind.
            // A renamed collection is the same working copy; a repointed one
            // is not, and gets a new area over the new directory.
            known[collection.id]
                ?.takeIf { it.collection.path == collection.path }
                ?.copy(collection = collection)
                ?: build(collection)
        }
    }

    override fun forget(collection: CollectionInUse) {
        // Opened again by identifier rather than cast back from the entry:
        // which file a collection's settings live in is the settings class's
        // business, and the first collection's is the one a single-directory
        // version of the application wrote.
        SyncSettings(context, collection.collection.id).clear()
    }

    private fun build(collection: NotesCollection): CollectionInUse {
        val directory = File(collection.path)
        val area = NotesStore(directory)
        val settings = SyncSettings(context, collection.id)

        return CollectionInUse(
            collection = collection,
            root = resolvedRoot(directory),
            area = area,
            settings = settings,
            editor = NotesEditor(area, settings),
            syncer = NotesSync(context, area),
        )
    }
}
