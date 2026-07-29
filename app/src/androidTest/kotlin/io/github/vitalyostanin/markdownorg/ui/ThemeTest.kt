package io.github.vitalyostanin.markdownorg.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/**
 * What the colour scheme hands to the Material components.
 *
 * The agenda paints most of itself from [io.github.vitalyostanin.markdownorg.ui.theme.AgendaColors],
 * but the components that come with Material — the sheet handle, the outlined
 * fields, the dividers — read the scheme directly, and a role holding someone
 * else's colour shows up there rather than here.
 */
class ThemeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun surfaceVariantIsNotTheContainerOfThePrimaryTone() {
        // Both were the same colour, so anything Material draws on a
        // `surfaceVariant` — the handle of the actions sheet, the fill of a
        // text field — came out in the saturated tone that means "selected".
        for (scheme in schemes()) {
            assertNotEquals(scheme.primaryContainer, scheme.surfaceVariant)
        }
    }

    @Test
    fun surfaceVariantStaysApartFromTheSurfaceItSitsOn() {
        // The role exists to separate a grouped control from the page behind
        // it; equal to `surface` it would separate nothing.
        for (scheme in schemes()) {
            assertNotEquals(scheme.surface, scheme.surfaceVariant)
        }
    }

    /** Both schemes, since a role can be right in one theme and wrong in the other. */
    private fun schemes(): List<ColorScheme> {
        val schemes = mutableListOf<ColorScheme>()
        compose.setContent {
            for (dark in listOf(false, true)) {
                MarkdownOrgTheme(darkTheme = dark) {
                    schemes += MaterialTheme.colorScheme
                }
            }
        }
        compose.waitForIdle()
        return schemes
    }
}
