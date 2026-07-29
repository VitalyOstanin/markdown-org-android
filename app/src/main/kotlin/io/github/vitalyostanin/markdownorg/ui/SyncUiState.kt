package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.RemoteUrlProblem
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
 * What to tell the user about the last attempt.
 *
 * A resource id and an optional detail rather than a finished string: the
 * wording belongs to the resources, and the detail is whatever the core said.
 */
data class SyncMessage(
    @param:StringRes val text: Int,
    val detail: String? = null,
    val failed: Boolean = false,
    val source: MessageSource = MessageSource.SYNC,
)

/**
 * What the message is about.
 *
 * Both kinds share one line of the interface, and they arrive from coroutines
 * that run independently, so which one may replace which has to be a rule
 * rather than whichever finished last — see [notDisplacedBy].
 */
enum class MessageSource { SYNC, EDIT }

/**
 * Keeps a failed edit on screen when the sync has nothing to say about it.
 *
 * A sync that went through does not answer "the task could not be changed",
 * and overwriting the line would leave the user believing the edit landed. A
 * failed sync does replace it: that one is about the same directory and is
 * the more immediate problem.
 */
fun SyncMessage?.notDisplacedBy(next: SyncMessage?): SyncMessage? = when {
    this != null && failed && source == MessageSource.EDIT && next?.failed != true -> this
    else -> next
}

/** What to tell the user about an address that cannot be used. */
fun RemoteUrlProblem.toMessage(): SyncMessage = when (this) {
    RemoteUrlProblem.EMPTY -> SyncMessage(R.string.settings_url_empty, failed = true)
    RemoteUrlProblem.SCHEME -> SyncMessage(R.string.settings_url_scheme, failed = true)
    RemoteUrlProblem.INCOMPLETE -> SyncMessage(R.string.settings_url_incomplete, failed = true)
}

/**
 * Maps a failure onto something worth reading.
 *
 * The variants are kept apart because the answer differs: a rejected token
 * has to be replaced, an unreachable host is worth retrying, and a diverged
 * or dirty checkout needs a decision the application cannot make.
 */
fun Throwable.toSyncMessage(): SyncMessage = when (this) {
    is SyncException.Auth -> SyncMessage(R.string.sync_failed_auth, detail, failed = true)
    is SyncException.Network -> SyncMessage(R.string.sync_failed_network, detail, failed = true)
    is SyncException.Diverged -> SyncMessage(R.string.sync_failed_diverged, detail, failed = true)
    is SyncException.Dirty -> SyncMessage(R.string.sync_failed_dirty, detail, failed = true)
    // Worth its own wording because nothing was attempted: the address was
    // refused before a connection was opened, so retrying changes nothing and
    // the token did not leave the device.
    is SyncException.Address -> SyncMessage(R.string.sync_failed_address, detail, failed = true)
    is SyncException.Repository -> SyncMessage(R.string.sync_failed_repository, detail, failed = true)
    else -> SyncMessage(R.string.sync_failed_repository, message, failed = true)
}.withoutCredentials()

/**
 * Takes credentials out of whatever the core quoted.
 *
 * Applied to every sync message rather than to the variants that are known to
 * name a host: the detail is libgit2's own text, and which failures carry the
 * address in it is not something this side decides.
 */
private fun SyncMessage.withoutCredentials(): SyncMessage =
    detail?.let { copy(detail = maskCredentials(it)) } ?: this

/**
 * Maps a failed edit onto something worth reading.
 *
 * `Stale` is the one the user can act on: the file moved on under the agenda,
 * and the answer is to look again. The rest describe a file or a request the
 * application cannot work with, and say so with whatever the core reported.
 */
fun Throwable.toEditMessage(): SyncMessage = when (this) {
    is EditException.Stale -> SyncMessage(R.string.edit_failed_stale, detail, failed = true)
    is EditException.NoPlanningLine ->
        SyncMessage(R.string.edit_failed_no_planning, detail, failed = true)

    is EditException.Unsupported ->
        SyncMessage(R.string.edit_failed_unsupported, detail, failed = true)

    is EditException.NotFound -> SyncMessage(R.string.edit_failed_missing, detail, failed = true)
    // Actionable in its own way: the file is there and readable, it is simply
    // in another encoding, and converting it is what fixes every edit to it.
    is EditException.NotUtf8 ->
        SyncMessage(R.string.edit_failed_encoding, detail, failed = true)

    // Nothing the user can do differently about these three, so they share
    // the general wording with the core's own detail under it. Listed one by
    // one rather than as the sealed parent, which carries no detail of its own.
    is EditException.InvalidPriority -> SyncMessage(R.string.edit_failed, detail, failed = true)
    is EditException.InvalidDate -> SyncMessage(R.string.edit_failed, detail, failed = true)
    is EditException.Io -> SyncMessage(R.string.edit_failed, detail, failed = true)
    else -> SyncMessage(R.string.edit_failed, message, failed = true)
}.copy(source = MessageSource.EDIT)
