package io.tapper.core.xtream

import io.tapper.core.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * iOS-only callback bridge over XtreamClient's suspend functions.
 *
 * Calling a Kotlin `suspend` function directly from Swift async/await is a
 * much less proven part of the KMP/Swift interop boundary than a plain
 * closure call - PlaylistLoader.swift's own doc comment flags this exact
 * caution for TmdbClient's suspend calls. A closure-based callback crossing
 * the Kotlin/Swift boundary, by contrast, is a well-established, low-risk
 * pattern. Every function below launches its own coroutine on a background
 * dispatcher and delivers the result back through a plain callback - Swift
 * never calls a suspend function directly.
 *
 * Callbacks fire on a background thread (whatever thread the coroutine
 * happens to finish on), not the main thread - matching PlaylistLoader's own
 * URLSession-based fetch, where the Swift side is the one responsible for
 * hopping back via `MainActor.run` before touching @Published state. Kept
 * consistent with that existing pattern rather than introducing a second,
 * different threading contract (e.g. forcing Dispatchers.Main here) that
 * would need to be learned separately.
 *
 * An `object`, not bare top-level functions - matching M3uParser's own
 * `object` + `.shared` pattern (already proven from Swift as
 * `M3uParser.shared.parse(...)` in PlaylistLoader.swift), rather than
 * relying on Kotlin/Native's file-name-based `XtreamBridgeKt` export, which
 * nothing in this codebase has exercised yet.
 */
object XtreamBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Validates the account, then loads all three catalogues. A catalogue
     * that fails to load after a successful login is reported through
     * [onWarning] (same as a failed category lookup inside XtreamClient
     * itself) rather than failing the whole load - a panel with working
     * live TV but a broken VOD endpoint should still hand back your live
     * channels instead of nothing. [onResult] fires exactly once, either
     * with a populated [XtreamLoadResult] or with an error message if the
     * login itself failed.
     */
    fun loginAndLoad(
        host: String,
        username: String,
        password: String,
        sourceId: String,
        onWarning: (String) -> Unit,
        onResult: (XtreamLoadResult?, String?) -> Unit,
    ) {
        scope.launch {
            val client = XtreamClient(host, username, password)
            try {
                val account = client.authenticate()
                val live = runCatching { client.liveChannels(sourceId, onWarning = onWarning) }
                    .onFailure { onWarning("Couldn't load live channels: ${it.message}") }
                    .getOrDefault(emptyList())
                val movies = runCatching { client.movies(sourceId, onWarning = onWarning) }
                    .onFailure { onWarning("Couldn't load movies: ${it.message}") }
                    .getOrDefault(emptyList())
                val series = runCatching { client.series(sourceId, onWarning = onWarning) }
                    .onFailure { onWarning("Couldn't load shows: ${it.message}") }
                    .getOrDefault(emptyList())
                onResult(XtreamLoadResult(account, live, movies, series), null)
            } catch (t: Throwable) {
                onResult(null, t.message ?: "Couldn't log in.")
            }
        }
    }

    /**
     * Episodes for one series - fetched on demand when a series is opened
     * rather than loaded up front for every series (see
     * XtreamClient.episodes's own doc comment for why). [onResult] fires
     * exactly once.
     */
    fun loadEpisodes(
        host: String,
        username: String,
        password: String,
        sourceId: String,
        seriesId: String,
        onResult: (List<Channel>?, String?) -> Unit,
    ) {
        scope.launch {
            try {
                val client = XtreamClient(host, username, password)
                val episodes = client.episodes(sourceId, seriesId)
                onResult(episodes, null)
            } catch (t: Throwable) {
                onResult(null, t.message ?: "Couldn't load episodes.")
            }
        }
    }
}

class XtreamLoadResult(
    val account: XtreamAccount,
    val live: List<Channel>,
    val movies: List<Channel>,
    val series: List<Channel>,
)
