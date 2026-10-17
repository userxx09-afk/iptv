import Foundation
import TapperCore

/// What is on right now, for the rows of the live channel list. Kept apart
/// from the refresh status on purpose: this publishes about once a minute,
/// the status publishes continuously during a guide import, and the list
/// rows must only redraw for the former.
final class EpgNowStore: ObservableObject {
    static let shared = EpgNowStore()

    /// Bumped whenever the map changes.
    @Published private(set) var revision = 0

    private(set) var nowPlaying: [String: EpgProgramme] = [:]
    /// Supplemental guides, by the compacted channel name they belong to.
    private(set) var hints: [(needle: String, ids: [String])] = []

    private init() {}

    func update(_ map: [String: EpgProgramme], hints: [(needle: String, ids: [String])]) {
        nowPlaying = map
        self.hints = hints
        revision += 1
    }

    func clear() {
        nowPlaying = [:]
        revision += 1
    }

    /// Guide ids to try for a channel, best first: the one its source
    /// declared, then any supplemental guide named after it (a channel whose
    /// provider never carries a schedule for it - Marquee Sports Network).
    func candidateKeys(for channel: Channel) -> [String] {
        var keys: [String] = []
        let direct = EpgDatabase.normalizeId(channel.epgChannelId)
        if !direct.isEmpty { keys.append(direct) }
        if !hints.isEmpty {
            let name = SupplementalEpgSource.compact(channel.name)
            for hint in hints where !hint.needle.isEmpty && name.contains(hint.needle) {
                keys.append(contentsOf: hint.ids)
            }
        }
        return keys
    }

    func programme(for channel: Channel) -> EpgProgramme? {
        if nowPlaying.isEmpty { return nil }
        for key in candidateKeys(for: channel) {
            if let p = nowPlaying[key] { return p }
        }
        return nil
    }
}

/// Progress and outcome of guide refreshes, for Settings. Post from any
/// thread; it hops to the main thread itself.
final class EpgStatus: ObservableObject {
    static let shared = EpgStatus()

    @Published private(set) var text = ""
    @Published private(set) var isRefreshing = false

    private init() {}

    func post(_ text: String, refreshing: Bool) {
        DispatchQueue.main.async {
            self.text = text
            self.isRefreshing = refreshing
        }
    }
}

/// A programme found by title search, with the channel it airs on (when that
/// channel is in the loaded source).
struct ProgrammeHit: Identifiable {
    let programme: EpgProgramme
    let channel: Channel?
    var id: String { "\(programme.channelId)#\(programme.start)" }
}

/// Downloads, stores and serves the programme guide - the iPad counterpart of
/// Fire TV's EpgRepository.
///
/// A source's guide comes from (in order) the guide URL set on the source,
/// the Xtream panel's own xmltv.php, and whatever an M3U playlist declares;
/// dead guide URLs are the norm, so each is tried until one works. Only
/// programmes for channels the source actually has are kept, in a window of a
/// few hours back to a day and a half ahead. Supplemental guides (Marquee
/// Sports Network, plus any added in Settings) are then layered on, touching
/// only the channel ids they declare.
///
/// Call from the main thread. The heavy work runs in the background.
final class EpgManager {
    static let shared = EpgManager()

    private static let staleAfter = 6 * 3600
    private static let retryAfter = 30 * 60
    private static let maxRows = 600_000

    private let db = EpgDatabase.shared
    private var source: TvSource?
    private var declaredUrls: [String] = []
    private var wanted = Set<String>()
    private var liveKeys: [String] = []
    private var channelByGuideId: [String: Channel] = [:]
    private var refreshTask: Task<Void, Never>?
    private var activation = 0
    private var clock: Timer?
    /// A film, episode or channel is on screen.
    private var playbackActive = false

    private init() {
        // Back on Wi-Fi (or the connection returning) is the moment a guide
        // that was held back gets its turn.
        NetworkMonitor.shared.observe { [weak self] old, new in
            let usable = new.online && !new.expensive && !new.constrained
            let wasUsable = old.online && !old.expensive && !old.constrained
            if usable && !wasUsable { self?.refreshIfStale() }
        }
    }

    /// The player opened or closed. A guide refresh is a large download and
    /// a long parse; it waits until nothing is playing, then runs if it is
    /// still due.
    func playbackChanged(active: Bool) {
        playbackActive = active
        if !active { refreshIfStale() }
    }

    /// Why a due refresh should wait, or nil to go ahead. A refresh the
    /// viewer asks for (Settings) always runs.
    private func deferReason() -> String? {
        if playbackActive { return "once playback ends" }
        let net = NetworkMonitor.shared.state
        if !net.online { return "when you're back online" }
        if net.expensive || net.constrained { return "on Wi-Fi" }
        return nil
    }

    // MARK: - Lifecycle

    /// A source finished loading: remember what its live channels are, show
    /// whatever guide is already stored, and refresh it in the background if
    /// it is old.
    func activate(source: TvSource, declaredEpgUrls: [String], liveChannels: [Channel]) {
        refreshTask?.cancel()
        activation += 1
        let mine = activation
        self.source = source
        declaredUrls = declaredEpgUrls
        wanted = []
        liveKeys = []
        channelByGuideId = [:]
        EpgNowStore.shared.clear()
        startClock()

        if liveChannels.isEmpty {
            EpgStatus.shared.post("No live channels in this source.", refreshing: false)
            return
        }

        let supplemental = SupplementalEpgStore.shared.all()
        Task.detached(priority: .utility) { [weak self] in
            // Reading a field off every channel crosses the Kotlin bridge
            // once per channel - done here, off the main thread, once.
            var keys: [String] = []
            keys.reserveCapacity(liveChannels.count)
            var byId: [String: Channel] = [:]
            for channel in liveChannels {
                let key = EpgDatabase.normalizeId(channel.epgChannelId)
                keys.append(key)
                if !key.isEmpty, byId[key] == nil { byId[key] = channel }
            }
            // Supplemental guides map to the channel named after them.
            let compactNames = liveChannels.map { SupplementalEpgSource.compact($0.name) }
            for entry in supplemental {
                let needle = entry.nameNeedle
                guard !needle.isEmpty,
                      let index = compactNames.firstIndex(where: { $0.contains(needle) }) else { continue }
                for id in entry.channelIds {
                    let normalized = EpgDatabase.normalizeId(id)
                    if byId[normalized] == nil { byId[normalized] = liveChannels[index] }
                }
            }
            let wantedIds = Set(keys.filter { !$0.isEmpty })

            await MainActor.run {
                guard let self, self.activation == mine else { return }
                self.liveKeys = keys
                self.wanted = wantedIds
                self.channelByGuideId = byId
                self.reloadNow()
                self.refreshIfStale()
            }
        }
    }

    func deactivate() {
        refreshTask?.cancel()
        activation += 1
        source = nil
        EpgNowStore.shared.clear()
        clock?.invalidate()
        clock = nil
    }

    private func startClock() {
        clock?.invalidate()
        clock = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            self?.reloadNow()
        }
    }

    /// Re-reads what is on now. Cheap; runs every minute and on returning to
    /// the foreground.
    func reloadNow() {
        guard let id = source?.id else { return }
        let hints = SupplementalEpgStore.shared.all().map { entry in
            (needle: entry.nameNeedle, ids: entry.channelIds.map { EpgDatabase.normalizeId($0) })
        }
        Task.detached(priority: .utility) { [weak self] in
            let map = EpgDatabase.shared.nowPlaying(id, now: Int(Date().timeIntervalSince1970))
            await MainActor.run {
                guard let self, self.source?.id == id else { return }
                EpgNowStore.shared.update(map, hints: hints)
            }
        }
    }

    // MARK: - Refresh

    private func attemptKey(_ id: String) -> String { "tapper.epg.attempt.\(id)" }

    private func refreshIfStale() {
        guard let source else { return }
        let now = Int(Date().timeIntervalSince1970)
        let last = max(db.fetchedAt(source.id), db.fetchedAt(source.id + "#supp"))
        let lastAttempt = UserDefaults.standard.integer(forKey: attemptKey(source.id))
        if now - last > Self.staleAfter && now - lastAttempt > Self.retryAfter {
            if let reason = deferReason() {
                AppLog.shared.log("EPG", "Guide refresh waits \(reason)")
                let age = last > 0 ? "Guide updated \(Self.ago(now - last)) ago. " : ""
                EpgStatus.shared.post("\(age)Will refresh \(reason).", refreshing: false)
                return
            }
            refresh()
        } else if last > 0 {
            EpgStatus.shared.post("Guide updated \(Self.ago(now - last)) ago.", refreshing: false)
        }
    }

    private static func ago(_ seconds: Int) -> String {
        if seconds < 90 { return "just now" }
        if seconds < 5400 { return "\(seconds / 60) min" }
        if seconds < 172_800 { return "\(seconds / 3600) hr" }
        return "\(seconds / 86_400) days"
    }

    /// Downloads the guide again now.
    func refresh() {
        guard let source else { return }
        refreshTask?.cancel()
        let mine = activation
        let urls = guideUrls(for: source)
        let wantedIds = wanted
        UserDefaults.standard.set(Int(Date().timeIntervalSince1970), forKey: attemptKey(source.id))
        EpgStatus.shared.post("Guide: downloading...", refreshing: true)

        refreshTask = Task.detached(priority: .utility) { [weak self] in
            let ok = await EpgManager.runRefresh(source: source, urls: urls, wanted: wantedIds)
            await MainActor.run {
                guard let self, self.activation == mine else { return }
                self.reloadNow()
                _ = ok
            }
        }
    }

    private func guideUrls(for source: TvSource) -> [String] {
        var urls: [String] = []
        if let override = source.epgUrlOverride?.trimmingCharacters(in: .whitespacesAndNewlines), !override.isEmpty {
            urls.append(override)
        }
        if source.kind == .xtream, let creds = CredentialVault.get(sourceId: source.id) {
            if let url = Self.xtreamGuideUrl(host: source.location, username: creds.username, password: creds.password) {
                urls.append(url)
            }
        }
        urls.append(contentsOf: declaredUrls)
        var seen = Set<String>()
        return urls.filter { seen.insert($0).inserted }
    }

    /// The panel's own guide, matched to exactly the channels it carries.
    /// Host normalisation mirrors the shared XtreamClient.
    private static func xtreamGuideUrl(host: String, username: String, password: String) -> String? {
        var base = host.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        if !(base.lowercased().hasPrefix("http://") || base.lowercased().hasPrefix("https://")) {
            base = "http://" + base
        }
        if let q = base.firstIndex(of: "?") { base = String(base[..<q]) }
        while base.hasSuffix("/") { base.removeLast() }
        for suffix in ["/player_api.php", "/panel_api.php", "/get.php", "/xmltv.php", "/c", "/index.php"] {
            if base.lowercased().hasSuffix(suffix) {
                base = String(base.dropLast(suffix.count))
                break
            }
        }
        while base.hasSuffix("/") { base.removeLast() }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let u = username.addingPercentEncoding(withAllowedCharacters: allowed),
              let p = password.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
        return "\(base)/xmltv.php?username=\(u)&password=\(p)"
    }

    private static func runRefresh(source: TvSource, urls: [String], wanted: Set<String>) async -> Bool {
        let status = EpgStatus.shared
        let log = AppLog.shared
        let now = Int(Date().timeIntervalSince1970)
        var primaryOk = false
        var lastError: String?
        var summary = ""

        if wanted.isEmpty {
            lastError = "this source's channels don't name any guide ids"
        } else if urls.isEmpty {
            lastError = "no guide URL (add one on the source)"
        } else {
            for urlString in urls {
                if Task.isCancelled { return false }
                guard let url = URL(string: urlString) else { continue }
                let host = AppLog.hostOnly(urlString)
                do {
                    let started = Date()
                    status.post("Guide: downloading from \(host)...", refreshing: true)
                    let xml = try await GuideFile.fetchXml(from: url)
                    defer { try? FileManager.default.removeItem(at: xml) }
                    let downloaded = Date().timeIntervalSince(started)
                    status.post("Guide: reading...", refreshing: true)
                    let (programmes, channels) = await importPrimary(xml: xml, sourceId: source.id, wanted: wanted, now: now)
                    let secs = String(format: "%.0f", Date().timeIntervalSince(started))
                    log.log("EPG", "\(host): \(programmes) programmes for \(channels) channels (download \(String(format: "%.0f", downloaded))s, total \(secs)s) - \(AppLog.memoryDescription())")
                    if programmes == 0 {
                        lastError = "\(host) had no programmes for these channels"
                        continue
                    }
                    EpgDatabase.shared.setFetched(source.id, at: now)
                    summary = "\(programmes) programmes, \(channels) channels"
                    primaryOk = true
                    break
                } catch {
                    lastError = "\(host): \(error.localizedDescription)"
                    log.log("EPG", "Guide failed - \(lastError ?? "")")
                }
            }
        }

        if Task.isCancelled { return false }
        status.post("Guide: adding supplemental listings...", refreshing: true)
        let extra = await mergeSupplemental(sourceId: source.id, now: now)
        EpgDatabase.shared.prune(before: now - 6 * 3600)

        var text: String
        if primaryOk {
            text = "Guide updated: \(summary)"
            if extra > 0 { text += ", plus \(extra) supplemental" }
        } else if extra > 0 {
            text = "Provider guide unavailable (\(lastError ?? "unknown")). \(extra) supplemental programmes added."
        } else {
            text = "Guide not updated: \(lastError ?? "unknown error")"
        }
        status.post(text, refreshing: false)
        return primaryOk || extra > 0
    }

    /// Parses and stores one provider guide off the cooperative thread pool -
    /// this is minutes of blocking work for a big guide.
    private static func importPrimary(xml: URL, sourceId: String, wanted: Set<String>, now: Int) async -> (Int, Int) {
        await withCheckedContinuation { (cont: CheckedContinuation<(Int, Int), Never>) in
            DispatchQueue.global(qos: .utility).async {
                var channels = 0
                let written = EpgDatabase.shared.replaceStreaming(
                    sourceId: sourceId,
                    onProgress: { rows in
                        EpgStatus.shared.post("Guide: reading, \(rows) programmes kept...", refreshing: true)
                    },
                    produce: { emit in
                        let parser = GuideFile.parse(file: xml, wanted: wanted, now: now, maxRows: EpgManager.maxRows, emit: emit)
                        channels = parser?.channelsSeen.count ?? 0
                    }
                )
                cont.resume(returning: (written, channels))
            }
        }
    }

    /// Fetches each supplemental guide once and writes in only the ids it
    /// declares, so the provider's own guide is never disturbed. Returns how
    /// many programmes were added. A failure here never fails the refresh.
    private static func mergeSupplemental(sourceId: String, now: Int) async -> Int {
        var added = 0
        let entries = SupplementalEpgStore.shared.all()
        var byUrl: [String: [SupplementalEpgSource]] = [:]
        for entry in entries { byUrl[entry.guideUrl, default: []].append(entry) }

        for (urlString, group) in byUrl {
            if Task.isCancelled { return added }
            let ids = Set(group.flatMap { $0.channelIds }.map { EpgDatabase.normalizeId($0) })
            guard !urlString.isEmpty, !ids.isEmpty, let url = URL(string: urlString) else { continue }
            let host = AppLog.hostOnly(urlString)
            do {
                let xml = try await GuideFile.fetchXml(from: url)
                defer { try? FileManager.default.removeItem(at: xml) }
                let rows: [EpgProgramme] = await withCheckedContinuation { (cont: CheckedContinuation<[EpgProgramme], Never>) in
                    DispatchQueue.global(qos: .utility).async {
                        var out: [EpgProgramme] = []
                        _ = GuideFile.parse(file: xml, wanted: ids, now: now, maxRows: 50_000) { out.append($0) }
                        cont.resume(returning: out)
                    }
                }
                EpgDatabase.shared.upsert(sourceId: sourceId, rows: rows)
                if !rows.isEmpty { EpgDatabase.shared.setFetched(sourceId + "#supp", at: now) }
                added += rows.count
                AppLog.shared.log("EPG", "Supplemental \(host): \(rows.count) programmes")
            } catch {
                AppLog.shared.log("EPG", "Supplemental \(host) failed: \(error.localizedDescription)")
            }
        }
        return added
    }

    // MARK: - Queries

    /// A channel's upcoming schedule (airing now first).
    func schedule(for channel: Channel) async -> [EpgProgramme] {
        guard let id = source?.id else { return [] }
        let keys = EpgNowStore.shared.candidateKeys(for: channel)
        return await Task.detached(priority: .userInitiated) {
            let now = Int(Date().timeIntervalSince1970)
            for key in keys {
                let rows = EpgDatabase.shared.upcoming(id, channelId: key, now: now)
                if !rows.isEmpty { return rows }
            }
            return []
        }.value
    }

    /// Programme-title search across the guide, with the channels they air on.
    func searchProgrammes(_ query: String) async -> [ProgrammeHit] {
        guard let id = source?.id else { return [] }
        let byId = channelByGuideId
        return await Task.detached(priority: .userInitiated) {
            let now = Int(Date().timeIntervalSince1970)
            let rows = EpgDatabase.shared.search(id, query: query, now: now)
            return rows.map { ProgrammeHit(programme: $0, channel: byId[$0.channelId]) }
        }.value
    }

    /// How well the stored guide lines up with this source's channels.
    func coverage() async -> (matched: Int, live: Int, guideChannels: Int, programmes: Int) {
        guard let id = source?.id else { return (0, 0, 0, 0) }
        let keys = liveKeys
        return await Task.detached(priority: .utility) {
            let ids = EpgDatabase.shared.guideChannelIds(id)
            let matched = keys.filter { !$0.isEmpty && ids.contains($0) }.count
            return (matched, keys.count, ids.count, EpgDatabase.shared.countFor(id))
        }.value
    }

    var hasSource: Bool { source != nil }
}
