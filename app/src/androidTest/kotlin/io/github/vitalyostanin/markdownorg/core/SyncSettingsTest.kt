package io.github.vitalyostanin.markdownorg.core

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the settings screen writes and what the sync reads back.
 *
 * Values arrive from text fields, so they carry whatever the keyboard left
 * behind: a trailing space in a URL makes a clone fail with an address that
 * looks correct on screen, and an emptied field has to become "unset" rather
 * than the empty string, which `isConfigured` would otherwise accept.
 */
class SyncSettingsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = SyncSettings(context)

    @Before
    @After
    fun clean() {
        settings.clear()
    }

    @Test
    fun surroundingSpaceIsNotPartOfTheAddress() {
        settings.remoteUrl = "  https://gitlab.com/user/notes.git  "
        settings.branch = " main "
        settings.token = "\tglpat-000\n"

        assertEquals("https://gitlab.com/user/notes.git", settings.remoteUrl)
        assertEquals("main", settings.branch)
        assertEquals("glpat-000", settings.token)
    }

    @Test
    fun anEmptiedFieldIsUnsetRatherThanEmpty() {
        settings.remoteUrl = "https://gitlab.com/user/notes.git"
        settings.remoteUrl = "   "

        assertNull(settings.remoteUrl)
        assertFalse(settings.isConfigured)
    }

    @Test
    fun aRemoteIsWhatMakesTheSettingsConfigured() {
        assertFalse(settings.isConfigured)

        settings.remoteUrl = "https://gitlab.com/user/notes.git"

        assertTrue(settings.isConfigured)
    }

    @Test
    fun theAuthorHasADefaultThatNamesTheApplication() {
        // A device carries no git configuration, and a wrong name in someone's
        // history is worse than an obviously generic one.
        assertEquals("markdown-org", settings.authorName)
        assertEquals("markdown-org@localhost", settings.authorEmail)

        settings.authorName = " Ada "
        settings.authorEmail = " ada@example.test "

        assertEquals("Ada", settings.authorName)
        assertEquals("ada@example.test", settings.authorEmail)
    }

    @Test
    fun anEmptiedAuthorFallsBackToTheDefaultRatherThanToNothing() {
        settings.authorName = "Ada"
        settings.authorName = "  "

        assertEquals("markdown-org", settings.authorName)
    }

    @Test
    fun theTimeOfTheLastSyncStartsAtZeroAndSurvivesAWrite() {
        assertEquals(0, settings.lastSyncedAt)

        settings.lastSyncedAt = 1_764_000_000_000

        assertEquals(1_764_000_000_000, settings.lastSyncedAt)
    }

    @Test
    fun clearingTakesTheTokenWithIt() {
        settings.remoteUrl = "https://gitlab.com/user/notes.git"
        settings.token = "glpat-000"
        settings.lastSyncedAt = 1_764_000_000_000

        settings.clear()

        assertNull(settings.remoteUrl)
        assertNull(settings.token)
        assertEquals(0, settings.lastSyncedAt)
        assertEquals("markdown-org", settings.authorName)
    }
}
