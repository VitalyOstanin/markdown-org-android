package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.markdown_org_ffi.Adoption
import uniffi.markdown_org_ffi.CommitAuthor
import uniffi.markdown_org_ffi.RepoStatus
import uniffi.markdown_org_ffi.SyncOutcome
import uniffi.markdown_org_ffi.SyncRequest
import uniffi.markdown_org_ffi.adoptDirectory
import uniffi.markdown_org_ffi.loadCaBundle
import uniffi.markdown_org_ffi.pushChanges
import uniffi.markdown_org_ffi.repositoryStatus
import uniffi.markdown_org_ffi.syncRepository
import uniffi.markdown_org_ffi.takeRemoteNotes

/**
 * What one press of the sync button did, both ways.
 *
 * The push is reported beside the fetch rather than in place of it: the two
 * halves fail independently, and a fetch that brought the notes forward stays
 * worth saying so even when the push after it was refused.
 */
data class SyncRun(
    /** What the fetch did, and the checkout as it left it. */
    val fetched: SyncOutcome,
    /** Commits handed to the remote afterwards. */
    val pushed: UInt = 0u,
    /** The checkout after both halves. */
    val head: RepoStatus = fetched.head,
    /** Why the push did not happen, when the fetch itself went through. */
    val pushFailure: Throwable? = null,
)

/** Keeps the working copy in step with a remote. */
interface NotesSyncer {

    suspend fun sync(settings: SyncPreferences): Result<SyncRun>

    /**
     * Start tracking the notes already in the directory, in the remote named
     * by [settings].
     *
     * For the directory that has been holding notes with no git at all. What
     * is in it becomes the first commit and stays on disk; nothing is emptied.
     * The answer says what happened to it — see [Adoption].
     */
    suspend fun adopt(settings: SyncPreferences): Result<Adoption>

    /**
     * Take the remote's notes over a directory whose own were adopted and turn
     * out to share no history with them.
     *
     * Only after the user has been asked: this replaces what is on disk. The
     * notes that were there stay in the repository, on a branch of their own.
     */
    suspend fun takeRemote(settings: SyncPreferences): Result<SyncOutcome>

    /** Whether the notes directory is a git checkout at all. */
    suspend fun holdsRepository(): Boolean

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
     * Clone on the first call, fast-forward afterwards, then hand the remote
     * whatever was committed here. Runs off the main thread and under the lock
     * on the notes directory: this rewrites the working copy the agenda reads
     * and an edit commits to.
     *
     * In that order because it is the order that works: a push is only ever a
     * fast-forward of the remote branch, so anything the remote gained in the
     * meantime has to be here first. A push refused after a successful fetch
     * is reported through [SyncRun.pushFailure] rather than as a failed sync —
     * the notes did come forward, and saying otherwise would send the user
     * looking for a fetch that did not fail.
     */
    override suspend fun sync(settings: SyncPreferences): Result<SyncRun> {
        val url = settings.remoteUrl
            ?: return Result.failure(IllegalStateException("no remote configured"))
        // Handed over before the lock on the directory, and off the main
        // thread: 180 kB out of the assets is not work the rest of the
        // application should be waiting behind, and it is not work the frame
        // showing "syncing" should be waiting behind either. The certificate
        // store lives as long as the process, so this is once.
        val certificates = runCatching { loadCertificates() }
        certificates.exceptionOrNull()?.let {
            // The store is filled under a lock, so this call waits, and a sync
            // the reader dropped while it waited answers with a cancellation.
            // `runCatching` catches that along with everything else; handed
            // back as a failure it would report a sync that failed where the
            // reader simply left, so it goes on out instead.
            if (it is CancellationException) throw it

            return Result.failure(it)
        }

        val request = request(url, settings)

        return notes.exclusive {
            // A phone loses the network mid-request often enough that one
            // attempt is not an answer. Only the transient failure is
            // repeated — see `worthRetrying`.
            retryingTransientFailures {
                runCatching { syncRepository(request) }
            }.map { fetched ->
                handOver(request, fetched)
            }.onSuccess {
                settings.lastSyncedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * Give the remote what was committed here, if anything was.
     *
     * The count comes from the checkout the fetch just left, so a remote that
     * is level with this one costs nothing beyond the comparison the fetch
     * already made. The retry is the fetch's: a push over a connection that
     * dropped is the one failure another attempt can fix, and a refusal is not.
     */
    private suspend fun handOver(request: SyncRequest, fetched: SyncOutcome): SyncRun {
        if (fetched.head.unpushed == 0u) {
            return SyncRun(fetched)
        }

        return retryingTransientFailures {
            runCatching { pushChanges(request) }
        }.fold(
            onSuccess = { pushed ->
                SyncRun(fetched, pushed = pushed.commitsPushed, head = pushed.head)
            },
            // The fetch stands: the notes came forward, and only the way back
            // was closed. Reported, not thrown, so the caller can say both.
            onFailure = { failure -> SyncRun(fetched, pushFailure = failure) },
        )
    }

    override suspend fun adopt(settings: SyncPreferences): Result<Adoption> {
        val url = settings.remoteUrl
            ?: return Result.failure(IllegalStateException("no remote configured"))
        val certificates = runCatching { loadCertificates() }
        certificates.exceptionOrNull()?.let {
            // Cancellation goes on out rather than being reported as a failed
            // sync, as above.
            if (it is CancellationException) throw it

            return Result.failure(it)
        }

        return notes.exclusive {
            retryingTransientFailures {
                runCatching {
                    adoptDirectory(
                        request = request(url, settings),
                        author = CommitAuthor(settings.authorName, settings.authorEmail),
                    )
                }
            }.onSuccess {
                settings.lastSyncedAt = System.currentTimeMillis()
            }
        }
    }

    override suspend fun takeRemote(settings: SyncPreferences): Result<SyncOutcome> {
        val url = settings.remoteUrl
            ?: return Result.failure(IllegalStateException("no remote configured"))

        return notes.exclusive {
            // No retry and no certificates: this touches no network at all —
            // what it writes out came down with the fetch that preceded it.
            runCatching { takeRemoteNotes(request(url, settings)) }
        }
    }

    // Qualified rather than imported: the member below it has the same name,
    // and reading which of the two is called should not depend on arity.
    override suspend fun holdsRepository(): Boolean = notes.exclusive {
        uniffi.markdown_org_ffi.holdsRepository(notes.root.absolutePath)
    }

    override suspend fun status(): Result<RepoStatus?> = notes.exclusive {
        runCatching { repositoryStatus(notes.root.absolutePath) }
    }

    private fun request(url: String, settings: SyncPreferences) = SyncRequest(
        dir = notes.root.absolutePath,
        url = url,
        token = settings.token,
        branch = settings.branch,
        sshKey = settings.sshKey,
        sshPassphrase = settings.sshPassphrase,
        knownHost = settings.knownHost,
    )

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
    private suspend fun loadCertificates() {
        CertificateStore.fill(context)
    }

    /**
     * Whether the core has been given the certificates, for the process.
     *
     * On the object rather than on an instance, because that is what the core
     * side of it is: `load_ca_bundle` writes into a store the whole library
     * shares. A flag per instance had the second [NotesSync] — the settings
     * screen builds one of its own — read the assets and copy 180 kB across
     * the boundary again for a store that was already filled.
     */
    private object CertificateStore {

        @Volatile
        private var filled: Boolean = false

        /**
         * A coroutine lock rather than a monitor: what is held across it is the
         * read of 180 kB and the parsing of 119 certificates on the core side,
         * and `synchronized` would hold a thread of the IO pool for all of it
         * while a second sync stood on another one waiting.
         */
        private val lock = Mutex()

        suspend fun fill(context: Context) {
            if (filled) {
                return
            }

            // Locked so that two syncs starting together do the reading once.
            // Checked again inside: the first of them may have finished
            // between the read above and the lock.
            lock.withLock {
                if (filled) {
                    return
                }

                // Off the main thread, which is where a sync is asked for: this
                // is a file out of the assets and a parse of every authority in
                // it, and it lands on the frame that puts "syncing" on screen.
                withContext(Dispatchers.IO) {
                    val pem = context.assets.open(CA_BUNDLE).use { it.readBytes().decodeToString() }
                    loadCaBundle(pem)
                }
                filled = true
            }
        }

        private const val CA_BUNDLE = "cacert.pem"
    }
}
