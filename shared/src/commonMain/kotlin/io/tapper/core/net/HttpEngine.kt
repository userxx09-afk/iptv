package io.tapper.core.net

import io.ktor.client.HttpClient

/**
 * Ktor's client API (used today by [io.tapper.core.tmdb.TmdbClient];
 * [io.tapper.core.xtream.XtreamClient] is still on HttpURLConnection/org.json
 * in androidMain, pending its own migration in a later round) already
 * abstracts away almost everything platform-specific about making an HTTP
 * request — building the request, reading headers/status/body, timeouts, are
 * all written once against Ktor's common API. The one thing it deliberately
 * leaves to the app is *which* engine actually opens the socket, because each
 * engine is a separate platform-specific library: OkHttp on Android, a
 * wrapper around NSURLSession (Ktor's "Darwin" engine) on iOS. This function
 * — and its two `actual` implementations — is the entire expect/actual
 * surface this networking code needs; everything else lives once in
 * commonMain.
 *
 * Returns a bare, unconfigured client. Callers install their own plugins
 * (timeouts, etc.) via `.config { }` rather than this function baking in one
 * fixed policy — XtreamClient (once migrated) and TmdbClient need different
 * timeout values (60s read for Xtream's large catalogue responses vs. 15s
 * for TMDb's small ones), and there's no reason to force them to match.
 */
expect fun tapperHttpClient(): HttpClient
