import SwiftUI
import TapperCore

/// Phase-1 scaffold screen. Its only job is to prove, on a real device via
/// TestFlight, that the whole pipeline works end to end: Gradle builds
/// shared/ into TapperCore.xcframework, Xcode links it into this app, and
/// Swift can call into Kotlin across that boundary. See
/// shared/src/commonMain/kotlin/io/tapper/core/TapperCoreVersion.kt for why
/// that particular symbol exists and when to delete it (and this comment
/// with it) in favor of real UI calling real shared logic.
struct ContentView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("Tapper IPTV — iPad")
                .font(.title)
            Text("shared/ says: \(TapperCoreVersion.shared.value)")
                .font(.body)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
