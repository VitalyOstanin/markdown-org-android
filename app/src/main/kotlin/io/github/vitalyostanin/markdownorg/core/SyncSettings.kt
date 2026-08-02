package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * Where the notes come from, as everything above the storage sees it.
 *
 * An interface rather than the class itself so the orchestration can be
 * exercised without a device: the implementation is Android preferences.
 */
interface SyncPreferences {

    var remoteUrl: String?

    /** Branch to track, or `null` for the remote's default. */
    var branch: String?

    var token: String?

    /** Private key for an `ssh://` remote, in the form the core reads it. */
    var sshKey: String?

    /** Passphrase of [sshKey], when it has one. */
    var sshPassphrase: String?

    /**
     * The public half of a key made on this device, as one OpenSSH line.
     *
     * Stored rather than derived so the settings screen can offer it again:
     * it is what gets pasted into a server's settings page, and needing it a
     * second time is the ordinary case. Empty for a key pasted in by hand —
     * its owner has the public half wherever they made it.
     */
    var sshPublicKey: String?

    /**
     * The server key the remote is known by, as `SHA256:<base64>`.
     *
     * SSH pins the server by this and nothing else, so it is stored per
     * settings rather than derived: an address whose host key is not this one
     * is not the server the notes were meant for, whatever it says about
     * itself. Dropped along with the token when the address changes.
     */
    var knownHost: String?

    var authorName: String

    var authorEmail: String

    /** When the last successful sync finished, or `0` if there was none. */
    var lastSyncedAt: Long

    /**
     * The notes live on this device and nowhere else, said outright.
     *
     * Kept apart from an empty [remoteUrl], which is the same state left
     * unstated: a fresh install has no remote because nothing has been set up
     * yet, and this is the answer "there is nothing to set up". What depends
     * on the difference is what the screens do — a store the user chose asks
     * for no address and runs no retry timer, while one nobody has configured
     * still invites them to.
     */
    var storesLocally: Boolean

    /** A remote to sync with. */
    val isConfigured: Boolean get() = !remoteUrl.isNullOrBlank()

    /** Set up at all: either a remote, or the local store chosen on purpose. */
    val isSettled: Boolean get() = isConfigured || storesLocally
}

/**
 * Where the notes come from, and what is needed to reach them.
 *
 * Plain [android.content.SharedPreferences] hold the token. That is now the
 * documented advice: `EncryptedSharedPreferences` is deprecated in favour of
 * the ordinary kind, since the file already lives in the application's
 * private storage on a device with file-based encryption. The manifest sets
 * `allowBackup="false"`, so the token does not leave the device either.
 */
class SyncSettings(context: Context) : SyncPreferences {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var remoteUrl: String?
        get() = preferences.getString(KEY_URL, null)
        set(value) = preferences.edit().putString(KEY_URL, value?.trim()?.ifEmpty { null }).apply()

    override var branch: String?
        get() = preferences.getString(KEY_BRANCH, null)
        set(
            value,
        ) = preferences.edit().putString(KEY_BRANCH, value?.trim()?.ifEmpty { null }).apply()

    override var token: String?
        get() = preferences.getString(KEY_TOKEN, null)
        set(
            value,
        ) = preferences.edit().putString(KEY_TOKEN, value?.trim()?.ifEmpty { null }).apply()

    override var sshKey: String?
        get() = preferences.getString(KEY_SSH_KEY, null)
        set(
            value,
        ) = preferences.edit().putString(KEY_SSH_KEY, value?.trim()?.ifEmpty { null }).apply()

    override var sshPassphrase: String?
        get() = preferences.getString(KEY_SSH_PASSPHRASE, null)
        // Not trimmed: a passphrase is what it is, spaces at either end
        // included, and quietly changing it turns a working key into a broken
        // one with nothing on screen to say why.
        set(value) = preferences.edit()
            .putString(KEY_SSH_PASSPHRASE, value?.ifEmpty { null })
            .apply()

    override var sshPublicKey: String?
        get() = preferences.getString(KEY_SSH_PUBLIC, null)
        set(
            value,
        ) = preferences.edit().putString(KEY_SSH_PUBLIC, value?.trim()?.ifEmpty { null }).apply()

    override var knownHost: String?
        get() = preferences.getString(KEY_KNOWN_HOST, null)
        set(
            value,
        ) = preferences.edit().putString(KEY_KNOWN_HOST, value?.trim()?.ifEmpty { null }).apply()

    /**
     * Who edits made here are attributed to.
     *
     * A device carries no git configuration to read, and the defaults name
     * the application rather than guessing at the person holding the phone:
     * a wrong name in the history of someone's notes is worse than an
     * obviously generic one. Neither is editable from the settings screen
     * yet — the setters are here for the screen that will offer them.
     */
    override var authorName: String
        get() = preferences.getString(KEY_AUTHOR_NAME, null) ?: DEFAULT_AUTHOR_NAME
        set(value) = preferences.edit()
            .putString(KEY_AUTHOR_NAME, value.trim().ifEmpty { null })
            .apply()

    override var authorEmail: String
        get() = preferences.getString(KEY_AUTHOR_EMAIL, null) ?: DEFAULT_AUTHOR_EMAIL
        set(value) = preferences.edit()
            .putString(KEY_AUTHOR_EMAIL, value.trim().ifEmpty { null })
            .apply()

    override var lastSyncedAt: Long
        get() = preferences.getLong(KEY_LAST_SYNCED, 0)
        set(value) = preferences.edit().putLong(KEY_LAST_SYNCED, value).apply()

    override var storesLocally: Boolean
        get() = preferences.getBoolean(KEY_LOCAL_STORE, false)
        set(value) = preferences.edit().putBoolean(KEY_LOCAL_STORE, value).apply()

    /**
     * Forget every stored setting, the token included.
     *
     * Nothing in the application calls this: a change of remote drops the
     * token that belonged to the old one, and that is the whole of what the
     * interface offers. It exists so a test can start from an empty store on
     * a device whose preferences outlive the process.
     */
    @VisibleForTesting
    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val FILE = "sync"
        const val KEY_URL = "remote_url"
        const val KEY_BRANCH = "branch"
        const val KEY_TOKEN = "token"
        const val KEY_SSH_KEY = "ssh_key"
        const val KEY_SSH_PASSPHRASE = "ssh_passphrase"
        const val KEY_SSH_PUBLIC = "ssh_public_key"
        const val KEY_KNOWN_HOST = "known_host"
        const val KEY_AUTHOR_NAME = "author_name"
        const val KEY_AUTHOR_EMAIL = "author_email"
        const val KEY_LAST_SYNCED = "last_synced_at"
        const val KEY_LOCAL_STORE = "stores_locally"
        const val DEFAULT_AUTHOR_NAME = "markdown-org"
        const val DEFAULT_AUTHOR_EMAIL = "markdown-org@localhost"
    }
}
