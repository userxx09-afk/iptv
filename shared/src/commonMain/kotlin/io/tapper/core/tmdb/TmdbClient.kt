package io.tapper.core.tmdb

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.tapper.core.net.tapperHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * TMDb (The Movie Database) title lookup - free for non-commercial use, and
 * the source behind the poster/synopsis/rating shown for Movies and Shows
 * (see MovieMetadataStore, which owns the API key and the local cache; this
 * class only knows how to ask TMDb one question and parse the answer).
 *
 * Built on Ktor (see net/HttpEngine.kt for why) and kotlinx.serialization's
 * loose JsonElement tree API rather than typed @Serializable classes - TMDb
 * responses are read the same tolerant way the old org.json version read
 * them: every field access degrades to null on a missing/wrong-shaped
 * field instead of throwing, since a lookup failing to parse one unexpected
 * field shouldn't take down the whole search.
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

    private companion object {
        // A shared, long-lived client, not one per TmdbClient instance.
        // MovieMetadataStore constructs a fresh `TmdbClient(key)` for every
        // single lookup (one per title scrolled past in Browse), and unlike
        // the old HttpURLConnection code — where "constructing a client" was
        // free — a Ktor HttpClient owns a real connection pool and its own
        // background threads. Creating and never closing one of those per
        // lookup would leak a thread pool per title over a long browsing
        // session. Ktor's own docs recommend exactly this: build once, reuse
        // for the app's lifetime, never call close(). Same values as the
        // previous HttpURLConnection-based client: 10s to establish the
        // connection, 15s of read idle time (Ktor's socketTimeoutMillis, like
        // Java's old readTimeout, resets on each byte received rather than
        // being a hard ceiling on the whole request — requestTimeoutMillis
        // is deliberately left unset/infinite so a slow-but-steadily-trickling
        // response can still finish, exactly as it could before).
        val http = tapperHttpClient().config {
            // Ktor throws on any non-2xx response by default (expectSuccess
            // defaults to true) before fetch() below ever sees the status
            // code — which would make the 401/other-HTTP-error handling in
            // fetch() unreachable dead code, silently replaced by a generic
            // "couldn't reach themoviedb.org" for every TMDb-side error.
            // Disabling it restores the old HttpURLConnection behaviour,
            // where a non-2xx response was just a response, not a thrown
            // exception, and this class decided what to do about it itself.
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
    }

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
    suspend fun search(title: String, year: Int?, isShow: Boolean): Result? {
        val endpoint = if (isShow) "tv" else "movie"
        val body = fetch(endpoint, title, year, isShow)

        // Body that isn't valid JSON at all (a proxy's HTML error page
        // served with a 200, a truncated response) is left to throw here,
        // same as the old JSONObject(body) did - that failure needs to
        // reach MovieMetadataStore's error path (onError/lastError), not
        // read as "TMDb has no match for this title", which is exactly the
        // ambiguity that class's own doc comment says it exists to avoid.
        // Once parsing has actually succeeded, individual fields being a
        // different shape than expected (a missing key, wrong type) is
        // handled leniently below via `as?` casts instead of the throwing
        // jsonObject/jsonArray extensions - that's a "TMDb's response
        // didn't have the field" case, not a "the response was garbage"
        // one, and shouldn't fail the whole lookup either.
        val root = Json.parseToJsonElement(body) as? JsonObject
        val results = root?.get("results") as? JsonArray
        if (results == null || results.isEmpty()) {
            return if (year != null) search(title, null, isShow) else null
        }
        val o = results[0] as? JsonObject ?: return null

        val name = (if (isShow) o.str("name") else o.str("title"))?.takeIf { it.isNotBlank() } ?: title
        val dateField = if (isShow) "first_air_date" else "release_date"
        val resultYear = o.str(dateField)?.take(4)?.toIntOrNull()
        val posterPath = o.str("poster_path")?.takeIf { it.isNotBlank() && it != "null" }
        val rating = (o["vote_average"] as? JsonPrimitive)?.doubleOrNull
        return Result(
            title = name,
            year = resultYear,
            overview = o.str("overview")?.takeIf { it.isNotBlank() },
            posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            rating = rating?.takeIf { !it.isNaN() && it > 0.0 },
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun fetch(endpoint: String, title: String, year: Int?, isShow: Boolean): String {
        val response: HttpResponse = try {
            http.get("https://api.themoviedb.org/3/search/$endpoint") {
                // Ktor's parameter()/header() URL-encode the value themselves -
                // no more hand-rolled java.net.URLEncoder calls, and no risk
                // of forgetting one on a title with an "&" or "%" in it.
                parameter("query", title)
                if (year != null) parameter(if (isShow) "first_air_date_year" else "year", year)
                parameter("include_adult", "false")
                // A v4 token authenticates via the Authorization header
                // below instead of this query parameter - see the class doc
                // comment for why both forms need to be supported.
                if (!isV4Token) parameter("api_key", apiKey)
                header("User-Agent", "TapperIPTV/0.12")
                if (isV4Token) header("Authorization", "Bearer $apiKey")
            }
        } catch (c: CancellationException) {
            // Must propagate, not get wrapped below - swallowing this would
            // break structured concurrency: cancelling a lookup (e.g. the
            // user scrolled away) would silently keep running it instead of
            // actually stopping, and could surface its result/error after
            // the screen that asked for it is gone.
            throw c
        } catch (t: Throwable) {
            throw TmdbException("Couldn't reach themoviedb.org: ${t.message}", t)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw TmdbException(
                "TMDb rejected the API key (HTTP 401). Double-check it was copied " +
                    "correctly in Settings - either the \"API Key\" or the \"API Read " +
                    "Access Token\" from TMDb's own Settings > API page works here."
            )
        }
        if (!response.status.isSuccess()) {
            throw TmdbException("TMDb returned HTTP ${response.status.value}.")
        }
        return response.bodyAsText()
    }
}
