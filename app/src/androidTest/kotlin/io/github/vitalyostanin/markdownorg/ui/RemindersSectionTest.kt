package io.github.vitalyostanin.markdownorg.ui

import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.MarkdownOrgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

/** The part of the settings that says whether the reader is told what is coming. */
class RemindersSectionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theTermsAreHiddenUntilAnythingIsAnnouncedAtAll() {
        show(RemindersUi(enabled = false))

        compose.onNodeWithTag("settings-reminders-enabled").assertIsDisplayed()
        compose.onNodeWithTag("settings-reminders-lead").assertDoesNotExist()
        compose.onNodeWithTag("settings-reminders-at-start").assertDoesNotExist()
        compose.onNodeWithTag("settings-reminders-digest").assertDoesNotExist()
    }

    @Test
    fun switchingItOnBringsTheTermsWithIt() {
        show(RemindersUi(enabled = true))

        compose.onNodeWithTag("settings-reminders-lead").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-reminders-at-start").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-reminders-digest").performScrollTo().assertIsDisplayed()
    }

    /**
     * The answer is reached the way the reader reaches it: the list is
     * scrolled to and opened, and the entry is pressed in the menu that opens
     * over the screen rather than scrolled to in its own right.
     */
    @Test
    fun theChosenLeadTimeIsReportedAsTheEntryThatWasPressed() {
        var chosen: ReminderLead? = null
        show(RemindersUi(enabled = true, onLeadChange = { chosen = it }))

        compose.onNodeWithTag("settings-reminders-lead").performScrollTo().performClick()
        compose.onNodeWithTag(ReminderLead.HOUR.testTag).performClick()

        assertEquals(ReminderLead.HOUR, chosen)
    }

    /**
     * The hour is written on the clock the device is set to, and not as the
     * stored value reads.
     *
     * On a device set to a 24-hour clock the two agree, which is why the
     * expectation is built rather than spelled out: what this holds down is
     * that the label comes from [timeLabel], the way every other hour on the
     * screen does. A 12-hour device is where the difference shows — `21:00`
     * against `9:00 PM`.
     */
    @Test
    fun theDigestHourIsWrittenOnTheClockOfTheDevice() {
        val at = LocalTime.of(21, 0)
        show(RemindersUi(enabled = true, digestAt = at))

        val locale = Locale.getDefault()
        val use24Hour = DateFormat.is24HourFormat(compose.activity)

        compose.onNodeWithTag("settings-reminders-digest")
            .performScrollTo()
            .assertTextEquals(timeLabel(at, locale, use24Hour))
    }

    /** A refusal of the platform's, and the screen that takes it back. */
    @Test
    fun aRefusalOfNotificationsIsStatedWhereTheChoiceIsMade() {
        var asked = false
        show(
            RemindersUi(
                enabled = true,
                needsNotifications = true,
                onGrantNotifications = { asked = true },
            ),
        )

        compose.onNodeWithText(string(R.string.settings_reminders_no_notifications))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("settings-reminders-grant").performScrollTo().performClick()

        assertTrue(asked)
    }

    @Test
    fun exactAlarmsThatAreNotAllowedAreStatedSeparately() {
        var asked = false
        show(
            RemindersUi(
                enabled = true,
                needsExactAlarms = true,
                onAllowExactAlarms = { asked = true },
            ),
        )

        compose.onNodeWithText(string(R.string.settings_reminders_no_exact_alarms))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("settings-reminders-allow-exact").performScrollTo().performClick()

        assertTrue(asked)
    }

    /**
     * Neither access is asked for while nothing is announced.
     *
     * The refusals are real either way — the platform does not withdraw them
     * because a switch is off — and stating them there would be the screen
     * complaining about a permission for a feature the reader has just turned
     * off.
     */
    @Test
    fun nothingIsAskedForWhileNothingIsAnnounced() {
        show(RemindersUi(enabled = false, needsNotifications = true, needsExactAlarms = true))

        compose.onNodeWithTag("settings-reminders-grant").assertDoesNotExist()
        compose.onNodeWithTag("settings-reminders-allow-exact").assertDoesNotExist()
    }

    private fun show(reminders: RemindersUi) {
        compose.setContent {
            MarkdownOrgTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    RemindersSection(reminders)
                }
            }
        }
    }

    private fun string(id: Int): String = compose.activity.getString(id)
}
