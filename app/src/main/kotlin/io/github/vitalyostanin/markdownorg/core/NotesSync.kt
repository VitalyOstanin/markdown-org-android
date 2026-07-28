package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.markdown_org_ffi.RepoStatus
import uniffi.markdown_org_ffi.SyncOutcome
import uniffi.markdown_org_ffi.SyncRequest
import uniffi.markdown_org_ffi.repositoryStatus
import uniffi.markdown_org_ffi.syncRepository

/**
 * The working copy of the notes, kept in step with a remote.
 *
 * The core clones and fast-forwards; it never merges. That is deliberate
 * while the application only reads: a conflict has no resolution the user
 * could act on from here, so it is reported instead.
 */
class NotesSync(private val context: Context, private val root: File) {

    /**
     * Clone on the first call, fast-forward afterwards. Runs off the main
     * thread: this is network and filesystem work.
     */
    suspend fun sync(settings: SyncSettings): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        val url = settings.remoteUrl
            ?: return@withContext Result.failure(IllegalStateException("no remote configured"))

        runCatching {
            syncRepository(
                SyncRequest(
                    dir = root.absolutePath,
                    url = url,
                    token = settings.token,
                    branch = settings.branch,
                    caBundlePem = caBundle(),
                )
            )
        }.onSuccess {
            settings.lastSyncedAt = System.currentTimeMillis()
        }
    }

    /** State of the checkout, without contacting the remote. */
    suspend fun status(): RepoStatus? = withContext(Dispatchers.IO) {
        runCatching { repositoryStatus(root.absolutePath) }.getOrNull()
    }

    /**
     * The certificate authorities, as the PEM text of the bundled asset.
     *
     * Android has no `/etc/ssl/certs`, which is where the TLS stack vendored
     * into the core looks by default. The contents go across rather than a
     * path because the core cannot open the file either: its OpenSSL is built
     * without stdio. Around 180 kB, read once per process.
     */
    private fun caBundle(): String {
        cached?.let { return it }

        val pem = context.assets.open(CA_BUNDLE).use { it.readBytes().decodeToString() }
        cached = pem
        return pem
    }

    @Volatile
    private var cached: String? = null

    private companion object {
        const val CA_BUNDLE = "cacert.pem"
    }
}
