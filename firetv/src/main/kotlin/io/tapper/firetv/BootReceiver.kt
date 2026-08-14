package io.tapper.firetv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager forgets every alarm on reboot - this is what re-arms whatever
 * is still upcoming. Reminders and recordings are independent stores, each
 * responsible for its own alarm scheduling; this is just the trigger.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as TapperApp
        app.reminders.rescheduleAll()
        app.recordings.rescheduleAll()
    }
}
