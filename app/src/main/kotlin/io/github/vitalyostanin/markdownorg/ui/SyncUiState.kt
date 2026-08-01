package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.NotesPathProblem
import io.github.vitalyostanin.markdownorg.core.RemoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.SyncRun
import io.github.vitalyostanin.markdownorg.core.maskCredentials
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
)

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
