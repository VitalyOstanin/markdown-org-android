package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.vitalyostanin.markdownorg.core.Component
import io.github.vitalyostanin.markdownorg.core.LicenceGroup
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Rule
import org.junit.Test

/**
 * The screen that answers "what else is in this application, and under what
 * terms" — the one place a recipient of the APK can read the notices without
 * finding the repository first.
 */
class LicensesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val catalog = listOf(
        LicenceGroup(
            id = "Apache-2.0",
            name = "Apache License 2.0",
            text = "The whole Apache text, all of it.",
            url = "",
            usedBy = listOf(
                Component("androidx.compose.ui:ui", "1.11.4", "https://cs.android.com"),
                Component("openssl", "3.6.3", "https://github.com/openssl/openssl"),
            ),
        ),
        LicenceGroup(
            id = "GPL-2.0-only WITH libgit2 linking exception",
            name = "GNU General Public License v2.0 with the libgit2 linking exception",
            text = "Linking exception, then the GPL.",
            url = "",
            usedBy = listOf(Component("libgit2", "1.9.6", "https://github.com/libgit2/libgit2")),
        ),
    )

    @Test
    fun everyLicenceIsListedWithWhatItCovers() {
        show()

        compose.onNodeWithText("Apache License 2.0").assertIsDisplayed()
        compose.onNodeWithText(
            "androidx.compose.ui:ui 1.11.4",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "libgit2 1.9.6",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
    }

    /**
     * Apache-2.0 §4(a) asks for the text itself to travel with the binary, and
     * a list of names is not that text. It stays folded away because the
     * texts are longer than everything else on the screen put together.
     */
    @Test
    fun theTextIsThereBehindTheEntry() {
        show()

        compose.onAllNodesWithText("Apache License 2.0").onFirst().performClick()

        compose.onNodeWithText("The whole Apache text, all of it.").assertIsDisplayed()
    }

    /** A statically linked GPL component is named as such, not folded into the rest. */
    @Test
    fun theLinkingExceptionIsNamed() {
        show()

        compose
            .onNodeWithText("GPL-2.0-only WITH libgit2 linking exception", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A build with no lists bundled says so rather than showing an empty page. */
    @Test
    fun anEmptyCatalogSaysWhy() {
        compose.setContent {
            MarkdownOrgTheme {
                LicensesScreen(catalog = emptyList(), onDismiss = {})
            }
        }

        compose.onNodeWithTag("licences-unavailable").assertIsDisplayed()
    }

    private fun show() {
        compose.setContent {
            MarkdownOrgTheme {
                LicensesScreen(catalog = catalog, onDismiss = {})
            }
        }
    }
}
