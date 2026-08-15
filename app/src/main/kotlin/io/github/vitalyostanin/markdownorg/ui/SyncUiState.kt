package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.CollectionProblem
import io.github.vitalyostanin.markdownorg.core.NotesPathProblem
import io.github.vitalyostanin.markdownorg.core.RemoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.SyncRun
import io.github.vitalyostanin.markdownorg.core.maskCredentials
import uniffi.markdown_org_ffi.Adoption
import uniffi.markdown_org_ffi.EditException
import uniffi.markdown_org_ffi.RepoStatus
import uniffi.markdown_org_ffi.SyncException

/** What the interface shows about the checkout and the last sync attempt. */
data class SyncUiState(
    /** A remote is configured, so syncing is possible at all. */
    val configured: Boolean = false,
    val running: Boolean = false,
    /** State of the checkout, absent until the first clone succeeds. */
    val repository: RepoStatus? = null,
    /** Wall-clock time of the last successful sync, or `0`. */
    val lastSyncedAt: Long = 0,
    /** Outcome of the most recent attempt, cleared when a new one starts. */
    val message: SyncMessage? = null,
    /**
     * What the collections synced in the last run each answered.
     *
     * Beside [message], which is the last answer of the lot: a run over three
     * repositories where the second failed is one where [message] describes
     * the third, and a header saying "up to date" would hide the failure. Held
     * empty while a single collection is synced — there the one line above says
     * everything this would.
     */
    val runs: List<CollectionRun> = emptyList(),
    /**
     * The notes are kept on this device on purpose.
     *
     * Distinct from `!configured`, which is the same directory with nobody
     * having said anything about it: this one asks for no remote and shows no
     * invitation to set one up.
     */
    val local: Boolean = false,
    /**
     * The branch on which the device's notes and the remote's turn out to
     * share no history, when that is what adopting the directory found.
     *
     * Present until the question is answered: the screen has to offer the
     * answer, and nothing else about the checkout says one is owed.
     */
    val unrelated: String? = null,
    /**
     * The server key waiting to be vouched for, as `SHA256:<base64>`.
     *
     * The other question the screen has to put: an SSH server proves itself
     * by its host key and nothing else, so the first sync with one — and any
     * sync where the key changed — stops here until the user says it is the
     * right server. [`replaces`][pendingHostReplaces] holds the key it would
     * take the place of, which is what makes the question the graver one.
     */
    val pendingHost: String? = null,
    val pendingHostReplaces: String? = null,
    /**
     * The public half of a key just made, so the settings screen can offer it
     * without being reopened.
     */
    val publicKey: String = "",
) {

    /**
     * Nothing has been said about where the notes belong yet.
     *
     * Neither an address nor the answer that this device is where they stay —
     * which is the state a fresh install is in, and the one thing the screen
     * has to lead out of. Distinct from both halves it is made of: `configured`
     * alone would call a deliberately local collection unfinished, and `local`
     * alone says nothing about a collection that has a server.
     */
    val unsettled: Boolean get() = !configured && !local
}

/** What one collection's turn in a sync run ended with. */
data class CollectionRun(val id: String, val name: String, val message: SyncMessage)

/**
 * What the settings form opens with.
 *
 * Named fields rather than a `Triple`: the caller destructures it positionally,
 * and two of the three are strings — swapping the address with the branch
 * would compile and show the wrong thing in both fields. The token itself
 * never travels, only whether one is stored: the form does not show it.
 */
data class SyncForm(
    val url: String,
    val branch: String,
    val hasToken: Boolean,
    /** The chosen notes directory, empty for the application's own storage. */
    val notesPath: String,
    /** What the collection is called on the agenda and in the filter. */
    val name: String = "",
    /** A private key is stored, which the form shows no more of than this. */
    val hasKey: Boolean = false,
    /** The public half of a key made here, empty when there is none to offer. */
    val publicKey: String = "",
    /** The server key the remote is known by, empty until one is vouched for. */
    val knownHost: String = "",
)

/**
 * What the settings form hands back when it is saved.
 *
 * Named fields rather than eight positional arguments, for the reason
 * [SyncForm] states: half of them are strings, three of them are secrets, and
 * a call that swaps two would compile and store the passphrase as the key.
 */
data class SyncFormValues(
    val url: String,
    val branch: String,
    /** Blank leaves the stored token alone; the form never shows it. */
    val token: String,
    val dropToken: Boolean,
    val notesPath: String,
    /** What the collection is called; blank is refused rather than stored. */
    val name: String = "",
    /** Blank leaves the stored key alone, the way a blank token does. */
    val sshKey: String = "",
    val sshPassphrase: String = "",
    val dropKey: Boolean = false,
)

/**
 * What to tell the user about the last attempt.
 *
 * A resource id and an optional detail rather than a finished string: the
 * wording belongs to the resources, and the detail is either data the
 * application words itself or diagnostics from a library.
 */
data class SyncMessage(
    @param:StringRes val text: Int,
    val detail: Detail? = null,
    val failed: Boolean = false,
)

/**
 * The second line under a message, and where its words come from.
 *
 * The distinction is what keeps a Russian heading from standing over an
 * English sentence: everything the application can word, it words from the
 * resources, and what is left is the text of a library, which is written in
 * whatever language that library writes in and cannot be translated here.
 */
sealed interface Detail {
    /** Diagnostics from libgit2 or the core, shown as it arrived. */
    data class Verbatim(val text: String) : Detail

    /** Worded here, over a name the core reported when there is one. */
    data class Worded(@param:StringRes val text: Int, val arg: String? = null) : Detail

    /** Worded here around a number, which picks the plural form. */
    data class Counted(@param:PluralsRes val text: Int, val count: Int) : Detail
}

/**
 * What to tell the user about a directory that cannot hold the notes.
 *
 * [NotesPathProblem.EMPTY] is not among them: an empty field means the
 * directory inside the application's own storage, which is where the notes
 * live until another one is chosen.
 */
fun NotesPathProblem.toMessage(): SyncMessage? = when (this) {
    NotesPathProblem.EMPTY -> null
    NotesPathProblem.RELATIVE -> SyncMessage(R.string.settings_notes_relative, failed = true)
    NotesPathProblem.NOT_A_DIRECTORY -> SyncMessage(R.string.settings_notes_not_dir, failed = true)
    NotesPathProblem.NEEDS_PERMISSION -> SyncMessage(R.string.settings_notes_denied, failed = true)
}

/**
 * What to tell the user about a directory that clashes with a collection they
 * already have.
 *
 * Apart from [NotesPathProblem] because it is a different question: that one
 * is about the directory itself — is it there, may it be read — and this one
 * is about the set, where the same notes read twice is the failure.
 */
fun CollectionProblem.toMessage(): SyncMessage = when (this) {
    CollectionProblem.NAME_EMPTY -> SyncMessage(R.string.collection_name_empty, failed = true)
    CollectionProblem.PATH_TAKEN -> SyncMessage(R.string.collection_path_taken, failed = true)
    CollectionProblem.PATH_NESTED -> SyncMessage(R.string.collection_path_nested, failed = true)
}

/** What to tell the user about an address that cannot be used. */
fun RemoteUrlProblem.toMessage(): SyncMessage = when (this) {
    RemoteUrlProblem.EMPTY -> SyncMessage(R.string.settings_url_empty, failed = true)
    RemoteUrlProblem.SCHEME -> SyncMessage(R.string.settings_url_scheme, failed = true)
    RemoteUrlProblem.INCOMPLETE -> SyncMessage(R.string.settings_url_incomplete, failed = true)
}

/**
 * What one sync amounted to, both halves of it.
 *
 * A refused push is reported over everything else the run did: what came down
 * from the server is already on screen, and what did not go up is the part
 * that still needs someone. Below that, the push is mentioned only when there
 * was one — a run that fetched and had nothing to send reads as it always did.
 */
fun SyncRun.toMessage(): SyncMessage = when {
    pushFailure != null -> pushFailure.toSyncMessage()

    pushed > 0u -> SyncMessage(
        R.string.sync_pushed,
        Detail.Counted(R.plurals.sync_pushed_detail, pushed.toInt()),
    )

    fetched.cloned -> SyncMessage(R.string.sync_cloned)

    fetched.commitsApplied > 0u -> SyncMessage(R.string.sync_updated)

    else -> SyncMessage(R.string.sync_already_current)
}

/**
 * What taking a directory into git amounted to.
 *
 * The unrelated case is not a failure and is not worded as one: nothing was
 * lost, nothing was sent, and what it needs is an answer. The screen offers
 * that answer beside this message.
 */
fun Adoption.toMessage(): SyncMessage = when (this) {
    is Adoption.Published -> SyncMessage(
        R.string.sync_published,
        Detail.Counted(R.plurals.sync_pushed_detail, commitsPushed.toInt()),
    )

    is Adoption.Took -> SyncMessage(R.string.sync_took_remote)

    is Adoption.Unrelated -> SyncMessage(
        R.string.sync_unrelated,
        Detail.Worded(R.string.sync_unrelated_detail, branch),
    )
}

/**
 * Maps a failure onto something worth reading.
 *
 * The variants are kept apart because the answer differs: a rejected token
 * has to be replaced, an unreachable host is worth retrying, and a diverged
 * or dirty checkout needs a decision the application cannot make.
 */
fun Throwable.toSyncMessage(): SyncMessage = when (this) {
    is SyncException.Auth -> SyncMessage(
        R.string.sync_failed_auth,
        Detail.Verbatim(detail),
        failed = true,
    )

    is SyncException.Network -> SyncMessage(
        R.string.sync_failed_network,
        Detail.Verbatim(detail),
        failed = true,
    )

    // The two below carry data rather than prose, and the sentence around it
    // is written where the translations are.
    is SyncException.Diverged -> SyncMessage(
        R.string.sync_failed_diverged,
        Detail.Worded(R.string.sync_diverged_detail, branch),
        failed = true,
    )

    is SyncException.Dirty -> SyncMessage(
        R.string.sync_failed_dirty,
        Detail.Counted(R.plurals.sync_dirty_detail, changed.toInt()),
        failed = true,
    )

    // The server's own refusal, as opposed to `Diverged` above, which is this
    // application declining to merge what it fetched. The edits are still on
    // the device; what is needed is another sync, which takes the remote's
    // commits first and can then hand over these.
    is SyncException.Rejected -> SyncMessage(
        R.string.sync_failed_rejected,
        Detail.Worded(R.string.sync_rejected_detail, branch),
        failed = true,
    )

    // Neither is a failure of the connection: the server answered, and what
    // is missing is somebody to say it is the right server. The key travels
    // as the detail because it is the whole of what there is to compare.
    is SyncException.UnknownHost -> SyncMessage(
        R.string.sync_host_unknown,
        Detail.Verbatim(fingerprint),
    )

    is SyncException.HostChanged -> SyncMessage(
        R.string.sync_host_changed,
        Detail.Verbatim(fingerprint),
        failed = true,
    )

    // Worth its own wording because nothing was attempted: the address was
    // refused before a connection was opened, so retrying changes nothing and
    // the token did not leave the device.
    is SyncException.Address -> SyncMessage(
        R.string.sync_failed_address,
        Detail.Verbatim(detail),
        failed = true,
    )

    is SyncException.Repository -> SyncMessage(
        R.string.sync_failed_repository,
        Detail.Verbatim(detail),
        failed = true,
    )

    else -> SyncMessage(
        R.string.sync_failed_repository,
        message?.let(Detail::Verbatim),
        failed = true,
    )
}.withoutCredentials()

/**
 * Takes credentials out of whatever the core quoted.
 *
 * Applied to every sync message rather than to the variants that are known to
 * name a host: the detail is libgit2's own text, and which failures carry the
 * address in it is not something this side decides. A detail worded here
 * cannot hold an address at all, so it is left alone.
 */
private fun SyncMessage.withoutCredentials(): SyncMessage = when (val shown = detail) {
    is Detail.Verbatim -> copy(detail = Detail.Verbatim(maskCredentials(shown.text)))
    else -> this
}

/**
 * Maps a failure of the scan onto what the screen shows in place of an agenda.
 *
 * What the core says about it is diagnostics — an invalid directory, a path
 * that cannot be read — and travels as it came. A failure that carries no
 * message at all used to be reported by the name of its Java class, which
 * says nothing to whoever is holding the phone; the wording says instead
 * where the reason can be found.
 */
fun Throwable.toAgendaMessage(): SyncMessage = SyncMessage(
    R.string.agenda_failed,
    message?.takeIf(String::isNotBlank)?.let(Detail::Verbatim)
        ?: Detail.Worded(R.string.agenda_failed_unknown),
    failed = true,
)

/**
 * Maps a failed edit onto something worth reading.
 *
 * `Stale` is the one the user can act on: the file moved on under the agenda,
 * and the answer is to look again. The rest describe a file or a request the
 * application cannot work with, and the wording per variant is what says
 * which.
 *
 * No detail travels with any of them. What the core reports there is an
 * English sentence written for a log — `line 9 is past the end of notes.md` —
 * and under a translated heading it makes a message that is half in each
 * language while saying no more than the heading already did. It goes to
 * logcat instead; see [AgendaViewModel.apply].
 *
 * The result belongs to a channel of its own — see
 * [AgendaViewModel.editIssue]. The banner under the header is about the
 * checkout, and "the task could not be changed" is not.
 */
fun Throwable.toEditMessage(): SyncMessage = when (this) {
    is EditException.Stale -> SyncMessage(R.string.edit_failed_stale, failed = true)

    is EditException.NoPlanningLine -> SyncMessage(R.string.edit_failed_no_planning, failed = true)

    is EditException.Unsupported -> SyncMessage(R.string.edit_failed_unsupported, failed = true)

    is EditException.NotFound -> SyncMessage(R.string.edit_failed_missing, failed = true)

    // Actionable in its own way: the file is there and readable, it is simply
    // in another encoding, and converting it is what fixes every edit to it.
    is EditException.NotUtf8 -> SyncMessage(R.string.edit_failed_encoding, failed = true)

    else -> SyncMessage(R.string.edit_failed, failed = true)
}
