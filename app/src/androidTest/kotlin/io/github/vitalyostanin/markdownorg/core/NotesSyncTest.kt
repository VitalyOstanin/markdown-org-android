package io.github.vitalyostanin.markdownorg.core

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The sync as the application sees it, across the boundary into the core.
 *
 * These go through the native library, so they cover the projection of its
 * results as well: a checkout that holds no repository has to arrive as
 * success with nothing in it, and a failure has to arrive as a failure —
 * telling the two apart is what keeps the working copy from being wiped.
 */
class NotesSyncTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = NotesStore(context)
    private val sync = NotesSync(context, store)

    private class Settings(
        override var remoteUrl: String? = null,
        override var branch: String? = null,
        override var token: String? = null,
        override var authorName: String = "markdown-org",
        override var authorEmail: String = "markdown-org@localhost",
        override var lastSyncedAt: Long = 0,
    ) : SyncPreferences

    @Before
    @After
    fun clean() = runBlocking {
        store.reset()
    }

    @Test
    fun anEmptyCheckoutHoldsNoRepositoryRatherThanBeingUnreadable() = runBlocking {
        store.ensureSeeded(LocalDate.of(2026, 7, 29)) { false }

        val status = sync.status()

        assertTrue(status.isSuccess)
        assertNull(status.getOrNull())
    }

    @Test
    fun aSyncWithoutARemoteFailsInsteadOfReachingTheCore() = runBlocking {
        val result = sync.sync(Settings(remoteUrl = null))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun aRemoteThatIsNotThereFailsWithoutMovingTheTimeOfTheLastSuccess() = runBlocking {
        val settings = Settings(remoteUrl = "file:///data/local/tmp/markdown-org-absent.git")

        val result = sync.sync(settings)

        assertTrue(result.isFailure)
        assertEquals(0, settings.lastSyncedAt)
    }
}
