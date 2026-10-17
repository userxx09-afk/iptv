import SwiftUI
import UserNotifications

@main
struct TapperIPadApp: App {
    init() {
        // Before anything else runs: start the on-device log (survives a
        // crash), note whether the previous run ended badly, and subscribe
        // to Apple's crash reports. See AppLog.swift.
        AppLog.shared.beginSession()
        MetricKitCollector.shared.start()
        // So a tapped reminder reaches the app, and shows even while it's open.
        UNUserNotificationCenter.current().delegate = ReminderCenter.shared
        // Start watching the connection from launch, so the player knows
        // whether it is on Wi-Fi or mobile data from its first frame.
        _ = NetworkMonitor.shared
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
