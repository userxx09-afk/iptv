package io.tapper.firetv.data

import android.content.Context
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Voice control through your own Alexa relay (the alexa/ folder in the repo):
 * "Alexa, ask Tapper to play ESPN". While Tapper is open, the TV keeps one
 * request open to the relay and acts on what Alexa queued for it.
 *
 * Off until a relay address is entered in Settings > Alexa.
 */
class AlexaLink(context: Context) {
    private val prefs = context.getSharedPreferences("tapper_alexa", Context.MODE_PRIVATE)

    data class Command(val type: String, val query: String?, val number: Int?)

    var serverUrl: String
        get() = prefs.getString("url", "").orEmpty()
        set(v) {
            val clean = v.trim().trimEnd('/')
            if (clean != serverUrl) prefs.edit().putString("url", clean).remove("tvId").remove("token").apply()
        }

    var registerKey: String
        get() = prefs.getString("key", "").orEmpty()
        set(v) = prefs.edit().putString("key", v.trim()).apply()

    val configured: Boolean get() = serverUrl.startsWith("https://") || serverUrl.startsWith("http://")
    val registered: Boolean get() = prefs.getString("tvId", null) != null

    /** Registers this TV with the relay, once. */
    suspend fun register(name: String) = withContext(Dispatchers.IO) {
        val (status, o) = call("POST", "/tv/register", JSONObject().put("name", name).put("app", "tapper"), auth = false)
        check(status == 200 && o != null) { if (status == 403) "The relay's key doesn't match." else "The relay answered HTTP $status." }
        prefs.edit().putString("tvId", o.getString("tvId")).putString("token", o.getString("token")).apply()
    }

    /** A fresh six-digit code to say to Alexa, valid ten minutes. */
    suspend fun pairCode(name: String): String = withContext(Dispatchers.IO) {
        if (!registered) register(name)
        var (status, o) = call("POST", "/tv/pair", null, auth = true)
        if (status == 401) { prefs.edit().remove("tvId").apply(); register(name); call("POST", "/tv/pair", null, auth = true).also { status = it.first; o = it.second } }
        check(status == 200 && o != null) { "The relay answered HTTP $status." }
        o!!.getString("code")
    }

    suspend fun isLinked(): Boolean = withContext(Dispatchers.IO) {
        if (!registered) return@withContext false
        call("GET", "/tv/status", null, auth = true).second?.optBoolean("linked") == true
    }

    suspend fun unlink() = withContext(Dispatchers.IO) { runCatching { call("POST", "/tv/unlink", null, auth = true) } }

    /** Waits for commands until cancelled, backing off while the relay is unreachable. */
    suspend fun listen(onCommand: suspend (Command) -> Unit) {
        var backoff = 2_000L
        while (coroutineContext.isActive) {
            if (!configured || !registered) return
            val result = withContext(Dispatchers.IO) { runCatching { call("GET", "/tv/next", null, auth = true, readTimeoutMs = 35_000) } }
            val (status, o) = result.getOrDefault(-1 to null)
            if (status == 200 && o != null) {
                backoff = 2_000L
                val arr = o.optJSONArray("commands") ?: continue
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    onCommand(Command(c.optString("type"), c.optString("query").ifBlank { null }, c.optInt("number", -1).takeIf { it >= 0 }))
                }
            } else {
                if (status == 401) return
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun call(method: String, path: String, body: JSONObject?, auth: Boolean, readTimeoutMs: Int = 15_000): Pair<Int, JSONObject?> {
        val c = (URL(serverUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000; readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json")
            if (registerKey.isNotBlank()) setRequestProperty("X-Tapper-Key", registerKey)
            if (auth) {
                setRequestProperty("X-Tapper-Tv", prefs.getString("tvId", "").orEmpty())
                setRequestProperty("Authorization", "Bearer " + prefs.getString("token", "").orEmpty())
            }
        }
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val status = c.responseCode
            val text = runCatching { (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull()
            return status to text?.let { runCatching { JSONObject(it) }.getOrNull() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private fun norm(s: String) = s.lowercase()
            .replace(Regex("""^[a-z]{2,3}\s*[:|\-]\s*"""), "")   // "US: ", "UK | "
            .replace(Regex("""\b(fhd|uhd|hd|sd|4k|hevc|east|west|\(.*?\)|\[.*?])"""), " ")
            .replace(Regex("[^a-z0-9]"), "")

        /** The live channel that best matches what was said: exact, then starts with, then contains. */
        fun bestMatch(query: String, channels: List<Channel>, favorites: Set<String> = emptySet()): Channel? {
            val q = norm(query.replace(Regex("(?i)\\b(channel|the)\\b"), ""))
            if (q.isEmpty()) return null
            val live = channels.filter { it.kind == ContentKind.LIVE && it.isPlayable }
            fun pick(list: List<Channel>) = list.sortedWith(
                compareByDescending<Channel> { "${it.sourceId}|${it.id}" in favorites }.thenBy { it.name.length }
            ).firstOrNull()
            return pick(live.filter { norm(it.name) == q })
                ?: pick(live.filter { norm(it.name).startsWith(q) })
                ?: pick(live.filter { norm(it.name).contains(q) })
        }
    }
}
