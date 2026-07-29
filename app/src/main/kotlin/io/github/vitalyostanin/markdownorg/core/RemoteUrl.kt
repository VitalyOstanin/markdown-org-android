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
 * `https` is the one network scheme the core is built for — git2 is vendored
 * with the `https` feature and no ssh. `http` is refused on purpose: the token
 * travels in the request, and the platform's cleartext ban does not reach the
 * vendored stack. A `file://` URL and a plain absolute path stay allowed;
 * that is what a repository copied onto the device looks like.
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
        value.startsWith(HTTPS) -> httpsProblem(value.removePrefix(HTTPS))
        else -> RemoteUrlProblem.SCHEME
    }
}

/** A host and something after it: `https://host` alone names no repository. */
private fun httpsProblem(rest: String): RemoteUrlProblem? {
    val separator = rest.indexOf('/')
    val host = rest.substringBefore('/')
    val path = rest.substringAfter('/', missingDelimiterValue = "")

    return if (separator < 0 || host.isEmpty() || path.isEmpty()) {
        RemoteUrlProblem.INCOMPLETE
    } else {
        null
    }
}

private const val HTTPS = "https://"
private const val FILE = "file://"
