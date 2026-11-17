package io.tapper.firetv.data

import io.tapper.core.model.Channel
import io.tapper.firetv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Subtitles for films and episodes that carry none, from OpenSubtitles.com.
 * Turning captions on (Menu while watching) on a title with no subtitle
 * track looks one up in the preferred language and adds it to the player.
 *
 * Hidden until the build has OPENSUBTITLES_API_KEY (a repository secret).
 * Anonymous downloads are limited by OpenSubtitles to a few a day per TV.
 */
class OpenSubtitlesClient(private val cacheDir: File) {
    private val key = BuildConfig.OPENSUBTITLES_API_KEY
    val configured: Boolean get() = key.isNotBlank()

    /** A local .srt for [ch] in [language] ("en" when blank), or a failure saying why. */
    suspend fun find(ch: Channel, language: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            check(configured) { "Subtitles service isn't set up in this build." }
            val lang = language.ifBlank { "en" }
            val (title, year) = MovieMetadataStore.parseTitle(ch.name)
            val dir = File(cacheDir, "subs").apply { mkdirs() }
            val out = File(dir, "${(title + year + lang).hashCode().toUInt()}.srt")
            if (out.exists() && out.length() > 0) return@runCatching out

            val q = buildString {
                append("query=").append(URLEncoder.encode(title, "UTF-8"))
                append("&languages=").append(lang)
                year?.let { append("&year=").append(it) }
            }
            val search = JSONObject(request("GET", "/subtitles?$q", null))
            val data = search.optJSONArray("data")
            check(data != null && data.length() > 0) { "No $lang subtitles found for $title." }
            val fileId = data.getJSONObject(0).getJSONObject("attributes").getJSONArray("files")
                .getJSONObject(0).getLong("file_id")
            val dl = JSONObject(request("POST", "/download", JSONObject().put("file_id", fileId).toString()))
            val link = dl.optString("link").takeIf { it.isNotBlank() }
                ?: error(dl.optString("message", "OpenSubtitles wouldn't give the file (daily limit?)."))
            out.writeBytes(URL(link).openStream().use { it.readBytes() })
            out
        }
    }

    private fun request(method: String, path: String, body: String?): String {
        val c = (URL("https://api.opensubtitles.com/api/v1$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Api-Key", key)
            setRequestProperty("User-Agent", "TapperIPTV v2.1")
            setRequestProperty("Accept", "application/json")
            if (body != null) { setRequestProperty("Content-Type", "application/json"); doOutput = true }
        }
        body?.let { b -> c.outputStream.use { it.write(b.toByteArray()) } }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        if (status !in 200..299) error("OpenSubtitles returned HTTP $status")
        return text
    }
}
