import Foundation

/// A saved starting point for one list: the country and/or category it opens
/// on instead of showing everything. nil country / nil category both mean
/// "All" - a Home can deliberately point at an unfiltered list.
struct NavHome: Equatable {
    var countryCode: String?
    var category: String?
}

/// Startup behaviour - the iPad counterpart to Fire TV's NavHomeStore plus its
/// "ON STARTUP" setting (resume the last channel, or open the channel list).
///
/// A starting point is per list ("live", "movie", "series", "playlist" - the
/// same namespaces CategoryFilteredList already uses for pinned categories),
/// and is applied whenever that list is first shown for a source. With none
/// set, each list simply opens unfiltered at the top. Plain UserDefaults;
/// nothing here is sensitive.
final class StartupStore: ObservableObject {
    static let shared = StartupStore()

    /// Bumped on every change so Settings (observing this) redraws.
    @Published private(set) var revision = 0

    private let defaults = UserDefaults.standard

    private init() {}

    // MARK: Starting points

    func home(for namespace: String) -> NavHome? {
        guard defaults.bool(forKey: "tapper.home.set.\(namespace)") else { return nil }
        let country = defaults.string(forKey: "tapper.home.country.\(namespace)") ?? ""
        let category = defaults.string(forKey: "tapper.home.category.\(namespace)") ?? ""
        return NavHome(
            countryCode: country.isEmpty ? nil : country,
            category: category.isEmpty ? nil : category
        )
    }

    func setHome(_ home: NavHome, for namespace: String) {
        defaults.set(true, forKey: "tapper.home.set.\(namespace)")
        defaults.set(home.countryCode ?? "", forKey: "tapper.home.country.\(namespace)")
        defaults.set(home.category ?? "", forKey: "tapper.home.category.\(namespace)")
        revision += 1
    }

    func clearHome(for namespace: String) {
        defaults.removeObject(forKey: "tapper.home.set.\(namespace)")
        defaults.removeObject(forKey: "tapper.home.country.\(namespace)")
        defaults.removeObject(forKey: "tapper.home.category.\(namespace)")
        revision += 1
    }

    // MARK: On startup

    /// Start playing whatever was on when the app last closed, instead of
    /// opening the channel list. Live TV only.
    var resumeLast: Bool {
        get { defaults.bool(forKey: "tapper.startup.resumeLast") }
        set { defaults.set(newValue, forKey: "tapper.startup.resumeLast"); revision += 1 }
    }

    /// Which tab opens first for an Xtream source: 0 Live, 1 Movies, 2 Shows.
    var startTab: Int {
        get { min(max(defaults.integer(forKey: "tapper.startup.tab"), 0), 2) }
        set { defaults.set(newValue, forKey: "tapper.startup.tab"); revision += 1 }
    }

    /// The live channel last started from the browse lists, with the saved
    /// source it belonged to (channel ids alone repeat between sources).
    var lastLive: (sourceId: String, channelId: String)? {
        guard let source = defaults.string(forKey: "tapper.startup.lastSource"),
              let channel = defaults.string(forKey: "tapper.startup.lastChannel") else { return nil }
        return (source, channel)
    }

    func recordLastLive(sourceId: String, channelId: String) {
        defaults.set(sourceId, forKey: "tapper.startup.lastSource")
        defaults.set(channelId, forKey: "tapper.startup.lastChannel")
    }
}

// MARK: - Labels

/// Display name for a list namespace.
func listLabel(_ namespace: String) -> String {
    switch namespace {
    case "live": return "Live TV"
    case "movie": return "Movies"
    case "series": return "Shows"
    default: return "Playlist"
    }
}

/// Country code (as iptv-org and Xtream category prefixes spell it) to a
/// readable name. Same table as the shared Country.label, kept in Swift so
/// there is nothing to get wrong at the Kotlin bridge; unknown codes show
/// upper-cased.
private let countryNames: [String: String] = [
    "us": "United States", "gb": "United Kingdom", "uk": "United Kingdom",
    "ca": "Canada", "in": "India", "de": "Germany", "ru": "Russia",
    "se": "Sweden", "br": "Brazil", "es": "Spain", "it": "Italy",
    "fr": "France", "mx": "Mexico", "ar": "Argentina", "nl": "Netherlands",
    "pl": "Poland", "tr": "Turkey", "ua": "Ukraine", "jp": "Japan",
    "kr": "South Korea", "cn": "China", "au": "Australia", "pt": "Portugal",
    "gr": "Greece", "ro": "Romania", "id": "Indonesia", "ph": "Philippines",
    "th": "Thailand", "vn": "Vietnam", "eg": "Egypt", "sa": "Saudi Arabia",
    "ae": "UAE", "il": "Israel", "za": "South Africa", "ng": "Nigeria",
    "no": "Norway", "dk": "Denmark", "fi": "Finland", "cz": "Czechia",
    "at": "Austria", "ch": "Switzerland", "be": "Belgium", "ie": "Ireland",
]

func countryLabel(_ code: String) -> String {
    countryNames[code.lowercased()] ?? code.uppercased()
}

/// "United States  ·  Sports" - how a starting point reads in Settings.
func homeSummary(_ home: NavHome) -> String {
    let parts = [home.countryCode.map(countryLabel), home.category ?? "All"].compactMap { $0 }
    return parts.joined(separator: "  ·  ")
}
