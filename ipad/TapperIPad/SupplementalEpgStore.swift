import Foundation

/// A guide layered on top of whatever a source's own guide provides, scoped
/// to just the channel ids it declares - not a replacement for that guide.
///
/// Marquee Sports Network is why this exists: it carries no schedule in the
/// iptv-org playlist's own guides, and most Xtream panels' xmltv.php doesn't
/// carry it either. epgshare01.online's "US2" bundle does, under two ids for
/// the main feed and the overflow feed used during scheduling conflicts.
/// Same sources, same ids, as the Fire TV app.
struct SupplementalEpgSource: Codable, Equatable, Identifiable {
    let id: String
    var name: String
    var guideUrl: String
    var channelIds: [String]

    static let marquee = SupplementalEpgSource(
        id: "marquee",
        name: "Marquee Sports Network",
        guideUrl: "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
        channelIds: [
            "marquee.sports.network.hd.us2",
            "marquee.sports.network.overflow.us2",
        ]
    )

    /// The name with everything but letters and digits removed, lowercased -
    /// what a channel's own (compacted) name is searched for.
    var nameNeedle: String { SupplementalEpgSource.compact(name) }

    static func compact(_ text: String) -> String {
        String(text.lowercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(Character.init))
    }
}

/// Plain UserDefaults + JSON, like SourceStore: guide URLs and channel ids,
/// nothing sensitive.
final class SupplementalEpgStore {
    static let shared = SupplementalEpgStore()

    private let defaults = UserDefaults.standard
    private let key = "tapper.supplementalEpg"

    private init() {}

    func all() -> [SupplementalEpgSource] {
        var list: [SupplementalEpgSource] = []
        if let data = defaults.data(forKey: key),
           let decoded = try? JSONDecoder().decode([SupplementalEpgSource].self, from: data) {
            list = decoded
        }
        // Marquee ships as the default every install starts with - and comes
        // back if a damaged value ever drops it.
        if !list.contains(where: { $0.id == SupplementalEpgSource.marquee.id }) {
            list.insert(.marquee, at: 0)
        }
        return list
    }

    private func save(_ list: [SupplementalEpgSource]) {
        if let data = try? JSONEncoder().encode(list) {
            defaults.set(data, forKey: key)
        }
    }

    func add(_ source: SupplementalEpgSource) {
        save(all().filter { $0.id != source.id } + [source])
    }

    func remove(id: String) {
        save(all().filter { $0.id != id })
    }
}
