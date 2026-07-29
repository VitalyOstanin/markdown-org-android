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
import uniffi.markdown_org_ffi.RepoStatus
import uniffi.markdown_org_ffi.SyncException
import java.time.LocalDate
import java.time.LocalTime

/** What the agenda says about the checkout and the last sync attempt. */
class SyncBannerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var synced = 0
    private var settingsOpened = false
    private var issuesShown = 0

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
        // Only failures carry the core's own words; a success has nothing to
        // add to "Notes updated".
        compose.onNodeWithText("401 Unauthorized").assertDoesNotExist()
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

    private fun status() = RepoStatus(
        url = "https://example.org/notes.git",
        branch = "main",
        headId = "0123456789abcdef",
        headSummary = "Add the quarterly report",
        headTime = 1_753_700_000,
        dirty = false,
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
                        timeline = sections.toTimeline(LocalTime.of(10, 0)),
                        notices = notices,
                    ),
                    layout = AgendaLayout.TIME,
                    onLayoutChange = {},
                    sync = sync.value,
                    editIssue = editIssue,
                    onEditIssueShown = { issuesShown++ },
                    onSync = { synced++ },
                    onOpenSettings = { settingsOpened = true },
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
    }
}
