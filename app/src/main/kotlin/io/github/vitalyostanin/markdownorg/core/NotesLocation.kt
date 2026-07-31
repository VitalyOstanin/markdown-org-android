package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import java.io.File

/**
 * Where the notes directory is, as everything above the storage sees it.
 *
 * An interface for the same reason as [SyncPreferences]: the view model is
 * exercised without a device, and the stored form is Android preferences.
 */
interface NotesLocationPreferences {

    /**
     * The directory the notes live in, or `null` for the one inside the
     * application's own storage.
     *
     * A path rather than a document URI: the core clones and scans through
     * libgit2 and `std::fs`, and neither can open anything the system's
     * document picker hands back. That is the whole reason the application
     * asks for access to all files rather than for a directory.
     */
    var path: String?
}

/**
 * Where the notes are, in a file of its own.
 *
 * Not in `sync`: that file is about a remote and holds the token, and it is
 * cleared as a unit. Where the notes sit outlives any one repository.
 */
class NotesLocation(context: Context) : NotesLocationPreferences {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var path: String?
        get() = preferences.getString(KEY_PATH, null)
        set(value) = preferences.edit().putString(KEY_PATH, value?.trim()?.ifEmpty { null }).apply()

    private companion object {
        const val FILE = "notes"
        const val KEY_PATH = "directory"
    }
}

/** The directory inside the application's own storage, used when none is chosen. */
fun ownNotesRoot(context: Context): File = File(context.filesDir, "notes")

/** Which directory the notes are read from: the chosen one, or the default. */
fun notesRoot(context: Context, location: NotesLocationPreferences): File =
    location.path?.takeUnless(String::isBlank)?.let(::File) ?: ownNotesRoot(context)

/**
 * The path a document tree from the system picker stands for.
 *
 * The picker is used for what it is good at — letting someone point at a
 * directory instead of typing its path — while the reading is still done by
 * path, because that is what the core takes. The identifier it hands back is
 * `volume:relative/path`: `primary` for the built-in shared storage, and the
 * volume's own identifier for a card.
 *
 * [primary] is passed in rather than read from the platform so the rule can be
 * exercised without a device.
 *
 * Returns `null` for an identifier of another shape — a provider other than
 * external storage hands back something this cannot turn into a path, and a
 * guess would name a directory that is not there.
 */
fun documentTreePath(documentId: String, primary: File): String? {
    val volume = documentId.substringBefore(':', missingDelimiterValue = "")
    val relative = documentId.substringAfter(':', missingDelimiterValue = "")

    if (volume.isEmpty() || relative.startsWith('/')) {
        return null
    }

    val root = if (volume == PRIMARY_VOLUME) primary else File(VOLUMES, volume)

    return if (relative.isEmpty()) root.path else File(root, relative).path
}

/** Why a directory cannot be used for the notes. */
enum class NotesPathProblem {

    /** Nothing was entered — which is not an error, but the default instead. */
    EMPTY,

    /** A path that names nothing on its own: the application has no working directory. */
    RELATIVE,

    /** Something is there under that name, and it is not a directory. */
    NOT_A_DIRECTORY,

    /** Outside the application's own storage, and access to all files was not granted. */
    NEEDS_PERMISSION,
}

/**
 * Checks a directory before the notes are moved into it.
 *
 * Checked here rather than left to the first scan, because the failure a scan
 * reports is about the walk it could not finish; what has to be said is which
 * of the four things above is wrong with the path, and only one of them —
 * [NotesPathProblem.NEEDS_PERMISSION] — has an action behind it.
 *
 * [granted] is asked of the caller rather than of the platform, so the rule
 * itself is testable on the JVM: what the platform says is a different
 * question from what follows from it.
 *
 * Returns `null` when the directory can be used.
 */
fun notesPathProblem(path: String, own: File, granted: Boolean): NotesPathProblem? {
    val value = path.trim()
    val target = File(value)

    return when {
        value.isEmpty() -> NotesPathProblem.EMPTY

        !target.isAbsolute -> NotesPathProblem.RELATIVE

        // Asked before the permission: a path that is a plain file cannot hold
        // the notes however much access there is to it.
        target.exists() && !target.isDirectory -> NotesPathProblem.NOT_A_DIRECTORY

        isInside(target, own) -> null

        granted -> null

        else -> NotesPathProblem.NEEDS_PERMISSION
    }
}

/**
 * Whether [target] is [parent] itself or sits under it.
 *
 * Compared as absolute paths with a separator appended, so that a sibling
 * whose name merely starts with the same letters — `/data/notes-old` against
 * `/data/notes` — does not read as being inside. Canonical paths are not
 * asked for: resolving one touches storage, and a symbolic link pointing out
 * of the application's storage is not a case worth a disk read on every
 * keystroke in the field.
 */
private fun isInside(target: File, parent: File): Boolean {
    val inside = target.absolutePath
    val root = parent.absolutePath

    return inside == root || inside.startsWith(root + File.separator)
}

/** What the picker calls the built-in shared storage. */
private const val PRIMARY_VOLUME = "primary"

/** Where the platform mounts everything else, a card included. */
private const val VOLUMES = "/storage"
