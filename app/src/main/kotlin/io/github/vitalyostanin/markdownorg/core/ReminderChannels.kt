package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import io.github.vitalyostanin.markdownorg.R

/**
 * The two channels reminders are raised on.
 *
 * Two rather than one because the reader silences them separately: a digest at
 * nine in the morning is worth a sound, or is worth none, quite apart from a
 * meeting announced a quarter of an hour ahead. The platform gives that choice
 * only per channel, and a channel's importance cannot be lowered from here once
 * it exists — whatever the reader set in the system settings stands, which is
 * the point of declaring both up front rather than at the first notification.
 *
 * The identifiers are stored in the notifications the platform holds, so
 * renaming one would orphan whatever is on screen. The visible names are
 * resources and follow the phone's language on the next declaration.
 */
object ReminderChannels {

    /** An entry held at an hour, announced ahead of it. */
    const val TIMED = "reminders-timed"

    /** What a day holds, announced once in the morning. */
    const val DIGEST = "reminders-digest"

    /**
     * Declare both channels, or refresh what the reader sees of them.
     *
     * Cheap and repeatable: the platform treats a second declaration of a
     * channel that exists as a rename, leaving the importance and the sound
     * the reader chose alone. Called on every launch rather than once, so a
     * phone whose language changed shows the new names.
     */
    fun declare(context: Context) {
        val manager = NotificationManagerCompat.from(context)

        manager.createNotificationChannelsCompat(
            listOf(
                channel(
                    context = context,
                    id = TIMED,
                    name = R.string.reminders_channel_timed,
                    description = R.string.reminders_channel_timed_description,
                    // Announced to be acted on within the lead time, so it
                    // arrives as a banner with a sound rather than as a line
                    // in the drawer to be found later.
                    importance = NotificationManagerCompat.IMPORTANCE_HIGH,
                ),
                channel(
                    context = context,
                    id = DIGEST,
                    name = R.string.reminders_channel_digest,
                    description = R.string.reminders_channel_digest_description,
                    // The day is not urgent: it waits in the drawer until the
                    // reader looks, which is what a digest is for.
                    importance = NotificationManagerCompat.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    private fun channel(
        context: Context,
        id: String,
        name: Int,
        description: Int,
        importance: Int,
    ): NotificationChannelCompat = NotificationChannelCompat.Builder(id, importance)
        .setName(context.getString(name))
        .setDescription(context.getString(description))
        .build()
}
