import AVFoundation
import Foundation
import TapperCore

/// How far through a film or episode the viewer got.
struct WatchEntry: Codable, Identifiable, Equatable {
    /// "appSourceId|channelId" - Xtream ids repeat between providers, so the
    /// app-level source is part of the key.
    var id: String
    var appSourceId: String
    var channelId: String
    var title: String
    /// For an episode: the show it belongs to.
    var seriesId: String?
    var seriesTitle: String?
    var positionSec: Double
    var durationSec: Double
    var watched: Bool
    var updated: Date

    /// Complete at 92%, or with under two minutes left, whichever comes
    /// first - the same rule as Fire TV. 92% of a three-hour film still
    /// leaves fourteen minutes, and nobody who sat through the credits wants
    /// it back under Continue Watching.
    static func isFinished(position: Double, duration: Double) -> Bool {
        guard duration > 0 else { return false }
        return position >= duration * 0.92 || duration - position <= 120
    }

    var isComplete: Bool {
        watched || WatchEntry.isFinished(position: positionSec, duration: durationSec)
    }

    var fraction: Double {
        guard durationSec > 0 else { return 0 }
        return min(max(positionSec / durationSec, 0), 1)
    }

    var minutesLeft: Int {
        guard durationSec > 0 else { return 0 }
        return max(Int(((durationSec - positionSec) / 60).rounded(.up)), 0)
    }
}

/// Watch progress for films and episodes, kept on this device. Powers the
/// resume-where-you-stopped behaviour, the Continue tab, and the watched
/// marks on lists.
final class WatchStore: ObservableObject {
    static let shared = WatchStore()

    /// Bumped when something a screen shows has changed (not on every
    /// progress tick, which only saves).
    @Published private(set) var revision = 0

    private var entries: [String: WatchEntry] = [:]
    private let url: URL
    private var saveWork: DispatchWorkItem?
    private let maxEntries = 500

    private init() {
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                appropriateFor: nil, create: true))
            ?? fm.temporaryDirectory
        url = base.appendingPathComponent("tapper-watch.json")
        if let data = try? Data(contentsOf: url),
           let decoded = try? JSONDecoder().decode([WatchEntry].self, from: data) {
            for entry in decoded { entries[entry.id] = entry }
        }
    }

    static var currentSourceId: String { SourceStore.shared.activeId }

    private func key(_ channelId: String) -> String { "\(Self.currentSourceId)|\(channelId)" }

    // MARK: - Reading

    func entry(channelId: String) -> WatchEntry? {
        entries[key(channelId)]
    }

    /// Partly-watched items on the active source, most recent first.
    func inProgress() -> [WatchEntry] {
        let source = Self.currentSourceId
        return entries.values
            .filter { $0.appSourceId == source && !$0.isComplete && $0.positionSec >= 30 }
            .sorted { $0.updated > $1.updated }
    }

    // MARK: - Writing

    /// Records playback progress. [publish] is true on the final call when
    /// the player closes; the periodic calls only save quietly.
    func record(channel: Channel, series: Channel?, position: Double, duration: Double, publish: Bool) {
        let source = Self.currentSourceId
        let k = "\(source)|\(channel.id)"
        var entry = entries[k] ?? WatchEntry(
            id: k, appSourceId: source, channelId: channel.id, title: channel.name,
            seriesId: series?.id, seriesTitle: series?.name,
            positionSec: 0, durationSec: 0, watched: false, updated: Date()
        )
        entry.title = channel.name
        entry.seriesId = series?.id
        entry.seriesTitle = series?.name
        if duration > 0 { entry.durationSec = duration }
        entry.updated = Date()

        if WatchEntry.isFinished(position: position, duration: entry.durationSec) {
            entry.watched = true
            entry.positionSec = 0
        } else {
            // Watching a finished item again puts it back in progress.
            if entry.watched && position > 30 { entry.watched = false }
            entry.positionSec = position
        }
        entries[k] = entry
        scheduleSave()
        if publish { revision += 1 }
    }

    func setWatched(channel: Channel, series: Channel?, watched: Bool) {
        let source = Self.currentSourceId
        let k = "\(source)|\(channel.id)"
        var entry = entries[k] ?? WatchEntry(
            id: k, appSourceId: source, channelId: channel.id, title: channel.name,
            seriesId: series?.id, seriesTitle: series?.name,
            positionSec: 0, durationSec: 0, watched: false, updated: Date()
        )
        entry.watched = watched
        entry.positionSec = 0
        entry.updated = Date()
        entries[k] = entry
        scheduleSave()
        revision += 1
    }

    func remove(id: String) {
        entries[id] = nil
        scheduleSave()
        revision += 1
    }

    func removeEntry(channelId: String) {
        remove(id: key(channelId))
    }

    func clearAll() {
        entries = [:]
        scheduleSave()
        revision += 1
    }

    private func scheduleSave() {
        saveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.save() }
        saveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: work)
    }

    private func save() {
        var all = Array(entries.values)
        if all.count > maxEntries {
            all.sort { $0.updated > $1.updated }
            all = Array(all.prefix(maxEntries))
            entries = Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })
        }
        if let data = try? JSONEncoder().encode(all) {
            try? data.write(to: url, options: .atomic)
        }
    }
}

/// Samples the player while a film or episode plays and saves progress. It
/// stays silent until [arm] when resuming, so the first ticks (position 0,
/// before the jump to the saved spot) never overwrite the saved position.
final class WatchRecorder: ObservableObject {
    private var token: Any?
    private weak var player: AVPlayer?
    private var channel: Channel?
    private var series: Channel?
    private var armed = false

    func start(player: AVPlayer, channel: Channel, series: Channel?, armed: Bool) {
        stop()
        self.player = player
        self.channel = channel
        self.series = series
        self.armed = armed
        token = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] _ in
            self?.tick(final: false)
        }
    }

    func arm() { armed = true }

    private func tick(final: Bool) {
        guard armed, let player, let channel else { return }
        let position = player.currentTime().seconds
        let rawDuration = player.currentItem?.duration.seconds ?? 0
        guard position.isFinite, position >= 0 else { return }
        WatchStore.shared.record(
            channel: channel, series: series,
            position: position, duration: rawDuration.isFinite ? rawDuration : 0,
            publish: final
        )
    }

    func stop() {
        if let token, let player {
            tick(final: true)
            player.removeTimeObserver(token)
        }
        token = nil
        player = nil
        channel = nil
        series = nil
        armed = false
    }
}
