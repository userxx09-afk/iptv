package io.tapper.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun tapperHttpClient(): HttpClient = HttpClient(Darwin)
