package io.github.vitalyostanin.markdownorg.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import io.github.vitalyostanin.markdownorg.R
import java.io.File

/**
 * Handing a note to a markdown editor of the user's choosing.
 *
 * This application shows an agenda and edits the one line a task sits on; it
 * is not a text editor, and the notes it reads are ordinary markdown that the
 * user reads and writes elsewhere too. Rather than grow an editor of its own,
 * it offers the file to whatever is installed.
 *
 * What the receiving application gets is a `content://` URI granted for the
 * one launch. A `file://` URI is refused by the platform outright
 * (`FileUriExposedException`), and it would also require the reader to hold a
 * storage permission of its own — over a directory the user may have put
 * anywhere.
 */
object ExternalNote {

    private const val TAG = "ExternalNote"

    /**
     * What a markdown file is called when handed to another application.
     *
     * `text/markdown` is what it is, and every editor worth offering it to
     * declares either that or the wildcard type over text. A plain-text type
     * would widen the list to every text viewer on the device, which is a
     * longer chooser and not a better one.
     */
    const val MIME = "text/markdown"

    /**
     * The extra some editors read the note's path from.
     *
     * A `content://` URI names a provider and a document within it, and the
     * receiver is meant to open a stream through the resolver. Editors that
     * work in terms of `java.io.File` instead try to recover a path from the
     * URI, and can only do that for the providers they were taught: Markor
     * matches a handful of prefixes and known authorities and shows "can only
     * edit local, offline available documents" for everything else, this
     * application's provider included.
     *
     * The path is offered alongside the URI rather than instead of it, so an
     * editor that reads the stream is unaffected — an extra it does not know
     * costs it nothing. The name is Markor's, and other applications that
     * pass files around read the same one.
     */
    private const val PATH_EXTRA = "EXTRA_FILEPATH"

    /**
     * The intent that offers [note] for reading and writing.
     *
     * Separate from [open] so the decision can be examined without launching
     * anything: what a test can check is the URI, the type and the flags, and
     * none of that needs an editor to be installed.
     *
     * `ACTION_VIEW` rather than `ACTION_EDIT`: editors declare it and viewers
     * declare it, while `ACTION_EDIT` is declared by few enough that the
     * chooser would be empty on a device that has a perfectly good editor on
     * it. The write permission travels with the URI regardless, so an editor
     * that opened through `ACTION_VIEW` can still save.
     */
    fun intentFor(context: Context, note: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.notes", note)

        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME)
            .putExtra(PATH_EXTRA, note.absolutePath)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
    }

    /**
     * Offer the note, and say so when nothing on the device takes it.
     *
     * Answers whether the launch happened, because the caller has a message
     * to show either way and only it knows where messages go.
     */
    fun open(context: Context, note: File): Boolean {
        val chooser = Intent
            .createChooser(
                intentFor(context, note),
                context.getString(R.string.action_open_externally),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(chooser)
            true
        } catch (absent: ActivityNotFoundException) {
            Log.w(TAG, "no application opens $note", absent)
            false
        }
    }
}
