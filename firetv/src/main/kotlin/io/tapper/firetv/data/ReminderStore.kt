package io.tapper.firetv.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.tapper.firetv.reminders.ReminderAlarmReceiver
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: String,
    val sourceId: String,
    val channelId: String,
    val channelName: String,
    val programmeTitle: String,
    val startUtc: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("sourceId", sourceId).put("channelId", channelId)
        .put("channelName", channelName).put("programmeTitle", programmeTitle)
        .put("startUtc", startUtc)

    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            id = o.getString("id"), sourceId = o.getString("sourceId"),
            channelId = o.getString("channelId"), channelName = o.getString("channelName"),
            programmeTitle = o.getString("programmeTitle"), startUtc = o.getLong("startUtc"),
        )

        fun idFor(sourceId: String, channelId: String, startUtc: Long) = "$sourceId|$channelId|$startUtc"
    }
}

/**
 * Reminders that survive a reboot.
 *
 * AlarmManager forgets every alarm when the device restarts; this is the
 * durable half, read back by BootReceiver to re-arm anything still in the
 * future. The store itself only ever holds a handful of rows, so it's read
 * back and rewritten whole on every change - the same pattern SourceStore and
 * FavoritesStore already use.
 */
class ReminderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("tapper_reminders", Context.MODE_PRIVATE)

    fun all(): List<Reminder> {
        val raw = prefs.getString("reminders", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Reminder>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { runCatching { Reminder.fromJson(it) }.getOrNull()?.let(out::add) }
        }
        return out
    }

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("reminders", arr.toString()).apply()
    }

    fun get(id: String): Reminder? = all().firstOrNull { it.id == id }

    fun has(sourceId: String, channelId: String, startUtc: Long): Boolean =
        get(Reminder.idFor(sourceId, channelId, startUtc)) != null

    /** Drops anything already in the past - a reminder whose alarm never
     *  fired (device was off) should not resurface as "upcoming" once it
     *  no longer is. */
    fun upcoming(now: Long = System.currentTimeMillis()): List<Reminder> =
        all().filter { it.startUtc > now }.sortedBy { it.startUtc }

    fun add(reminder: Reminder) {
        save(all().filterNot { it.id == reminder.id } + reminder)
        schedule(context, reminder)
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
        cancel(context, id)
    }

    /** Called by BootReceiver: alarms are gone after a restart, the records
     *  are not - everything still upcoming needs a fresh one. */
    fun rescheduleAll() {
        upcoming().forEach { schedule(context, it) }
    }

    companion object {
        private fun pendingIntent(context: Context, id: String): PendingIntent {
            val intent = Intent(context, ReminderAlarmReceiver::class.java).putExtra("reminder_id", id)
            return PendingIntent.getBroadcast(
                context, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun schedule(context: Context, reminder: Reminder) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = pendingIntent(context, reminder.id)
            // Falls back to an inexact alarm rather than throwing: exact-alarm
            // scheduling can be revoked out from under the app on API 33+, and
            // a reminder that fires a few minutes late beats a crash.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.startUtc, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.startUtc, pi)
            }
        }

        private fun cancel(context: Context, id: String) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(pendingIntent(context, id))
        }
    }
}
