package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalProvidableLocaleList
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.times
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import uniffi.markdown_org_ffi.RepoStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * That the header holds together on the screens the app runs on.
 *
 * The rest of the suite runs at whatever size the emulator happens to be, and
 * that is how a header that broke the name of the day between two of its
 * letters reached a phone: at the width of the emulator it fit. Every screen
 * here is a size the layout has to hold, and the narrow one in landscape is
 * the least room the app is ever given.
 *
 * The size is overridden rather than the device rotated. A rotation recreates
 * the activity, and the content of these tests is set by the test rather than
 * by the activity, so it would not survive one — what is measured here is the
 * layout at a size, which is what the rotation was going to change anyway.
 */
@RunWith(Parameterized::class)
class AgendaHeaderSizeTest(private val screen: Screen) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** One size the layout has to hold, named for the report. */
    data class Screen(val name: String, val size: DpSize) {
        override fun toString(): String = name
    }

    @Test
    fun theDayAndItsDateEachTakeOneLine() {
        // A word has nowhere to wrap, so a name of a day too wide for its line
        // breaks between two of its letters. Sunday in Russian is the longest
        // name there is, which makes it the case to hold the header to.
        showAgenda()

        assertEquals("the name of the day is on one line", 1, linesOf(weekday))
        assertEquals("the date is on one line", 1, linesOf(date))
    }

    @Test
    fun everyControlIsReachable() {
        // The controls share a line, and the layout switch is the wide one. On
        // a narrow screen it is what would push the others off the edge.
        showAgenda()

        for (control in controls) {
            compose.onNodeWithContentDescription(compose.activity.getString(control))
                .assertIsDisplayed()
        }
    }

    @Test
    fun mostOfTheScreenIsTheAgendaItself() {
        // Everything above the plan — the day, its date, the controls, the
        // collections, the state of the checkout — is worth a screen only in
        // as much as it leaves the plan on it. Set out one thing per row, the
        // five of them took two thirds of a phone on its side and left the
        // agenda a row and a half.
        //
        // Filled rather than left at its defaults: with no collections and no
        // checkout two of the five rows are not drawn at all, and the header
        // would pass this at any height.
        showAgenda(
            filters = AgendaFilters(
                collections = listOf("personal", "work", "projects").mapIndexed { tone, name ->
                    CollectionChoice(CollectionLabel(name, name, tone), shown = true)
                },
            ),
            sync = SyncUiState(
                configured = true,
                repository = CHECKOUT,
                lastSyncedAt = LAST_SYNCED,
            ),
        )

        val header = compose.onNodeWithTag("agenda-header-area")
            .getUnclippedBoundsInRoot()
            .height

        // Two fifths: the roomy header takes about a quarter of a phone held
        // upright, and the short one about a third of the same phone on its
        // side. A header past this is one that has stopped giving way.
        val allowed = screen.size.height * 0.4f
        assertTrue(
            "the header takes $header of a ${screen.size.height} screen, leaving " +
                "the agenda less than it needs; at most $allowed was expected",
            header < allowed,
        )
    }

    private fun showAgenda(
        filters: AgendaFilters = AgendaFilters(),
        sync: SyncUiState = SyncUiState(),
    ) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(screen.size)) {
                CompositionLocalProvider(
                    LocalProvidableLocaleList provides LocaleList(listOf(Locale(LANGUAGE))),
                ) {
                    MarkdownOrgTheme {
                        AgendaScreen(
                            state = AgendaUiState.Ready(date = SUNDAY, sections = EMPTY),
                            view = AgendaView(layout = AgendaLayout.LIST),
                            sync = sync,
                            filters = filters,
                        )
                    }
                }
            }
        }
    }

    /** How many lines the text laid out over. */
    private fun linesOf(text: String): Int {
        val laid = mutableListOf<TextLayoutResult>()

        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(laid) }

        return laid.first().lineCount
    }

    private companion object {
        /** The language whose names of days are the longest the app is translated for. */
        const val LANGUAGE = "ru"

        val SUNDAY: LocalDate = LocalDate.of(2026, 7, 28).with(DayOfWeek.SUNDAY)
        val EMPTY =
            AgendaSections(
                overdue = emptyList(),
                timed = emptyList(),
                untimed = emptyList(),
            )

        /** A checkout that has something to say about itself, unsent edits and all. */
        val CHECKOUT = RepoStatus(
            url = "https://example.org/notes.git",
            branch = "main",
            headId = "0123456789abcdef",
            headSummary = "Add the quarterly report",
            headTime = 1_753_700_000,
            dirty = false,
            unpushed = 2u,
        )

        /** Long enough ago that the banner words it rather than leaving it out. */
        const val LAST_SYNCED = 1_753_700_000_000

        val controls = listOf(
            R.string.sync_now,
            R.string.settings_title,
            R.string.agenda_layout_time,
            R.string.agenda_layout_list,
        )

        val platformLocale: java.util.Locale = java.util.Locale.forLanguageTag(LANGUAGE)

        /** The name of the day as the header writes it. */
        val weekday: String = SUNDAY
            .format(DateTimeFormatter.ofPattern("EEEE", platformLocale))
            .replaceFirstChar { it.titlecase(platformLocale) }

        /** The date as the header writes it. */
        val date: String = SUNDAY.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(platformLocale),
        )

        /**
         * The screens the layout has to hold.
         *
         * A small phone, the one the app is drawn for, and a tablet — each
         * upright and on its side. The smallest width here, 320dp, is the one
         * Android names as the least a phone reports.
         */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens(): List<Screen> = listOf(
            Screen("small phone, upright", DpSize(320.dp, 640.dp)),
            Screen("small phone, on its side", DpSize(640.dp, 320.dp)),
            Screen("phone, upright", DpSize(411.dp, 891.dp)),
            Screen("phone, on its side", DpSize(891.dp, 411.dp)),
            Screen("tablet, upright", DpSize(800.dp, 1280.dp)),
            Screen("tablet, on its side", DpSize(1280.dp, 800.dp)),
        )
    }
}
