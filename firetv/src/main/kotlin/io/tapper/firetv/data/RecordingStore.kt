package io.tapper.firetv.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.tapper.firetv.recordings.RecordingAlarmReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class RecordingStatus { SCHEDULED, RECORDING, DONE, FAILED }

data class Recording(
    val id: String,
    val sourceId: String,
    val channelId: String,
    val channelName: String,
    val streamUrl: String,
    val startUtc: Long,
    val endUtc: Long,
    val filePath: String? = null,
    val status: RecordingStatus = RecordingStatus.SCHEDULED,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("sourceId", sourceId).put("channelId", channelId)
        .put("channelName", channelName).put("streamUrl", streamUrl)
        .put("startUtc", startUtc).put("endUtc", endUtc)
        .put("filePath", filePath ?: JSONObject.NULL).put("status", status.name)

    companion object {
        fun fromJson(o: JSONObject) = Recording(
            id = o.getString("id"), sourceId = o.getString("sourceId"),
            channelId = o.getString("channelId"), channelName = o.getString("channelName"),
            streamUrl = o.getString("streamUrl"),
            startUtc = o.getLong("startUtc"), endUtc = o.getLong("endUtc"),
            filePath = o.optString("filePath").takeIf { it.isNotBlank() && it != "null" },
            status = runCatching { RecordingStatus.valueOf(o.optString("status", "SCHEDULED")) }
                .getOrDefault(RecordingStatus.SCHEDULED),
        )

        fun newId(): String = "rec-" + UUID.randomUUID().toString().take(8)
    }
}

/**
 * Scheduled and completed recordings, durable across restarts the same way
 * [ReminderStore] is.
 *
 * A recording is raw stream bytes written straight to a file by
 * [io.tapper.firetv.recordings.RecordingService] - there is no transcoding or
 * remuxing step here. Most IPTV live channels (Xtream's "ts" extension, most
 * M3U providers) are plain MPEG-TS under the hood, and a saved chunk of one
 * is itself a directly playable file. A source serving fragmented or
 * encrypted HLS instead will still produce a file, just not necessarily one
 * that plays back cleanly - that would need real segment demuxing, which
 * this does not attempt.
 */
class RecordingStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("tapper_recordings", Context.MODE_PRIVATE)

    fun all(): List<Recording> {
        val raw = prefs.getString("recordings", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Recording>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { runCatching { Recording.fromJson(it) }.getOrNull()?.let(out::add) }
        }
        return out
    }

    private fun save(list: List<Recording>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("recordings", arr.toString()).apply()
    }

    fun get(id: String): Recording? = all().firstOrNull { it.id == id }

    fun upsert(recording: Recording) {
        save(all().filterNot { it.id == recording.id } + recording)
    }

    fun remove(id: String) {
        val rec = get(id)
        save(all().filterNot { it.id == id })
        cancelAlarms(context, id)
        rec?.filePath?.let { runCatching { File(it).delete() } }
    }

    fun schedule(recording: Recording) {
        upsert(recording)
        scheduleAlarms(context, recording)
    }

    /** Anything that was mid-recording when the process died (crash, OS kill,
     *  an update, a reboot) is not resumable - the safest thing is to mark it
     *  failed rather than leave a RECORDING row that will never finish and
     *  never show up as done or failed in the UI. Call once at app startup. */
    fun markOrphanedRecordingsFailed() {
        val stuck = all().filter { it.status == RecordingStatus.RECORDING }
        if (stuck.isEmpty()) return
        save(all().map { if (it.status == RecordingStatus.RECORDING) it.copy(status = RecordingStatus.FAILED) else it })
    }

    /** Called by BootReceiver. Only SCHEDULED (not yet started) recordings
     *  are re-armed; anything that was RECORDING is handled separately by
     *  [markOrphanedRecordingsFailed]. */
    fun rescheduleAll(now: Long = System.currentTimeMillis()) {
        all().filter { it.status == RecordingStatus.SCHEDULED && it.endUtc > now }
            .forEach { scheduleAlarms(context, it) }
    }

    companion object {
        private fun startIntent(context: Context, id: String) =
            Intent(context, RecordingAlarmReceiver::class.java)
                .setAction(RecordingAlarmReceiver.ACTION_START)
                .putExtra("recording_id", id)

        private fun stopIntent(context: Context, id: String) =
            Intent(context, RecordingAlarmReceiver::class.java)
                .setAction(RecordingAlarmReceiver.ACTION_STOP)
                .putExtra("recording_id", id)

        private fun pendingIntent(context: Context, id: String, suffix: String, intent: Intent) =
            PendingIntent.getBroadcast(
                context, (id + suffix).hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun exactOrInexact(am: AlarmManager, at: Long, pi: PendingIntent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        private fun scheduleAlarms(context: Context, recording: Recording) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val now = System.currentTimeMillis()
            // A start time already in the past (the device was off when a
            // recording was meant to begin) starts immediately instead of
            // never firing at all - a short recording beats a silently missed
            // one.
            val start = recording.startUtc.coerceAtLeast(now)
            exactOrInexact(
                am, start,
                pendingIntent(context, recording.id, "-start", startIntent(context, recording.id)),
            )
            if (recording.endUtc > start) {
                exactOrInexact(
                    am, recording.endUtc,
                    pendingIntent(context, recording.id, "-stop", stopIntent(context, recording.id)),
                )
            }
        }

        private fun cancelAlarms(context: Context, id: String) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(pendingIntent(context, id, "-start", startIntent(context, id)))
            am.cancel(pendingIntent(context, id, "-stop", stopIntent(context, id)))
        }
    }
}
