package io.tapper.core.tmdb

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TMDb (The Movie Database) title lookup - free for non-commercial use, and
 * the source behind the poster/synopsis/rating shown for Movies and Shows
 * (see MovieMetadataStore, which owns the API key and the local cache; this
 * class only knows how to ask TMDb one question and parse the answer).
 *
 * Plain HttpURLConnection + org.json, matching XtreamClient - no new
 * dependency for what is a handful of small JSON responses.
 *
 * TMDb hands out two different credential types from the same account
 * settings page, and it is easy to copy the wrong one: a classic 32-char
 * hex "API Key (v3 auth)", meant to travel as the `api_key` query
 * parameter, and a much longer JWT-style "API Read Access Token (v4 auth)",
 * meant to travel as an `Authorization: Bearer` header instead - pasting a
 * v4 token into a v3 `api_key=` query param (or vice versa) gets a 401 on
 * every single request, which is indistinguishable from "TMDb has nothing
 * on this" once that error is swallowed. Rather than make the user know
 * which kind they copied, this detects the format (v4 tokens are long
 * three-part JWTs; v3 keys are a short hex string) and authenticates the
 * right way for whichever one was pasted in.
 */
class TmdbClient(private val apiKey: String) {

    class TmdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // v3 API keys are 32 hex characters. v4 Read Access Tokens are JWTs -
    // three dot-separated base64url segments, comfortably over 100 chars.
    // Length alone is enough to tell them apart without trying to validate
    // JWT structure.
    private val isV4Token = apiKey.length > 40

    data class Result(
        val title: String,
        val year: Int?,
        val overview: String?,
        /** A full, ready-to-load image URL - never a bare TMDb path fragment
         *  - or null if this result has no poster at all. */
        val posterUrl: String?,
        /** 0-10 as TMDb reports it; null if this title has no votes yet. */
        val rating: Double?,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * [isShow] picks the /search/tv endpoint over /search/movie - TMDb keeps
     * these separate, with different field names for title ("name" vs
     * "title") and release date ("first_air_date" vs "release_date").
     *
     * A [year] that turns up nothing is retried once without it - IPTV
     * listings get release years wrong often enough (a regional cut, a
     * re-release, a plain typo in the VOD title) that refusing to fall back
     * would silently lose titles TMDb genuinely does have.
     */
    fun search(title: String, year: Int?, isShow: Boolean): Result? {
        val endpoint = if (isShow) "tv" else "movie"
        val yearParam = when {
            year == null -> ""
            isShow -> "&first_air_date_year=$year"
            else -> "&year=$year"
        }
        // A v4 token authenticates via the Authorization header (added in
        // fetch() below) instead of this query parameter - see the class
        // doc comment for why both forms need to be supported.
        val authParam = if (isV4Token) "" else "&api_key=${enc(apiKey)}"
        val url = "https://api.themoviedb.org/3/search/$endpoint" +
            "?query=${enc(title)}$yearParam&include_adult=false$authParam"
        val results = JSONObject(fetch(url)).optJSONArray("results")
        if (results == null || results.length() == 0) {
            return if (year != null) search(title, null, isShow) else null
        }
        val o = results.getJSONObject(0)
        val name = (if (isShow) o.optString("name") else o.optString("title")).ifBlank { title }
        val dateField = if (isShow) "first_air_date" else "release_date"
        val resultYear = o.optString(dateField).take(4).toIntOrNull()
        val posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
        val rating = o.optDouble("vote_average")
        return Result(
            title = name,
            year = resultYear,
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            rating = rating.takeIf { !it.isNaN() && it > 0.0 },
        )
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "TapperIPTV/0.12")
            if (isV4Token) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val code = try {
            conn.responseCode
        } catch (t: Throwable) {
            // The connection can already be live at this point (a read
            // timeout waiting on headers, for instance) - disconnect() on
            // this path too, not just the HTTP-error paths below, or a
            // string of failed lookups while scrolling leaks one connection
            // each.
            conn.disconnect()
            throw TmdbException("Couldn't reach themoviedb.org: ${t.message}", t)
        }
        if (code == 401) {
            conn.disconnect()
            throw TmdbException(
                "TMDb rejected the API key (HTTP 401). Double-check it was copied " +
                    "correctly in Settings - either the \"API Key\" or the \"API Read " +
                    "Access Token\" from TMDb's own Settings > API page works here."
            )
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw TmdbException("TMDb returned HTTP $code.")
        }
        return conn.inputStream.use { it.bufferedReader().readText() }
    }
}
