package io.tapper.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun tapperHttpClient(): HttpClient = HttpClient(OkHttp)
