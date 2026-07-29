package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * git accepts credentials inside the address, and the address is the one field
 * of the settings shown in the clear. Whatever arrives that way is moved into
 * the token, which is stored the same but never displayed.
 */
class RemoteCredentialsTest {

    @Test
    fun aTokenWrittenIntoTheAddressIsTakenOutOfIt() {
        val split = splitCredentials("https://x-access-token:ghp_secret@git.example.org/notes.git")

        assertEquals("https://git.example.org/notes.git", split.url)
        assertEquals("ghp_secret", split.token)
    }

    @Test
    fun anAddressCarryingOnlyATokenIsReadTheSameWay() {
        // What a copied clone command usually looks like: no username at all.
        val split = splitCredentials("https://ghp_secret@git.example.org/notes.git")

        assertEquals("https://git.example.org/notes.git", split.url)
        assertEquals("ghp_secret", split.token)
    }

    @Test
    fun anOrdinaryAddressIsLeftAlone() {
        val split = splitCredentials("https://git.example.org/notes.git")

        assertEquals("https://git.example.org/notes.git", split.url)
        assertNull(split.token)
    }

    @Test
    fun aPathOnTheDeviceIsNotAnAddressWithCredentials() {
        // An `@` in a directory name must not be read as a userinfo section.
        val split = splitCredentials("/data/user/0/notes@home")

        assertEquals("/data/user/0/notes@home", split.url)
        assertNull(split.token)
    }

    @Test
    fun theCoreSaysWhichHostAndTheCredentialsGoOutOfThatToo() {
        assertEquals(
            "failed to connect to https://***@git.example.org/notes.git",
            maskCredentials("failed to connect to https://x:ghp_secret@git.example.org/notes.git"),
        )
    }

    @Test
    fun textWithNoCredentialsInItIsUnchanged() {
        val detail = "2 file(s) changed since the last commit"

        assertEquals(detail, maskCredentials(detail))
    }
}
