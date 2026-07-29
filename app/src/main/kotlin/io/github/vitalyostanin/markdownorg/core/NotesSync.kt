package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import uniffi.markdown_org_ffi.RepoStatus
import uniffi.markdown_org_ffi.SyncOutcome
import uniffi.markdown_org_ffi.SyncRequest
import uniffi.markdown_org_ffi.loadCaBundle
import uniffi.markdown_org_ffi.repositoryStatus
import uniffi.markdown_org_ffi.syncRepository

/** Keeps the working copy in step with a remote. */
interface NotesSyncer {

    suspend fun sync(settings: SyncPreferences): Result<SyncOutcome>

    /**
     * State of the checkout, without contacting the remote.
     *
     * Success with `null` means the directory holds no repository; a failure
     * means the state could not be read. The two are kept apart because the
     * caller acts on them differently: the first invites a clone, the second
     * must not, since the wipe that precedes one would take the only copy of
     * whatever is already committed there.
     */
    suspend fun status(): Result<RepoStatus?>
}

/**
 * The working copy of the notes, kept in step with a remote.
 *
 * The core clones and fast-forwards; it never merges. That is deliberate
 * while the application only reads: a conflict has no resolution the user
 * could act on from here, so it is reported instead.
 */
class NotesSync(private val context: Context, private val notes: NotesArea) : NotesSyncer {

    /**
     * Clone on the first call, fast-forward afterwards. Runs off the main
     * thread and under the lock on the notes directory: this rewrites the
     * working copy the agenda reads and an edit commits to.
     */
    override suspend fun sync(settings: SyncPreferences): Result<SyncOutcome> {
        val url = settings.remoteUrl
            ?: return Result.failure(IllegalStateException("no remote configured"))
        // Handed over before the lock, and only until the core has it: 180 kB
        // off the assets is not work the rest of the application should be
        // waiting behind, and the certificate store lives as long as the
        // process.
        val certificates = runCatching { loadCertificates() }
        certificates.exceptionOrNull()?.let { return Result.failure(it) }

        return notes.exclusive {
            // A phone loses the network mid-request often enough that one
            // attempt is not an answer. Only the transient failure is
            // repeated — see `worthRetrying`.
            retryingTransientFailures {
                runCatching {
                    syncRepository(
                        SyncRequest(
                            dir = notes.root.absolutePath,
                            url = url,
                            token = settings.token,
                            branch = settings.branch,
                        ),
                    )
                }
            }.onSuccess {
                settings.lastSyncedAt = System.currentTimeMillis()
            }
        }
    }

    override suspend fun status(): Result<RepoStatus?> = notes.exclusive {
        runCatching { repositoryStatus(notes.root.absolutePath) }
    }

    /**
     * Give the core the certificate authorities, once per process.
     *
     * Android has no `/etc/ssl/certs`, which is where the TLS stack vendored
     * into the core looks by default. The contents go across rather than a
     * path because the core cannot open the file either: its OpenSSL is built
     * without stdio. Around 180 kB, read off the assets and copied over the
     * FFI boundary — which is why it happens once rather than on every sync.
     *
     * A failure is not remembered: the core does not keep a half-filled store
     * either, so the next sync tries again rather than connecting without
     * certificates.
     */
    private fun loadCertificates() {
        if (loaded) {
            return
        }

        val pem = context.assets.open(CA_BUNDLE).use { it.readBytes().decodeToString() }
        loadCaBundle(pem)
        loaded = true
    }

    @Volatile
    private var loaded: Boolean = false

    private companion object {
        const val CA_BUNDLE = "cacert.pem"
    }
}
