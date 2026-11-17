import Foundation
import TapperCore

/// The Free TV lineup and the extra guide files, shared with GarysTV - the
/// iPad counterpart to Fire TV's GarysTvFeed.kt.
///
/// Read from the GarysTV public repo so a channel added there shows up here
/// at the next refresh with no new build; kept for a day in Caches; and a
/// copy ships in the app (GarysTv/*.json) for a first launch offline.
/// Parsing is shared Kotlin (GarysTvFiles), the same rules Fire TV uses.
final class GarysTvFeed {
    static let shared = GarysTvFeed()

    private let maxAge: TimeInterval = 24 * 3600
    private let cacheDir: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]

    private init() {}

    private func cacheURL(_ path: String) -> URL {
        cacheDir.appendingPathComponent("garystv-" + (path as NSString).lastPathComponent)
    }

    /// A fresh cached copy, else GitHub, else any cached copy, else the bundled one.
    func text(_ path: String, force: Bool = false) async -> String {
        let cache = cacheURL(path)
        let attrs = try? FileManager.default.attributesOfItem(atPath: cache.path)
        let modified = attrs?[.modificationDate] as? Date
        if !force, let modified, Date().timeIntervalSince(modified) < maxAge,
           let cached = try? String(contentsOf: cache, encoding: .utf8) {
            return cached
        }
        if let url = URL(string: GarysTvFiles.shared.BASE + path),
           let (data, response) = try? await URLSession.shared.data(from: url),
           (response as? HTTPURLResponse)?.statusCode == 200,
           let body = String(data: data, encoding: .utf8),
           (try? JSONSerialization.jsonObject(with: data)) != nil {
            try? data.write(to: cache, options: .atomic)
            return body
        }
        if let cached = try? String(contentsOf: cache, encoding: .utf8) { return cached }
        let name = ((path as NSString).lastPathComponent as NSString).deletingPathExtension
        if let bundled = Bundle.main.url(forResource: name, withExtension: "json"),
           let text = try? String(contentsOf: bundled, encoding: .utf8) {
            return text
        }
        return ""
    }

    /// The Free TV channels as `sourceId`'s live lineup.
    func lineup(sourceId: String, force: Bool = false) async -> [Channel] {
        let json = await text(GarysTvFiles.shared.LINEUP, force: force)
        return GarysTvFiles.shared.lineup(json: json, sourceId: sourceId)
    }

    /// Both guide files, merged.
    func guides() async -> [GarysTvFiles.GuideFile] {
        let extra = await text(GarysTvFiles.shared.EXTRA_GUIDES)
        let free = await text(GarysTvFiles.shared.FREE_GUIDES)
        return GarysTvFiles.shared.guides(jsons: [extra, free])
    }
}

/// Free TV settings. "Show alongside" merges Free TV's live channels into
/// whichever source is active, under "Free TV · ..." categories at the bottom
/// - on by default, as on Fire TV.
final class FreeTvSettings {
    static let shared = FreeTvSettings()
    private let defaults = UserDefaults.standard
    private init() {}

    var showAlongside: Bool {
        get { defaults.object(forKey: "tapper.freeTv.alongside") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "tapper.freeTv.alongside") }
    }
}

/// Free TV's channels for showing alongside the active source, loaded once
/// per launch (and on demand from Settings).
@MainActor
final class FreeTvLoader: ObservableObject {
    static let shared = FreeTvLoader()
    @Published private(set) var channels: [Channel] = []
    private var loading = false

    func loadIfNeeded(force: Bool = false) {
        guard force || (channels.isEmpty && !loading) else { return }
        loading = true
        Task {
            let lineup = await GarysTvFeed.shared.lineup(sourceId: TvSource.freeTv.id, force: force)
            self.channels = GarysTvFiles.shared.alongside(channels: lineup)
            self.loading = false
            AppLog.shared.log("FREETV", "Free TV lineup: \(lineup.count) channels")
        }
    }
}
