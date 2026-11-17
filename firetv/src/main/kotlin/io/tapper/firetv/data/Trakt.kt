package io.tapper.firetv.data

import android.content.Context
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.firetv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Trakt: films and episodes finished here are added to the viewer's Trakt
 * history. Sign-in uses Trakt's device code flow (a code shown on the TV,
 * entered at trakt.tv/activate on a phone), so no server is involved.
 *
 * Hidden entirely until the build has TRAKT_CLIENT_ID and
 * TRAKT_CLIENT_SECRET (repository secrets; see README).
 */
class TraktClient(context: Context) {
    private val prefs = context.getSharedPreferences("tapper_trakt", Context.MODE_PRIVATE)
    private val clientId = BuildConfig.TRAKT_CLIENT_ID
    private val clientSecret = BuildConfig.TRAKT_CLIENT_SECRET

    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val signedIn: Boolean get() = prefs.getString("access", null) != null
    val userName: String? get() = prefs.getString("user", null)

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresAt: Long, val intervalSec: Int)

    suspend fun startSignIn(): DeviceCode = withContext(Dispatchers.IO) {
        val o = post("/oauth/device/code", JSONObject().put("client_id", clientId), auth = false).second
            ?: error("Trakt didn't answer.")
        DeviceCode(
            o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://trakt.tv/activate"),
            System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000L, o.optInt("interval", 5),
        )
    }

    /** Waits for the viewer to enter the code. True once signed in; false when it expired. */
    suspend fun finishSignIn(code: DeviceCode): Boolean = withContext(Dispatchers.IO) {
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(code.intervalSec * 1000L)
            val (status, o) = post(
                "/oauth/device/token",
                JSONObject().put("code", code.deviceCode).put("client_id", clientId).put("client_secret", clientSecret),
                auth = false,
            )
            when (status) {
                200 -> {
                    o ?: return@withContext false
                    save(o)
                    runCatching { fetchUser() }
                    return@withContext true
                }
                400 -> continue                        // not entered yet
                429 -> delay(code.intervalSec * 1000L) // polling too fast
                else -> return@withContext false       // denied, expired, used
            }
        }
        false
    }

    fun signOut() { prefs.edit().clear().apply() }

    /** Adds a finished film or episode to the Trakt history. Quietly does nothing when signed out. */
    suspend fun markWatched(ch: Channel, seriesName: String? = null, season: Int = 0, episode: Int = 0): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!configured || !signedIn) return@runCatching
            refreshIfNeeded()
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val (title, year) = MovieMetadataStore.parseTitle(ch.name)
            val body = JSONObject()
            val ep = EPISODE.find(ch.name)?.destructured?.let { (s, e) -> s.toInt() to e.toInt() }
                ?: (season to episode).takeIf { seriesName != null && season > 0 && episode > 0 }
            if (ch.kind == ContentKind.MOVIE || ep == null) {
                body.put("movies", JSONArray().put(JSONObject().put("title", title).apply { year?.let { put("year", it) } }.put("watched_at", now)))
            } else {
                val (s, e) = ep
                val show = seriesName?.let { MovieMetadataStore.parseTitle(it).first } ?: title.substringBefore(" S").trim()
                body.put(
                    "shows", JSONArray().put(
                        JSONObject().put("title", show).put(
                            "seasons", JSONArray().put(
                                JSONObject().put("number", s).put(
                                    "episodes", JSONArray().put(JSONObject().put("number", e).put("watched_at", now))
                                )
                            )
                        )
                    )
                )
            }
            val (status, _) = post("/sync/history", body, auth = true)
            if (status !in 200..299) error("Trakt returned HTTP $status")
        }
    }

    private fun refreshIfNeeded() {
        val expires = prefs.getLong("expires", 0L)
        if (expires == 0L || System.currentTimeMillis() < expires - 86_400_000L) return
        val refresh = prefs.getString("refresh", null) ?: return
        val (status, o) = post(
            "/oauth/token",
            JSONObject().put("refresh_token", refresh).put("client_id", clientId).put("client_secret", clientSecret)
                .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token"),
            auth = false,
        )
        if (status == 200 && o != null) save(o) else if (status == 401) signOut()
    }

    private fun save(o: JSONObject) {
        val created = o.optLong("created_at", System.currentTimeMillis() / 1000L)
        prefs.edit()
            .putString("access", o.getString("access_token"))
            .putString("refresh", o.optString("refresh_token"))
            .putLong("expires", (created + o.optLong("expires_in", 7_776_000L)) * 1000L)
            .apply()
    }

    private fun fetchUser() {
        val c = open("/users/settings", "GET", auth = true)
        if (c.responseCode == 200) {
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            prefs.edit().putString("user", o.optJSONObject("user")?.optString("username")).apply()
        }
        c.disconnect()
    }

    private fun open(path: String, method: String, auth: Boolean): HttpURLConnection =
        (URL("https://api.trakt.tv$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("trakt-api-version", "2")
            setRequestProperty("trakt-api-key", clientId)
            setRequestProperty("User-Agent", "TapperIPTV/2.1")
            if (auth) prefs.getString("access", null)?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun post(path: String, body: JSONObject, auth: Boolean): Pair<Int, JSONObject?> {
        val c = open(path, "POST", auth)
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val status = c.responseCode
        val text = runCatching { (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull()
        c.disconnect()
        return status to text?.let { t -> runCatching { JSONObject(t) }.getOrNull() }
    }

    companion object {
        private val EPISODE = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""")
    }
}
