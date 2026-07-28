package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import io.github.vitalyostanin.markdownorg.R
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
)

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
    is SyncException.Repository -> SyncMessage(R.string.sync_failed_repository, detail, failed = true)
    else -> SyncMessage(R.string.sync_failed_repository, message, failed = true)
}
