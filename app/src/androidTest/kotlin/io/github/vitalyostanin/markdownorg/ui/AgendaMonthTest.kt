package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * hands the day it stands for to the panel under the grid, and that the
 * setting gives the list back.
 */
class AgendaMonthTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The grid of August 2026 as `Scope.MONTH_GRID` answers with it: the six
     * whole weeks the month touches, every one of their days present, with
     * work on two of them and the earlier one slipped.
     *
     * Whole rather than the two days that carry anything: the calendar lays
     * out the days it was given and computes none of its own, so a payload of
     * two days is a calendar of two cells.
     *
     * The slipped task appears twice, as the core reports it: on the day it
     * was dated to, and again under today as arrears. The calendar has to
     * count it once.
     */
    private val month = gridOf(
        AgendaDay(
            LocalDate.of(2026, 8, 3),
            agenda(
                day(
                    date = "2026-08-03",
                    scheduledNoTime = listOf(
                        task(heading = "Order the parts", date = "2026-08-03"),
                    ),
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
                    overdue = listOf(
                        task(
                            heading = "Order the parts",
                            date = "2026-08-03",
                            daysOffset = -13,
                            line = 3u,
                        ),
                    ),
                    scheduledTimed = listOf(
                        task(heading = "Daily standup", time = "09:30", line = 7u),
                    ),
                ),
            ).toSections(),
        ),
    )

    /**
     * Those days, laid into the grid the core would have answered with: the
     * whole weeks from 27 July to 6 September, each day the caller named in
     * its place and the rest of them empty.
     */
    private fun gridOf(vararg carried: AgendaDay): List<AgendaDay> {
        val byDate = carried.associateBy { it.date }
        val first = LocalDate.of(2026, 7, 27)

        return (0 until 42).map { offset ->
            val date = first.plusDays(offset.toLong())
            byDate[date] ?: AgendaDay(date, agenda(day(date = date.toString())).toSections())
        }
    }

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

        // One row each, and the heading itself is the panel's to show: the
        // cell has room for the count and nothing else. Read by the tag of the
        // cell — the count of a day and the number of another day are the same
        // text. Unmerged, because the tooltip around the cell merges the
        // semantics of everything it wraps.
        compose.onNodeWithTag("month-load-2026-08-16", useUnmergedTree = true)
            .assertTextEquals("1")
    }

    @Test
    fun theDayUnderTheGridIsTodayUntilAnotherIsPicked() {
        showCalendar()

        // The reader who has not picked anything is looking at the month they
        // are living through, and the day they are living through is the one
        // worth opening the screen with.
        compose.onNodeWithTag("month-panel").assertIsDisplayed()
        compose.onNodeWithText("Daily standup").assertIsDisplayed()
    }

    @Test
    fun aTapOnACellPicksItsDayRatherThanOpeningIt() {
        val opened = mutableListOf<LocalDate>()
        showCalendar(onShowDay = { opened += it })

        compose.onNodeWithTag("month-cell-2026-08-03").performClick()

        // The panel follows the tap, and nothing is opened: leaving the
        // calendar is what the button under the date is for.
        compose.onNodeWithText("Order the parts").assertIsDisplayed()
        assertEquals(emptyList<LocalDate>(), opened)
    }

    @Test
    fun theDayIsOpenedFromThePanelRatherThanFromTheCell() {
        val opened = mutableListOf<LocalDate>()
        showCalendar(onShowDay = { opened += it })

        compose.onNodeWithTag("month-cell-2026-08-03").performClick()
        compose.onNodeWithTag("month-panel-open").performClick()

        assertEquals(listOf(LocalDate.of(2026, 8, 3)), opened)
    }

    @Test
    fun aDayWithNothingOnItSaysSoRatherThanShowingAnEmptyPanel() {
        showCalendar()

        compose.onNodeWithTag("month-cell-2026-08-05").performClick()

        compose.onNodeWithTag("month-panel-empty").assertIsDisplayed()
    }

    @Test
    fun aTaskThatSlippedIsCountedInItsOwnDayAndNotTwice() {
        showMonth()

        // The core reports it in both places; the calendar counts it where it
        // was dated to. Counted from the arrears bucket as well, today read 2
        // — and read 2 in whichever cell the reader had paged to, since that
        // is where the core gathers arrears.
        compose.onNodeWithTag("month-load-2026-08-03", useUnmergedTree = true)
            .assertTextEquals("1")
        compose.onNodeWithTag("month-load-2026-08-16", useUnmergedTree = true)
            .assertTextEquals("1")
    }

    @Test
    fun aShortWeekLaysTheCellOutFlatRatherThanSlicingTheChip() {
        // Landscape gives a week about a third of the height portrait does.
        // Stacked regardless, the chip was sliced into a stripe and the count
        // could not be read at all.
        showShort()

        compose.onNodeWithTag("month-load-2026-08-16", useUnmergedTree = true)
            .assertTextEquals("1")
        compose.onNodeWithTag("month-cell-2026-09-06").assertIsDisplayed()
    }

    @Test
    fun aShortWindowKeepsTheWholeMonthAndLeavesThePanelOut() {
        // The whole month is what the calendar is for; a panel showing one row
        // of a day is not the day, and the six weeks would go under the fold
        // to make room for it.
        showCalendar(height = SHORT)

        compose.onNodeWithTag("month-panel").assertDoesNotExist()
        compose.onNodeWithTag("month-cell-2026-07-27").assertIsDisplayed()
        compose.onNodeWithTag("month-cell-2026-09-06").assertIsDisplayed()
    }

    @Test
    fun theSettingGivesTheListBack() {
        showMonth(asGrid = false)

        // The reading the month had before the calendar: a heading per day
        // that has anything on it, and the rows under it.
        compose.onNodeWithTag("agenda-list").assertIsDisplayed()
        compose.onNodeWithText("Daily standup").assertIsDisplayed()
    }

    /**
     * The calendar on its own, in a window of a stated height.
     *
     * Without the screen around it: whether the panel is drawn is decided by
     * the height left below the grid, and measuring that through a header
     * whose own height depends on the window would make the test about the
     * header.
     */
    private fun showCalendar(height: Dp = ROOMY, onShowDay: (LocalDate) -> Unit = {}) {
        compose.setContent {
            MarkdownOrgTheme {
                Box(Modifier.height(height)) {
                    MonthLayout(
                        cells = buildMonthGrid(month, TODAY, TODAY),
                        load = month.monthLoad(TODAY),
                        days = month,
                        anchor = TODAY,
                        today = TODAY,
                        onDayClick = onShowDay,
                    )
                }
            }
        }
    }

    /** The month in a window too short for anything under the grid. */
    private fun showShort() {
        compose.setContent {
            MarkdownOrgTheme {
                Box(Modifier.height(360.dp)) { MonthScreen() }
            }
        }
    }

    private fun showMonth(asGrid: Boolean = true, onShowDay: (LocalDate) -> Unit = {}) {
        compose.setContent {
            MarkdownOrgTheme {
                MonthScreen(asGrid, onShowDay)
            }
        }
    }

    @Composable
    private fun MonthScreen(asGrid: Boolean = true, onShowDay: (LocalDate) -> Unit = {}) {
        AgendaScreen(
            state = AgendaUiState.Ready(
                date = TODAY,
                days = month,
                span = AgendaSpan.MONTH,
            ),
            view = AgendaView(
                // Left on the axis on purpose, as in the week: what decides
                // the layout of a month is the span and the setting, not the
                // switch.
                layout = AgendaLayout.TIME,
                span = AgendaSpan.MONTH,
                monthAsGrid = asGrid,
            ),
            actions = AgendaActions(onShowDay = onShowDay),
            now = TODAY.atTime(9, 0),
        )
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 16)

        /** Room for the six weeks at full height and the panel under them. */
        val ROOMY = 600.dp

        /** And a window that has room for the weeks and nothing else. */
        val SHORT = 380.dp
    }
}
