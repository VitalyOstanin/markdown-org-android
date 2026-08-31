package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which settings a typed word names.
 *
 * The catalogue is the list the screen filters itself by, and it is a list
 * apart from the screen for one reason: a heading has to be drawn or dropped
 * before the items under it are, and a section cannot know whether anything
 * inside it matched until it has been asked.
 */
class SettingsSearchTest {

    /** Text of a resource in a test that has no resources: the id itself. */
    private fun ids(id: Int) = "text-$id"

    @Test
    fun `an empty query leaves the screen as it was`() {
        val match = settingsMatch("   ", ::ids)

        assertTrue(match.shows("settings-url"))
        assertTrue(match.shows(SettingsPart.REMINDERS))
        assertFalse(match.nothingFound)
    }

    @Test
    fun `a word of a label names its item and nothing else`() {
        val match = settingsMatch("needle") { id ->
            if (id == R.string.settings_inbox) "The needle file" else "something else"
        }

        assertTrue(match.shows("settings-inbox"))
        assertFalse(match.shows("settings-url"))
        assertTrue(match.shows(SettingsPart.NOTES))
        assertFalse(match.shows(SettingsPart.AGENDA))
    }

    @Test
    fun `a word of the line under an item finds it too`() {
        // The reader remembers what a setting does rather than what it is
        // called: "moved into another file" is the support text of the place
        // entries are written at, and not a word of its label.
        val match = settingsMatch("needle") { id ->
            if (id == R.string.settings_write_at_support) "moved needle" else "something else"
        }

        assertTrue(match.shows("settings-write-at"))
        assertFalse(match.shows("settings-inbox"))
    }

    @Test
    fun `case and the letter yo are not something to get right`() {
        val match = settingsMatch("НАЧАЛО НЕДЕЛИ") { id ->
            if (id == R.string.settings_week_start) "начало недёли" else "something else"
        }

        assertTrue(match.shows("settings-week-start"))
    }

    @Test
    fun `a heading that matches carries its whole section`() {
        val match = settingsMatch("needle") { id ->
            if (id == R.string.settings_reminders) "needle" else "something else"
        }

        assertTrue(match.shows(SettingsPart.REMINDERS))
        assertTrue(match.shows("settings-reminders-lead"))
        assertFalse(match.shows("settings-url"))
    }

    @Test
    fun `a query nothing answers to says so`() {
        val match = settingsMatch("needle") { "something else" }

        assertTrue(match.nothingFound)
        assertFalse(match.shows("settings-url"))
        assertFalse(match.shows(SettingsPart.NOTES))
    }

    @Test
    fun `every item in the catalogue is reachable by its own label`() {
        // What keeps the catalogue honest as the screen grows: an item whose
        // label is spelled by nothing that is searched cannot be found at all,
        // and the screen would silently stop offering it.
        for (item in settingsCatalogue) {
            val match = settingsMatch("needle") { id ->
                if (id == item.texts.first()) "needle" else "something else"
            }

            assertTrue("${item.tag} is not found by its own label", match.shows(item.tag))
        }
    }

    @Test
    fun `no two items answer to the same tag`() {
        val tags = settingsCatalogue.map { it.tag }

        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun `every part of the screen holds items`() {
        // A part with nothing in it is a heading the search would draw over an
        // empty stretch of screen.
        for (part in SettingsPart.entries) {
            assertTrue("$part holds nothing", settingsCatalogue.any { it.part == part })
        }
    }
}
