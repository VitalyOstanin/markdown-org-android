package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        PERSONAL to CollectionLabel(id = "1", name = "Personal", tone = 0),
        WORK to CollectionLabel(id = "2", name = "Work", tone = 1),
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

        compose.onNodeWithText("Personal").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
    }

    @Test
    fun theTimeLayoutCarriesTheMarksAsWell() {
        showAgenda(AgendaLayout.TIME)

        // Both layouts show the same agenda; a mark on one of them only would
        // make which collection a task is in a property of the layout.
        compose.onNodeWithText("Personal").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
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
                    collections = labels.values.map { label ->
                        CollectionChoice(label = label, shown = label.id in shown)
                    },
                    onCollectionShown = { id, on ->
                        shown = if (on) shown + id else shown - id
                    },
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
     * The agenda with the marks and without the filter above it.
     *
     * The chips carry the same names as the marks, so a screen showing both
     * has every name twice and "the row says where it is from" cannot be
     * asserted by looking for one. The filter has a test of its own.
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

    private fun readyState(sections: AgendaSections) = AgendaUiState.Ready(
        date = SHOWN_DAY,
        sections = sections,
    )

    private companion object {
        const val PERSONAL = "/notes/personal"
        const val WORK = "/notes/work"
        val SHOWN_DAY: LocalDate = LocalDate.of(2026, 7, 28)
        val MOMENT: LocalDateTime = SHOWN_DAY.atTime(9, 40)
    }
}
