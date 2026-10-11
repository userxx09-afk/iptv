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

    // Sync. Optional so progress saved before sync existed still loads.
    /// Lamport clock shared with Fire TV: orders changes between devices
    /// whatever either device thinks the time is.
    var clock: Int64? = nil
    /// Which device last changed this. Only that device publishes it.
    var device: String? = nil
    var season: Int? = nil
    var number: Int? = nil
    /// Set when this device deliberately cleared the entry (remove, mark as
    /// unwatched). Older copies arriving from other devices are ignored, so
    /// the clear is not undone by the next sync.
    var resetClock: Int64? = nil

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
    private let deviceId = SyncDevice.id
    private var highClock: Int64 = 0

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
        // Progress saved before sync existed: it all belongs to this device.
        for (key, var entry) in Array(entries) where entry.clock == nil {
            entry.clock = Int64(entry.updated.timeIntervalSince1970 * 1000)
            entry.device = deviceId
            entries[key] = entry
        }
        highClock = entries.values.compactMap { $0.clock }.max() ?? 0
    }

    /// A Lamport clock: the wall clock, but never behind anything seen so far
    /// (including other devices' changes), so a change made here always sorts
    /// after what it was based on.
    private func nextClock() -> Int64 {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        highClock = max(now, highClock + 1)
        return highClock
    }

    private static func seasonNumber(from group: String?) -> Int? {
        guard let group else { return nil }
        let digits = group.filter { $0.isNumber }
        return digits.isEmpty ? nil : Int(digits)
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
        entry.clock = nextClock()
        entry.device = deviceId
        if series != nil {
            entry.season = Self.seasonNumber(from: channel.group)
            entry.number = channel.number.map { Int(truncating: $0) }
        }

        if WatchEntry.isFinished(position: position, duration: entry.durationSec) {
            entry.watched = true
            entry.positionSec = 0
        } else {
            // Watching a finished item again puts it back in progress.
            if entry.watched && position > 30 {
                entry.watched = false
                entry.resetClock = entry.clock
            }
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
        entry.clock = nextClock()
        entry.device = deviceId
        if !watched { entry.resetClock = entry.clock }
        entries[k] = entry
        scheduleSave()
        revision += 1
    }

    /// Forgets progress. With sync on, the entry is kept but cleared (and
    /// stamped), so another device's older copy can't bring it back.
    func remove(id: String) {
        reset(id: id)
        scheduleSave()
        revision += 1
    }

    private func reset(id: String) {
        guard var entry = entries[id] else { return }
        if !SyncConfig.isConfigured {
            entries[id] = nil
            return
        }
        entry.positionSec = 0
        entry.watched = false
        entry.updated = Date()
        entry.clock = nextClock()
        entry.device = deviceId
        entry.resetClock = entry.clock
        entries[id] = entry
    }

    func removeEntry(channelId: String) {
        remove(id: key(channelId))
    }

    func clearAll() {
        for id in Array(entries.keys) { reset(id: id) }
        scheduleSave()
        revision += 1
    }

    /// Writes pending changes now - for when the app goes to the background.
    func flush() {
        saveWork?.cancel()
        save()
    }

    // MARK: - Sync with other devices

    /// This device's progress, in the file format Fire TV reads and writes
    /// (positions in milliseconds, one file per device). Only entries this
    /// device last changed go in - everything else is published by the
    /// device that made it.
    func exportOwn(label: String) -> Data {
        let mine = entries.values
            .filter { $0.device == deviceId && $0.clock != nil }
            .sorted { ($0.clock ?? 0) > ($1.clock ?? 0) }
        let rows: [[String: Any]] = mine.map { (e: WatchEntry) -> [String: Any] in
            var series: Any = NSNull()
            if let sid = e.seriesId, !sid.isEmpty {
                series = sid.hasPrefix("series-") ? String(sid.dropFirst("series-".count)) : sid
            }
            var row: [String: Any] = [
                "item": e.channelId,
                "source": e.appSourceId,
                "series": series,
                "season": e.season ?? 0,
                "number": e.number ?? 0,
                "title": e.title,
                "pos": Int64((e.positionSec * 1000).rounded()),
                "dur": Int64((e.durationSec * 1000).rounded()),
                "watched": e.watched,
                "clock": e.clock ?? 0,
                "device": deviceId,
            ]
            // A deliberate clear (mark unwatched, remove, watching a finished
            // item again) tells the other devices to drop their watched mark
            // too. Only while it is the latest change to the entry.
            if let reset = e.resetClock, reset == e.clock { row["reset"] = true }
            return row
        }
        let root: [String: Any] = [
            "version": 1,
            "device": deviceId,
            "label": label,
            "written": Int64(Date().timeIntervalSince1970 * 1000),
            "entries": rows,
        ]
        return (try? JSONSerialization.data(withJSONObject: root)) ?? Data("{}".utf8)
    }

    /// Merges another device's file. Same rules as Fire TV, so every device
    /// ends up with the same answer whatever order they sync in: watched
    /// never reverts, the newer change wins, and a finished item has no
    /// position.
    func importShard(_ data: Data) -> (changed: Int, label: String?) {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return (0, nil)
        }
        let label = root["label"] as? String
        let shardDevice = root["device"] as? String ?? ""
        guard let list = root["entries"] as? [[String: Any]] else { return (0, label) }

        let knownSources = Set(SourceStore.shared.all().map { $0.id })
        let active = Self.currentSourceId
        let now = Date()
        var changed = 0

        func int(_ any: Any?) -> Int64 { (any as? NSNumber)?.int64Value ?? 0 }

        for o in list {
            guard let item = o["item"] as? String, !item.isEmpty else { continue }
            // A source this iPad doesn't know (Fire TV's ids are its own)
            // is filed under the one in use, so it still shows up.
            var source = o["source"] as? String ?? ""
            if !knownSources.contains(source) { source = active }

            var seriesId: String?
            // Only episodes belong to a show (older Fire TV builds tagged
            // films watched after browsing a show with that show's id).
            if item.hasPrefix("ep-"), let raw = o["series"] as? String, !raw.isEmpty, raw != "null" {
                seriesId = raw.hasPrefix("series-") ? raw : "series-" + raw
            }
            let clock = int(o["clock"])
            highClock = max(highClock, clock)
            let stamp = min(Date(timeIntervalSince1970: Double(clock) / 1000), now)
            let device = (o["device"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? shardDevice

            let remote = WatchEntry(
                id: "\(source)|\(item)", appSourceId: source, channelId: item,
                title: o["title"] as? String ?? "",
                seriesId: seriesId, seriesTitle: nil,
                positionSec: Double(int(o["pos"])) / 1000,
                durationSec: Double(int(o["dur"])) / 1000,
                watched: (o["watched"] as? Bool) ?? false,
                updated: stamp,
                clock: clock, device: device,
                season: Int(int(o["season"])), number: Int(int(o["number"]))
            )
            if merge(remote, isReset: (o["reset"] as? Bool) ?? false) { changed += 1 }
        }
        if changed > 0 {
            scheduleSave()
            revision += 1
        }
        return (changed, label)
    }

    private func merge(_ remote: WatchEntry, isReset: Bool) -> Bool {
        guard let local = entries[remote.id] else {
            entries[remote.id] = remote
            return true
        }
        let remoteClock = remote.clock ?? 0
        let localClock = local.clock ?? 0
        // Cleared here on purpose, after this copy was made.
        if let reset = local.resetClock, remoteClock <= reset { return false }

        // Watched is one-way, except that a deliberate clear which is also the
        // newest change wins.
        let remoteNewer = remoteClock >= localClock
        let watched = (remoteNewer && isReset) ? false : (local.watched || remote.watched)
        var merged = remoteNewer ? remote : local
        merged.watched = watched
        merged.durationSec = max(local.durationSec, remote.durationSec)
        merged.positionSec = watched ? 0 : merged.positionSec
        merged.clock = max(localClock, remoteClock)
        // Details only this iPad knows survive a merge.
        merged.seriesTitle = merged.seriesTitle ?? local.seriesTitle
        merged.seriesId = merged.seriesId ?? local.seriesId
        merged.resetClock = local.resetClock
        if merged.title.isEmpty { merged.title = local.title }

        if merged == local { return false }
        entries[remote.id] = merged
        return true
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
