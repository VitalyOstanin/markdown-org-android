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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
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
        compose.onNodeWithTag("settings-save").performScrollTo().performClick()

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
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsEnabled()

        compose.onNodeWithTag("settings-url").performTextInput("https://example.org/notes.git")
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsEnabled()
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
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aBlankTokenKeepsWhateverIsStored() {
        showForm(url = "https://example.org/notes.git", hasToken = true)

        // The stored token is never read back into the field, so the hint has
        // to say that leaving it alone is safe.
        compose.onNodeWithText(string(R.string.settings_token_kept)).assertIsDisplayed()

        compose.onNodeWithTag("settings-save").performScrollTo().performClick()
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

        compose.onNodeWithTag("settings-token-drop").performScrollTo().performClick()
        compose.onNodeWithTag("settings-save").performScrollTo().performClick()

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

        compose.onNodeWithTag("settings-version").performScrollTo()
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
                    onSave = { },
                    onDismiss = {},
                    onOpenLicences = {},
                    crash = "java.lang.IllegalStateException: the list was empty",
                    onForgetCrash = { forgotten = true },
                )
            }
        }

        compose.onNodeWithTag("settings-crash").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-crash-trace")
            .assertTextContains("the list was empty", substring = true)
        compose.onNodeWithTag("settings-crash-forget").performScrollTo().performClick()

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
        compose.onNodeWithTag("settings-cancel").performScrollTo().performClick()

        assertTrue(dismissed)
        assertNull(saved)
    }

    private var licencesOpened = false

    @Test
    fun theChosenDirectoryFillsTheFieldAndIsSavedWithTheRest() {
        showForm(url = "https://example.org/notes.git", notesPath = SHARED)

        compose.onNodeWithText(SHARED).assertIsDisplayed()
        compose.onNodeWithTag("settings-save").performScrollTo().performClick()

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

        compose.onNodeWithTag("settings-notes-pick").performScrollTo().performClick()
        assertTrue(pickerOpened)

        picked = SHARED
        compose.waitForIdle()

        compose.onNodeWithText(SHARED).assertIsDisplayed()
        compose.onNodeWithTag("settings-save").performScrollTo().performClick()
        assertEquals(SHARED, savedNotesPath)
    }

    @Test
    fun aRelativeDirectoryIsRefusedInTheField() {
        showForm(url = "https://example.org/notes.git", notesPath = "notes")

        compose.onNodeWithText(string(R.string.settings_notes_relative)).assertIsDisplayed()
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
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
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("settings-notes-grant").performScrollTo().performClick()

        assertTrue(accessRequested)
    }

    @Test
    fun theWayToGrantTheAccessIsNotShownOnceItIsThere() {
        showForm(url = "https://example.org/notes.git", notesPath = SHARED, granted = true)

        compose.onNodeWithTag("settings-notes-grant").assertDoesNotExist()
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsEnabled()
    }

    /**
     * A first launch used to offer nothing but an address, and the notes are
     * not always meant to leave the device. Saying so is a press here, and
     * what it changes is that the screen stops asking.
     */
    @Test
    fun keepingTheNotesOnTheDeviceIsOfferedWhileNoAddressIsGiven() {
        showForm()

        compose.onNodeWithText(string(R.string.settings_hint)).assertIsDisplayed()
        compose.onNodeWithTag("settings-keep-local").performScrollTo().performClick()

        assertTrue(keptLocal)
    }

    @Test
    fun aFormWithAnAddressDoesNotOfferToKeepTheNotesHere() {
        showForm(url = "https://example.org/notes.git")

        compose.onNodeWithTag("settings-keep-local").assertDoesNotExist()
    }

    /**
     * The choice is not a dead end: the form still takes an address, and what
     * changes is the sentence over it — nothing is said about a repository
     * being cloned into a directory that already holds the notes.
     */
    @Test
    fun onceTheNotesStayHereTheFormSaysSoInsteadOfAskingAgain() {
        showForm(storesLocally = true)

        compose.onNodeWithText(string(R.string.settings_local_hint)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_hint)).assertDoesNotExist()
        compose.onNodeWithTag("settings-keep-local").assertDoesNotExist()
        compose.onNodeWithTag("settings-save").performScrollTo().assertIsEnabled()
    }

    /**
     * A key is pasted rather than typed — it is many lines of base64 — and
     * what it opens may be behind a passphrase. Both travel with the save.
     */
    @Test
    fun theKeyAndItsPassphraseAreSavedWithTheRest() {
        showForm(url = "ssh://git@example.org/notes.git")

        compose.onNodeWithTag("settings-ssh-key").performScrollTo().performTextInput(KEY)
        compose.onNodeWithTag("settings-ssh-passphrase")
            .performScrollTo()
            .performTextInput("let me in")
        compose.onNodeWithTag("settings-save").performScrollTo().performClick()

        assertEquals(KEY, savedKey)
        assertEquals("let me in", savedPassphrase)
    }

    @Test
    fun aBlankKeyFieldKeepsWhateverIsStored() {
        showForm(url = "ssh://git@example.org/notes.git", hasKey = true)

        compose.onNodeWithText(string(R.string.settings_ssh_key_kept)).assertIsDisplayed()

        compose.onNodeWithTag("settings-save").performScrollTo().performClick()
        assertTrue(savedKey.isNullOrEmpty())
    }

    @Test
    fun theStoredKeyCanBeForgotten() {
        showForm(url = "ssh://git@example.org/notes.git", hasKey = true)

        compose.onNodeWithTag("settings-ssh-key-drop").performScrollTo().performClick()
        compose.onNodeWithTag("settings-save").performScrollTo().performClick()

        assertEquals(true, droppedKey)
    }

    @Test
    fun withoutAStoredKeyThereIsNothingToForget() {
        showForm(url = "ssh://git@example.org/notes.git")

        compose.onNodeWithTag("settings-ssh-key-drop").assertDoesNotExist()
    }

    /**
     * The public half is of no use on the phone: it has to reach a server's
     * settings page, and the clipboard is how it gets there.
     */
    @Test
    fun aKeyMadeHereIsShownWithTheWayToCarryItToAServer() {
        showForm(publicKey = PUBLIC_KEY)

        compose.onNodeWithTag("settings-ssh-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("settings-ssh-public")
            .performScrollTo()
            .assertTextContains(PUBLIC_KEY)
        compose.onNodeWithTag("settings-ssh-copy").performScrollTo().performClick()
    }

    @Test
    fun makingAKeyIsOfferedAndWithoutOneNothingIsShownToCopy() {
        showForm()

        compose.onNodeWithTag("settings-ssh-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("settings-ssh-public").assertDoesNotExist()
        compose.onNodeWithTag("settings-ssh-copy").assertDoesNotExist()
        compose.onNodeWithTag("settings-ssh-create").performScrollTo().performClick()

        assertTrue(keyMade)
    }

    /**
     * The server is pinned by its key and by nothing else, so the one it is
     * pinned by has to be readable — that is what it gets compared against.
     */
    @Test
    fun theServerKeyIsShownOnceOneHasBeenVouchedFor() {
        showForm(url = "ssh://git@example.org/notes.git", knownHost = FINGERPRINT)

        compose.onNodeWithTag("settings-ssh-host")
            .performScrollTo()
            .assertTextContains(FINGERPRINT, substring = true)
    }

    @Test
    fun beforeAnySyncNoServerKeyIsClaimedToBeKnown() {
        showForm(url = "ssh://git@example.org/notes.git")

        compose.onNodeWithTag("settings-ssh-host").assertDoesNotExist()
    }

    @Test
    fun aButtonSaysWhatSavingActuallyDoes() {
        // "Save" names the gesture, not what the collection does next: the
        // notes are read again straight away, and the server is not touched.
        showForm()

        compose.onNodeWithTag("settings-save")
            .performScrollTo()
            .performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_settings_save), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theButtonForTheDirectoryPickerSaysWhatIsStored() {
        showForm()

        compose.onNodeWithTag("settings-notes-pick")
            .performScrollTo()
            .performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.hint_settings_notes_pick), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theAddressFieldSaysWhatItTakesBeforeAnythingIsWrong() {
        // A line that only appears once the address is refused leaves the
        // shape of a working one to be guessed at.
        showForm()

        compose.onNodeWithText(string(R.string.settings_url_default)).assertIsDisplayed()
    }

    private var savedNotesPath: String? = null
    private var accessRequested = false
    private var keptLocal = false
    private var savedKey: String? = null
    private var savedPassphrase: String? = null
    private var droppedKey: Boolean? = null
    private var keyMade = false

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
        storesLocally: Boolean = false,
        hasKey: Boolean = false,
        publicKey: String = "",
        knownHost: String = "",
    ) {
        compose.setContent {
            MarkdownOrgTheme {
                SyncSettingsScreen(
                    initialUrl = url,
                    initialBranch = branch,
                    hasToken = hasToken,
                    onSave = { values ->
                        saved = Triple(values.url, values.branch, values.token)
                        droppedToken = values.dropToken
                        savedNotesPath = values.notesPath
                        savedKey = values.sshKey
                        savedPassphrase = values.sshPassphrase
                        droppedKey = values.dropKey
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
                    storesLocally = storesLocally,
                    onKeepLocal = { keptLocal = true },
                    hasKey = hasKey,
                    publicKey = publicKey,
                    knownHost = knownHost,
                    onCreateKey = { keyMade = true },
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

        /** Stands in for a private key: what matters is that it travels whole. */
        const val KEY = "-----BEGIN PRIVATE KEY-----"

        const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample markdown-org"

        /** A server key, spelled the way OpenSSH spells one. */
        const val FINGERPRINT = "SHA256:2sJ8mQBz1TeQ5iTGH7t7zZ0hqRk3sB0Xk8v0FhK0aBc"
    }
}
