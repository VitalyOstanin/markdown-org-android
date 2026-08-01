package io.github.vitalyostanin.markdownorg.ui

import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalProvidableLocaleList
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDecoration
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType
import java.time.LocalDate
import java.time.LocalDateTime

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
                    timestampType = TimestampType.DEADLINE,
                    daysOffset = 5,
                ),
            ),
        ),
    ).toSections()

    /**
     * Overdue entries of every age, as a file kept for years holds them: a
     * repeat missed this week next to a date from three years ago.
     */
    private val aged = agenda(
        day(
            overdue = listOf(
                task(
                    heading = "Missed the standup",
                    line = 1u,
                    repeater = "++7d",
                    date = "2026-07-25",
                    daysOffset = -3,
                ),
                task(heading = "Pay the tax", line = 2u, date = "2026-07-26", daysOffset = -2),
                task(
                    heading = "Service the car",
                    line = 3u,
                    date = "2026-03-02",
                    daysOffset = -152,
                ),
                task(heading = "Eye clinic", line = 4u, date = "2021-04-02", daysOffset = -1947),
            ),
        ),
    ).toSections()

    @Test
    fun timeLayoutShowsEverySection() {
        showAgenda(AgendaLayout.TIME)

        compose.onNodeWithText(string(R.string.agenda_section_overdue_recent), ignoreCase = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Renew the certificate").assertIsDisplayed()
        compose.onNodeWithText("Daily standup").assertIsDisplayed()
        compose.onNodeWithText("Update the pins").assertIsDisplayed()
    }

    @Test
    fun timeLayoutDrawsTheHourAxisAndCollapsesTheEmptyStretch() {
        showAgenda(AgendaLayout.TIME)

        // Written through the same formatter the axis uses rather than as
        // `09:00`: the label follows the locale and the clock the device is
        // set to, and the literal held on a 24-hour Russian phone only.
        compose.onNodeWithText(hour(9)).assertIsDisplayed()
        // 10:00 through 12:59 is three empty hours, which is where collapsing
        // starts; the hours themselves must be gone.
        compose.onNodeWithText(
            string(R.string.agenda_free_between, hour(10), hour(13)),
        ).assertIsDisplayed()
        compose.onNodeWithText(hour(11)).assertDoesNotExist()
    }

    @Test
    fun timeLayoutMarksTheCurrentMoment() {
        showAgenda(AgendaLayout.TIME, now = MID_MORNING)

        compose.onNodeWithContentDescription(string(R.string.agenda_now)).assertExists()
    }

    @Test
    fun anotherDayHasNoCurrentMomentMarker() {
        showAgenda(AgendaLayout.TIME, now = MID_MORNING.plusDays(1))

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
    fun theOldestBandOpensFoldedAndUnfoldsWhenItsHeadingIsTapped() {
        showAgenda(AgendaLayout.LIST, sections = aged)

        // What can be acted on today is on screen; the archive is behind its
        // heading, which still says how much is in it.
        compose.onNodeWithText("Missed the standup").assertIsDisplayed()
        compose.onNodeWithText("Eye clinic").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.agenda_section_overdue_long), ignoreCase = true)
            .assertIsDisplayed()

        compose.onNodeWithText(string(R.string.agenda_section_overdue_long), ignoreCase = true)
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Eye clinic").assertIsDisplayed()
    }

    @Test
    fun aFoldedBandStaysFoldedAcrossTheLayoutSwitch() {
        var layout by mutableStateOf(AgendaLayout.LIST)
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(aged),
                    layout = layout,
                    onLayoutChange = { layout = it },
                    now = MID_MORNING,
                )
            }
        }

        // Folding is an answer about the agenda, not about one way of drawing
        // it: the other layout has to come up with the same bands folded.
        compose.onNodeWithText(string(R.string.agenda_section_overdue_recent), ignoreCase = true)
            .performClick()
        compose.onNodeWithTag(AgendaLayout.TIME.testTag).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Pay the tax").assertDoesNotExist()
        compose.onNodeWithText("Missed the standup").assertIsDisplayed()
    }

    @Test
    fun anAgeNoLongerNewsIsNotSpelledOut() {
        showAgenda(AgendaLayout.LIST, sections = aged)

        compose.onNodeWithText(
            string(R.string.agenda_section_overdue_earlier),
            ignoreCase = true,
        ).assertIsDisplayed()
        // The row from May carries its date and its band; the sentence about
        // how many days that is would take the heading's width.
        compose.onNodeWithText(daysOverdue(152), substring = true).assertDoesNotExist()
        compose.onNodeWithText(daysOverdue(2), substring = true).assertIsDisplayed()
    }

    @Test
    fun switchingLayoutRedrawsTheSameTasks() {
        var layout by mutableStateOf(AgendaLayout.TIME)
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = readyState(sample),
                    layout = layout,
                    onLayoutChange = { layout = it },
                    now = MID_MORNING,
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
                    state = readyState(sample).copy(refreshing = true),
                    layout = AgendaLayout.TIME,
                    onLayoutChange = {},
                    now = MID_MORNING,
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
                    state = readyState(sections),
                    layout = layout,
                    onLayoutChange = { layout = it },
                    now = MID_MORNING,
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
                    state = AgendaUiState.Failed(
                        IllegalStateException("invalid directory: /nowhere").toAgendaMessage(),
                    ),
                    layout = AgendaLayout.TIME,
                    onLayoutChange = {},
                )
            }
        }

        compose.onNodeWithText(string(R.string.agenda_failed)).assertIsDisplayed()
        compose.onNodeWithText("invalid directory: /nowhere").assertIsDisplayed()
    }

    /**
     * `03.07` is the third of July in Russian and the seventh of March in
     * English, and the column gives no clue which is meant. The order of the
     * two parts has to come from the locale the screen is drawn in.
     */
    @Test
    fun theDateOfAnOverdueRowFollowsTheLocaleOfTheScreen() {
        val overdue = agenda(
            day(
                overdue = listOf(
                    task(heading = "Renew certificate", date = "2026-07-24", daysOffset = -4),
                ),
            ),
        ).toSections()

        showAgenda(AgendaLayout.LIST, sections = overdue, locale = Locale("en-US"))

        compose.onNodeWithText("7/24", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * The other half of the pair above, and what tells the two apart: the
     * same row on the same device draws `24.07` for a Russian reader. It also
     * says the locale of the composition is what decides, since the emulator
     * itself runs in neither of the two on purpose.
     */
    @Test
    fun theSameOverdueRowReadsTheOtherWayRoundInRussian() {
        val overdue = agenda(
            day(
                overdue = listOf(
                    task(heading = "Renew certificate", date = "2026-07-24", daysOffset = -4),
                ),
            ),
        ).toSections()

        showAgenda(AgendaLayout.LIST, sections = overdue, locale = Locale("ru-RU"))

        compose.onNodeWithText("24.07", useUnmergedTree = true).assertIsDisplayed()
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
        now: LocalDateTime = MID_MORNING,
        locale: Locale? = null,
    ) {
        compose.setContent {
            MarkdownOrgTheme {
                val screen = @Composable {
                    AgendaScreen(
                        state = readyState(sections),
                        layout = layout,
                        onLayoutChange = {},
                        now = now,
                    )
                }

                if (locale == null) {
                    screen()
                } else {
                    // Both LocalLocale and LocalLocaleList are read-only —
                    // the first computes from the head of the second, and the
                    // second reads the owner — so this is the one a test can
                    // provide.
                    CompositionLocalProvider(
                        LocalProvidableLocaleList provides LocaleList(listOf(locale)),
                        content = screen,
                    )
                }
            }
        }
    }

    private fun readyState(sections: AgendaSections) = AgendaUiState.Ready(
        date = SHOWN_DAY,
        sections = sections,
    )

    private fun string(id: Int, vararg formatArgs: Any): String =
        compose.activity.getString(id, *formatArgs)

    /** How the row words its age, in the plural form the count asks for. */
    private fun daysOverdue(days: Int): String = compose.activity.resources
        .getQuantityString(R.plurals.agenda_days_overdue, days, days)

    /** The label of a whole hour, as the device would write it. */
    private fun hour(of: Int): String = hourLabel(
        of,
        compose.activity.resources.configuration.locales[0],
        DateFormat.is24HourFormat(compose.activity),
    )

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

    private companion object {
        /** The day every state here is for. */
        val SHOWN_DAY: LocalDate = LocalDate.of(2026, 7, 28)

        /**
         * The moment the screen is shown at, on that day. Fixed rather than
         * read from the clock: the marker line is drawn only while the two
         * agree, and a test that took the real time would move with it.
         */
        val MID_MORNING: LocalDateTime = SHOWN_DAY.atTime(10, 0)
    }
}
