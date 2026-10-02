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
@MainActor
final class TapperPlayer: ObservableObject {
    @Published private(set) var player: AVPlayer?
    @Published var diagnosisMessage: String?
    @Published private(set) var isBuffering = true

    private var attempt = 0
    private var startedAt = Date()
    private var renderedFrames = false

    // Separate from `attempt` above, which moves to a different *feed*.
    // This counts retries of the *same* feed after what looks like a
    // transient connection drop rather than a real failure - see
    // handleFailure's own comment for why this exists.
    private var transientRetries = 0
    private let maxTransientRetries = 2

    private var itemStatusObservation: NSKeyValueObservation?
    private var failureObserver: NSObjectProtocol?

    func play(_ channel: Channel) {
        attempt = 0
        transientRetries = 0
        diagnosisMessage = nil

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
        startStream(channel: channel, stream: first)
    }

    private func startStream(channel: Channel, stream: StreamRef) {
        renderedFrames = false
        startedAt = Date()
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
        item.preferredForwardBufferDuration = PlayerSettingsStore.shared.bufferSize.forwardBufferSeconds

        tearDownObservers()

        let avPlayer = player ?? AVPlayer()
        avPlayer.replaceCurrentItem(with: item)
        if player == nil { player = avPlayer }

        itemStatusObservation = item.observe(\.status, options: [.new]) { [weak self] observedItem, _ in
            guard let self else { return }
            Task { @MainActor in
                switch observedItem.status {
                case .readyToPlay:
                    self.isBuffering = false
                    self.renderedFrames = true
                    // A later stall in this same playback session gets its
                    // own fresh retry budget rather than inheriting however
                    // many of these were already spent getting here.
                    self.transientRetries = 0
                case .failed:
                    self.handleFailure(channel: channel, error: observedItem.error as NSError?)
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
                self?.handleFailure(channel: channel, error: error)
            }
        }

        avPlayer.play()
    }

    private func handleFailure(channel: Channel, error: NSError?) {
        // Try the next alternate feed before surfacing anything, same as
        // TapperPlayer.kt's onPlayerError - on a free playlist this
        // recovers a large share of failures invisibly.
        let next = channel.streams.count > attempt + 1 ? channel.streams[attempt + 1] : nil
        if let next, M3uParser.shared.isPlayable(url: next.url) {
            attempt += 1
            transientRetries = 0
            startStream(channel: channel, stream: next)
            return
        }
        let (httpStatus, socketError) = classify(error: error)

        // No HTTP status at all plus a recognized socket-error code (timed
        // out, connection lost, not connected, DNS failure - see classify
        // above) looks like a dropped connection, not a real answer from
        // the server - retrying a definitive 403/404 would just ask the
        // same question again, but a connection that was lost can often be
        // reopened immediately. Same reasoning as the short retry already
        // added to XtreamClient.fetch() for the API calls: AVFoundation
        // doesn't quietly retry a request the way ExoPlayer's pipeline
        // often does on Fire TV, so a brief network blip here otherwise
        // ends playback outright instead of recovering on its own.
        if httpStatus == nil, socketError, transientRetries < maxTransientRetries {
            transientRetries += 1
            let stream = channel.streams[attempt]
            let delay = DispatchTimeInterval.milliseconds(600 * transientRetries)
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                self?.startStream(channel: channel, stream: stream)
            }
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
        let elapsedMs = Int64(Date().timeIntervalSince(startedAt) * 1000)
        let message = PlaybackDiagnosisBridge.shared.diagnose(
            httpStatus: Int32(httpStatus ?? 0),
            hasHttpStatus: httpStatus != nil,
            socketError: socketError,
            elapsedMs: elapsedMs,
            renderedFrames: renderedFrames,
            networkReachable: true
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
        if let failureObserver {
            NotificationCenter.default.removeObserver(failureObserver)
        }
        failureObserver = nil
    }

    func release() {
        tearDownObservers()
        player?.pause()
        player = nil
    }
}
