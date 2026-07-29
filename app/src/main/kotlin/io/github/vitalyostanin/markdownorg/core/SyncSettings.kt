package io.github.vitalyostanin.markdownorg.core

import android.content.Context

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

    var authorName: String

    var authorEmail: String

    /** When the last successful sync finished, or `0` if there was none. */
    var lastSyncedAt: Long

    val isConfigured: Boolean get() = !remoteUrl.isNullOrBlank()
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

    /**
     * Who edits made here are attributed to.
     *
     * A device carries no git configuration to read, and the defaults name
     * the application rather than guessing at the person holding the phone:
     * a wrong name in the history of someone's notes is worse than an
     * obviously generic one. Both are overridable from the settings screen.
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

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val FILE = "sync"
        const val KEY_URL = "remote_url"
        const val KEY_BRANCH = "branch"
        const val KEY_TOKEN = "token"
        const val KEY_AUTHOR_NAME = "author_name"
        const val KEY_AUTHOR_EMAIL = "author_email"
        const val KEY_LAST_SYNCED = "last_synced_at"
        const val DEFAULT_AUTHOR_NAME = "markdown-org"
        const val DEFAULT_AUTHOR_EMAIL = "markdown-org@localhost"
    }
}
