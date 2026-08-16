package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * What the month is drawn as.
 *
 * The layout of the grid is settled on the JVM (`MonthGridTest`); what only a
 * device answers is that the month reaches the calendar at all, that a cell
 * hands back the day it stands for, and that the setting gives the list back.
 */
class AgendaMonthTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** A month with work on two of its days, one of them overdue. */
    private val month = listOf(
        AgendaDay(
            LocalDate.of(2026, 8, 3),
            agenda(
                day(
                    date = "2026-08-03",
                    scheduledNoTime = listOf(task(heading = "Order the parts")),
                ),
            ).toSections(),
        ),
        AgendaDay(
            TODAY,
            agenda(
                day(
                    date = "2026-08-16",
                    // Lines of their own: a row is keyed by where it stands in
                    // its file, and two rows of one day sharing a line is a
                    // duplicate key the list refuses to draw.
                    overdue = listOf(task(heading = "Renew the certificate", line = 3u)),
                    scheduledTimed = listOf(
                        task(heading = "Daily standup", time = "09:30", line = 7u),
                    ),
                ),
            ).toSections(),
        ),
    )

    @Test
    fun aMonthIsDrawnAsACalendarOfItsDays() {
        showMonth()

        compose.onNodeWithTag("agenda-month").assertIsDisplayed()
        // The first cell of August 2026 is the Monday its first week borrows
        // from July, and the last is the Sunday of September that finishes it.
        compose.onNodeWithTag("month-cell-2026-07-27").assertIsDisplayed()
        compose.onNodeWithTag("month-cell-2026-08-16").assertIsDisplayed()
        compose.onNodeWithTag("month-cell-2026-09-06").assertIsDisplayed()
    }

    @Test
    fun aCellSaysHowMuchItsDayCarriesRatherThanWhat() {
        showMonth()

        // Two rows on today, and the headings themselves are the day's to
        // show: the cell has room for the count and nothing else. Read by the
        // tag of that cell — the count of a day and the number of another day
        // are the same text. Unmerged, because the tooltip around the cell
        // merges the semantics of everything it wraps.
        compose.onNodeWithTag("month-load-2026-08-16", useUnmergedTree = true)
            .assertTextEquals("2")
        compose.onNodeWithText("Renew the certificate").assertDoesNotExist()
    }

    @Test
    fun aTapOnACellAsksForThatDay() {
        val opened = mutableListOf<LocalDate>()
        showMonth(onShowDay = { opened += it })

        compose.onNodeWithTag("month-cell-2026-08-03").performClick()

        assertEquals(listOf(LocalDate.of(2026, 8, 3)), opened)
    }

    @Test
    fun theSettingGivesTheListBack() {
        showMonth(asGrid = false)

        // The reading the month had before the calendar: a heading per day
        // that has anything on it, and the rows under it.
        compose.onNodeWithTag("agenda-list").assertIsDisplayed()
        compose.onNodeWithText("Renew the certificate").assertIsDisplayed()
    }

    private fun showMonth(asGrid: Boolean = true, onShowDay: (LocalDate) -> Unit = {}) {
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Ready(
                        date = TODAY,
                        days = month,
                        span = AgendaSpan.MONTH,
                    ),
                    view = AgendaView(
                        // Left on the axis on purpose, as in the week: what
                        // decides the layout of a month is the span and the
                        // setting, not the switch.
                        layout = AgendaLayout.TIME,
                        span = AgendaSpan.MONTH,
                        monthAsGrid = asGrid,
                    ),
                    actions = AgendaActions(onShowDay = onShowDay),
                    now = TODAY.atTime(9, 0),
                )
            }
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 16)
    }
}
