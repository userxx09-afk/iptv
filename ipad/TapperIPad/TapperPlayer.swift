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

    private var itemStatusObservation: NSKeyValueObservation?
    private var failureObserver: NSObjectProtocol?

    func play(_ channel: Channel) {
        attempt = 0
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
            explain(httpStatus: nil, socketError: true)
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
            startStream(channel: channel, stream: next)
            return
        }
        let (httpStatus, socketError) = classify(error: error)
        explain(httpStatus: httpStatus, socketError: socketError)
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

    private func explain(httpStatus: Int?, socketError: Bool) {
        isBuffering = false
        let elapsedMs = Int64(Date().timeIntervalSince(startedAt) * 1000)
        diagnosisMessage = PlaybackDiagnosisBridge.shared.diagnose(
            httpStatus: Int32(httpStatus ?? 0),
            hasHttpStatus: httpStatus != nil,
            socketError: socketError,
            elapsedMs: elapsedMs,
            renderedFrames: renderedFrames,
            networkReachable: true
        )
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
