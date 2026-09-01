package io.github.vitalyostanin.markdownorg.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

/**
 * That the three answers to where a week begins stay readable words.
 *
 * They used to be a row of chips held side by side whatever the width: on a
 * phone held upright in Russian the last of them, "Воскресенья", was squeezed
 * until its label wrapped a letter to a line and the chip grew into a column
 * of characters. They are a list now, which names one answer in place and
 * offers the rest on a tap.
 *
 * Two things are held down, and neither is the text itself. That the closed
 * list stays one line high says its word was not broken to fit; that opening
 * it shows all three says no answer is out of reach.
 */
@RunWith(Parameterized::class)
class WeekStartChoiceSizeTest(private val screen: Screen) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** One size, language and text scale the choice has to hold. */
    data class Screen(
        val name: String,
        val size: DpSize,
        val language: String,
        val textScale: Float,
    ) {
        override fun toString(): String = name
    }

    @Test
    fun everyWeekStartIsOnScreenAtOnce() {
        show()

        compose.onNodeWithTag("settings-week-start").performClick()

        for (option in WeekStart.entries) {
            compose.onNodeWithTag(option.testTag).assertIsDisplayed()
        }
    }

    @Test
    fun theAnswerInPlaceIsNotSqueezedIntoAColumnOfLetters() {
        show()

        val closed = compose.onNodeWithTag("settings-week-start")
            .getUnclippedBoundsInRoot()
            .height

        assertTrue(
            "the list stands $closed tall, so the answer in it wrapped",
            closed <= ONE_LINE * screen.textScale,
        )
    }

    private fun show() {
        compose.setContent {
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
                        // The choice alone rather than the whole screen: what
                        // is in question is whether its three answers fit.
                        Box(modifier = Modifier.size(screen.size)) {
                            WeekStartChoice(current = WeekStart.AUTO, onChange = {})
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /**
         * How tall a field holding one line of text stands, before the text
         * scale is applied. Material's outlined field is 56dp; the allowance
         * above that is for the rounding a scaled font brings, and is far
         * short of the height a second line would add.
         */
        val ONE_LINE = 72.dp

        val SMALL = DpSize(320.dp, 640.dp)
        val PHONE = DpSize(365.dp, 800.dp)
        val USUAL = DpSize(411.dp, 891.dp)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = listOf(
            Screen("small phone, upright, Russian", SMALL, "ru", 1.0f),
            Screen("small phone, upright, English", SMALL, "en", 1.0f),
            Screen("phone, upright, Russian", PHONE, "ru", 1.0f),
            Screen("phone, upright, English", PHONE, "en", 1.0f),
            Screen("wide phone, upright, Russian", USUAL, "ru", 1.0f),
            Screen("wide phone, upright, English", USUAL, "en", 1.0f),
            Screen("phone, upright, Russian, large text", PHONE, "ru", 1.3f),
            Screen("phone, upright, English, large text", PHONE, "en", 1.3f),
        )
    }
}
