package io.github.vitalyostanin.markdownorg.core

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * What the platform lets this application do about reminders, and how to ask
 * for what it does not.
 *
 * Two accesses, granted separately and refused separately:
 *
 * - **Notifications.** Asked for at runtime from Android 13; before that they
 *   are on unless the reader switched them off in the system settings, which
 *   `areNotificationsEnabled` reports either way.
 * - **Exact alarms.** Held from Android 13 by `USE_EXACT_ALARM`, which is
 *   granted at install and cannot be withdrawn while the application is
 *   installed, so there is nothing to ask for and nothing to lose (ADR-0045).
 *   Android 12 has no such permission and asks for `SCHEDULE_EXACT_ALARM` on
 *   a settings screen instead; refused, the platform still delivers within an
 *   hour of the time asked for — which is a reminder for a day and not for a
 *   meeting, so the settings say so rather than pretending the plan holds.
 *
 * Every answer is asked of the platform at the moment it is needed rather than
 * read once. Both are granted in screens outside this application, and the
 * documentation is explicit that revoking the exact-alarm access stops the
 * application and cancels its alarms.
 */
object ReminderAccess {

    /** Whether anything raised here would reach the screen. */
    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() && permissionHeld(context)

    /** Whether an alarm may be asked for to the minute. */
    fun exactAlarmsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false

        return alarms.canScheduleExactAlarms()
    }

    /**
     * The runtime permission to ask for, or nothing on a platform that has
     * none.
     *
     * An array because that is what the launcher takes, and empty rather than
     * null so the caller has one shape to handle.
     */
    fun notificationPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    /**
     * The system screen where notifications of this application are switched
     * on.
     *
     * For the reader who refused the dialog: the permission is asked once, and
     * after that the only way back is the settings.
     */
    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** The system screen where exact alarms are allowed, or nothing before 12. */
    fun exactAlarmSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.fromParts("package", context.packageName, null),
            )
        } else {
            null
        }

    private fun permissionHeld(context: Context): Boolean =
        notificationPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
}
