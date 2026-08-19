package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * What has been typed into the settings form, held together.
 *
 * One object rather than nine pieces of remembered state, so that a section of
 * the form takes the form rather than a field, a setter and a flag apiece: the
 * fields belong to one another — a token is about the address above it — and
 * passing them separately is what grew the call sites past reading.
 *
 * Saved rather than merely remembered: the activity declares no
 * `configChanges`, so a turn of the phone rebuilds it, and a URL typed by hand
 * would be gone. The secrets are saved with the rest. They go into the saved
 * state of the activity, which lives in the process and in the private storage
 * the process is killed to — the same storage the token is already stored in,
 * and a rotation away from being typed again by hand.
 */
@Stable
class SyncFormState(
    url: String = "",
    branch: String = "",
    name: String = "",
    notesPath: String = "",
    inbox: String = "",
) {
    var url by mutableStateOf(url)
    var branch by mutableStateOf(branch)
    var name by mutableStateOf(name)
    var notesPath by mutableStateOf(notesPath)

    /** The file this collection receives new tasks in, relative to its directory. */
    var inbox by mutableStateOf(inbox)

    /** Blank leaves the stored token alone: the form never shows it. */
    var token by mutableStateOf("")
    var dropToken by mutableStateOf(false)

    /** The same rule as the token, for the private half of an ssh key. */
    var sshKey by mutableStateOf("")
    var sshPassphrase by mutableStateOf("")
    var dropKey by mutableStateOf(false)

    /** The form as the view model takes it. */
    fun values(): SyncFormValues = SyncFormValues(
        url = url,
        branch = branch,
        token = token,
        dropToken = dropToken,
        notesPath = notesPath,
        name = name,
        inbox = inbox,
        sshKey = sshKey,
        sshPassphrase = sshPassphrase,
        dropKey = dropKey,
    )

    companion object {
        val Saver: Saver<SyncFormState, Any> = mapSaver(
            save = { form ->
                mapOf(
                    "url" to form.url,
                    "branch" to form.branch,
                    "name" to form.name,
                    "notesPath" to form.notesPath,
                    "inbox" to form.inbox,
                    "token" to form.token,
                    "dropToken" to form.dropToken,
                    "sshKey" to form.sshKey,
                    "sshPassphrase" to form.sshPassphrase,
                    "dropKey" to form.dropKey,
                )
            },
            restore = { saved ->
                SyncFormState(
                    url = saved["url"] as? String ?: "",
                    branch = saved["branch"] as? String ?: "",
                    name = saved["name"] as? String ?: "",
                    notesPath = saved["notesPath"] as? String ?: "",
                    inbox = saved["inbox"] as? String ?: "",
                ).apply {
                    token = saved["token"] as? String ?: ""
                    dropToken = saved["dropToken"] as? Boolean ?: false
                    sshKey = saved["sshKey"] as? String ?: ""
                    sshPassphrase = saved["sshPassphrase"] as? String ?: ""
                    dropKey = saved["dropKey"] as? Boolean ?: false
                }
            },
        )
    }
}

/**
 * The form of one collection, brought back as it was left.
 *
 * Keyed on the collection being edited: every field belongs to one of them, and
 * switching from a work repository to a personal one has to bring that one's
 * address rather than keep what was typed for the other.
 */
@Composable
fun rememberSyncForm(
    editingId: String,
    url: String,
    branch: String,
    name: String,
    notesPath: String,
    inbox: String,
): SyncFormState = rememberSaveable(editingId, saver = SyncFormState.Saver) {
    SyncFormState(
        url = url,
        branch = branch,
        name = name,
        notesPath = notesPath,
        inbox = inbox,
    )
}
