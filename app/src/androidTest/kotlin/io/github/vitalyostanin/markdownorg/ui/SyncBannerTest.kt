package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.Adoption
import uniffi.markdown_org_ffi.RepoStatus
import uniffi.markdown_org_ffi.SyncException
import java.time.LocalDate

/** What the agenda says about the checkout and the last sync attempt. */
class SyncBannerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var synced = 0
    private var settingsOpened = false
    private var issuesShown = 0
    private var remotesTaken = 0
    private var hostsTrusted = 0

    @Test
    fun beforeARemoteIsConfiguredNothingIsSaid() {
        showAgenda(SyncUiState())

        // An empty state repeating "not configured" on every launch would be
        // noise, so the line is absent until there is something to report.
        compose.onNodeWithTag("sync-banner").assertDoesNotExist()
    }

    @Test
    fun aCheckoutIsDescribedByItsBranchAndTip() {
        showAgenda(SyncUiState(configured = true, repository = status()))

        compose.onNodeWithText(
            string(R.string.sync_checkout, "main", "Add the quarterly report"),
        ).assertIsDisplayed()
    }

    @Test
    fun aRunningSyncSaysSoAndBlocksASecondOne() {
        showAgenda(SyncUiState(configured = true, running = true))

        compose.onNodeWithText(string(R.string.sync_running)).assertIsDisplayed()
        compose.onNodeWithTag("sync-now").assertIsNotEnabled()
    }

    @Test
    fun aFailureShowsBothTheReasonAndWhatTheCoreSaid() {
        val state = SyncUiState(
            configured = true,
            message = SyncException.Auth("401 Unauthorized").toSyncMessage(),
        )
        showAgenda(state)

        compose.onNodeWithText(string(R.string.sync_failed_auth)).assertIsDisplayed()
        compose.onNodeWithText("401 Unauthorized").assertIsDisplayed()
    }

    /**
     * The core used to spell this out itself — `master and origin/master have
     * both moved; the application does not merge` — and that English sentence
     * appeared under a Russian heading whatever the language of the phone.
     * Now the core reports the branch and the sentence lives in the resources.
     */
    @Test
    fun theExplanationOfADivergenceIsReadFromTheResources() {
        showAgenda(
            SyncUiState(
                configured = true,
                message = SyncException.Diverged("master").toSyncMessage(),
            ),
        )

        compose.onNodeWithText(string(R.string.sync_diverged_detail, "master"))
            .assertIsDisplayed()
    }

    @Test
    fun theFilesInTheWayAreCountedInTheLanguageOfTheInterface() {
        showAgenda(
            SyncUiState(configured = true, message = SyncException.Dirty(3u).toSyncMessage()),
        )

        // `3 file(s) changed` was the old wording, in a form no language has.
        compose.onNodeWithText(plural(R.plurals.sync_dirty_detail, 3)).assertIsDisplayed()
    }

    @Test
    fun aSuccessfulSyncReportsWhatItDid() {
        showAgenda(SyncUiState(configured = true, message = SyncMessage(R.string.sync_updated)))

        compose.onNodeWithText(string(R.string.sync_updated)).assertIsDisplayed()
        // A run that only fetched has nothing to add to "Notes updated": the
        // second line appears when there is one, and this message carries none.
        compose.onNodeWithText("401 Unauthorized").assertDoesNotExist()
    }

    @Test
    fun whatWentBackToTheServerIsCountedInTheLanguageOfTheInterface() {
        showAgenda(
            SyncUiState(
                configured = true,
                repository = status(),
                message = SyncMessage(
                    R.string.sync_pushed,
                    Detail.Counted(R.plurals.sync_pushed_detail, 2),
                ),
            ),
        )

        compose.onNodeWithText(string(R.string.sync_pushed)).assertIsDisplayed()
        compose.onNodeWithText(plural(R.plurals.sync_pushed_detail, 2)).assertIsDisplayed()
    }

    /**
     * A commit made with no network stays on the device until a sync gets
     * through, and nothing on screen used to say so: the banner described the
     * checkout as though it matched the server.
     */
    @Test
    fun editsThatHaveNotReachedTheServerAreCountedOnScreen() {
        showAgenda(SyncUiState(configured = true, repository = status(unpushed = 2u)))

        compose.onNodeWithTag("sync-unpushed").assertIsDisplayed()
        compose.onNodeWithText(plural(R.plurals.sync_unpushed, 2)).assertIsDisplayed()
    }

    @Test
    fun aCheckoutTheServerHasSeenSaysNothingAboutSendingAnything() {
        showAgenda(SyncUiState(configured = true, repository = status()))

        compose.onNodeWithTag("sync-unpushed").assertDoesNotExist()
    }

    @Test
    fun aRunningSyncDoesNotStateACountItIsAboutToChange() {
        showAgenda(
            SyncUiState(configured = true, running = true, repository = status(unpushed = 1u)),
        )

        compose.onNodeWithTag("sync-unpushed").assertDoesNotExist()
    }

    /**
     * Notes kept on the device and notes on the server can share no history at
     * all, and neither side is wrong: the answer is which of the two the
     * directory is to hold. Nothing else on this screen asks for one, so the
     * question is put where it is raised.
     */
    @Test
    fun historiesThatShareNothingAreAQuestionWithItsAnswerBesideIt() {
        showAgenda(
            SyncUiState(
                configured = true,
                unrelated = "main",
                message = Adoption.Unrelated("main").toMessage(),
            ),
        )

        compose.onNodeWithText(string(R.string.sync_unrelated)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sync_unrelated_detail, "main")).assertIsDisplayed()
        compose.onNodeWithTag("sync-take-remote").performClick()

        assertEquals(1, remotesTaken)
    }

    /**
     * Saving an address over a directory that is a checkout of somewhere else
     * used to empty it, and then offered to empty it at a press. Neither
     * happens now: the files are the user's, and the banner says what to do
     * instead of holding a button that would remove them.
     */
    @Test
    fun aDirectoryHoldingAnotherCheckoutIsLeftAloneAndSaysWhatToDo() {
        showAgenda(
            SyncUiState(
                configured = true,
                message = SyncMessage(R.string.settings_other_checkout, failed = true),
            ),
        )

        compose.onNodeWithText(string(R.string.settings_other_checkout)).assertIsDisplayed()
        compose.onNodeWithTag("sync-replace-notes").performClick()

        assertEquals(1, notesReplaced)
    }

    /**
     * The one question that stops everything else: nothing was fetched, and
     * an SSH server proves itself by its host key alone. The key goes on
     * screen to be compared with what the server says about itself.
     */
    @Test
    fun aServerNobodyHasVouchedForIsShownWithItsKeyAndTheWayToAcceptIt() {
        showAgenda(
            SyncUiState(
                configured = true,
                pendingHost = FINGERPRINT,
                message = SyncException.UnknownHost("git.example.org", FINGERPRINT).toSyncMessage(),
            ),
        )

        compose.onNodeWithText(string(R.string.sync_host_unknown)).assertIsDisplayed()
        compose.onNodeWithText(FINGERPRINT).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sync_host_accept)).assertIsDisplayed()
        compose.onNodeWithTag("sync-trust-host").performClick()

        assertEquals(1, hostsTrusted)
    }

    /**
     * A key that contradicts a stored one is the graver of the two questions,
     * and the button has to say which of them is being answered.
     */
    @Test
    fun replacingAKnownServerKeyIsWordedAsAReplacement() {
        showAgenda(
            SyncUiState(
                configured = true,
                pendingHost = FINGERPRINT,
                pendingHostReplaces = "SHA256:what-was-known",
                message = SyncException
                    .HostChanged("git.example.org", FINGERPRINT, "SHA256:what-was-known")
                    .toSyncMessage(),
            ),
        )

        compose.onNodeWithText(string(R.string.sync_host_changed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sync_host_accept_new)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sync_host_accept)).assertDoesNotExist()
    }

    @Test
    fun aCheckoutWithNothingToDecideIsShownWithoutAButton() {
        showAgenda(SyncUiState(configured = true, repository = status()))

        compose.onNodeWithTag("sync-take-remote").assertDoesNotExist()
        compose.onNodeWithTag("sync-replace-notes").assertDoesNotExist()
    }

    @Test
    fun aRunningSyncDoesNotOfferAnAnswerToAQuestionItMaySettle() {
        showAgenda(SyncUiState(configured = true, running = true, unrelated = "main"))

        compose.onNodeWithTag("sync-take-remote").assertDoesNotExist()
    }

    @Test
    fun whenTheNotesLastMatchedTheServerIsOnScreen() {
        // The moment was recorded and stored across restarts, and no part of
        // the interface said it: an agenda from three days ago looked exactly
        // like one fetched a minute ago.
        showAgenda(
            SyncUiState(configured = true, repository = status(), lastSyncedAt = SYNCED_AT),
        )

        compose.onNodeWithTag("sync-last-synced").assertIsDisplayed()
    }

    @Test
    fun nothingIsSaidAboutASyncThatHasNeverHappened() {
        showAgenda(SyncUiState(configured = true, repository = status()))

        compose.onNodeWithTag("sync-last-synced").assertDoesNotExist()
    }

    @Test
    fun theMomentSurvivesTheFailureOfTheNextAttempt() {
        // This is where it matters most: the attempt failed, and the question
        // it raises is how old what is on screen now is.
        showAgenda(
            SyncUiState(
                configured = true,
                lastSyncedAt = SYNCED_AT,
                message = SyncException.Network("no route to host").toSyncMessage(),
            ),
        )

        compose.onNodeWithText(string(R.string.sync_failed_network)).assertIsDisplayed()
        compose.onNodeWithTag("sync-last-synced").assertIsDisplayed()
    }

    @Test
    fun aRunningSyncDoesNotStateAMomentItIsAboutToReplace() {
        showAgenda(SyncUiState(configured = true, running = true, lastSyncedAt = SYNCED_AT))

        compose.onNodeWithTag("sync-last-synced").assertDoesNotExist()
    }

    @Test
    fun syncingIsOfferedOnlyOnceARemoteIsKnown() {
        val state = mutableStateOf(SyncUiState())
        showAgenda(state)

        compose.onNodeWithTag("sync-now").assertIsNotEnabled()

        state.value = SyncUiState(configured = true)
        compose.onNodeWithTag("sync-now").performClick()
        assertEquals(1, synced)
    }

    @Test
    fun settingsAreReachableWithNothingConfiguredYet() {
        showAgenda(SyncUiState())

        // The way out of the unconfigured state, so this one is never disabled.
        assertFalse(settingsOpened)
        compose.onNodeWithTag("open-settings").performClick()
        assertTrue(settingsOpened)
    }

    @Test
    fun aFailedEditIsAnsweredWithoutTakingOverTheBanner() {
        // Both used to share the line under the header, so an edit failure
        // stood in place of the checkout it says nothing about — and stayed
        // there until the next sync.
        showAgenda(
            SyncUiState(configured = true, repository = status()),
            editIssue = SyncMessage(R.string.edit_failed_stale, failed = true),
        )

        compose.onNodeWithText(string(R.string.edit_failed_stale)).assertIsDisplayed()
        compose.onNodeWithText(
            string(R.string.sync_checkout, "main", "Add the quarterly report"),
        ).assertIsDisplayed()
        // Dropped once it has been read rather than when it is put up: the
        // message has to outlive the redraw that follows the edit, and
        // clearing it early would take the snackbar down with it.
        compose.waitUntil(timeoutMillis = SNACKBAR_LIFETIME) { issuesShown == 1 }
    }

    @Test
    fun aNoteSkippedForItsEncodingIsNamedAboveTheAgenda() {
        showAgenda(
            SyncUiState(configured = true),
            notices = listOf(ScanNotice.Counted(R.plurals.agenda_skipped_encoding, 1)),
        )

        compose.onNodeWithTag("scan-notices").assertIsDisplayed()
        compose.onNodeWithText(plural(R.plurals.agenda_skipped_encoding, 1)).assertIsDisplayed()
    }

    @Test
    fun aCleanScanSaysNothingAboutTheFilesBehindTheAgenda() {
        showAgenda(SyncUiState(configured = true))

        compose.onNodeWithTag("scan-notices").assertDoesNotExist()
    }

    private fun status(unpushed: UInt = 0u) = RepoStatus(
        url = "https://example.org/notes.git",
        branch = "main",
        headId = "0123456789abcdef",
        headSummary = "Add the quarterly report",
        headTime = 1_753_700_000,
        dirty = false,
        unpushed = unpushed,
    )

    private fun showAgenda(
        sync: SyncUiState,
        notices: List<ScanNotice> = emptyList(),
        editIssue: SyncMessage? = null,
    ) = showAgenda(mutableStateOf(sync), notices, editIssue)

    private fun showAgenda(
        sync: MutableState<SyncUiState>,
        notices: List<ScanNotice> = emptyList(),
        editIssue: SyncMessage? = null,
    ) {
        val sections = agenda(
            day(scheduledTimed = listOf(task(heading = "Daily standup", time = "09:30"))),
        ).toSections()

        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Ready(
                        date = LocalDate.of(2026, 7, 28),
                        sections = sections,
                        notices = notices,
                    ),
                    layout = AgendaLayout.TIME,
                    onLayoutChange = {},
                    now = LocalDate.of(2026, 7, 28).atTime(10, 0),
                    sync = sync.value,
                    editIssue = editIssue,
                    onEditIssueShown = { issuesShown++ },
                    onSync = { synced++ },
                    onOpenSettings = { settingsOpened = true },
                    onTakeRemote = { remotesTaken++ },
                    onReplaceNotes = { notesReplaced++ },
                    onTrustHost = { hostsTrusted++ },
                )
            }
        }
    }

    private fun string(id: Int, vararg formatArgs: Any): String =
        compose.activity.getString(id, *formatArgs)

    private fun plural(id: Int, count: Int): String =
        compose.activity.resources.getQuantityString(id, count, count)

    private companion object {
        /** Long enough for a short snackbar to come and go on the emulator. */
        const val SNACKBAR_LIFETIME = 10_000L

        /**
         * Some moment in the past, as milliseconds. How it is written is the
         * business of the locale and of the clock the device is set to — see
         * TimeLabelsTest — so these check that the line is there at all.
         */
        const val SYNCED_AT = 1_753_700_000_000L

        /** A server key, spelled the way OpenSSH spells one. */
        const val FINGERPRINT = "SHA256:2sJ8mQBz1TeQ5iTGH7t7zZ0hqRk3sB0Xk8v0FhK0aBc"
    }
}
