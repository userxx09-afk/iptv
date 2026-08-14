package io.tapper.firetv.recordings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.tapper.firetv.TapperApp

/**
 * Bridges an exact alarm to the foreground service that actually captures
 * the stream.
 *
 * A BroadcastReceiver's onReceive must return quickly - it cannot itself run
 * a long background network download - so all this does is start or stop
 * [RecordingService] and let it do the work.
 */
class RecordingAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_START = "io.tapper.firetv.recordings.START"
        const val ACTION_STOP = "io.tapper.firetv.recordings.STOP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("recording_id") ?: return
        val app = context.applicationContext as TapperApp
        if (app.recordings.get(id) == null) return
        val serviceIntent = Intent(context, RecordingService::class.java)
            .putExtra("recording_id", id)
            .setAction(intent.action)
        when (intent.action) {
            // startForegroundService: the OS requires the service to promote
            // itself to foreground within a few seconds of this call or be
            // killed - RecordingService does that as the first thing it does.
            ACTION_START -> ContextCompat.startForegroundService(context, serviceIntent)
            ACTION_STOP -> context.startService(serviceIntent)
        }
    }
}
