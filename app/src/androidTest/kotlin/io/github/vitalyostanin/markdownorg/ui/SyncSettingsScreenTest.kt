package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.vitalyostanin.markdownorg.BuildConfig
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The form that says where the notes come from. */
class SyncSettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var saved: Triple<String, String, String>? = null
    private var droppedToken: Boolean? = null
    private var dismissed = false

    @Test
    fun whatWasTypedIsWhatGetsSaved() {
        showForm()

        compose.onNodeWithTag("settings-url")
            .performTextInput("https://example.org/notes.git")
        compose.onNodeWithTag("settings-branch").performTextInput("main")
        compose.onNodeWithTag("settings-token").performTextInput("ghp_secret")
        compose.onNodeWithTag("settings-save").performClick()

        assertEquals(Triple("https://example.org/notes.git", "main", "ghp_secret"), saved)
    }

    @Test
    fun theStoredSettingsFillTheForm() {
        showForm(url = "https://example.org/notes.git", branch = "notes")

        compose.onNodeWithText("https://example.org/notes.git").assertIsDisplayed()
        compose.onNodeWithText("notes").assertIsDisplayed()
    }

    @Test
    fun theFormSavesWithoutARepositoryBecauseItAlsoCarriesTheDirectory() {
        showForm()

        // An empty address is a form that is only about where the notes are
        // kept. It used to disable the button, which left no way to point the
        // application at notes already on the device.
        compose.onNodeWithTag("settings-save").assertIsEnabled()

        compose.onNodeWithTag("settings-url").performTextInput("https://example.org/notes.git")
        compose.onNodeWithTag("settings-save").assertIsEnabled()
    }

    @Test
    fun anAddressTheCoreCannotFetchIsRefusedInTheField() {
        showForm()

        // Saving empties the working copy, and edits made on the device are
        // committed locally and never pushed. An address that cannot work has
        // to be stopped here, not after the clone failed over an empty
        // directory.
        compose.onNodeWithTag("settings-url").performTextInput("http://example.org/notes.git")

        compose.onNodeWithText(string(R.string.settings_url_scheme)).assertIsDisplayed()
        compose.onNodeWithTag("settings-save").assertIsNotEnabled()
    }

    @Test
    fun aBlankTokenKeepsWhateverIsStored() {
        showForm(url = "https://example.org/notes.git", hasToken = true)

        // The stored token is never read back into the field, so the hint has
        // to say that leaving it alone is safe.
        compose.onNodeWithText(string(R.string.settings_token_kept)).assertIsDisplayed()

        compose.onNodeWithTag("settings-save").performClick()
        assertTrue(saved?.third.isNullOrEmpty())
    }

    @Test
    fun withoutATokenTheFieldSaysWhenItIsNeeded() {
        showForm(url = "https://example.org/notes.git")

        compose.onNodeWithText(string(R.string.settings_token_none)).assertIsDisplayed()
    }

    @Test
    fun theSavedTokenCanBeForgotten() {
        // An empty field means "keep the stored one", and the stored one is
        // never shown -- so without this there is no way back to a remote that
        // needs no credentials.
        showForm(url = "https://example.org/notes.git", hasToken = true)

        compose.onNodeWithTag("settings-token-drop").performClick()
        compose.onNodeWithTag("settings-save").performClick()

        assertEquals(true, droppedToken)
    }

    @Test
    fun withoutAStoredTokenThereIsNothingToForget() {
        showForm(url = "https://example.org/notes.git")

        compose.onNodeWithTag("settings-token-drop").assertDoesNotExist()
    }

    @Test
    fun theFormNamesTheBuildItIsPartOf() {
        // A report about an installed build is worth nothing without the
        // version it was made from, and this is the only screen that says it.
        showForm()

        compose.onNodeWithTag("settings-version")
            .assertIsDisplayed()
            .assertTextContains(BuildConfig.VERSION_NAME, substring = true)
    }

    @Test
    fun theTraceOfTheRunThatCrashedIsOfferedAndCanBeForgotten() {
        // The trace is the whole of what makes a report about a crash worth
        // anything, and logcat is gone by the time anyone opens this.
        var forgotten = false
        compose.setContent {
            MarkdownOrgTheme {
                SyncSettingsScreen(
                    initialUrl = "",
                    initialBranch = "",
                    hasToken = false,
                    onSave = { _, _, _, _, _ -> },
                    onDismiss = {},
                    onOpenLicences = {},
                    crash = "java.lang.IllegalStateException: the list was empty",
                    onForgetCrash = { forgotten = true },
                )
            }
        }

        compose.onNodeWithTag("settings-crash").assertIsDisplayed()
        compose.onNodeWithTag("settings-crash-trace")
            .assertTextContains("the list was empty", substring = true)
        compose.onNodeWithTag("settings-crash-forget").performClick()

        assertTrue(forgotten)
    }

    @Test
    fun aRunThatEndedWellSaysNothingAboutACrash() {
        showForm()

        compose.onNodeWithTag("settings-crash").assertDoesNotExist()
    }

    @Test
    fun cancellingChangesNothing() {
        showForm(url = "https://example.org/notes.git")

        compose.onNodeWithTag("settings-url").performTextReplacement("https://elsewhere/x.git")
        compose.onNodeWithTag("settings-cancel").performClick()

        assertTrue(dismissed)
        assertNull(saved)
    }

    private var licencesOpened = false

    @Test
    fun theChosenDirectoryFillsTheFieldAndIsSavedWithTheRest() {
        showForm(url = "https://example.org/notes.git", notesPath = SHARED)

        compose.onNodeWithText(SHARED).assertIsDisplayed()
        compose.onNodeWithTag("settings-save").performClick()

        assertEquals(SHARED, savedNotesPath)
    }

    @Test
    fun anEmptyDirectoryFieldSaysWhereTheNotesGoInstead() {
        showForm()

        compose.onNodeWithText(string(R.string.settings_notes_default)).assertIsDisplayed()
    }

    @Test
    fun aFilledDirectoryFieldDoesNotStillSayItIsEmpty() {
        showForm(notesPath = SHARED)

        compose.onNodeWithText(string(R.string.settings_notes_default)).assertDoesNotExist()
    }

    /**
     * A phone keyboard is a poor way to enter a path: on the device this was
     * written for, `/sdcard/…` came out as `/SD card/…`.
     */
    @Test
    fun theDirectoryCanBePickedInsteadOfTyped() {
        showForm()

        compose.onNodeWithTag("settings-notes-pick").performClick()
        assertTrue(pickerOpened)

        picked = SHARED
        compose.waitForIdle()

        compose.onNodeWithText(SHARED).assertIsDisplayed()
        compose.onNodeWithTag("settings-save").performClick()
        assertEquals(SHARED, savedNotesPath)
    }

    @Test
    fun aRelativeDirectoryIsRefusedInTheField() {
        showForm(url = "https://example.org/notes.git", notesPath = "notes")

        compose.onNodeWithText(string(R.string.settings_notes_relative)).assertIsDisplayed()
        compose.onNodeWithTag("settings-save").assertIsNotEnabled()
    }

    /**
     * The one refusal with an action behind it: without the access, the
     * directory cannot be read at all, and the way to grant it is a screen of
     * the platform this form can only open.
     */
    @Test
    fun aDirectoryOutsideTheOwnStorageOffersTheWayToGrantTheAccess() {
        showForm(url = "https://example.org/notes.git", notesPath = SHARED, granted = false)

        compose.onNodeWithText(string(R.string.settings_notes_denied)).assertIsDisplayed()
        compose.onNodeWithTag("settings-save").assertIsNotEnabled()
        compose.onNodeWithTag("settings-notes-grant").performClick()

        assertTrue(accessRequested)
    }

    @Test
    fun theWayToGrantTheAccessIsNotShownOnceItIsThere() {
        showForm(url = "https://example.org/notes.git", notesPath = SHARED, granted = true)

        compose.onNodeWithTag("settings-notes-grant").assertDoesNotExist()
        compose.onNodeWithTag("settings-save").assertIsEnabled()
    }

    private var savedNotesPath: String? = null
    private var accessRequested = false

    /**
     * What the picker answered, as state: the form takes it in on the
     * recomposition that follows, the way it does on a device when the picker
     * returns.
     */
    private var picked by mutableStateOf<String?>(null)
    private var pickerOpened = false

    private fun showForm(
        url: String = "",
        branch: String = "",
        hasToken: Boolean = false,
        notesPath: String = "",
        granted: Boolean = true,
    ) {
        compose.setContent {
            MarkdownOrgTheme {
                SyncSettingsScreen(
                    initialUrl = url,
                    initialBranch = branch,
                    hasToken = hasToken,
                    onSave = { savedUrl, savedBranch, token, dropToken, directory ->
                        saved = Triple(savedUrl, savedBranch, token)
                        droppedToken = dropToken
                        savedNotesPath = directory
                    },
                    onDismiss = { dismissed = true },
                    onOpenLicences = { licencesOpened = true },
                    initialNotesPath = notesPath,
                    ownNotesPath = ownNotes.absolutePath,
                    storageGranted = granted,
                    onRequestStorage = { accessRequested = true },
                    pickedNotesPath = picked,
                    onPickNotesDirectory = { pickerOpened = true },
                    onPickedNotesTaken = { picked = null },
                )
            }
        }
    }

    /** Stands in for the directory inside the application's own storage. */
    private val ownNotes by lazy { File(compose.activity.filesDir, "notes") }

    private fun string(id: Int): String = compose.activity.getString(id)

    private companion object {
        /** A directory on the shared storage, which is what needs the access. */
        const val SHARED = "/storage/emulated/0/Documents/notes"
    }
}
