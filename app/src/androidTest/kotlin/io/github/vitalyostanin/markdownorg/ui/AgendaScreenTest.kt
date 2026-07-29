package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.TaskType
import java.time.LocalDate
import java.time.LocalTime

/**
 * The screen over a state built by hand.
 *
 * Nothing here goes through the ViewModel or the core: the point is what the
 * layouts draw, and the projections that feed them have their own tests on
 * the JVM.
 */
class AgendaScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sample = agenda(
        day(
            overdue = listOf(
                task(
                    heading = "Renew the certificate",
                    line = 1u,
                    priority = "A",
                    date = "2026-07-24",
                    daysOffset = -4,
                ),
            ),
            scheduledTimed = listOf(
                task(heading = "Daily standup", line = 2u, time = "09:30", repeater = "++7d"),
                task(heading = "Review pull requests", line = 3u, time = "13:00", priority = "A"),
            ),
            scheduledNoTime = listOf(task(heading = "Update the pins", line = 4u)),
            upcoming = listOf(
                task(
                    heading = "Quarterly report",
                    line = 5u,
                    timestampType = "DEADLINE",
                    daysOffset = 5,
                ),
            ),
        ),
    ).toSections()

    @Test
    fun timeLayoutShowsEverySection() {
        showAgenda(AgendaLayout.TIME)

        compose.onNodeWithText(string(R.string.agenda_section_overdue), ignoreCase = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Renew the certificate").assertIsDisplayed()
        compose.onNodeWithText("Daily standup").assertIsDisplayed()
        compose.onNodeWithText("Update the pins").assertIsDisplayed()
    }

    @Test
    fun timeLayoutDrawsTheHourAxisAndCollapsesTheEmptyStretch() {
        showAgenda(AgendaLayout.TIME)

        compose.onNodeWithText("09:00").assertIsDisplayed()
        // 10:00 through 12:59 is three empty hours, which is where collapsing
        // starts; the hours themselves must be gone.
        compose.onNodeWithText(
            string(R.string.agenda_free_between, "10:00", "13:00"),
        ).assertIsDisplayed()
        compose.onNodeWithText("11:00").assertDoesNotExist()
    }

    @Test
    fun timeLayoutMarksTheCurrentMoment() {
        showAgenda(AgendaLayout.TIME, now = LocalTime.of(10, 0))

        compose.onNodeWithContentDescription(string(R.string.agenda_now)).assertExists()
    }

    @Test
    fun anotherDayHasNoCurrentMomentMarker() {
        showAgenda(AgendaLayout.TIME, now = null)

        compose.onNodeWithContentDescription(string(R.string.agenda_now)).assertDoesNotExist()
    }

    @Test
    fun bothLayoutsShowTheSameTasks() {
        showAgenda(AgendaLayout.LIST)

        // The promise the two layouts make: same tasks, same wording, only a
        // different visual language.
        for (heading in headings) {
            compose.onNodeWithText(heading).assertExists()
        }
    }

    @Test
    fun switchingLayoutRedrawsTheSameTasks() {
        var layout by mutableStateOf(AgendaLayout.TIME)
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(sample, now = LocalTime.of(10, 0)),
                    layout = layout,
                    onLayoutChange = { layout = it },
                )
            }
        }

        compose.onNodeWithTag(AgendaLayout.LIST.testTag).performClick()
        compose.waitForIdle()

        assertEquals(AgendaLayout.LIST, layout)
        for (heading in headings) {
            compose.onNodeWithText(heading).assertExists()
        }
        // The axis belongs to the other layout and has to be gone with it.
        compose.onNodeWithText("09:00").assertDoesNotExist()
    }

    @Test
    fun anAgendaBeingRebuiltKeepsItsHeaderAndItsRows() {
        // The rebuild follows an edit, and what it produces differs from what
        // is on screen by one line. Replacing the whole screen with a spinner
        // takes away the layout switch and the place in the list.
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(sample, now = LocalTime.of(10, 0)).copy(refreshing = true),
                    layout = AgendaLayout.TIME,
                    onLayoutChange = {},
                )
            }
        }

        compose.onNodeWithTag(AgendaLayout.LIST.testTag).assertExists()
        compose.onNodeWithText("Renew the certificate").assertIsDisplayed()
        compose.onNodeWithTag("agenda-refreshing").assertExists()
    }

    @Test
    fun anAgendaThatIsNotBeingRebuiltShowsNoProgress() {
        showAgenda(AgendaLayout.TIME)

        compose.onNodeWithTag("agenda-refreshing").assertDoesNotExist()
    }

    @Test
    fun anOverdueCancelledTaskReadsTheSameInBothLayouts() {
        // The head of a task row — glyph, priority badge, heading — used to be
        // written out once per row kind, and the copies had drifted: the
        // overdue row on the axis dropped the strike-through that says the
        // task is no longer going to happen.
        val sections = agenda(
            day(
                overdue = listOf(
                    task(
                        heading = "Drop the endpoint",
                        taskType = TaskType.CANCELLED,
                        priority = "B",
                        date = "2026-07-26",
                        daysOffset = -2,
                    ),
                ),
            ),
        ).toSections()
        var layout by mutableStateOf(AgendaLayout.TIME)
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(sections, now = LocalTime.of(10, 0)),
                    layout = layout,
                    onLayoutChange = { layout = it },
                )
            }
        }

        compose.onNodeWithText("B").assertExists()
        val onAxis = decorationOf("Drop the endpoint")

        layout = AgendaLayout.LIST
        compose.waitForIdle()

        compose.onNodeWithText("B").assertExists()
        val inList = decorationOf("Drop the endpoint")
        assertEquals(
            "axis and list disagree",
            TextDecoration.LineThrough to TextDecoration.LineThrough,
            onAxis to inList,
        )
    }

    @Test
    fun everyControlInTheHeaderIsNamed() {
        // The controls used to be bare glyphs whose only label was the one
        // spoken by the screen reader — a sighted user found out what ◫ did by
        // trying it, and a device whose font lacks the character showed an
        // empty box.
        showAgenda(AgendaLayout.TIME)

        for (name in controls) {
            compose.onNodeWithContentDescription(string(name)).assertExists()
        }
    }

    @Test
    fun anEmptyAgendaSaysSo() {
        showAgenda(AgendaLayout.TIME, sections = agenda(day()).toSections())

        compose.onNodeWithText(string(R.string.agenda_empty)).assertIsDisplayed()
    }

    @Test
    fun aFailureShowsWhatWentWrong() {
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Failed("invalid directory: /nowhere"),
                    layout = AgendaLayout.TIME,
                    onLayoutChange = {},
                )
            }
        }

        compose.onNodeWithText(string(R.string.agenda_failed)).assertIsDisplayed()
        compose.onNodeWithText("invalid directory: /nowhere").assertIsDisplayed()
    }

    @Test
    fun aCancelledTaskKeepsItsHeadingVisible() {
        val sections = agenda(
            day(
                scheduledTimed = listOf(
                    task(
                        heading = "Drop the endpoint",
                        time = "16:00",
                        taskType = TaskType.CANCELLED,
                    ),
                ),
            ),
        ).toSections()
        showAgenda(AgendaLayout.TIME, sections = sections)

        // Struck through, not hidden: a cancelled task still occupies the
        // hour it was booked for.
        compose.onNodeWithText("Drop the endpoint").assertIsDisplayed()
        assertTrue(sections.timed.single().task.kind() == AgendaKind.CANCELLED)
    }

    private val controls = listOf(
        R.string.sync_now,
        R.string.settings_title,
        R.string.agenda_layout_time,
        R.string.agenda_layout_list,
    )

    private val headings = listOf(
        "Renew the certificate",
        "Daily standup",
        "Review pull requests",
        "Update the pins",
        "Quarterly report",
    )

    private fun showAgenda(
        layout: AgendaLayout,
        sections: AgendaSections = sample,
        now: LocalTime? = LocalTime.of(10, 0),
    ) {
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(sections, now),
                    layout = layout,
                    onLayoutChange = {},
                )
            }
        }
    }

    private fun readyState(sections: AgendaSections, now: LocalTime?) = AgendaUiState.Ready(
        date = LocalDate.of(2026, 7, 28),
        sections = sections,
        timeline = sections.toTimeline(now),
    )

    private fun string(id: Int, vararg formatArgs: Any): String =
        compose.activity.getString(id, *formatArgs)

    /**
     * How the text was actually laid out, which is where a strike-through
     * shows.
     *
     * Read off the unmerged tree: a row merges the semantics of everything in
     * it, and the layout of the merged node is that of its first text — the
     * time, not the heading.
     */
    private fun decorationOf(text: String): TextDecoration? {
        val laid = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(laid) }
        return laid.first().layoutInput.style.textDecoration
    }
}
