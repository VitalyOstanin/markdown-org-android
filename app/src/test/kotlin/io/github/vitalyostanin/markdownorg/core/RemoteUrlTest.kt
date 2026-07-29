package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which addresses the application will accept as the remote.
 *
 * The check exists because saving one empties the working copy, and edits
 * made on the device are committed locally and never pushed: a typo takes the
 * only copy of them with it.
 */
class RemoteUrlTest {

    @Test
    fun anHttpsRepositoryIsAccepted() {
        assertNull(remoteUrlProblem("https://gitlab.com/user/notes.git"))
        assertNull(remoteUrlProblem("https://gitlab.com/group/subgroup/notes.git"))
        assertNull(remoteUrlProblem("https://github.com/user/notes"))
        assertNull(remoteUrlProblem("https://git.example.org:8443/user/notes.git"))
    }

    @Test
    fun aLocalRepositoryIsAccepted() {
        // What a repository copied onto the device looks like, and what the
        // core's own tests clone from.
        assertNull(remoteUrlProblem("/sdcard/notes.git"))
        assertNull(remoteUrlProblem("file:///sdcard/notes.git"))
    }

    @Test
    fun surroundingSpaceIsIgnored() {
        // Pasted addresses arrive with it, and the stored value is trimmed
        // anyway.
        assertNull(remoteUrlProblem("  https://gitlab.com/user/notes.git  "))
    }

    @Test
    fun cleartextHttpIsRefused() {
        // The token travels in the request, and the platform's ban on
        // cleartext does not reach the TLS stack vendored into the core.
        assertEquals(RemoteUrlProblem.SCHEME, remoteUrlProblem("http://gitlab.com/user/notes.git"))
    }

    @Test
    fun sshIsRefused() {
        // git2 is vendored with the https feature and no ssh, so this would
        // fail inside the core with a message about an unsupported transport.
        assertEquals(RemoteUrlProblem.SCHEME, remoteUrlProblem("git@gitlab.com:user/notes.git"))
        assertEquals(
            RemoteUrlProblem.SCHEME,
            remoteUrlProblem("ssh://git@gitlab.com/user/notes.git"),
        )
    }

    @Test
    fun anAddressWithoutARepositoryIsRefused() {
        assertEquals(RemoteUrlProblem.INCOMPLETE, remoteUrlProblem("https://gitlab.com"))
        assertEquals(RemoteUrlProblem.INCOMPLETE, remoteUrlProblem("https://gitlab.com/"))
        assertEquals(RemoteUrlProblem.INCOMPLETE, remoteUrlProblem("https://"))
        assertEquals(RemoteUrlProblem.INCOMPLETE, remoteUrlProblem("file://"))
    }

    @Test
    fun anEmptyAddressIsItsOwnCase() {
        // The form disables saving rather than showing an error over a field
        // nobody has typed in yet.
        assertEquals(RemoteUrlProblem.EMPTY, remoteUrlProblem(""))
        assertEquals(RemoteUrlProblem.EMPTY, remoteUrlProblem("   "))
    }
}
