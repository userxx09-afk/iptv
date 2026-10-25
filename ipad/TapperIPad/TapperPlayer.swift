import AVFoundation
@preconcurrency import TapperCore

/// AVPlayer wrapper mirroring Fire TV's TapperPlayer.kt: per-stream request
/// headers, silent failover across alternate feeds before bothering the
/// user, and the same shared failure-diagnosis logic (Diagnose.from, via
/// PlaybackDiagnosisBridge) rather than a generic "something went wrong".
///
/// Deliberately simpler than TapperPlayer.kt in two platform-driven ways,
/// not missed parity:
///  1. No hand-built transport controls - PlayerView uses AVKit's
///     VideoPlayer, which already gives native play/pause/scrub for
///     on-demand items and a live-edge indicator for live streams, for free.
///  2. AVFoundation doesn't reliably surface the HTTP status code of a
///     failed request the way ExoPlayer's HttpDataSource does, so
///     status-code-based diagnosis (403/404/5xx) is best-effort here:
///     populated when AVFoundation's error happens to carry one, absent
///     otherwise. Diagnose.from already treats httpStatus as optional for
///     exactly this reason, so this degrades to a slightly vaguer message
///     rather than a wrong one.
///
/// Built for use on the move: it watches the connection (NetworkMonitor), keeps
/// a bigger cushion and a quality cap while on mobile data, reconnects on its
/// own when a stream freezes or the iPad hands over between Wi-Fi and cellular,
/// and tells the viewer what it is doing instead of showing a bare spinner.
@MainActor
final class TapperPlayer: ObservableObject {
    @Published private(set) var player: AVPlayer?
    @Published var diagnosisMessage: String?
    @Published private(set) var isBuffering = true
    /// A short line for the viewer while playback is struggling or
    /// reconnecting ("Waiting for network...", "Reconnecting..."). Nil when
    /// everything is fine.
    @Published private(set) var statusNote: String?

    private var currentChannel: Channel?
    private var attempt = 0
    private var startedAt = Date()
    private var renderedFrames = false

    // Separate from `attempt` above, which moves to a different *feed*.
    // This counts retries of the *same* feed after what looks like a
    // transient connection drop rather than a real failure - see
    // handleFailure's own comment for why this exists.
    private var transientRetries = 0
    private let maxTransientRetries = 2

    /// Same-feed restarts after the picture froze (reset once playback has
    /// been healthy for a while).
    private var stallRestarts = 0
    private let maxStallRestarts = 3
    /// Retries after a failure that came right after the connection changed.
    private var networkRetries = 0
    private let maxNetworkRetries = 4
    private var lastNetworkChange = Date.distantPast

    // Stall watching.
    private var lastMove = Date()
    private var lastPosition = -1.0
    private var movingSince: Date?
    private var monitorTask: Task<Void, Never>?
    private var reconnectWork: Task<Void, Never>?
    private var awaitingReconnect = false

    /// Where to jump back to once a restarted film / episode is ready.
    private var pendingResume: Double?
    /// The stream failed while the iPad had no connection; start it again
    /// as soon as one returns.
    private var restartWhenOnline = false
    /// The message on screen is about the connection, so a returning
    /// connection should clear it and try again.
    private var failedForNetwork = false
    private weak var failedItem: AVPlayerItem?

    // Commercial-break mute: a fixed-length, viewer-triggered mute. Sound
    // returns by itself when the time is up, or sooner on a second tap, and a
    // channel change always ends it. Kept here (not in the view) because every
    // reconnect builds a new item and must come back up still muted.
    /// When the current mute ends; nil when sound is on.
    @Published private(set) var muteEndsAt: Date?
    private var muteTask: Task<Void, Never>?

    // Ad-marker diagnostic: what the stream says about ad breaks, if anything.
    // Counted and logged, never acted on - it is there so we can tell whether
    // a provider sends markers at all.
    @Published private(set) var markerSummary = ""
    private var markerCount = 0
    private var markerCollector: AVPlayerItemMetadataCollector?
    private var markerListener: DateRangeListener?

    private var itemStatusObservation: NSKeyValueObservation?
    private var timeControlObservation: NSKeyValueObservation?
    private var failureObserver: NSObjectProtocol?
    private var networkToken: UUID?

    /// Mutes for `minutes`, or - when already muted - brings the sound back.
    /// Returns true if it is now muted.
    @discardableResult
    func toggleMute(minutes: Int) -> Bool {
        if muteEndsAt != nil { endMute(); return false }
        let seconds = Double(max(minutes, 1) * 60)
        muteEndsAt = Date().addingTimeInterval(seconds)
        player?.isMuted = true
        muteTask?.cancel()
        muteTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            if Task.isCancelled { return }
            self?.endMute()
        }
        return true
    }

    func endMute() {
        muteTask?.cancel()
        muteTask = nil
        muteEndsAt = nil
        player?.isMuted = false
    }

    private func noteDateRanges(_ labels: [String]) {
        guard !labels.isEmpty else { return }
        let first = markerCount == 0
        markerCount += labels.count
        markerSummary = "Ad markers seen: HLS date ranges x\(markerCount)"
        if first {
            AppLog.shared.log("ADS", "Stream carries ad/date-range markers: \(labels.joined(separator: ", "))")
        }
    }

    func play(_ channel: Channel) {
        endMute()
        markerCount = 0
        markerSummary = ""
        attempt = 0
        transientRetries = 0
        stallRestarts = 0
        networkRetries = 0
        lastNetworkChange = .distantPast
        pendingResume = nil
        restartWhenOnline = false
        failedForNetwork = false
        failedItem = nil
        awaitingReconnect = false
        reconnectWork?.cancel()
        reconnectWork = nil
        currentChannel = nil
        diagnosisMessage = nil
        statusNote = nil

        guard let first = channel.streams.first else {
            diagnosisMessage = "This item has nothing to play."
            return
        }
        // Fail fast and honestly rather than loading something AVPlayer will
        // never open - mirrors TapperPlayer.kt's isPlayable pre-check; two
        // channels in the default playlist are mmsh://, same as there.
        guard M3uParser.shared.isPlayable(url: first.url) else {
            let scheme = first.url.components(separatedBy: "://").first ?? first.url
            diagnosisMessage = "This channel uses a format this app can't play (\(scheme))."
            return
        }
        currentChannel = channel
        configureAudioSession()
        startWatchingNetwork()
        startMonitor()
        startStream(channel: channel, stream: first, resumeAt: nil)
    }

    private func startStream(channel: Channel, stream: StreamRef, resumeAt: Double?) {
        renderedFrames = false
        startedAt = Date()
        lastMove = Date()
        lastPosition = -1
        movingSince = nil
        awaitingReconnect = false
        failedItem = nil
        failedForNetwork = false
        diagnosisMessage = nil
        pendingResume = resumeAt
        isBuffering = true

        guard let url = URL(string: stream.url) else {
            explain(httpStatus: nil, socketError: true, rawError: nil)
            return
        }

        var options: [String: Any] = [:]
        // StreamRef.headers (Kotlin Map<String, String>) bridges straight to
        // a Swift [String: String] - confirmed by a real compile (an earlier
        // defensive `as? String` cast on each key/value here came back as
        // "always succeeds", i.e. the compiler already sees String on both
        // sides), so no generic Any-keyed iteration is needed.
        //
        // AVURLAssetHTTPHeaderFieldsKey itself isn't visible as a Swift
        // symbol on this SDK (Xcode 26 marks several legacy AVFoundation
        // NSString key constants Swift-unavailable), so the key is spelled
        // as the literal string Apple's docs give for it - the underlying
        // options-dictionary key AVPlayer reads, unchanged by that
        // Swift-visibility change.
        if !stream.headers.isEmpty {
            options["AVURLAssetHTTPHeaderFieldsKey"] = stream.headers
        }

        let asset = AVURLAsset(url: url, options: options)
        let item = AVPlayerItem(asset: asset)
        // Configurable from the toolbar's Buffering menu (ContentView.swift
        // + PlayerSettings.swift) - how many seconds of media AVPlayer tries
        // to keep buffered ahead of the playhead. 0 (Small) leaves this to
        // AVPlayer's own default; anything larger trades a slower start for
        // more cushion against the network drops/hiccups that show up as
        // stalls or rebuffering mid-stream.
        //
        // On mobile data, a hotspot or Low Data Mode the signal comes and
        // goes, so never go below 20 seconds of cushion there, and hold the
        // picture quality down (applyQuality) so each segment is smaller and
        // finishes downloading before the signal changes.
        let net = NetworkMonitor.shared.state
        var buffer = PlayerSettingsStore.shared.bufferSize.forwardBufferSeconds
        if isMetered(net) { buffer = max(buffer, 20) }
        item.preferredForwardBufferDuration = buffer
        applyQuality(to: item, state: net)

        tearDownObservers()

        // HLS date ranges (#EXT-X-DATERANGE) are where a provider's ad-break
        // cues turn up, when it keeps them at all.
        let listener = DateRangeListener { [weak self] labels in
            Task { @MainActor in self?.noteDateRanges(labels) }
        }
        let collector = AVPlayerItemMetadataCollector(identifiers: nil, classifyingLabels: nil)
        collector.setDelegate(listener, queue: DispatchQueue.main)
        item.add(collector)
        markerListener = listener
        markerCollector = collector

        let avPlayer = player ?? AVPlayer()
        // A mute in progress carries over a reconnect.
        avPlayer.isMuted = muteEndsAt != nil
        avPlayer.replaceCurrentItem(with: item)
        if player == nil { player = avPlayer }

        timeControlObservation = avPlayer.observe(\.timeControlStatus, options: [.new]) { [weak self] observedPlayer, _ in
            guard let self else { return }
            let status = observedPlayer.timeControlStatus
            Task { @MainActor in
                self.timeControlChanged(status)
            }
        }

        itemStatusObservation = item.observe(\.status, options: [.new]) { [weak self] observedItem, _ in
            guard let self else { return }
            Task { @MainActor in
                switch observedItem.status {
                case .readyToPlay:
                    self.isBuffering = false
                    self.renderedFrames = true
                    self.logAudio(for: observedItem)
                    // A later stall in this same playback session gets its
                    // own fresh retry budget rather than inheriting however
                    // many of these were already spent getting here.
                    self.transientRetries = 0
                    // A film or episode restarted after a drop goes back to
                    // where it was rather than to the beginning.
                    if let target = self.pendingResume {
                        self.pendingResume = nil
                        observedItem.seek(
                            to: CMTime(seconds: target, preferredTimescale: 600),
                            toleranceBefore: .positiveInfinity,
                            toleranceAfter: .positiveInfinity,
                            completionHandler: nil
                        )
                    }
                case .failed:
                    self.handleFailure(channel: channel, item: observedItem, error: observedItem.error as NSError?)
                default:
                    break
                }
            }
        }

        failureObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] notification in
            let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
            Task { @MainActor in
                self?.handleFailure(channel: channel, item: item, error: error)
            }
        }

        avPlayer.play()
    }

    // MARK: - Audio

    /// Plays as a video app: sound comes out whatever the silent / mute
    /// setting says, and the session is reclaimed after a call or another
    /// app's audio ends. Without a category set, iOS uses its default, which
    /// follows the mute switch and can leave a stream silent.
    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .moviePlayback)
            try session.setActive(true)
        } catch {
            AppLog.shared.log("AUDIO", "Couldn't set up audio: \(error.localizedDescription)")
        }
    }

    /// One line per stream start in Settings > Logs, so a silent stream can be
    /// told apart: no audio track in the stream, muted, volume down, or sound
    /// going somewhere else.
    private func logAudio(for item: AVPlayerItem) {
        let session = AVAudioSession.sharedInstance()
        let audioTracks = item.tracks.filter { $0.assetTrack?.mediaType == .audio }.count
        let outputs = session.currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: ",")
        let muted = player?.isMuted ?? false
        let volume = player?.volume ?? 0
        AppLog.shared.log(
            "AUDIO",
            "ready: audio tracks \(audioTracks), player muted \(muted), player volume \(volume), device volume \(session.outputVolume), category \(session.category.rawValue), output \(outputs)"
        )
    }

    // MARK: - Connection awareness

    private func isMetered(_ state: NetworkMonitor.State) -> Bool {
        state.expensive || state.constrained
    }

    /// Caps (or uncaps) the picture quality for the current connection. Safe
    /// to call on an item that is already playing: AVPlayer simply picks a
    /// different variant from the next segment on.
    private func applyQuality(to item: AVPlayerItem, state: NetworkMonitor.State) {
        guard isMetered(state) else {
            item.preferredPeakBitRate = 0
            item.preferredMaximumResolution = .zero
            return
        }
        var quality = PlayerSettingsStore.shared.mobileQuality
        // Low Data Mode is an explicit request to use less data.
        if state.constrained && quality == .auto { quality = .saver }
        item.preferredPeakBitRate = quality.peakBitRate
        item.preferredMaximumResolution = quality.maxResolution ?? .zero
    }

    private func startWatchingNetwork() {
        guard networkToken == nil else { return }
        networkToken = NetworkMonitor.shared.observe { [weak self] old, new in
            guard let self else { return }
            Task { @MainActor in
                self.networkChanged(from: old, to: new)
            }
        }
    }

    private func networkChanged(from old: NetworkMonitor.State, to new: NetworkMonitor.State) {
        guard currentChannel != nil, player != nil else { return }
        AppLog.shared.log("NET", "\(old.interface) -> \(new.interface), online \(new.online)")

        if let item = player?.currentItem { applyQuality(to: item, state: new) }

        if !new.online {
            // Whatever is already buffered keeps playing; the note shows
            // once it runs out (monitorTick).
            return
        }

        let cameBack = !old.online
        let switched = old.online && old.interface != new.interface
        guard cameBack || switched else { return }

        lastNetworkChange = Date()
        stallRestarts = 0
        networkRetries = 0

        // The message on screen is something other than a lost connection:
        // a new connection won't change it.
        if diagnosisMessage != nil && !failedForNetwork { return }

        // Still playing from what was buffered: leave it be. If the old
        // connection is dead it will run dry in a few seconds and
        // monitorTick restarts it quickly (it is on a short fuse for the
        // next minute). Restarting a healthy picture would only add a blip.
        let healthy = player?.timeControlStatus == .playing
            && diagnosisMessage == nil && !restartWhenOnline && !awaitingReconnect
        if healthy { return }

        restartWhenOnline = false
        statusNote = cameBack ? "Back online - reconnecting..." : "Network changed - reconnecting..."
        isBuffering = true
        scheduleReconnect(after: 1.0)
    }

    /// Starts the current feed again after [seconds], keeping a film's place.
    private func scheduleReconnect(after seconds: Double) {
        reconnectWork?.cancel()
        awaitingReconnect = true
        reconnectWork = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            self.restartCurrent()
        }
    }

    private func restartCurrent() {
        guard let channel = currentChannel, channel.streams.indices.contains(attempt) else {
            awaitingReconnect = false
            return
        }
        startStream(channel: channel, stream: channel.streams[attempt], resumeAt: capturePosition())
    }

    /// For a film or episode (a finite duration), where playback has got to;
    /// nil for live TV, which should come back at the live edge.
    private func capturePosition() -> Double? {
        if let item = player?.currentItem {
            let duration = item.duration.seconds
            let position = item.currentTime().seconds
            if duration.isFinite, duration > 0, position.isFinite, position > 5 {
                return position
            }
        }
        return pendingResume
    }

    // MARK: - Stall watching

    private func timeControlChanged(_ status: AVPlayer.TimeControlStatus) {
        switch status {
        case .playing:
            if isBuffering { isBuffering = false }
        case .waitingToPlayAtSpecifiedRate:
            // Before the first frame the spinner is already up; this is the
            // mid-stream "ran out of buffer" case.
            if renderedFrames { isBuffering = true }
        default:
            break
        }
    }

    private func startMonitor() {
        guard monitorTask == nil else { return }
        monitorTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !Task.isCancelled, let self else { return }
                self.monitorTick()
            }
        }
    }

    private func monitorTick() {
        guard let player, let channel = currentChannel,
              diagnosisMessage == nil, !awaitingReconnect else {
            lastMove = Date()
            return
        }
        let now = Date()
        let status = player.timeControlStatus
        let position = player.currentTime().seconds

        // Paused by the viewer (films) - nothing to watch for.
        if status == .paused {
            lastMove = now
            lastPosition = position
            movingSince = nil
            return
        }

        let moving = status == .playing && position.isFinite && position != lastPosition
        if position.isFinite { lastPosition = position }

        if moving {
            lastMove = now
            if movingSince == nil { movingSince = now }
            // Healthy for a while: give the next hiccup a fresh budget.
            if let since = movingSince, now.timeIntervalSince(since) > 30 {
                stallRestarts = 0
                networkRetries = 0
            }
            if statusNote != nil { statusNote = nil }
            return
        }
        movingSince = nil

        let net = NetworkMonitor.shared.state
        if !net.online {
            // Nothing to fix until the connection returns, and time spent
            // without one must not use up the restarts.
            lastMove = now
            if statusNote != "Waiting for network..." { statusNote = "Waiting for network..." }
            if !isBuffering { isBuffering = true }
            return
        }

        let stalledFor = now.timeIntervalSince(lastMove)
        if stalledFor >= 4 && statusNote == nil {
            statusNote = isMetered(net) ? "Weak connection - waiting..." : "Slow connection - waiting..."
        }
        if stalledFor >= stallLimit(net: net, vod: isFiniteItem(player)) {
            recover(channel: channel)
        }
    }

    private func isFiniteItem(_ player: AVPlayer) -> Bool {
        let duration = player.currentItem?.duration.seconds ?? 0
        return duration.isFinite && duration > 0
    }

    private func stallLimit(net: NetworkMonitor.State, vod: Bool) -> TimeInterval {
        var limit: TimeInterval = renderedFrames ? 10 : 20
        if isMetered(net) { limit *= 1.5 }
        if vod { limit *= 2 }
        // Just after the connection changed, a stream that was playing is
        // most likely stuck on the old one - don't wait the full time.
        if renderedFrames, Date().timeIntervalSince(lastNetworkChange) < 60 {
            limit = min(limit, 8)
        }
        return limit
    }

    /// The picture froze and didn't come back: reconnect the same feed a few
    /// times (a frozen connection usually just needs reopening), then move
    /// on to an alternate feed, then explain.
    private func recover(channel: Channel) {
        if stallRestarts < maxStallRestarts, channel.streams.indices.contains(attempt) {
            stallRestarts += 1
            statusNote = "Reconnecting..."
            isBuffering = true
            AppLog.shared.log("PLAYER", "stalled, reconnecting (\(stallRestarts)/\(maxStallRestarts))")
            restartCurrent()
            return
        }
        let next = channel.streams.count > attempt + 1 ? channel.streams[attempt + 1] : nil
        if let next, M3uParser.shared.isPlayable(url: next.url) {
            attempt += 1
            stallRestarts = 0
            transientRetries = 0
            statusNote = "Trying another feed..."
            AppLog.shared.log("PLAYER", "stalled, trying alternate feed")
            startStream(channel: channel, stream: next, resumeAt: capturePosition())
            return
        }
        explain(httpStatus: nil, socketError: true, rawError: nil)
    }

    // MARK: - Failures

    private func handleFailure(channel: Channel, item: AVPlayerItem, error: NSError?) {
        // An item that was already replaced, or a failure already being
        // handled (AVFoundation can report the same one twice).
        guard item === player?.currentItem, failedItem !== item else { return }
        failedItem = item

        let (httpStatus, socketError) = classify(error: error)

        // No connection at all: nothing to try until one returns. The network
        // monitor restarts playback the moment it does.
        if !NetworkMonitor.shared.state.online {
            restartWhenOnline = true
            isBuffering = true
            statusNote = "Waiting for network..."
            return
        }

        // A failure shortly after the connection changed is nearly always the
        // old connection dying, or the provider not yet having noticed it
        // closed (many limit how many connections one account may hold, and
        // answer 403 / 429 / 509 until the old one times out). Retry the same
        // feed with a growing pause rather than giving up or burning through
        // the alternate feeds.
        let providerLimit: Set<Int> = [403, 429, 458, 503, 509]
        let looksLikeHandover = socketError || (httpStatus.map { providerLimit.contains($0) } ?? false)
        if looksLikeHandover, networkRetries < maxNetworkRetries,
           Date().timeIntervalSince(lastNetworkChange) < 90 {
            networkRetries += 1
            statusNote = "Reconnecting..."
            isBuffering = true
            scheduleReconnect(after: 2.0 * Double(networkRetries))
            return
        }

        // Try the next alternate feed before surfacing anything, same as
        // TapperPlayer.kt's onPlayerError - on a free playlist this
        // recovers a large share of failures invisibly.
        let next = channel.streams.count > attempt + 1 ? channel.streams[attempt + 1] : nil
        if let next, M3uParser.shared.isPlayable(url: next.url) {
            attempt += 1
            transientRetries = 0
            startStream(channel: channel, stream: next, resumeAt: capturePosition())
            return
        }

        // No HTTP status at all plus a recognized socket-error code (timed
        // out, connection lost, not connected, DNS failure - see classify
        // below) looks like a dropped connection, not a real answer from
        // the server - retrying a definitive 403/404 would just ask the
        // same question again, but a connection that was lost can often be
        // reopened immediately. Same reasoning as the short retry already
        // added to XtreamClient.fetch() for the API calls: AVFoundation
        // doesn't quietly retry a request the way ExoPlayer's pipeline
        // often does on Fire TV, so a brief network blip here otherwise
        // ends playback outright instead of recovering on its own.
        if httpStatus == nil, socketError, transientRetries < maxTransientRetries {
            transientRetries += 1
            scheduleReconnect(after: 0.6 * Double(transientRetries))
            return
        }

        explain(httpStatus: httpStatus, socketError: socketError, rawError: error)
    }

    // AVErrorHTTPStatusCodeKey, like AVURLAssetHTTPHeaderFieldsKey above,
    // isn't visible as a Swift symbol on this SDK - spelled as the literal
    // string instead, which is the actual userInfo key AVFoundation writes.
    private func classify(error: NSError?) -> (httpStatus: Int?, socketError: Bool) {
        guard let error else { return (nil, true) }
        if let status = error.userInfo["AVErrorHTTPStatusCodeKey"] as? Int {
            return (status, false)
        }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError,
           let status = underlying.userInfo["AVErrorHTTPStatusCodeKey"] as? Int {
            return (status, false)
        }
        let socketCodes: Set<Int> = [
            NSURLErrorTimedOut, NSURLErrorCannotConnectToHost,
            NSURLErrorNetworkConnectionLost, NSURLErrorNotConnectedToInternet,
            NSURLErrorDNSLookupFailed,
        ]
        return (nil, socketCodes.contains(error.code))
    }

    // rawError is purely diagnostic: appended to the user-facing message
    // (not used by Diagnose.from itself, which only sees the classified
    // httpStatus/socketError) so that if a failure doesn't fit any of the
    // existing diagnosis tiers, the exact NSError domain/code comes back in
    // the next bug report instead of another guess-and-rebuild cycle. Cheap
    // to pull out once the real failure mode here is confirmed and handled.
    private func explain(httpStatus: Int?, socketError: Bool, rawError: NSError?) {
        isBuffering = false
        statusNote = nil
        awaitingReconnect = false
        failedForNetwork = socketError
        let elapsedMs = Int64(Date().timeIntervalSince(startedAt) * 1000)
        let message = PlaybackDiagnosisBridge.shared.diagnose(
            httpStatus: Int32(httpStatus ?? 0),
            hasHttpStatus: httpStatus != nil,
            socketError: socketError,
            elapsedMs: elapsedMs,
            renderedFrames: renderedFrames,
            networkReachable: NetworkMonitor.shared.state.online
        )
        if let rawError {
            diagnosisMessage = "\(message)\n\n(\(rawError.domain) \(rawError.code))"
        } else {
            diagnosisMessage = message
        }
    }

    private func tearDownObservers() {
        itemStatusObservation?.invalidate()
        itemStatusObservation = nil
        timeControlObservation?.invalidate()
        timeControlObservation = nil
        if let failureObserver {
            NotificationCenter.default.removeObserver(failureObserver)
        }
        failureObserver = nil
    }

    func release() {
        muteTask?.cancel()
        muteTask = nil
        monitorTask?.cancel()
        monitorTask = nil
        reconnectWork?.cancel()
        reconnectWork = nil
        if let networkToken {
            NetworkMonitor.shared.remove(networkToken)
        }
        networkToken = nil
        currentChannel = nil
        tearDownObservers()
        player?.pause()
        player = nil
    }
}

/// Receives HLS date-range metadata - the `#EXT-X-DATERANGE` tags an ad break
/// is announced with - and reports a short label for each new one. Separate
/// from TapperPlayer because AVFoundation delivers it through an Objective-C
/// delegate, which needs an NSObject.
private final class DateRangeListener: NSObject, AVPlayerItemMetadataCollectorPushDelegate {
    private let onNew: ([String]) -> Void

    init(onNew: @escaping ([String]) -> Void) {
        self.onNew = onNew
    }

    func metadataCollector(
        _ metadataCollector: AVPlayerItemMetadataCollector,
        didCollect metadataGroups: [AVDateRangeMetadataGroup],
        indexesOfNewGroups: IndexSet,
        indexesOfModifiedGroups: IndexSet
    ) {
        var labels: [String] = []
        for index in indexesOfNewGroups where metadataGroups.indices.contains(index) {
            let group = metadataGroups[index]
            let keys = group.items.compactMap { $0.key as? String }
            let scte = keys.contains { $0.uppercased().contains("SCTE") }
            labels.append(scte ? "SCTE-35 date range" : (group.classifyingLabel ?? "date range"))
        }
        if !labels.isEmpty { onNew(labels) }
    }
}
