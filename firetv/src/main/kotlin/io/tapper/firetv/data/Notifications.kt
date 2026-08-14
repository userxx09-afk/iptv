package io.tapper.firetv.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.tapper.firetv.MainActivity

/**
 * One notification channel per concern, created once at app startup.
 *
 * Posting to a channel that doesn't exist yet silently drops the
 * notification on API 26+ rather than erroring - creating both channels
 * unconditionally on launch is what keeps that failure mode from ever
 * happening instead of surfacing as "reminders just don't work".
 *
 * Icons here are stock system drawables rather than custom monochrome ones -
 * functionally correct, just not styled; worth a real icon later.
 */
object Notifications {
    const val REMINDERS_CHANNEL = "reminders"
    const val RECORDING_CHANNEL = "recording"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(REMINDERS_CHANNEL, "Program reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(RECORDING_CHANNEL, "Recording in progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun showReminder(context: Context, reminder: Reminder) {
        val openApp = PendingIntent.getActivity(
            context, reminder.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, REMINDERS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(reminder.programmeTitle)
            .setContentText(reminder.channelName + " is starting now")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // POST_NOTIFICATIONS can be denied on API 33+; without this guard that
        // would throw a SecurityException out of a BroadcastReceiver instead
        // of just skipping the alert.
        runCatching { nm.notify(reminder.id.hashCode(), notification) }
    }

    fun recordingNotification(context: Context, channelName: String): Notification =
        NotificationCompat.Builder(context, RECORDING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Recording")
            .setContentText(channelName)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
