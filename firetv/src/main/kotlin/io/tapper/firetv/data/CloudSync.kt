package io.tapper.firetv.data

import android.net.Uri
import io.tapper.firetv.BuildConfig
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Drive and OneDrive as sync destinations, signed in from inside the app.
 *
 * Sign-in uses the OAuth 2.0 *device authorization grant*: the TV shows a short
 * code and an address, the user enters the code on their phone or computer,
 * and the TV polls until that's done. It's the only sign-in that works on Fire
 * TV, which has no usable browser, and it means neither Google nor Microsoft
 * ever sees a password typed into this app.
 *
 * Once signed in, the sync folder is created behind the scenes - nothing to
 * pick or paste:
 *
 *  - Google Drive: a "Tapper IPTV Sync" folder in My Drive. The app asks only
 *    for drive.file, so it can see the files it created and nothing else in
 *    the user's Drive.
 *  - OneDrive: the app's own folder, Apps/Tapper IPTV. Files.ReadWrite.AppFolder
 *    likewise reaches only that folder.
 *
 * The iPad app uses the same client IDs, folder and file names, so a Fire TV
 * and an iPad signed in to the same account share watch history.
 *
 * Client IDs come from the build (see firetv/build.gradle.kts); a build made
 * without them simply reports that cloud sign-in isn't set up.
 */
enum class CloudProvider(val label: String, val company: String) {
    GOOGLE("Google Drive", "Google"),
    MICROSOFT("OneDrive", "Microsoft");

    val isConfigured: Boolean
        get() = when (this) {
            GOOGLE -> BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank() &&
                BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET.isNotBlank()
            MICROSOFT -> BuildConfig.MICROSOFT_OAUTH_CLIENT_ID.isNotBlank()
        }
}

/** What Settings shows while a sign-in is under way. */
data class CloudSignInState(
    val provider: CloudProvider,
    /** Null while the code is still being requested. */
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val error: String? = null,
)

class CloudAuthException(message: String) : Exception(message)

// MARK: - HTTP

internal object CloudHttp {
    class Response(val code: Int, val body: String, val location: String?)

    fun call(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        followRedirects: Boolean = true,
    ): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", "TapperIPTV")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                contentType?.let { setRequestProperty("Content-Type", it) }
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, text, conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }

    fun form(vararg pairs: Pair<String, String>): ByteArray =
        pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }.toByteArray()

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun json(body: String): JSONObject = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
}

// MARK: - Sign-in

object CloudAuth {
    private const val GOOGLE_DEVICE = "https://oauth2.googleapis.com/device/code"
    private const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
    private const val GOOGLE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    private const val MS_BASE = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private const val MS_SCOPE = "Files.ReadWrite.AppFolder User.Read offline_access"

    data class DeviceCode(
        val provider: CloudProvider,
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Int,
        val expiresAtMs: Long,
    )

    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long)

    private fun notConfigured(p: CloudProvider) = CloudAuthException(
        "${p.company} sign-in isn't set up in this build. See \"Cloud sync setup\" in the README."
    )

    /** Asks the provider for a code to show the user. Blocking; call off the main thread. */
    fun start(p: CloudProvider): DeviceCode {
        if (!p.isConfigured) throw notConfigured(p)
        val r = when (p) {
            CloudProvider.GOOGLE -> CloudHttp.call(
                GOOGLE_DEVICE, "POST",
                body = CloudHttp.form("client_id" to BuildConfig.GOOGLE_OAUTH_CLIENT_ID, "scope" to GOOGLE_SCOPE),
                contentType = "application/x-www-form-urlencoded",
            )
            CloudProvider.MICROSOFT -> CloudHttp.call(
                "$MS_BASE/devicecode", "POST",
                body = CloudHttp.form("client_id" to BuildConfig.MICROSOFT_OAUTH_CLIENT_ID, "scope" to MS_SCOPE),
                contentType = "application/x-www-form-urlencoded",
            )
        }
        val j = CloudHttp.json(r.body)
        if (r.code !in 200..299 || !j.has("device_code")) {
            throw CloudAuthException(describeError(p, j, r.code))
        }
        return DeviceCode(
            provider = p,
            deviceCode = j.getString("device_code"),
            userCode = j.getString("user_code"),
            // Google says verification_url, Microsoft (and the RFC) verification_uri.
            verificationUrl = j.optString("verification_url").ifBlank { j.optString("verification_uri") },
            intervalSeconds = j.optInt("interval", 5).coerceAtLeast(1),
            expiresAtMs = System.currentTimeMillis() + j.optLong("expires_in", 900) * 1000,
        )
    }

    /**
     * Polls until the user finishes signing in on their other device. Suspends
     * between polls, so cancelling the calling coroutine stops it cleanly.
     * The network calls inside block; run this on Dispatchers.IO.
     */
    suspend fun await(code: DeviceCode): Tokens {
        var interval = code.intervalSeconds
        while (true) {
            delay(interval * 1000L)
            if (System.currentTimeMillis() > code.expiresAtMs) {
                throw CloudAuthException("The code expired before sign-in finished. Try again.")
            }
            val r = when (code.provider) {
                CloudProvider.GOOGLE -> CloudHttp.call(
                    GOOGLE_TOKEN, "POST",
                    body = CloudHttp.form(
                        "client_id" to BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
                        "client_secret" to BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET,
                        "device_code" to code.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                    contentType = "application/x-www-form-urlencoded",
                )
                CloudProvider.MICROSOFT -> CloudHttp.call(
                    "$MS_BASE/token", "POST",
                    body = CloudHttp.form(
                        "client_id" to BuildConfig.MICROSOFT_OAUTH_CLIENT_ID,
                        "device_code" to code.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                    contentType = "application/x-www-form-urlencoded",
                )
            }
            val j = CloudHttp.json(r.body)
            if (r.code in 200..299 && j.has("access_token")) return tokens(j, null)
            when (j.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied", "authorization_declined" ->
                    throw CloudAuthException("Sign-in was cancelled on the other device.")
                "expired_token", "bad_verification_code" ->
                    throw CloudAuthException("The code expired before sign-in finished. Try again.")
                else -> throw CloudAuthException(describeError(code.provider, j, r.code))
            }
        }
    }

    /** Trades a refresh token for a fresh access token. Blocking. */
    fun refresh(p: CloudProvider, refreshToken: String): Tokens {
        if (!p.isConfigured) throw notConfigured(p)
        val r = when (p) {
            CloudProvider.GOOGLE -> CloudHttp.call(
                GOOGLE_TOKEN, "POST",
                body = CloudHttp.form(
                    "client_id" to BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
                    "client_secret" to BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                ),
                contentType = "application/x-www-form-urlencoded",
            )
            CloudProvider.MICROSOFT -> CloudHttp.call(
                "$MS_BASE/token", "POST",
                body = CloudHttp.form(
                    "client_id" to BuildConfig.MICROSOFT_OAUTH_CLIENT_ID,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                    "scope" to MS_SCOPE,
                ),
                contentType = "application/x-www-form-urlencoded",
            )
        }
        val j = CloudHttp.json(r.body)
        if (r.code in 200..299 && j.has("access_token")) return tokens(j, refreshToken)
        if (j.optString("error") == "invalid_grant") {
            throw CloudAuthException(
                "The ${p.label} sign-in has expired or was revoked. Sign in again in Settings."
            )
        }
        throw CloudAuthException(describeError(p, j, r.code))
    }

    /** The signed-in account's email, for display. Blocking. */
    fun accountName(p: CloudProvider, accessToken: String): String {
        val auth = mapOf("Authorization" to "Bearer $accessToken")
        return runCatching {
            when (p) {
                CloudProvider.GOOGLE -> {
                    val r = CloudHttp.call("https://www.googleapis.com/drive/v3/about?fields=user", headers = auth)
                    val u = CloudHttp.json(r.body).optJSONObject("user")
                    u?.optString("emailAddress")?.ifBlank { u.optString("displayName") }
                }
                CloudProvider.MICROSOFT -> {
                    val r = CloudHttp.call(
                        "https://graph.microsoft.com/v1.0/me?\$select=mail,userPrincipalName,displayName",
                        headers = auth,
                    )
                    val j = CloudHttp.json(r.body)
                    j.optString("mail").takeIf { it.isNotBlank() && it != "null" }
                        ?: j.optString("userPrincipalName").ifBlank { j.optString("displayName") }
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: p.label
    }

    private fun tokens(j: JSONObject, previousRefresh: String?): Tokens = Tokens(
        accessToken = j.getString("access_token"),
        // Microsoft rotates refresh tokens on every use; Google usually doesn't
        // send a new one, in which case the old one stays valid.
        refreshToken = j.optString("refresh_token").ifBlank { previousRefresh },
        // A minute's margin so a token never expires mid-sync.
        expiresAtMs = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000,
    )

    private fun describeError(p: CloudProvider, j: JSONObject, code: Int): String {
        val detail = j.optString("error_description").ifBlank { j.optString("error") }
        return when {
            j.optString("error") == "invalid_client" || j.optString("error") == "unauthorized_client" ->
                "${p.company} rejected this app's client ID. Check the OAuth app setup (README, \"Cloud sync setup\")."
            detail.isNotBlank() -> "${p.company} sign-in failed: $detail"
            else -> "${p.company} sign-in failed (HTTP $code)."
        }
    }
}

/**
 * A signed-in account: hands out access tokens, refreshing when they expire,
 * and saves the refresh token whenever the provider rotates it.
 */
class CloudSession(
    val provider: CloudProvider,
    val account: String,
    private var refreshToken: String,
    private val onRefreshTokenChanged: (String) -> Unit,
) {
    private companion object {
        // Survives WatchSync building a fresh backend for every sync, so a
        // sync doesn't spend a round trip on a token it already has.
        val cache = ConcurrentHashMap<String, CloudAuth.Tokens>()
    }

    @Synchronized
    fun accessToken(forceRefresh: Boolean = false): String {
        val key = provider.name + ":" + refreshToken
        val cached = cache[key]
        if (!forceRefresh && cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
            return cached.accessToken
        }
        val t = CloudAuth.refresh(provider, refreshToken)
        cache.remove(key)
        val newRefresh = t.refreshToken ?: refreshToken
        if (newRefresh != refreshToken) {
            refreshToken = newRefresh
            onRefreshTokenChanged(newRefresh)
        }
        cache[provider.name + ":" + refreshToken] = t
        return t.accessToken
    }

    /** An authenticated call that retries once with a fresh token on 401. */
    fun call(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        followRedirects: Boolean = true,
    ): CloudHttp.Response {
        fun go(token: String) = CloudHttp.call(
            url, method, headers + ("Authorization" to "Bearer $token"), body, contentType, followRedirects
        )
        val first = go(accessToken())
        return if (first.code == 401) go(accessToken(forceRefresh = true)) else first
    }
}

// MARK: - Backends

/** Files in a "Tapper IPTV Sync" folder in the user's My Drive. */
class GoogleDriveBackend(private val session: CloudSession) : SyncBackend {

    companion object {
        const val FOLDER = "Tapper IPTV Sync"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    }

    override val label get() = "Google Drive: ${session.account} · $FOLDER"

    private var folderId: String? = null
    private var ids: MutableMap<String, String>? = null

    private fun check(r: CloudHttp.Response, what: String): String {
        if (r.code in 200..299) return r.body
        val msg = CloudHttp.json(r.body).optJSONObject("error")?.optString("message").orEmpty()
        error("Google Drive returned HTTP ${r.code} $what" + if (msg.isNotBlank()) ": $msg" else ".")
    }

    /**
     * Finds the sync folder, creating it on first use. With drive.file the
     * search only sees folders this app made, so a same-named folder of the
     * user's own is never picked up by mistake. Oldest first, so if two
     * devices both created one at the same moment they still agree.
     */
    fun ensureFolder(): String {
        folderId?.let { return it }
        val q = "name = '$FOLDER' and mimeType = '$FOLDER_MIME' and trashed = false"
        val found = CloudHttp.json(check(
            session.call("$API/files?q=${CloudHttp.enc(q)}&spaces=drive&orderBy=createdTime&fields=files(id)"),
            "finding the sync folder",
        )).optJSONArray("files")
        val id = if (found != null && found.length() > 0) {
            found.getJSONObject(0).getString("id")
        } else {
            val meta = JSONObject().put("name", FOLDER).put("mimeType", FOLDER_MIME).toString()
            CloudHttp.json(check(
                session.call("$API/files?fields=id", "POST", body = meta.toByteArray(),
                    contentType = "application/json; charset=UTF-8"),
                "creating the sync folder",
            )).getString("id")
        }
        folderId = id
        return id
    }

    private fun index(): MutableMap<String, String> {
        ids?.let { return it }
        val parent = ensureFolder()
        val out = HashMap<String, String>()
        var pageToken: String? = null
        do {
            val q = "'$parent' in parents and trashed = false"
            val url = "$API/files?q=${CloudHttp.enc(q)}&spaces=drive&pageSize=1000" +
                "&fields=nextPageToken,files(id,name)" +
                (pageToken?.let { "&pageToken=" + CloudHttp.enc(it) } ?: "")
            val j = CloudHttp.json(check(session.call(url), "listing the sync folder"))
            val files = j.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out.putIfAbsent(f.getString("name"), f.getString("id"))
            }
            pageToken = j.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        ids = out
        return out
    }

    override fun list(prefix: String): List<String> = index().keys.filter { it.startsWith(prefix) }

    override fun read(name: String): String? {
        val id = index()[name] ?: return null
        val r = session.call("$API/files/$id?alt=media")
        return if (r.code in 200..299) r.body else null
    }

    override fun write(name: String, content: String) {
        val bytes = content.toByteArray()
        val existing = index()[name]
        if (existing != null) {
            // HttpURLConnection can't send PATCH; Google's front end accepts
            // POST with this override header instead.
            val r = session.call(
                "$UPLOAD/files/$existing?uploadType=media", "POST",
                headers = mapOf("X-HTTP-Method-Override" to "PATCH"),
                body = bytes, contentType = "application/json",
            )
            if (r.code in 200..299) return
            if (r.code != 404) check(r, "saving $name")
            index().remove(name) // deleted behind our back - create it again
        }
        val boundary = "tapper" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(ensureFolder()))
        val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n").toByteArray() +
            bytes + "\r\n--$boundary--\r\n".toByteArray()
        val created = CloudHttp.json(check(
            session.call("$UPLOAD/files?uploadType=multipart&fields=id", "POST",
                body = body, contentType = "multipart/related; boundary=$boundary"),
            "creating $name",
        ))
        index()[name] = created.getString("id")
    }
}

/** Files in the app's own OneDrive folder, Apps/Tapper IPTV. */
class OneDriveBackend(private val session: CloudSession) : SyncBackend {

    private companion object {
        const val ROOT = "https://graph.microsoft.com/v1.0/me/drive/special/approot"
    }

    override val label get() = "OneDrive: ${session.account} · Apps/Tapper IPTV"

    private fun check(r: CloudHttp.Response, what: String): String {
        if (r.code in 200..299) return r.body
        val msg = CloudHttp.json(r.body).optJSONObject("error")?.optString("message").orEmpty()
        error("OneDrive returned HTTP ${r.code} $what" + if (msg.isNotBlank()) ": $msg" else ".")
    }

    /** Touching approot is what makes OneDrive create the app folder. */
    fun ensureFolder() {
        check(session.call("$ROOT?\$select=id,name"), "opening the app folder")
    }

    override fun list(prefix: String): List<String> {
        val out = ArrayList<String>()
        var next: String? = "$ROOT/children?\$select=name&\$top=999"
        while (next != null) {
            val j = CloudHttp.json(check(session.call(next), "listing the app folder"))
            val items = j.optJSONArray("value")
            if (items != null) for (i in 0 until items.length()) {
                items.getJSONObject(i).optString("name").takeIf { it.startsWith(prefix) }?.let(out::add)
            }
            next = j.optString("@odata.nextLink").ifBlank { null }
        }
        return out
    }

    override fun read(name: String): String? {
        // Graph answers with a redirect to a pre-signed download URL. Followed
        // by hand so the bearer token isn't sent on to the storage host.
        val r = session.call("$ROOT:/${Uri.encode(name)}:/content", followRedirects = false)
        return when {
            r.code in 200..299 -> r.body
            r.code in 300..399 && r.location != null ->
                CloudHttp.call(r.location).takeIf { it.code in 200..299 }?.body
            else -> null
        }
    }

    override fun write(name: String, content: String) {
        check(
            session.call("$ROOT:/${Uri.encode(name)}:/content", "PUT",
                body = content.toByteArray(), contentType = "application/json"),
            "saving $name",
        )
    }
}
