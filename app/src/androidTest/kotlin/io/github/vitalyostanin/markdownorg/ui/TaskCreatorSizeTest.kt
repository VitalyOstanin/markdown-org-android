package io.github.vitalyostanin.markdownorg.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

/**
 * That the chips of the screen hold their words in every language the app is
 * translated for.
 *
 * The rest of the suite runs in whatever language the emulator is set to,
 * which is English, and English is the short one. Wider words fit a row only
 * by being squeezed, and a chip squeezed far enough sets its label one letter
 * to a line: the keyword row on a phone held upright turned Без into a column
 * of three letters, and it took a look at a real phone to see it. A chip whose
 * label wraps has run out of room, whatever the reason, so the count of lines
 * is what this holds them to.
 */
@RunWith(Parameterized::class)
class TaskCreatorSizeTest(private val screen: Screen) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** One size, language and text scale the screen has to hold. */
    data class Screen(
        val name: String,
        val size: DpSize,
        val language: String,
        val textScale: Float,
    ) {
        override fun toString(): String = name
    }

    @Test
    fun everyChipKeepsItsLabelOnOneLine() {
        show()

        for (chip in CHIPS) {
            assertEquals("$chip wraps its label", 1, linesOf(chip))
        }
    }

    @Test
    fun theButtonsUnderThePhraseKeepTheirLabels() {
        show()

        for (button in PHRASE_BUTTONS) {
            assertEquals("$button wraps its label", 1, linesOf(button))
        }
    }

    @Test
    fun thePhraseFieldKeepsTheWidthOfTheForm() {
        // The buttons stand under the field rather than beside it, and this is
        // why: with both of them in its row the field was left a column narrow
        // enough to set its own label over three lines. The buttons' labels
        // were still one line apiece, so a test of them alone said nothing.
        show()

        val field = compose.onNodeWithTag("create-phrase").getUnclippedBoundsInRoot().width

        assertTrue(
            "the phrase field is $field wide on a screen of ${screen.size.width}",
            field > screen.size.width * 0.8f,
        )
    }

    private fun show() {
        compose.setContent {
            // The screen itself opens as a Dialog — a window of its own, which
            // takes its width from the platform and not from what a test asks
            // for. Its fields are where the chips live, so a box of the size in
            // question is what holds them here.
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(screen.textScale)) {
                // The strings come from the resources of the context, so the
                // language is set by handing the content a context configured
                // for it rather than by overriding the configuration alone.
                val spoken = Configuration(LocalConfiguration.current).apply {
                    setLocale(Locale.forLanguageTag(screen.language))
                }

                val speaking = LocalContext.current.createConfigurationContext(spoken)

                CompositionLocalProvider(
                    LocalContext provides speaking,
                    LocalResources provides speaking.resources,
                ) {
                    MarkdownOrgTheme {
                        Box(modifier = Modifier.size(screen.size)) {
                            CreatorFields(
                                state = remember { NewTaskState(PAIR.first().id) },
                                collections = PAIR,
                                weekStart = WeekStart.AUTO,
                                // The context handed in above is one made for
                                // a language rather than the activity, and the
                                // recogniser is reached through the activity.
                                // Nothing here presses the button that would
                                // open one: what is being measured is how the
                                // chips lay out.
                                dictation = { _, _ -> false },
                            )
                        }
                    }
                }
            }
        }
    }

    /** How many lines the label of the chip laid out over. */
    private fun linesOf(tag: String): Int {
        val laid = mutableListOf<TextLayoutResult>()

        compose.onNode(
            hasAnyAncestor(hasTestTag(tag)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(laid) }

        return laid.first().lineCount
    }

    private companion object {
        val SMALL = DpSize(320.dp, 640.dp)
        val USUAL = DpSize(411.dp, 891.dp)

        /** The chips a task with no date offers; the repeat row needs one first. */
        val CHIPS = listOf(
            "create-keyword-TODO",
            "create-keyword-DONE",
            "create-keyword-CANCELLED",
            "create-keyword-none",
            "create-priority-A",
            "create-priority-none",
            "create-kind-scheduled",
            "create-kind-deadline",
        )

        /** The two buttons under the phrase field. */
        val PHRASE_BUTTONS = listOf("create-phrase-speak", "create-phrase-parse")

        val PAIR = listOf(
            NotesCollection(
                id = "1",
                name = "Personal",
                path = "/notes/personal",
                inbox = "inbox.md",
            ),
            NotesCollection(id = "2", name = "Work", path = "/notes/work", inbox = "work.md"),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = listOf(
            Screen("small phone, upright, Russian", SMALL, "ru", 1.0f),
            Screen("small phone, upright, English", SMALL, "en", 1.0f),
            Screen("phone, upright, Russian", USUAL, "ru", 1.0f),
            Screen("phone, upright, English", USUAL, "en", 1.0f),
            Screen("phone, upright, Russian, large text", USUAL, "ru", 1.3f),
            Screen("phone, upright, English, large text", USUAL, "en", 1.3f),
        )
    }
}
