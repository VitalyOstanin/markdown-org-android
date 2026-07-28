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
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.RepoStatus
import uniffi.markdown_org_ffi.SyncException

/** What the agenda says about the checkout and the last sync attempt. */
class SyncBannerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var synced = 0
    private var settingsOpened = false

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

    private fun status() = RepoStatus(
        url = "https://example.org/notes.git",
        branch = "main",
        headId = "0123456789abcdef",
        headSummary = "Add the quarterly report",
        headTime = 1_753_700_000,
        dirty = false,
    )

    private fun showAgenda(sync: SyncUiState) = showAgenda(mutableStateOf(sync))

    private fun showAgenda(sync: MutableState<SyncUiState>) {
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
                    ),
                    layout = AgendaLayout.TIME,
                    onLayoutChange = {},
                    sync = sync.value,
                    onSync = { synced++ },
                    onOpenSettings = { settingsOpened = true },
                )
            }
        }
    }

    private fun string(id: Int, vararg formatArgs: Any): String =
        compose.activity.getString(id, *formatArgs)
}
