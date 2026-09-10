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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

/**
 * That every lead time can be reached on the screen it is offered on.
 *
 * The five answers carry translated words. In a row of chips they did not fit
 * the width of a phone held upright — first sliding past the edge of a row
 * that scrolled sideways, then, once the row wrapped, spending two lines of a
 * column already thirty settings long. They are a list now, and what this
 * holds down is that opening it puts every one of the five on the screen: a
 * choice the reader cannot see is one they cannot make.
 */
@RunWith(Parameterized::class)
class RemindersSectionSizeTest(private val screen: Screen) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** One size, language and text scale the section has to hold. */
    data class Screen(
        val name: String,
        val size: DpSize,
        val language: String,
        val textScale: Float,
    ) {
        override fun toString(): String = name
    }

    @Test
    fun everyLeadTimeIsOnScreenAtOnce() {
        show()

        compose.onNodeWithTag("settings-reminders-lead").performClick()

        for (lead in DefaultLead.entries) {
            compose.onNodeWithTag(lead.testTag).assertIsDisplayed()
        }
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
                        // The choice alone rather than the whole section: what
                        // is in question is whether its five answers fit the
                        // screen, and the notices and the digest above would
                        // push them off a box this short for reasons of their
                        // own.
                        Box(modifier = Modifier.size(screen.size)) {
                            LeadChoice(current = DefaultLead.NONE, onChange = {})
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val SMALL = DpSize(320.dp, 640.dp)
        val USUAL = DpSize(411.dp, 891.dp)

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
