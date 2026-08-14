package io.tapper.firetv.recordings

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.tapper.firetv.TapperApp
import io.tapper.firetv.data.Notifications
import io.tapper.firetv.data.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * Captures a live stream to a file for the scheduled window.
 *
 * No transcoding or remuxing: this opens the same URL playback already uses
 * and writes the response body straight to disk. That's enough for a plain
 * MPEG-TS stream (the common case for Xtream's "ts" extension and most M3U
 * providers) to produce a directly playable file; see [io.tapper.firetv.data.RecordingStore]
 * for the caveat on fragmented/encrypted HLS sources.
 *
 * Runs as a foreground service because a background coroutine tied to
 * nothing gets killed by the OS within minutes on a device like a Fire TV
 * stick - the ongoing notification is what keeps this alive for the length
 * of a scheduled recording.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val connections = HashMap<String, HttpURLConnection>()

    // Ids whose stop was requested by this service, not caused by the
    // network - a read failure for one of these is the intended shutdown
    // path (disconnect() unblocks a blocked read via IOException), not a
    // real recording failure worth marking FAILED.
    private val stopping = Collections.synchronizedSet(HashSet<String>())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("recording_id")
        val app = applicationContext as TapperApp
        when (intent?.action) {
            RecordingAlarmReceiver.ACTION_START -> id?.let { startRecording(app, it) }
            RecordingAlarmReceiver.ACTION_STOP -> id?.let { stopRecording(it) }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(app: TapperApp, id: String) {
        if (jobs.containsKey(id)) return  // a duplicate alarm must not open a second file
        val recording = app.recordings.get(id) ?: return

        // Must happen within seconds of the service starting (see
        // RecordingAlarmReceiver) - done first, before any I/O that could
        // stall.
        startForeground(id.hashCode(), Notifications.recordingNotification(this, recording.channelName))

        val outFile = File(
            getExternalFilesDir("recordings") ?: filesDir,
            sanitizeFileName(recording.channelName) + "-" + recording.startUtc + ".ts",
        )
        app.recordings.upsert(
            recording.copy(status = RecordingStatus.RECORDING, filePath = outFile.absolutePath)
        )

        jobs[id] = scope.launch {
            var failed = false
            try {
                val conn = (URL(recording.streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "TapperIPTV/0.3")
                }
                connections[id] = conn
                if (conn.responseCode !in 200..299) {
                    failed = true
                } else {
                    outFile.parentFile?.mkdirs()
                    conn.inputStream.use { input ->
                        outFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // stopRecording()'s disconnect() is what normally ends the
                // read loop above - it surfaces here as an IOException on the
                // blocked read, which is the expected shutdown path, not a
                // real failure.
                failed = id !in stopping
            } finally {
                connections.remove(id)?.let { runCatching { it.disconnect() } }
            }
            jobs.remove(id)
            stopping.remove(id)
            app.recordings.get(id)?.let {
                app.recordings.upsert(it.copy(status = if (failed) RecordingStatus.FAILED else RecordingStatus.DONE))
            }
            // Only the job that just finished decides whether the service is
            // done, and only after cleanup above has actually run - stopping
            // eagerly from stopRecording() would tear down the coroutine
            // scope (onDestroy) before the file got closed.
            if (jobs.isEmpty()) stopSelf()
        }
    }

    /** The read loop above is a blocking call, not a suspend point, so
     *  cancelling its coroutine alone would not unblock it until network
     *  activity happened to resume on its own. Disconnecting the live
     *  connection is what actually stops it, immediately. */
    private fun stopRecording(id: String) {
        stopping.add(id)
        connections[id]?.let { runCatching { it.disconnect() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun sanitizeFileName(name: String): String =
        name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifBlank { "recording" }
}
