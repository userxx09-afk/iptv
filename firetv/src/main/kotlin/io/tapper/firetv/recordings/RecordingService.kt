package io.tapper.firetv.recordings

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.tapper.firetv.TapperApp
import android.app.NotificationManager
import io.tapper.firetv.data.Notifications
import io.tapper.firetv.data.Recording
import io.tapper.firetv.data.RecordingLocationStore
import io.tapper.firetv.data.RecordingStatus
import io.tapper.firetv.data.SmbStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
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
    private val jobs = Collections.synchronizedMap(HashMap<String, Job>())
    private val connections = Collections.synchronizedMap(HashMap<String, HttpURLConnection>())

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
            RecordingAlarmReceiver.ACTION_DOWNLOAD -> {
                // Started with startForegroundService, so it has to go
                // foreground straight away even if the queue turns out empty.
                startForeground(DOWNLOAD_NOTIFICATION, Notifications.downloadNotification(this, "Getting ready", -1))
                kickDownloads(app)
            }
        }
        return START_NOT_STICKY
    }

    // ---------------- downloads ----------------

    /** The one download running now. One at a time: most IPTV plans allow
     *  one or two connections, and a second download would also take the
     *  one the TV needs to watch anything while it runs. */
    private var downloading: String? = null

    @Synchronized
    private fun kickDownloads(app: TapperApp) {
        if (downloading != null) return
        val next = app.recordings.pendingDownloads().firstOrNull()
        if (next == null) {
            if (jobs.isEmpty()) {
                stopSelf()
            } else {
                // A live recording is still going: put its notice back as
                // the foreground one in place of the download's.
                jobs.keys.firstNotNullOfOrNull { app.recordings.get(it) }?.let {
                    startForeground(it.id.hashCode(), Notifications.recordingNotification(this, it.channelName))
                }
                runCatching { getSystemService(NotificationManager::class.java)?.cancel(DOWNLOAD_NOTIFICATION) }
            }
            return
        }
        download(app, next)
    }

    private fun download(app: TapperApp, rec: Recording) {
        val id = rec.id
        downloading = id
        startForeground(DOWNLOAD_NOTIFICATION, Notifications.downloadNotification(this, rec.channelName, -1))
        app.recordings.upsert(rec.copy(status = RecordingStatus.RECORDING, error = null))
        val nm = getSystemService(NotificationManager::class.java)
        val downloader = Downloader(
            app,
            stopRequested = { id in stopping || app.recordings.get(id) == null },
            onConnection = { c -> if (c == null) connections.remove(id) else connections[id] = c },
            onProgress = { done, total ->
                app.recordings.updateProgress(id, done, total)
                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                runCatching { nm?.notify(DOWNLOAD_NOTIFICATION, Notifications.downloadNotification(this@RecordingService, rec.channelName, pct)) }
            },
        )
        jobs[id] = scope.launch {
            val outcome = runCatching { downloader.run(rec) }
                .getOrElse { Downloader.Outcome.Failed("Download stopped: " + (it.message ?: it.javaClass.simpleName)) }
            val row = app.recordings.get(id)
            if (row == null) {
                // Deleted while it ran: the partial file goes too (a share
                // may have refused the delete while the file was still open).
                runCatching { downloader.path?.let { deleteFile(app, it) } }
            } else when (outcome) {
                is Downloader.Outcome.Done -> {
                    app.recordings.upsert(row.copy(status = RecordingStatus.DONE, bytesDone = outcome.bytes,
                        bytesTotal = outcome.bytes, endUtc = System.currentTimeMillis(), error = null))
                    runCatching { nm?.notify(id.hashCode(), Notifications.downloadDoneNotification(this@RecordingService, rec.channelName)) }
                }
                is Downloader.Outcome.Failed -> {
                    app.logEvent("DOWNLOAD", "${rec.channelName}: ${outcome.reason}")
                    app.recordings.upsert(row.copy(status = RecordingStatus.FAILED, error = outcome.reason))
                }
                // Paused: back in the list as waiting, with what it has so
                // far; Resume picks up from there.
                Downloader.Outcome.Stopped ->
                    if (row.paused) app.recordings.upsert(row.copy(status = RecordingStatus.SCHEDULED, error = null))
                    else app.recordings.upsert(row.copy(status = RecordingStatus.FAILED, error = "Stopped"))
            }
            synchronized(this@RecordingService) {
                jobs.remove(id)
                stopping.remove(id)
                downloading = null
            }
            kickDownloads(app)
        }
    }

    private fun deleteFile(app: TapperApp, path: String) {
        if (path.startsWith("smb://", ignoreCase = true)) {
            app.recordingLocation.shareFor(path)?.let { SmbStorage.delete(path, it) }
        } else {
            java.io.File(path).delete()
        }
    }

    private fun startRecording(app: TapperApp, id: String) {
        if (jobs.containsKey(id)) return  // a duplicate alarm must not open a second file
        val recording = app.recordings.get(id) ?: return

        // Must happen within seconds of the service starting (see
        // RecordingAlarmReceiver) - done first, before any I/O that could
        // stall.
        startForeground(id.hashCode(), Notifications.recordingNotification(this, recording.channelName))

        // Where it goes: the network share picked in Settings > Recording,
        // or this Fire TV. The share is only tried once the stream answers,
        // so a dead channel doesn't leave empty files on the NAS.
        val location = app.recordingLocation
        val name = sanitizeFileName(recording.channelName) + "-" + recording.startUtc + ".ts"
        val localFile = File(location.localDir(), name)
        val share = location.activeShare()
        app.recordings.upsert(
            recording.copy(
                status = RecordingStatus.RECORDING,
                filePath = share?.uriOf(name) ?: localFile.absolutePath,
            )
        )

        jobs[id] = scope.launch {
            var failed = false
            var written = 0L
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
                    val output = openOutput(app, id, share, name, localFile, recording.channelName)
                    conn.inputStream.use { input ->
                        output.use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                try {
                                    out.write(buffer, 0, n)
                                } catch (t: Throwable) {
                                    // The share went away mid-recording (NAS
                                    // asleep, Wi-Fi drop). What's already
                                    // there is a playable file, so keep it
                                    // as a short recording rather than a
                                    // failed one.
                                    app.logEvent(
                                        "RECORDING",
                                        "Lost the network share while recording ${recording.channelName}: " +
                                            SmbStorage.friendly(t),
                                    )
                                    if (written == 0L) failed = true
                                    break
                                }
                                written += n
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
            if (jobs.isEmpty() && downloading == null) stopSelf()
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

    /** The share's file, or this Fire TV's when the share can't be opened. */
    private fun openOutput(
        app: TapperApp, id: String, share: RecordingLocationStore.Share?,
        name: String, localFile: File, channelName: String,
    ): OutputStream {
        if (share != null) {
            try {
                return SmbStorage.create(share, name)
            } catch (t: Throwable) {
                app.logEvent(
                    "RECORDING",
                    "Couldn't save $channelName to ${share.label} (${SmbStorage.friendly(t)}). Saving it on this Fire TV instead.",
                )
                app.recordings.get(id)?.let { app.recordings.upsert(it.copy(filePath = localFile.absolutePath)) }
            }
        }
        localFile.parentFile?.mkdirs()
        return localFile.outputStream()
    }

    companion object {
        private const val DOWNLOAD_NOTIFICATION = 0x7A0D
    }

    private fun sanitizeFileName(name: String): String =
        name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifBlank { "recording" }
}
