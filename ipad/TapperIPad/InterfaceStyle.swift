import SwiftUI

/// Which look the app uses, picked in Settings > Interface - the iPad
/// counterpart to Fire TV's UiStyle (ui/theme/Theme.kt).
///
/// `tapper` is the stock iOS look the app has always had. `garysTV` matches
/// the GarysTV DVR's screens: deep navy background and bars, a bright red
/// accent, and a clock in the header.
enum UiStyle: String, CaseIterable, Identifiable {
    case tapper, garysTV

    static let storageKey = "tapper.ui.style"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .tapper: return "Tapper (default)"
        case .garysTV: return "GarysTV"
        }
    }

    var description: String {
        switch self {
        case .tapper: return "The standard iPad look."
        case .garysTV: return "Navy panels with a red highlight, like the GarysTV DVR."
        }
    }
}

/// GarysTV's palette, from its styles.css.
enum GarysPalette {
    static let bg = Color(red: 0x04 / 255, green: 0x07 / 255, blue: 0x0d / 255)      // --bg
    static let bg2 = Color(red: 0x0a / 255, green: 0x14 / 255, blue: 0x24 / 255)     // --bg2
    static let glow = Color(red: 0x15 / 255, green: 0x28 / 255, blue: 0x4a / 255)    // gradient top
    static let panel = Color(red: 0x0e / 255, green: 0x1a / 255, blue: 0x2e / 255)   // --panel
    static let red = Color(red: 0xff / 255, green: 0x33 / 255, blue: 0x55 / 255)     // --red2
    static let ring = Color(red: 0xe4 / 255, green: 0x00 / 255, blue: 0x2b / 255)    // --red

    /// The page background: a soft navy glow from the top left, as on GarysTV.
    static var backdrop: some View {
        RadialGradient(
            colors: [glow, bg2, bg],
            center: UnitPoint(x: 0.2, y: -0.1),
            startRadius: 0,
            endRadius: 1400
        )
        .ignoresSafeArea()
    }
}

/// Applies the chosen interface to a screen. Put it on the content *inside*
/// each NavigationStack (the bar settings only reach a stack from there).
private struct InterfaceStyleModifier: ViewModifier {
    @AppStorage(UiStyle.storageKey) private var styleRaw = UiStyle.tapper.rawValue

    @ViewBuilder
    func body(content: Content) -> some View {
        if UiStyle(rawValue: styleRaw) == .garysTV {
            content
                .scrollContentBackground(.hidden)
                .background { GarysPalette.backdrop }
                .toolbarBackground(GarysPalette.bg2, for: .navigationBar, .tabBar)
                .toolbarBackground(.visible, for: .navigationBar, .tabBar)
                .toolbarColorScheme(.dark, for: .navigationBar, .tabBar)
                .tint(GarysPalette.red)
                .preferredColorScheme(.dark)
        } else {
            content
        }
    }
}

extension View {
    func interfaceStyle() -> some View { modifier(InterfaceStyleModifier()) }
}

/// The header's brand block: the logo with GarysTV's red ring, and a clock,
/// when the GarysTV interface is on; just the logo otherwise.
struct HeaderBrand: View {
    let title: String
    @AppStorage(UiStyle.storageKey) private var styleRaw = UiStyle.tapper.rawValue

    var body: some View {
        if UiStyle(rawValue: styleRaw) == .garysTV {
            HStack(spacing: 10) {
                AppLogoView(size: 28)
                    .overlay(Circle().stroke(GarysPalette.ring, lineWidth: 2))
                    .shadow(color: GarysPalette.ring.opacity(0.45), radius: 6)
                Text(title)
                    .font(.headline.weight(.heavy))
                    .lineLimit(1)
                TimelineView(.everyMinute) { context in
                    Text(context.date, format: .dateTime.hour().minute())
                        .font(.headline.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        } else {
            HStack(spacing: 8) {
                AppLogoView(size: 28)
                Text(title)
                    .font(.headline)
                    .lineLimit(1)
            }
        }
    }
}
