package io.tapper.firetv.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.tapper.firetv.TapperApp
import io.tapper.firetv.data.Notifications

/**
 * Fires once, at a programme's start time.
 *
 * Deliberately does not attempt to deep-link straight into the channel: that
 * needs MainActivity to understand a "tune to X" intent it doesn't have
 * today, and getting that wrong (wrong source active, catalogue not loaded
 * yet) would fail silently in a way nobody watching TV would see the cause
 * of. A notification that opens the app is the reliable version of this;
 * an actual tuning deep-link is a reasonable follow-up once this exists and
 * is trusted.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("reminder_id") ?: return
        val app = context.applicationContext as TapperApp
        val reminder = app.reminders.get(id) ?: return
        Notifications.showReminder(context, reminder)
        app.reminders.remove(id)  // one-shot: consumed once fired, never repeats
    }
}
