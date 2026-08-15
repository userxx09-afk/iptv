package io.tapper.core

/**
 * A tiny marker with exactly one job: let the iPad app's first-run "hello
 * world" screen (see ipad/TapperIPad/ContentView.swift) call into a real
 * symbol compiled from shared/'s commonMain and prove, on an actual device
 * via TestFlight, that the whole pipeline links end to end — Gradle builds
 * this module into TapperCore.xcframework, Xcode embeds it, Swift calls
 * Kotlin. In generated Objective-C/Swift interop, this object is reachable
 * as `TapperCoreVersion.shared.value`.
 *
 * Delete this once the iPad UI is calling real shared logic (XtreamClient,
 * M3uParser, etc. once those move into commonMain) and it stops being the
 * only thing proving the link works.
 */
object TapperCoreVersion {
    const val value: String = "shared-kmp-scaffold-v1"
}
