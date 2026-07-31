package io.github.vitalyostanin.markdownorg.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Whether the notes may live outside the application's own storage, and how
 * to ask for that.
 *
 * Two platforms behind one question. From Android 11 the only access that
 * reaches an arbitrary directory by path is "all files access", which is not a
 * runtime permission at all: it is granted in a settings screen the
 * application can only open. Before that it is the ordinary storage
 * permission, granted from a dialog.
 *
 * Access by path is what the core needs. It clones with libgit2 and walks the
 * directory with `std::fs`, and a document tree picked through the system's
 * picker is a URI that neither can open — so the picker is not an alternative
 * here, however much lighter its terms are.
 */
object StorageAccess {

    /** Whether a directory outside the application's storage can be read now. */
    fun granted(context: Context): Boolean = if (allFilesAccess()) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(WRITE) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The settings screen that grants all files access, or `null` before
     * Android 11, where the dialog of [permissions] is the way instead.
     *
     * Two intents rather than one: the per-application screen is what should
     * open, but a device whose settings do not carry it answers nothing to the
     * `package:` form, and the list of all applications is still better than a
     * button that does nothing.
     */
    fun settings(context: Context): Intent? {
        if (!allFilesAccess()) {
            return null
        }

        val own = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )

        return own.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    /**
     * What to ask for in a dialog before Android 11, and nothing after it:
     * from Android 11 the storage permissions are granted and mean nothing,
     * and asking for them would show a dialog that changes no answer.
     */
    fun permissions(): Array<String> = if (allFilesAccess()) {
        emptyArray()
    } else {
        arrayOf(WRITE, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * The directory a tree picked in the system's picker stands for.
     *
     * The picker is only a way to point at a directory without typing its
     * path — a phone keyboard turns `/sdcard` into `/SD card` and worse. What
     * the notes are then read through is the path, and the access to it is
     * the permission above; the URI itself is not kept.
     *
     * `null` when the tree belongs to a provider whose identifier does not
     * name a directory on the shared storage — a cloud provider, say. The form
     * then says nothing was chosen rather than storing a path that is not
     * there.
     */
    fun directoryOf(tree: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(tree)
    }.getOrNull()?.let { documentTreePath(it, Environment.getExternalStorageDirectory()) }

    /**
     * Whether this is a platform where all files access is the mechanism.
     *
     * Annotated so that Android Lint follows the check through the call and
     * does not report the API behind it as unavailable: without the
     * annotation, a version check hidden in a function is invisible to it.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    private fun allFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private const val WRITE = Manifest.permission.WRITE_EXTERNAL_STORAGE
}
