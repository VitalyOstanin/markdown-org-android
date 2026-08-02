package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalProvidableLocaleList
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
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

    private fun showAgenda() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(screen.size)) {
                CompositionLocalProvider(
                    LocalProvidableLocaleList provides LocaleList(listOf(Locale(LANGUAGE))),
                ) {
                    MarkdownOrgTheme {
                        AgendaScreen(
                            state = AgendaUiState.Ready(date = SUNDAY, sections = EMPTY),
                            layout = AgendaLayout.LIST,
                            onLayoutChange = {},
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
