package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * What the agenda shows once the rows come from more than one collection.
 *
 * The rules behind the marks and the filter are asserted on the JVM; what is
 * only answerable here is whether they are drawn, and whether a device with a
 * single collection is left exactly as it was.
 */
class CollectionMarksTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val labels = mapOf(
        PERSONAL to CollectionLabel(id = "1", name = "Personal", tone = 0, root = PERSONAL),
        WORK to CollectionLabel(id = "2", name = "Work", tone = 1, root = WORK),
    )

    private val mixed = agenda(
        day(
            scheduledNoTime = listOf(
                task(heading = "Pay the tax", line = 1u, root = PERSONAL),
                task(heading = "Write the report", line = 2u, root = WORK),
            ),
        ),
    ).toSections(labels)

    @Test
    fun aRowCarriesTheNameOfTheCollectionItCameFrom() {
        showAgenda(AgendaLayout.LIST)

        // The mark is a dot, so the name is what it is spoken as rather than
        // what it is written as: a screen reader still says which collection
        // the row came from, where the colour says nothing.
        compose.onNodeWithContentDescription("Personal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Work").assertIsDisplayed()
    }

    @Test
    fun theTimeLayoutCarriesTheMarksAsWell() {
        showAgenda(AgendaLayout.TIME)

        // Both layouts show the same agenda; a mark on one of them only would
        // make which collection a task is in a property of the layout.
        compose.onNodeWithContentDescription("Personal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Work").assertIsDisplayed()
    }

    /**
     * The time layout puts two untimed cards to a row, and each is asked for
     * half of it.
     *
     * Asserted by width rather than left to `assertIsDisplayed`: a card that
     * ends up with no width at all is still in the tree, still carries its
     * heading and still answers every query about its contents — the one thing
     * it does not do is appear on screen.
     */
    @Test
    fun theTwoCardsOfARowShareItsWidth() {
        showAgenda(AgendaLayout.TIME)

        val first = compose.onNodeWithText("Pay the tax").fetchSemanticsNode().size.width
        val second = compose.onNodeWithText("Write the report").fetchSemanticsNode().size.width

        assertTrue("first=$first second=$second", second > 0)
        assertTrue("first=$first second=$second", abs(first - second) <= 1)
    }

    @Test
    fun aSingleCollectionLeavesTheScreenWithoutMarksOrFilter() {
        // The same agenda from a device that has never added a second
        // collection: no labels, so no marks, and an empty filter.
        val alone = agenda(
            day(scheduledNoTime = listOf(task(heading = "Pay the tax", root = PERSONAL))),
        ).toSections()

        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(alone),
                    layout = AgendaLayout.LIST,
                    onLayoutChange = {},
                    now = MOMENT,
                )
            }
        }

        compose.onNodeWithText("Pay the tax").assertIsDisplayed()
        compose.onNodeWithTag("collection-filter").assertDoesNotExist()
        compose.onNodeWithContentDescription("Personal").assertDoesNotExist()
    }

    @Test
    fun turningACollectionOffTakesItsRowsOffTheScreen() {
        var shown by mutableStateOf(setOf("1", "2"))
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(mixed.showing(labels.values.map { it.id }.toSet() - shown)),
                    layout = AgendaLayout.LIST,
                    onLayoutChange = {},
                    now = MOMENT,
                    filters = AgendaFilters(
                        collections = labels.values.map { label ->
                            CollectionChoice(label = label, shown = label.id in shown)
                        },
                        onCollectionShown = { id, on ->
                            shown = if (on) shown + id else shown - id
                        },
                    ),
                )
            }
        }

        compose.onNodeWithText("Write the report").assertIsDisplayed()

        compose.onNodeWithTag("collection-chip-2").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Write the report").assertDoesNotExist()
        compose.onNodeWithText("Pay the tax").assertIsDisplayed()
    }

    /**
     * The dot is six points across and sits inside the row's own tooltip, so
     * it is the row that answers a press with the name behind the colour.
     */
    @Test
    fun aLongPressOnARowNamesTheCollectionBehindTheDot() {
        showAgenda(AgendaLayout.LIST)

        compose.onNodeWithText("Write the report").performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.tooltip_collection, "Work"), substring = true)
            .assertIsDisplayed()
    }

    /** The chip carries a name; where that name reads from is the question. */
    @Test
    fun aLongPressOnAChipNamesTheDirectoryItReads() {
        showFilter()

        compose.onNodeWithTag("collection-chip-2").performTouchInput { longClick() }

        compose.onNodeWithText(WORK, substring = true).assertIsDisplayed()
    }

    /**
     * The agenda with the marks and without the filter above it.
     *
     * The marks are asserted through what they are spoken as, and a chip
     * carries the same name; a screen with both would answer twice to the same
     * query. The filter has a test of its own.
     */
    private fun showAgenda(layout: AgendaLayout) {
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(mixed),
                    layout = layout,
                    onLayoutChange = {},
                    now = MOMENT,
                )
            }
        }
    }

    /** The same agenda with the row of chips above it, none of them turned off. */
    private fun showFilter() {
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(mixed),
                    layout = AgendaLayout.LIST,
                    onLayoutChange = {},
                    now = MOMENT,
                    filters = AgendaFilters(
                        collections = labels.values.map {
                            CollectionChoice(label = it, shown = true)
                        },
                    ),
                )
            }
        }
    }

    private fun readyState(sections: AgendaSections) = AgendaUiState.Ready(
        date = SHOWN_DAY,
        sections = sections,
    )

    private fun string(id: Int, vararg formatArgs: Any): String =
        compose.activity.getString(id, *formatArgs)

    private companion object {
        const val PERSONAL = "/notes/personal"
        const val WORK = "/notes/work"
        val SHOWN_DAY: LocalDate = LocalDate.of(2026, 7, 28)
        val MOMENT: LocalDateTime = SHOWN_DAY.atTime(9, 40)
    }
}
