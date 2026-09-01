package io.github.vitalyostanin.markdownorg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings screen explains about itself, and what it leaves alone.
 *
 * The explanations are a list apart from the screen, keyed by the tags the
 * screen draws its items under. Two lists in step by hand drift the moment one
 * of them is edited alone: a tag renamed on the screen leaves a mark that opens
 * an empty screen, and a setting added without an explanation leaves the reader
 * with a name and nothing behind it. Both are questions of the lists rather
 * than of the drawing, which is why they are asked here and not on a device.
 */
class SettingHelpTest {

    /** The items the screen draws that are left without a screen of their own. */
    private val plain = setOf(
        "settings-collection-name",
        "settings-collection-remove",
        "settings-token-page",
        "settings-token-drop",
        "settings-ssh-key-drop",
        "settings-ssh-create",
        "settings-notes-pick",
        "settings-week-start",
        "settings-licences",
        "settings-version",
    )

    @Test
    fun `every explanation belongs to an item the screen draws`() {
        val drawn = settingsCatalogue.map { it.tag }.toSet()
        val orphans = settingHelp.keys - drawn

        assertEquals(emptySet<String>(), orphans)
    }

    @Test
    fun `the items left unexplained are the ones that answer for themselves`() {
        // A setting whose label is the whole answer — the button that opens the
        // system picker, the weekday a week starts on — is left as it stands
        // rather than given three paragraphs repeating its own name.
        val unexplained = settingsCatalogue.map { it.tag }.toSet() - settingHelp.keys

        assertEquals(plain, unexplained)
    }

    @Test
    fun `an explanation says four separate things`() {
        for ((tag, help) in settingHelp) {
            assertEquals("$tag repeats itself", 4, help.texts.toSet().size)
        }
    }

    @Test
    fun `an explanation is keyed by the tag it names`() {
        for ((tag, help) in settingHelp) {
            assertEquals(tag, help.tag)
        }
    }

    @Test
    fun `the settings that carry a screen are most of them`() {
        // Guards the balance rather than the count: an explanation left off
        // most of the screen would make the mark a curiosity rather than the
        // way a setting is read.
        assertTrue(settingHelp.size > settingsCatalogue.size / 2)
    }
}
