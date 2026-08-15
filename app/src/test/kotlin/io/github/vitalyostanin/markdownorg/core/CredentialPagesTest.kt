package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which page the settings form offers to open, for the address it was given.
 *
 * The whole point is the phone: a token is issued on the server and a public
 * key is pasted there, and neither is reachable from a form that only says the
 * words "access token". What the button must never do is lead somewhere that
 * is not the server the notes come from.
 */
class CredentialPagesTest {

    @Test
    fun gitHubIsOfferedItsOwnTwoPages() {
        val pages = credentialPages("https://github.com/user/notes.git")

        assertEquals("https://github.com/settings/tokens", pages?.token)
        assertEquals("https://github.com/settings/keys", pages?.key)
    }

    @Test
    fun gitLabIsOfferedItsOwnTwoPages() {
        val pages = credentialPages("https://gitlab.com/group/notes.git")

        assertEquals(
            "https://gitlab.com/-/user_settings/personal_access_tokens",
            pages?.token,
        )
        assertEquals("https://gitlab.com/-/user_settings/ssh_keys", pages?.key)
    }

    @Test
    fun anotherHostIsOfferedItsFrontPage() {
        // A self-hosted GitLab, a Gitea, a Forgejo: each puts these pages
        // somewhere of its own, and a guess at the path leads to a 404 where
        // the front page leads to a menu the user knows.
        val pages = credentialPages("https://git.example.org/user/notes.git")

        assertEquals("https://git.example.org/", pages?.token)
        assertEquals("https://git.example.org/", pages?.key)
    }

    @Test
    fun anSshAddressNamesTheSameHost() {
        // Both spellings of ssh, and the scp one is what a repository page
        // offers to copy.
        assertEquals(
            "https://github.com/settings/keys",
            credentialPages("ssh://git@github.com/user/notes.git")?.key,
        )
        assertEquals(
            "https://github.com/settings/keys",
            credentialPages("git@github.com:user/notes.git")?.key,
        )
    }

    @Test
    fun theWebPageIsNotOnTheSshPort() {
        // The remote is served over ssh on 2222; the pages are on the web
        // server, which is not.
        assertEquals(
            "https://git.example.org/",
            credentialPages("ssh://git@git.example.org:2222/user/notes.git")?.token,
        )
    }

    @Test
    fun theHostIsComparedInLowerCase() {
        assertEquals(
            "https://github.com/settings/tokens",
            credentialPages("https://GitHub.COM/user/notes.git")?.token,
        )
    }

    @Test
    fun credentialsInTheAddressAreNotPartOfTheHost() {
        // A clone command copied from a repository page carries them, and the
        // form is offered such an address before it is split.
        assertEquals(
            "https://github.com/settings/tokens",
            credentialPages("https://x-access-token:secret@github.com/user/notes.git")?.token,
        )
    }

    @Test
    fun anAddressWithNoHostIsOfferedNothing() {
        // The notes on this device, and the empty field that means the same.
        assertNull(credentialPages("/sdcard/notes"))
        assertNull(credentialPages("file:///sdcard/notes.git"))
        assertNull(credentialPages(""))
        assertNull(credentialPages("   "))
    }

    @Test
    fun surroundingSpaceIsIgnored() {
        assertEquals(
            "https://github.com/settings/tokens",
            credentialPages("  https://github.com/user/notes.git  ")?.token,
        )
    }
}
