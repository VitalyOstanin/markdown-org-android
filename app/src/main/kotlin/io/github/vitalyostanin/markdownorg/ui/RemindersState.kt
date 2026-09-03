package io.github.vitalyostanin.markdownorg.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.vitalyostanin.markdownorg.core.ReminderAccess
import io.github.vitalyostanin.markdownorg.core.ReminderNotifications
import io.github.vitalyostanin.markdownorg.core.ReminderSettings
import io.github.vitalyostanin.markdownorg.core.Replanning
import java.time.LocalTime

/**
 * What the reader chose about reminders, and what the platform allows of it.
 *
 * Every change is written down and then planned again, rather than saved on
 * leaving the screen: the plan is what the choices are for, and a lead time
 * changed and forgotten about would take effect at the next fetch instead of
 * at once. Planning is a walk of the notes, so it goes to a coroutine and the
 * switch does not wait for it.
 *
 * The two accesses are read again whenever this screen comes back to the
 * front. They are granted in screens of the platform — one of them reachable
 * without leaving through the button here — and the notice that says an access
 * is missing has to go when it is granted, however the reader granted it.
 */
@Composable
internal fun rememberRemindersUi(): RemindersUi {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { ReminderSettings(context) }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var lead by remember { mutableStateOf(ReminderLead.of(settings.leadMinutes)) }
    var alsoAtStart by remember { mutableStateOf(settings.alsoAtStart) }
    var digestAt by remember { mutableStateOf(settings.digestAt) }
    var allowed by remember { mutableStateOf(ReminderAccess.notificationsAllowed(context)) }
    var exact by remember { mutableStateOf(ReminderAccess.exactAlarmsAllowed(context)) }

    // Asked for rather than run here: the walk of the notes outlives this
    // screen, and a scope belonging to the composition is cancelled the moment
    // the reader leaves it -- the preference written down and the alarms
    // unchanged. See `Replanning`.
    val replan = { Replanning.request(context) }
    val readAccess: (Any?) -> Unit = {
        allowed = ReminderAccess.notificationsAllowed(context)
        exact = ReminderAccess.exactAlarmsAllowed(context)
        // The alarms held were made under the access as it was: allowing exact
        // alarms turns the inexact ones into exact ones, and losing the access
        // has already cancelled them on the platform's side.
        replan()
    }
    LifecycleResumeEffect(context) {
        readAccess(null)
        onPauseOrDispose {}
    }
    // The settings screens answer "cancelled" however they went, so what they
    // did is read off the platform rather than off their result.
    val screen = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        readAccess,
    )
    val dialog = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        readAccess(granted)
        // The dialog is shown once per install. A second request returns the
        // refusal without showing anything, so a reader who refused once is
        // taken to the settings — otherwise the button here would do nothing
        // at all, twice.
        if (!ReminderAccess.notificationsAllowed(context)) {
            screen.launch(ReminderAccess.notificationSettings(context))
        }
    }

    return RemindersUi(
        enabled = enabled,
        onEnabledChange = { on ->
            enabled = on
            settings.enabled = on
            if (!on) {
                // What was raised stands in the drawer until it is dismissed,
                // and a digest from this morning or an entry announced a
                // quarter of an hour ago would outlive the switch that
                // stopped the reminders.
                ReminderNotifications.cancelAll(context)
            }
            replan()
            if (on && !allowed) {
                askForNotifications(context, dialog::launch, screen::launch)
            }
        },
        lead = lead,
        onLeadChange = { choice ->
            lead = choice
            settings.leadMinutes = choice.minutes
            replan()
        },
        alsoAtStart = alsoAtStart,
        onAlsoAtStartChange = { on ->
            alsoAtStart = on
            settings.alsoAtStart = on
            replan()
        },
        digestAt = digestAt,
        onDigestChange = { time: LocalTime ->
            digestAt = time
            settings.digestAt = time
            replan()
        },
        // Nothing is said about either access while reminders are off: neither
        // is asked for until there is something to announce.
        needsNotifications = enabled && !allowed,
        onGrantNotifications = { askForNotifications(context, dialog::launch, screen::launch) },
        needsExactAlarms = enabled && !exact,
        onAllowExactAlarms = { ReminderAccess.exactAlarmSettings(context)?.let(screen::launch) },
    )
}

/**
 * Ask for notifications the way this platform asks.
 *
 * Before Android 13 there is no request to make: notifications are on unless
 * they were switched off in the settings, which is then the only way back. On
 * the platforms that do have the dialog, what a refusal leads to is decided
 * where the dialog's answer arrives.
 */
private fun askForNotifications(
    context: Context,
    dialog: (Array<String>) -> Unit,
    screen: (Intent) -> Unit,
) {
    val permissions = ReminderAccess.notificationPermissions()

    if (permissions.isEmpty()) {
        screen(ReminderAccess.notificationSettings(context))
    } else {
        dialog(permissions)
    }
}
