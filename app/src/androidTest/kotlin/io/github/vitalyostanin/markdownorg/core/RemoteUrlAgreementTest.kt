package io.github.vitalyostanin.markdownorg.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.markdown_org_ffi.remoteUrlSupported

/**
 * That the screen never accepts an address the core would refuse.
 *
 * Two readings of one rule: [remoteUrlProblem] names which part of an address
 * is wrong, in the language the screen is drawn in, and `ensure_supported` in
 * the core is what actually refuses to fetch over one. The promise between
 * them used to be a sentence of documentation, and by the time it was checked
 * the two had drifted apart in three ways -- one of them in the direction
 * that costs something: the screen accepted `notes/me@host:repo.git`, saving
 * it emptied the working copy, and the sync then refused the address.
 *
 * Instrumented rather than a unit test because the core is a native library:
 * on the JVM there is nothing to ask.
 *
 * The addresses are assembled from parts rather than listed, so the case
 * nobody thought to write down is covered as well.
 *
 * Both sides are asked about the address in the form it is stored in --
 * trimmed, and with whatever credentials it carried moved into the token,
 * which is what the settings screen does before it checks anything. Asking
 * the core about the raw field would compare two different addresses: the
 * core reads what it is given literally, and a pasted address arrives with a
 * space around it.
 */
@RunWith(AndroidJUnit4::class)
class RemoteUrlAgreementTest {

    @Test
    fun theScreenAcceptsNothingTheCoreWouldRefuse() {
        val disagreed = stored().filter { url ->
            remoteUrlProblem(url) == null && !remoteUrlSupported(url)
        }

        assertEquals(
            "addresses the screen took and the core would not",
            emptyList<String>(),
            disagreed,
        )
    }

    /**
     * The other direction, which is allowed to differ and is named here so a
     * change to either side is noticed.
     *
     * An address the core would take but that names no repository is refused
     * by the screen: a remote saved from it fetches nothing, and saving any
     * remote empties the working copy.
     */
    @Test
    fun theScreenIsStricterOnlyWhereAnAddressNamesNoRepository() {
        val stricter = stored().filter { url ->
            remoteUrlProblem(url) != null && remoteUrlSupported(url)
        }

        assertTrue(
            "the screen refuses an address that names a repository: $stricter",
            stricter.all { url -> remoteUrlProblem(url) == RemoteUrlProblem.INCOMPLETE },
        )
    }

    /**
     * What the core makes of an address nobody trimmed, which is the case the
     * screen answers by trimming: the core reads what it is given literally,
     * so a space around an otherwise good address is an address it refuses.
     */
    @Test
    fun theCoreReadsAnAddressLiterallyAndTheScreenIsWhatTrimsIt() {
        assertEquals(false, remoteUrlSupported(" https://host/notes.git "))
        assertNull(remoteUrlProblem(" https://host/notes.git "))
        assertTrue(remoteUrlSupported(splitCredentials(" https://host/notes.git ").url))
    }

    @Test
    fun aPathWithAnAtSignInItIsNotAnAddressOnEitherSide() {
        // The case the agreement was drifting on: a slash before the `@`
        // makes it a directory on the device, and there is no such directory.
        assertEquals(RemoteUrlProblem.INCOMPLETE, remoteUrlProblem("notes/me@host:repo.git"))
        assertEquals(false, remoteUrlSupported("notes/me@host:repo.git"))
    }

    @Test
    fun theAddressesTheApplicationIsUsedWithAreTakenByBoth() {
        for (url in USED) {
            assertNull("the screen refused $url", remoteUrlProblem(url))
            assertTrue("the core refused $url", remoteUrlSupported(url))
        }
    }

    /** Every address the parts below spell, as the settings would store it. */
    private fun stored(): List<String> = addresses().map { url -> splitCredentials(url).url }

    /** Every address the parts below spell, including the ones nobody types. */
    private fun addresses(): List<String> = buildList {
        for (scheme in SCHEMES) {
            for (userinfo in USERINFO) {
                for (host in HOSTS) {
                    for (path in PATHS) {
                        for (space in SPACE) {
                            add("$space$scheme$userinfo$host$path$space")
                        }
                    }
                }
            }
        }
    }

    private companion object {

        val SCHEMES = listOf("", "https://", "http://", "ssh://", "git://", "file://")

        val USERINFO = listOf("", "git@", "x-access-token:secret@", "notes/me@")

        val HOSTS = listOf("", "host", "git.example.org:2222", "ho/st")

        val PATHS = listOf("", "/notes.git", ":notes.git", "/group/sub/notes.git")

        val SPACE = listOf("", " ")

        /** What a repository page offers and what the device holds. */
        val USED = listOf(
            "https://github.com/user/notes.git",
            "https://x-access-token:secret@github.com/user/notes.git",
            "ssh://git@git.example.org:2222/user/notes.git",
            "git@github.com:user/notes.git",
            "/sdcard/notes.git",
            "file:///sdcard/notes.git",
        )
    }
}
