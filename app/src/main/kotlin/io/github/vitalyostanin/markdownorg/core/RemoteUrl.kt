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
 * What actually refuses an address is `ensure_supported` in the core; this
 * exists because the screen has to say so where the address was typed, before
 * anything is stored. The two are held to each other by `RemoteUrlAgreement`,
 * which asks both about the same address: whatever is accepted here the core
 * accepts as well. The other direction is allowed to differ — an address the
 * core would take but that names no repository (`https://host`, `file://`) is
 * refused here as incomplete, because a remote saved from it fetches nothing
 * and the working copy is emptied either way.
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

    // The slash is refused on both sides of the `@`: `notes/me@host:repo.git`
    // is a path on the device that happens to hold one, not an address, and
    // the core reads it that way -- this used to accept it and leave the
    // refusal to a sync that had already emptied the working copy.
    return if (user.isEmpty() ||
        user.contains('/') ||
        host.isEmpty() ||
        host.contains('/') ||
        path.isEmpty()
    ) {
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
 * Where the server the notes come from issues a token and takes a public key.
 *
 * Both are pages of a browser, and the phone has one: the settings screen asks
 * for a token and shows half a key, and until now said nothing about where
 * either is answered. What is needed is not a login — the pages are opened as
 * the user, in their own browser, already signed in there or not.
 */
data class CredentialPages(val token: String, val key: String)

/**
 * The pages of the host in [url], or `null` when the address names none.
 *
 * Only the two hosts the ecosystem is used with have their paths spelled out
 * here. For anything else the answer is the host itself: a self-hosted GitLab
 * lives under a different prefix per version, a Gitea puts the pages somewhere
 * else again, and a link to a page that is not there is worse than a link to
 * the front door of a server whose menu the user knows.
 *
 * A path on the device and a `file://` URL name no host and get nothing.
 */
fun credentialPages(url: String): CredentialPages? = when (val host = remoteHost(url)) {
    null -> null

    "github.com" -> CredentialPages(
        token = "https://github.com/settings/tokens",
        key = "https://github.com/settings/keys",
    )

    "gitlab.com" -> CredentialPages(
        token = "https://gitlab.com/-/user_settings/personal_access_tokens",
        key = "https://gitlab.com/-/user_settings/ssh_keys",
    )

    else -> CredentialPages(token = "https://$host/", key = "https://$host/")
}

/**
 * The host an address is fetched from, lowercased, or `null` for a local one.
 *
 * The port is left out on purpose: an `ssh://host:2222/` remote is served over
 * ssh on that port, and the pages are on the web server, which is on its own.
 */
private fun remoteHost(url: String): String? {
    val value = url.trim()
    val authority = when {
        value.isEmpty() || value.startsWith("/") || value.startsWith(FILE) -> return null

        value.startsWith(HTTPS) -> value.removePrefix(HTTPS).substringBefore('/')

        value.startsWith(SSH) -> value.removePrefix(SSH).substringBefore('/')

        // `git@host:path`, the scp spelling; anything else with a scheme is an
        // address this application refuses anyway.
        !value.contains(SEPARATOR) && value.contains('@') ->
            value.substringAfter('@').substringBefore(':')

        else -> return null
    }

    return authority.substringAfterLast('@').substringBefore(':')
        .lowercase()
        .ifEmpty { null }
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
