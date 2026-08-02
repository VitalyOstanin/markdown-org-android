package io.github.vitalyostanin.markdownorg.core

/** Why an address cannot be used as the remote. */
enum class RemoteUrlProblem {

    /** Nothing was entered. */
    EMPTY,

    /** A scheme the core cannot fetch over, or one that would leak the token. */
    SCHEME,

    /** The right scheme, but nothing usable after it. */
    INCOMPLETE,
}

/**
 * Checks an address before anything is done with it.
 *
 * Worth doing here rather than leaving to the core, because saving a remote
 * empties the working copy: edits are committed locally and never pushed, so
 * a typo in the address would take the only copy of them with it.
 *
 * `https` and `ssh` are the network schemes the core is built for. `http` is
 * refused on purpose: the token travels in the request, and the platform's
 * cleartext ban does not reach the vendored stack. `git://` is refused for
 * more — it neither encrypts nor authenticates anything. A `file://` URL and
 * a plain absolute path stay allowed; that is what a repository copied onto
 * the device looks like.
 *
 * The rules here mirror `ensure_supported` in the core, which is what
 * actually refuses an address. This one exists because the screen has to say
 * so where the address was typed, before anything is stored.
 *
 * Returns `null` when the address is usable.
 */
fun remoteUrlProblem(url: String): RemoteUrlProblem? {
    val value = url.trim()

    return when {
        value.isEmpty() -> RemoteUrlProblem.EMPTY

        value.startsWith("/") -> null

        value.startsWith(FILE) ->
            if (value.startsWith("$FILE/")) null else RemoteUrlProblem.INCOMPLETE

        value.startsWith(HTTPS) -> hostAndPath(value.removePrefix(HTTPS))

        value.startsWith(SSH) -> hostAndPath(value.removePrefix(SSH))

        // `git@host:path` — ssh spelled the scp way, which is how a repository
        // page offers it. Recognised by shape: a user, a host with no slash in
        // it, and a path after the colon.
        value.contains(SEPARATOR) -> RemoteUrlProblem.SCHEME

        value.contains('@') -> scpProblem(value)

        else -> RemoteUrlProblem.SCHEME
    }
}

/** A host and something after it: `https://host` alone names no repository. */
private fun hostAndPath(rest: String): RemoteUrlProblem? {
    val separator = rest.indexOf('/')
    val host = rest.substringBefore('/')
    val path = rest.substringAfter('/', missingDelimiterValue = "")

    return if (separator < 0 || host.isEmpty() || path.isEmpty()) {
        RemoteUrlProblem.INCOMPLETE
    } else {
        null
    }
}

/** `user@host:path`, with all three parts present. */
private fun scpProblem(value: String): RemoteUrlProblem? {
    val user = value.substringBefore('@')
    val rest = value.substringAfter('@')
    val host = rest.substringBefore(':')
    val path = rest.substringAfter(':', missingDelimiterValue = "")

    return if (user.isEmpty() || host.isEmpty() || host.contains('/') || path.isEmpty()) {
        RemoteUrlProblem.INCOMPLETE
    } else {
        null
    }
}

/** An address with whatever credentials it carried taken out of it. */
data class SplitRemoteUrl(
    val url: String,
    /** What stood in the userinfo section, or `null` when there was none. */
    val token: String?,
)

/**
 * Moves credentials out of the address and into the token.
 *
 * A clone command copied from a repository page reads
 * `https://x-access-token:<token>@host/repo.git`, and git accepts it as it
 * stands. Kept out of the stored address on purpose: the address is the one
 * setting shown in the clear, in a monospace field the width of the screen,
 * while the token field is masked and never read back.
 *
 * Only an address with a scheme is looked at — an `@` in a directory name on
 * the device is part of the name.
 *
 * `ssh://` is left alone, and so is `git@host:path`: what stands before the
 * `@` there is the login name, not a secret. Moving it into the token field
 * would both store a password that is not one and leave an address the server
 * refuses to log anybody in with.
 */
fun splitCredentials(url: String): SplitRemoteUrl {
    val value = url.trim()
    val scheme = value.indexOf(SEPARATOR)
    if (scheme < 0 || value.startsWith(SSH)) {
        return SplitRemoteUrl(value, null)
    }

    val start = scheme + SEPARATOR.length
    val authority = value.substring(start).substringBefore('/')
    val at = authority.lastIndexOf('@')
    if (at < 0) {
        return SplitRemoteUrl(value, null)
    }

    val userinfo = authority.substring(0, at)

    return SplitRemoteUrl(
        url = value.substring(0, start) + value.substring(start + at + 1),
        // `user:token` is the usual shape, but a token on its own is what a
        // copied command often holds; either way what follows the colon, or
        // the whole of it, is the secret.
        token = userinfo.substringAfter(':', missingDelimiterValue = userinfo).ifEmpty { null },
    )
}

/**
 * Hides credentials in text on its way to the screen.
 *
 * The core passes libgit2's own words through, and those quote the address the
 * request went to — which is the address as `origin` holds it, credentials and
 * all, for a checkout cloned before [splitCredentials] existed or made
 * elsewhere.
 */
fun maskCredentials(text: String): String = text.replace(CREDENTIALS, "$1***@")

/** `://` up to the `@` that closes a userinfo section, on one line. */
private val CREDENTIALS = Regex("""(://)[^/\s@]+@""")

private const val HTTPS = "https://"
private const val SSH = "ssh://"
private const val FILE = "file://"
private const val SEPARATOR = "://"
