package io.github.vitalyostanin.markdownorg.ui

import android.util.Log
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.AgendaLoader
import io.github.vitalyostanin.markdownorg.core.CollectionInUse
import io.github.vitalyostanin.markdownorg.core.CollectionProblem
import io.github.vitalyostanin.markdownorg.core.CollectionsInUse
import io.github.vitalyostanin.markdownorg.core.NotesArea
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.core.NotesCollectionsPreferences
import io.github.vitalyostanin.markdownorg.core.NotesLocation
import io.github.vitalyostanin.markdownorg.core.NotesSyncer
import io.github.vitalyostanin.markdownorg.core.NotesWriter
import io.github.vitalyostanin.markdownorg.core.RemoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.SyncPreferences
import io.github.vitalyostanin.markdownorg.core.SyncRun
import io.github.vitalyostanin.markdownorg.core.collectionProblem
import io.github.vitalyostanin.markdownorg.core.mainFileProblem
import io.github.vitalyostanin.markdownorg.core.markdownFiles
import io.github.vitalyostanin.markdownorg.core.noteFileProblem
import io.github.vitalyostanin.markdownorg.core.notesPathProblem
import io.github.vitalyostanin.markdownorg.core.remoteUrlProblem
import io.github.vitalyostanin.markdownorg.core.single
import io.github.vitalyostanin.markdownorg.core.splitCredentials
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.markdown_org_ffi.Adoption
import uniffi.markdown_org_ffi.Scope
import uniffi.markdown_org_ffi.SyncException
import uniffi.markdown_org_ffi.WritePosition
import uniffi.markdown_org_ffi.generateSshKey
import java.io.File

/**
 * The settings screen behind the agenda: where a collection's notes live, what
 * they are synced with, and what happened the last time they were.
 *
 * Held apart from the agenda because it answers a different question. The
 * agenda reads the notes and draws them; this writes down where they are read
 * from, and every operation here ends in the same two places -- the stored
 * preferences and a checkout on disk -- rather than in what is on screen. What
 * they share is the moment a sync brings new notes in, which is why [rescan]
 * is passed rather than called from here directly.
 *
 * @param collections the collections in use, each with its working copy
 * @param stored where the set of collections is kept between launches
 * @param agenda the loader whose cache a moved or synced checkout invalidates
 * @param ownNotes the directory a collection falls back to
 * @param storageGranted whether the device has granted access to shared storage
 * @param scope the model's scope, so a sync ends when the model does
 * @param io where reads and writes of the checkout are done
 * @param editingId which collection the screen is about
 * @param onCollectionSet what to do when the set of collections is rewritten
 * @param onCollections what to do when the collections in use have to be rebuilt
 * @param rescan asks for the agenda again, after notes have moved
 * @param onNotesMoved says that the notes on disk are not the ones the
 *   reminders were planned against
 */
@Suppress("LongParameterList", "TooManyFunctions")
class NotesSettings(
    private val collections: CollectionsInUse,
    private val stored: NotesCollectionsPreferences,
    private val agenda: AgendaLoader,
    private val ownNotes: File,
    private val storageGranted: () -> Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val editingId: () -> String,
    private val onCollectionSet: (List<NotesCollection>) -> Unit,
    private val onCollections: (List<NotesCollection>) -> Unit,
    private val rescan: () -> Unit,
    private val onNotesMoved: () -> Unit,
) {

    /** What the last sync of each collection answered with. */
    private val _syncState = MutableStateFlow(SyncUiState())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    /** The sync in flight, so a second press does not start a second one. */
    private var syncJob: Job? = null

    /** The collection the settings screen is about. */
    private val editing: CollectionInUse
        get() = collections.entries.firstOrNull { it.collection.id == editingId() }
            ?: collections.single

    /** The collection the screen is about, as it is written down. */
    val collection: NotesCollection get() = editing.collection

    private val notes: NotesArea get() = editing.area
    private val settings: SyncPreferences get() = editing.settings
    private val editor: NotesWriter get() = editing.editor
    private val sync: NotesSyncer get() = editing.syncer

    /**
     * Fetch and fast-forward every collection that has a remote, then rebuild
     * the agenda over what arrived.
     *
     * One after another rather than together: each holds its own working copy
     * while it runs, and a phone syncing three repositories at once spends the
     * radio on all of them and finishes none of them sooner. A collection that
     * fails does not stop the ones after it — the notes of the others did come
     * forward, and saying otherwise would send the user looking for a fetch
     * that worked.
     */
    fun syncNow() {
        if (syncJob?.isActive == true) {
            return
        }

        val configured = collections.entries.filter { it.settings.isConfigured }
        if (configured.isEmpty()) {
            return
        }

        // What the previous run answered goes before this one starts: a line
        // per collection that is left over from an hour ago describes a
        // checkout nobody has looked at since.
        _syncState.update { it.copy(runs = emptyList()) }

        syncJob = scope.launch {
            configured.forEach { collection -> runSync(collection) }
        }
    }

    /**
     * Stores the remote and gets the directory into a state it can be synced
     * from.
     *
     * Nothing here empties anything, and nothing anywhere else does either. A
     * directory that already holds notes and no git is taken in as it stands —
     * `adopt` makes what is in it the first commit and only then adds the
     * remote — and a directory holding neither is cloned into.
     *
     * A checkout of another remote is the one case saving cannot resolve, and
     * it stays unresolved: it says so and leaves both alone. The files are the
     * user's, the commits in them may exist nowhere else, and the way on is
     * another directory or a hand emptying this one — not a button here.
     *
     * [token] empty means "leave the stored one alone", since the form never
     * shows it. That cannot hold across a change of host, though — a token is
     * issued by one server and has no business reaching another — so the
     * stored one is dropped along with the URL it belonged to. [dropToken]
     * clears it outright, which is the only way to go back to a remote that
     * needs no credentials.
     *
     * An address that carries credentials of its own — which is how a clone
     * command copied from a repository page reads — is split before anything
     * else: the secret belongs in the token, not in the field the screen shows
     * in the clear.
     *
     * [sshKey] follows the token's rule, with one difference: it is not
     * dropped when the address changes. A token is issued by one server; a key
     * belongs to the device and is added to as many servers as its owner
     * likes. What is dropped with the address is the server key it was known
     * by — that one is about the host and about nothing else.
     */
    @Suppress("LongParameterList")
    fun saveSettings(
        url: String,
        branch: String,
        token: String,
        dropToken: Boolean = false,
        notesPath: String = editing.collection.path,
        name: String = editing.collection.name,
        inbox: String = editing.collection.inbox,
        writeAt: WritePosition = editing.collection.writeAt,
        mainFile: String = editing.collection.mainFile,
        sshKey: String = "",
        sshPassphrase: String = "",
        dropKey: Boolean = false,
    ) {
        // Before anything is stored, and against the same rule the set itself
        // is checked by: a collection with no name is one the filter offers as
        // a blank chip and the rows carry a blank mark.
        val named = name.trim()
        if (named.isEmpty()) {
            _syncState.update { it.copy(message = CollectionProblem.NAME_EMPTY.toMessage()) }
            return
        }

        // And on the same terms: a collection whose receiving file cannot hold
        // a task is one where the button that writes one has nowhere to go.
        val receiving = inbox.trim()
        noteFileProblem(receiving)?.let { problem ->
            _syncState.update { it.copy(message = problem.toMessage()) }
            return
        }

        // The main file is checked the same way, with one difference: leaving
        // it unnamed is an answer, and what it costs is the offer to move an
        // entry there.
        val main = mainFile.trim()
        mainFileProblem(main)?.let { problem ->
            _syncState.update { it.copy(message = problem.toMessage()) }
            return
        }

        val split = splitCredentials(url)
        val address = split.url
        val secret = token.ifBlank { split.token.orEmpty() }

        // An empty address is not a failure but a form that is only about the
        // directory: notes already on the device need no remote, and the one
        // configured earlier — if any — is left exactly as it was.
        val problem = remoteUrlProblem(address).takeUnless { it == RemoteUrlProblem.EMPTY }
        if (problem != null) {
            // Nothing is stored and nothing is deleted: the address is checked
            // before the destructive part, not after the clone fails.
            _syncState.update { it.copy(message = problem.toMessage()) }
            return
        }

        // Checked here as well as on the form, and before anything is stored:
        // the form is one caller, and a directory that cannot hold the notes
        // must not become the one the next scan walks.
        val directoryProblem = notesPathProblem(notesPath, ownNotes, storageGranted())
            ?.toMessage()
        if (directoryProblem != null) {
            _syncState.update { it.copy(message = directoryProblem) }
            return
        }

        // Saving is work on the working copy, so it becomes the job that
        // stands for one: everything that asks "is a sync under way" — the
        // sync icon, the answers beside the banner — has to be told yes while
        // the directory is being moved and the address stored. Held in a local
        // first, because the job this is about to become cannot cancel itself.
        val running = syncJob
        syncJob = scope.launch {
            // A sync in flight owns the directory this is about to point
            // somewhere else, so it is stopped rather than raced with.
            //
            // Cancelling asks; it does not interrupt. The sync is inside a
            // call into the core, and that call returns when it returns — a
            // fetch on a stalled connection, at the outside, when the core's
            // own network timeouts expire. This waits for that, and it is why
            // the core has those timeouts rather than the operating system's.
            running?.cancelAndJoin()
            _syncState.update { it.copy(running = false) }

            if (!saveDirectory(notesPath, Collected(named, receiving, writeAt, main))) {
                return@launch
            }

            // The rest is about a remote, and there is none in the form. What
            // was stored before stays: clearing it here would be a way to lose
            // a repository by saving a directory.
            if (address.isEmpty()) {
                return@launch
            }

            saveRemote(address, branch, secret, dropToken, sshKey, sshPassphrase, dropKey)
        }
    }

    /**
     * Put the notes where the form says, under the name it gives them.
     *
     * Runs before the remote half of the same save: everything there reads the
     * checkout, and after a move that has to be the checkout in the new
     * directory. Returns whether the save may go on — a move that failed leaves
     * the rest untouched, because storing a remote against a directory the
     * notes are not in would clone into the old one.
     */
    private suspend fun saveDirectory(notesPath: String, collected: Collected): Boolean {
        if (moveNotes(notesPath).isFailure) {
            return false
        }

        // After the move, and over what the move stored: the two are edits to
        // the same set, and renaming against the set as it was would put the
        // old directory back.
        reviseEditing(collected)

        return true
    }

    /**
     * What the form says about the collection itself, apart from its remote.
     *
     * One object because they are stored together, in one revision of the set:
     * passing four values through the save would be four parameters of the
     * same three types, and a call that swapped two of them would compile.
     */
    private data class Collected(
        val name: String,
        val inbox: String,
        val writeAt: WritePosition,
        val mainFile: String,
    )

    /**
     * Store the address and the credentials, then take up the directory.
     *
     * The second half of a save, reached only with an address in the form. What
     * happens to the directory afterwards is decided from the checkout that is
     * already in it: a fetch into a checkout of this same remote, an adoption
     * of notes that are not in git yet, or a refusal to touch a checkout of
     * somewhere else.
     */
    @Suppress("LongParameterList")
    private suspend fun saveRemote(
        address: String,
        branch: String,
        secret: String,
        dropToken: Boolean,
        sshKey: String,
        sshPassphrase: String,
        dropKey: Boolean,
    ) {
        // Which host the stored token was issued for: the settings, not the
        // checkout. A directory holding no repository yet says nothing about
        // where the token came from.
        val configuredUrl = settings.remoteUrl

        // Read off disk rather than from the state: the state is filled in
        // asynchronously after launch, and saving before it arrives would
        // throw away a checkout that did not need to go.
        val previous = sync.status()
        if (previous.isFailure) {
            // The checkout is there but could not be read. Emptying it now
            // would delete commits that exist nowhere else, so the address
            // is stored and the directory left for a human to look at.
            storeRemote(address, branch, secret, dropToken, configuredUrl)
            storeKey(sshKey, sshPassphrase, dropKey)
            _syncState.update {
                it.copy(
                    configured = settings.isConfigured,
                    message = SyncMessage(R.string.sync_status_unreadable, failed = true),
                )
            }
            return
        }

        // Compared without the credentials the checkout's own `origin` may
        // carry: a clone made before those were split off names the same
        // repository, and treating it as another one would send the user
        // to a decision there is nothing to decide.
        val before = previous.getOrNull()?.url?.let { splitCredentials(it).url }
        val checkout = previous.getOrNull() != null
        storeRemote(address, branch, secret, dropToken, configuredUrl)
        storeKey(sshKey, sshPassphrase, dropKey)
        // An address was named, so this is no longer the store the user
        // said was local — whatever happens to the directory below.
        settings.storesLocally = false
        _syncState.update { it.copy(configured = settings.isConfigured) }

        when {
            // Somebody else's checkout, or this one pointed elsewhere. The
            // address is kept and the directory is not touched: emptying
            // it is what used to happen, and it took every commit that had
            // not been pushed with it. The message says the way on.
            checkout && before != settings.remoteUrl -> _syncState.update {
                it.copy(
                    message = SyncMessage(R.string.settings_other_checkout, failed = true),
                )
            }

            // Already a checkout of this remote: fetch into it, branch
            // change included — the core moves the checkout onto the new
            // branch without touching what is committed here.
            checkout -> startSync()

            // A directory with notes and no git: taken in as it stands.
            else -> startAdoption()
        }
    }

    /**
     * Keep the notes on this device and stop asking for a remote.
     *
     * The state was reachable by accident before — a directory with no address
     * is a plain directory — and read as "not set up yet" on every launch.
     * Said outright it is a way to use the application: no banner, no retry,
     * and a remote can still be added later without the notes going anywhere.
     */
    fun keepNotesLocal() {
        settings.storesLocally = true
        _syncState.update {
            it.copy(local = true, message = SyncMessage(R.string.settings_local_chosen))
        }
    }

    /**
     * Take the notes already in the directory into git and point them at the
     * configured remote.
     *
     * Runs in place of the clone when the directory holds notes: the files
     * stay where they are, and what happens next depends on what the remote
     * turns out to hold — see [Adoption].
     */
    private fun startAdoption() {
        syncJob = scope.launch {
            _syncState.update { it.copy(running = true, message = null) }

            val adopted = sync.adopt(settings)
            val status = sync.status()
                .onFailure { failure -> Log.w(TAG, "the checkout could not be read", failure) }
                .getOrNull()
            val host = adopted.hostInQuestion()

            _syncState.update { current ->
                current.copy(
                    configured = settings.isConfigured,
                    running = false,
                    repository = status,
                    lastSyncedAt = settings.lastSyncedAt,
                    message = adopted.fold(
                        onSuccess = Adoption::toMessage,
                        onFailure = Throwable::toSyncMessage,
                    ),
                    // The one outcome that needs an answer rather than a
                    // reading: both sides hold notes, and joining them is not
                    // something this application does by itself.
                    unrelated = (adopted.getOrNull() as? Adoption.Unrelated)?.branch,
                    pendingHost = host?.first,
                    pendingHostReplaces = host?.second,
                )
            }

            if (adopted.isSuccess) {
                agenda.invalidate()
                rescan()
            }
        }
    }

    /**
     * Answer a sync stopped by uncommitted changes: commit them and go again.
     *
     * The commit is the one every sync makes anyway; what the button adds is
     * a second attempt at it, for the state where it failed the first time —
     * a directory that momentarily could not be written to, an index another
     * process held. Nothing here forces anything: a commit that fails again
     * leaves the same message and the same offer.
     */
    fun settleAndSync() {
        if (syncJob?.isActive == true) {
            return
        }

        _syncState.update { it.copy(blockedByUncommitted = false) }
        syncNow()
    }

    /**
     * Answer the unrelated-histories question with "take what the server has".
     *
     * What was in the directory is not deleted: the core leaves it as a commit
     * on a branch of its own, readable by any git client.
     */
    fun takeRemoteNotes() {
        if (syncJob?.isActive == true) {
            return
        }

        syncJob = scope.launch {
            _syncState.update { it.copy(running = true, message = null) }

            val taken = sync.takeRemote(settings)
            _syncState.update { current ->
                current.copy(
                    running = false,
                    repository = taken.getOrNull()?.head ?: current.repository,
                    unrelated = if (taken.isSuccess) null else current.unrelated,
                    message = taken.fold(
                        onSuccess = { SyncMessage(R.string.sync_took_remote) },
                        onFailure = Throwable::toSyncMessage,
                    ),
                )
            }

            if (taken.isSuccess) {
                agenda.invalidate()
                rescan()
            }
        }
    }

    /**
     * Vouch for the server key the last attempt stopped on, and try again.
     *
     * The key is stored only here, on a press: an application that recorded
     * whatever answered the first time would pin nothing, since the first time
     * is exactly when a wrong server would be believed. What follows is the
     * attempt that was interrupted — a directory that is already a checkout
     * fetches, one that is not is taken in as it stands.
     */
    fun trustHost() {
        val fingerprint = _syncState.value.pendingHost ?: return
        if (syncJob?.isActive == true) {
            return
        }

        settings.knownHost = fingerprint
        _syncState.update {
            it.copy(pendingHost = null, pendingHostReplaces = null, message = null)
        }

        syncJob = scope.launch {
            if (sync.holdsRepository()) {
                startSync()
            } else {
                startAdoption()
            }
        }
    }

    /**
     * The server key an attempt is waiting on, and the one it would replace.
     *
     * Both failures mean the same thing to the screen — a question about who
     * answered — and differ only in how grave it is, which is what the second
     * half of the pair says.
     */
    private fun Result<*>.hostInQuestion(): Pair<String, String?>? =
        when (val failure = exceptionOrNull()) {
            is SyncException.UnknownHost -> failure.fingerprint to null
            is SyncException.HostChanged -> failure.fingerprint to failure.known
            else -> null
        }

    /**
     * Store the address and what is sent to it, dropping what belonged to the
     * address it replaces.
     *
     * The server key goes with the address rather than surviving it: `origin`
     * pointing somewhere else is a different server, and a key remembered for
     * the old one would vouch for whatever answers at the new.
     */
    private fun storeRemote(
        address: String,
        branch: String,
        secret: String,
        dropToken: Boolean,
        configuredUrl: String?,
    ) {
        val moved = configuredUrl != address
        settings.remoteUrl = address
        settings.branch = branch
        settings.token = tokenFor(secret, dropToken, changedHost = moved)
        if (moved) {
            settings.knownHost = null
        }
    }

    /**
     * Store the key an `ssh://` remote is reached with.
     *
     * Blank means "leave the stored one alone", the way a blank token does:
     * the form does not show a key it holds, so an empty field is silence
     * rather than an instruction. [dropKey] takes both halves — a passphrase
     * outliving the key it opens is worth nothing.
     */
    private fun storeKey(typed: String, passphrase: String, dropKey: Boolean) {
        when {
            dropKey -> {
                settings.sshKey = null
                settings.sshPassphrase = null
            }

            typed.isNotBlank() -> {
                settings.sshKey = typed
                settings.sshPassphrase = passphrase.ifEmpty { null }
            }

            // A passphrase typed against a key already stored: the key stays,
            // and this is how a wrong one gets corrected.
            passphrase.isNotEmpty() -> settings.sshPassphrase = passphrase
        }
    }

    /**
     * Which token to store: the one just typed, none, or the one already
     * there.
     *
     * Kept out of [saveSettings] because it is the whole of the rule and both
     * branches of that function apply it.
     */
    private fun tokenFor(typed: String, dropped: Boolean, changedHost: Boolean): String? = when {
        dropped -> null
        typed.isNotBlank() -> typed
        changedHost -> null
        else -> settings.token
    }

    /**
     * Points the working copy at the chosen directory, when the choice changed.
     *
     * Answers with a failure the caller stops on, having already put the
     * reason on screen: the whole of what saving does afterwards is about a
     * directory the notes are in, and there is nothing sensible to do with a
     * remote when the move did not happen.
     *
     * The directory is stored only after the move went through, so a path
     * that cannot be used is not what the application opens on next time.
     */
    private suspend fun moveNotes(path: String): Result<Unit> {
        val collection = editing.collection
        val chosen = path.trim().ifEmpty { null }?.let(::File) ?: ownNotes
        if (chosen.absolutePath == collection.path) {
            return Result.success(Unit)
        }

        // Against the other collections as well as against the filesystem: a
        // directory that is one of them, or sits inside one, would have every
        // note under it read twice — once per collection — and an edit would
        // then act on one of the two copies on screen.
        val others = collections.entries.map { it.collection }.filter { it.id != collection.id }
        val clash = collectionProblem(collection.name, chosen.absolutePath, others)
        if (clash != null) {
            _syncState.update { it.copy(message = clash.toMessage()) }
            return Result.failure(IllegalStateException("the directory clashes with a collection"))
        }

        val used = editing.area.useDirectory(chosen)
        if (used.isFailure) {
            // The sentence is about a directory on this device, and the
            // wording is in the resources; what the filesystem said about it
            // goes to the log.
            Log.w(TAG, "the notes directory could not be used", used.exceptionOrNull())
            _syncState.update {
                it.copy(message = SyncMessage(R.string.settings_notes_failed, failed = true))
            }
            return used
        }

        // Stored and put to work in one step: the set the walk reads and the
        // set the next launch reads are the same set, and a directory that
        // could not be used never becomes either.
        val moved = stored.collections.map { entry ->
            if (entry.id == collection.id) entry.copy(path = chosen.absolutePath) else entry
        }
        stored.collections = moved
        collections.use(moved)
        onCollectionSet(moved)
        // The notes held from the previous directory describe files this one
        // does not have. Dropped before anything reads them, for the same
        // reason the header below is cleared.
        agenda.invalidate()
        // What the header showed belongs to the directory that was left
        // behind; nothing is known about a checkout in the new one yet.
        _syncState.update { it.copy(repository = null) }
        // Ahead of the sync rather than after it: the agenda of what is
        // already in the new directory is on screen while the fetch runs, and
        // when no remote is configured it is all there is going to be.
        rescan()

        return used
    }

    /**
     * Give the collection being edited what the form says about it.
     *
     * Its directory is not among those settings, so the working copy and the
     * lock over it are the ones already in use — [CollectionsInUse.use] keeps
     * an entry whose directory has not moved.
     */
    private fun reviseEditing(collected: Collected) {
        val collection = editing.collection
        val revised = collection.copy(
            name = collected.name,
            inbox = collected.inbox,
            writeAt = collected.writeAt,
            mainFile = collected.mainFile,
        )
        if (revised == collection) {
            return
        }

        onCollections(
            stored.collections.map { entry ->
                if (entry.id == collection.id) {
                    entry.copy(
                        name = collected.name,
                        inbox = collected.inbox,
                        writeAt = collected.writeAt,
                        mainFile = collected.mainFile,
                    )
                } else {
                    entry
                }
            },
        )
    }

    /** Current settings, for filling the form. */
    fun currentSettings(): SyncForm = SyncForm(
        url = settings.remoteUrl.orEmpty(),
        branch = settings.branch.orEmpty(),
        hasToken = !settings.token.isNullOrBlank(),
        notesPath = editing.collection.path,
        name = editing.collection.name,
        inbox = editing.collection.inbox,
        writeAt = editing.collection.writeAt,
        mainFile = editing.collection.mainFile,
        hasKey = !settings.sshKey.isNullOrBlank(),
        publicKey = settings.sshPublicKey.orEmpty(),
        knownHost = settings.knownHost.orEmpty(),
    )

    /**
     * Make a key for this device, keeping the private half here.
     *
     * Stored as it is made rather than when the form is saved: the public half
     * has to be taken to a server before anything can be synced, and a key
     * shown but not kept would let someone add a key to their account that
     * this device no longer holds.
     *
     * A key made this way replaces whatever was stored, passphrase included:
     * the new one has none, and an old passphrase against a new key is a
     * failure with nothing on screen to explain it.
     */
    fun createSshKey() {
        val made = runCatching { generateSshKey(KEY_COMMENT) }
        made.onFailure { failure ->
            Log.w(TAG, "the key could not be made", failure)
            _syncState.update {
                it.copy(message = SyncMessage(R.string.settings_key_failed, failed = true))
            }
        }

        val key = made.getOrNull() ?: return
        settings.sshKey = key.privateKey
        settings.sshPassphrase = null
        settings.sshPublicKey = key.publicKey
        _syncState.update { it.copy(publicKey = key.publicKey) }
    }

    /** Sync the collection the settings screen is about. */
    private fun startSync() {
        if (!settings.isConfigured) {
            return
        }

        val collection = editing
        syncJob = scope.launch { runSync(collection) }
    }

    /**
     * Fetch, fast-forward and push one collection, reporting what happened.
     *
     * Written as one suspending step rather than as a job of its own, so that
     * syncing every collection is that step repeated: the working copies are
     * held one at a time and the screen is told after each.
     */
    private suspend fun runSync(collection: CollectionInUse) {
        // Named apart from the properties of the same shape: those read the
        // collection the settings screen is about, and this run is over the one
        // it was handed.
        val theirSettings = collection.settings
        val theirSyncer = collection.syncer

        _syncState.update { it.copy(running = true, message = null) }

        // An edit whose commit did not happen leaves the checkout dirty, and
        // the core refuses to fast-forward a dirty checkout. The core's commit
        // is idempotent, so this costs nothing when there is nothing to
        // commit.
        //
        // What it settles is not only this application's own unfinished
        // business: a note written in another editor is an uncommitted change
        // like any other, and without this it would sit here unsent while the
        // sync reported success. Whether anything was committed is carried on
        // to the message, because a commit the user did not ask for should not
        // appear in the history unannounced.
        val settled = collection.editor.commitPending()
            .onFailure { failure ->
                Log.w(TAG, "the uncommitted edits could not be committed", failure)
            }
            .getOrDefault(false)

        val outcome = theirSyncer.sync(theirSettings)
            // The path with the most ways to fail — the network, the
            // credentials, a host key, a history that diverged, a checkout
            // left dirty — and the only failure that used to leave nothing
            // behind it. What reaches the screen is a phrase assembled from
            // the resources, so afterwards neither the class of the failure
            // nor what the core said with it was anywhere. Safe to write down:
            // the address is stored with the credentials already split off it
            // (`splitCredentials`, where the settings are saved).
            .onFailure { failure -> Log.w(TAG, "the sync failed", failure) }
        outcome.getOrNull()?.pushFailure?.let { failure ->
            // Beside a fetch that went through, so it never reaches the branch
            // above: the run is reported as a success with a note, and the
            // refusal itself is the half the screen says least about.
            Log.w(TAG, "the push was refused", failure)
        }
        // A sync that went through hands back the state of the checkout it
        // wrote. Asking again walks every file in the working copy, untracked
        // ones included, for an answer already in hand; only a failed sync has
        // nothing to report and has to read.
        val status = outcome.getOrNull()?.head
            ?: theirSyncer.status()
                .onFailure { failure -> Log.w(TAG, "the checkout could not be read", failure) }
                .getOrNull()
        val message = outcome.fold(
            onSuccess = { run -> run.toMessage(settledEdits = settled) },
            onFailure = Throwable::toSyncMessage,
        )

        val host = outcome.hostInQuestion()
        _syncState.update { current ->
            current.copy(
                configured = theirSettings.isConfigured,
                running = false,
                // The header is about the collection the settings screen is
                // about, so a run over the others leaves what it says alone.
                repository = status.takeIf { collection.collection.id == editingId() }
                    ?: current.repository,
                lastSyncedAt = theirSettings.lastSyncedAt,
                message = message,
                // The collection's own answer, kept apart from the last one of
                // the run: over several repositories the header alone cannot
                // say which of them failed.
                runs = current.runs.filterNot { it.id == collection.collection.id } +
                    CollectionRun(
                        id = collection.collection.id,
                        name = collection.collection.name,
                        message = message,
                    ),
                pendingHost = host?.first,
                pendingHostReplaces = host?.second,
                // Reached only when the commit above could not be made:
                // otherwise the tree is clean by the time the fetch runs. The
                // screen offers to try both again, because nothing else in the
                // application moves a checkout in this state.
                blockedByUncommitted = outcome.exceptionOrNull() is SyncException.Dirty,
            )
        }

        if (outcome.isSuccess) {
            // A fetch rewrites whatever it fast-forwarded over, and which
            // files those were is not something this side is told. The held
            // notes are stale as a whole, so the agenda that follows walks
            // the directories again.
            agenda.invalidate()
            rescan()
            onNotesMoved()
        }
    }

    /** Read the state of the checkout into the header of the settings screen. */
    fun readCheckout() {
        scope.launch {
            // A checkout that cannot be read leaves the header saying nothing
            // about it, which is all the screen can do here — but the reason
            // has to end up somewhere, and this is the only place it exists.
            val status = sync.status()
                .onFailure { failure -> Log.w(TAG, "the checkout could not be read", failure) }
                .getOrNull()
            _syncState.update {
                it.copy(
                    configured = settings.isConfigured,
                    local = settings.storesLocally,
                    repository = status,
                    lastSyncedAt = settings.lastSyncedAt,
                )
            }
        }
    }

    /** Say something on the settings screen that happened outside this class. */
    fun say(message: SyncMessage) {
        _syncState.update { it.copy(message = message) }
    }

    private companion object {
        private const val TAG = "NotesSettings"

        /**
         * What a key made here is labelled with on the server it is added to.
         *
         * Not the device's own name: that is the user's to give, it says who
         * they are to whoever reads the list of keys, and asking a phone for
         * it needs a permission this application has no other use for.
         */
        private const val KEY_COMMENT = "markdown-org"
    }
}
