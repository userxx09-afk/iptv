import SwiftUI

@main
struct TapperIPadApp: App {
    init() {
        // Before anything else runs: start the on-device log (survives a
        // crash), note whether the previous run ended badly, and subscribe
        // to Apple's crash reports. See AppLog.swift.
        AppLog.shared.beginSession()
        MetricKitCollector.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
