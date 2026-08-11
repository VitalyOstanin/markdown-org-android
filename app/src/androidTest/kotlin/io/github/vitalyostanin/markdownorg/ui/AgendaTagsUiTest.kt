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
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.DeclaredTag
import io.github.vitalyostanin.markdownorg.core.TagDeclaration
import io.github.vitalyostanin.markdownorg.core.mergeTagDictionaries
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The tag filter on the screen: choosing one, and reading what one means.
 *
 * What a tag takes is settled on the JVM; what is only answerable here is
 * whether the menu offers the merged names, whether picking one narrows the
 * agenda, and whether the dictionary the merge produced can be read at all.
 */
class AgendaTagsUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Two collections, and only one of them ever heard of `WORK`. */
    private val tags = mergeTagDictionaries(
        listOf(
            TagDeclaration(
                collection = "Work",
                tags = listOf(
                    DeclaredTag(
                        name = "WORK",
                        include = listOf("work"),
                        exclude = listOf("archive"),
                    ),
                ),
            ),
            TagDeclaration(
                collection = "Personal",
                tags = listOf(DeclaredTag(name = "REST", pattern = "!")),
            ),
        ),
    )

    private val mixed = agenda(
        day(
            scheduledNoTime = listOf(
                task(heading = "Write the report", file = "/notes/work/work-plan.md"),
                task(heading = "Pay the tax", file = "/notes/personal/bills.md"),
                task(heading = "Read the old minutes", file = "/notes/work/work-archive.md"),
            ),
        ),
    ).toSections()

    @Test
    fun theMenuOffersEveryMergedName() {
        showAgenda()

        compose.onNodeWithTag("tag-menu").performClick()

        compose.onNodeWithTag("tag-WORK").assertIsDisplayed()
        compose.onNodeWithTag("tag-REST").assertIsDisplayed()
        // The way back is an entry of its own rather than picking the tag
        // again: a filter has to be releasable without knowing what is on.
        compose.onNodeWithTag("tag-none").assertIsDisplayed()
    }

    @Test
    fun pickingATagNarrowsTheAgendaAndReleasingItGivesTheRowsBack() {
        var current by mutableStateOf<String?>(null)
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Ready(
                        date = SHOWN_DAY,
                        sections = mixed.tagged(current, tags),
                    ),
                    layout = AgendaLayout.LIST,
                    onLayoutChange = {},
                    now = MOMENT,
                    filters = AgendaFilters(
                        tags = tags,
                        currentTag = current,
                        onTagChange = { current = it },
                    ),
                )
            }
        }

        compose.onNodeWithTag("tag-menu").performClick()
        compose.onNodeWithTag("tag-WORK").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Write the report").assertIsDisplayed()
        compose.onNodeWithText("Pay the tax").assertDoesNotExist()
        // Refused by the tag that would otherwise take it, so it is not in
        // `WORK` -- and the file name is the whole reason, not the directory.
        compose.onNodeWithText("Read the old minutes").assertDoesNotExist()

        compose.onNodeWithTag("tag-menu").performClick()
        compose.onNodeWithTag("tag-none").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Pay the tax").assertIsDisplayed()
    }

    @Test
    fun theDictionaryNamesEveryPatternAndWhoDeclaredIt() {
        showAgenda()

        compose.onNodeWithTag("tag-menu").performClick()
        compose.onNodeWithTag("tag-explain").performClick()

        compose.onNodeWithTag("tag-dictionary").assertIsDisplayed()
        compose.onNodeWithText(
            string(R.string.agenda_tag_origin, string(R.string.agenda_tag_takes, "work"), "Work"),
        ).assertIsDisplayed()
        val keptOut = string(R.string.agenda_tag_keeps_out, "archive")
        compose.onNodeWithText(string(R.string.agenda_tag_origin, keptOut, "Work"))
            .assertIsDisplayed()

        compose.onNodeWithTag("tag-dictionary-close").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("tag-dictionary").assertDoesNotExist()
    }

    @Test
    fun notesThatDeclareNoTagsLeaveTheScreenWithoutTheMenu() {
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Ready(date = SHOWN_DAY, sections = mixed),
                    layout = AgendaLayout.LIST,
                    onLayoutChange = {},
                    now = MOMENT,
                )
            }
        }

        compose.onNodeWithText("Pay the tax").assertIsDisplayed()
        compose.onNodeWithTag("tag-menu").assertDoesNotExist()
    }

    private fun showAgenda() {
        compose.setContent {
            MarkdownOrgTheme {
                AgendaScreen(
                    state = AgendaUiState.Ready(date = SHOWN_DAY, sections = mixed),
                    layout = AgendaLayout.LIST,
                    onLayoutChange = {},
                    now = MOMENT,
                    filters = AgendaFilters(tags = tags),
                )
            }
        }
    }

    private fun string(id: Int, vararg formatArgs: Any): String =
        compose.activity.getString(id, *formatArgs)

    private companion object {
        val SHOWN_DAY: LocalDate = LocalDate.of(2026, 7, 28)
        val MOMENT: LocalDateTime = SHOWN_DAY.atTime(9, 40)
    }
}
