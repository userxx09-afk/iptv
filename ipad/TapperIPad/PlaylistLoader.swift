import Foundation
import TapperCore

/// Fetches an M3U playlist and parses it into real Channels. The network
/// fetch itself stays plain Swift/URLSession rather than routing through
/// shared/'s Ktor client - the parsing logic (M3uParser) is what's worth
/// sharing with Fire TV, not the fetch, and calling a synchronous shared
/// function (parse) from Swift is a well-established, low-risk part of the
/// KMP/Swift boundary. Calling a *suspend* shared function from Swift
/// (which XtreamClient/TmdbClient are) is a much less proven part of that
/// boundary and is being deliberately kept out of this first real screen.
@MainActor
final class PlaylistLoader: ObservableObject {
    @Published private(set) var channels: [Channel] = []
    /// Guide (XMLTV) URLs the playlist itself names.
    private(set) var declaredEpgUrls: [String] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    /// See XtreamLoader.generation - drops a stale result after a source switch.
    private var generation = 0

    enum LoadError: LocalizedError {
        case invalidUrl
        case badResponse(Int)
        case notText

        var errorDescription: String? {
            switch self {
            case .invalidUrl:
                return "That doesn't look like a valid URL."
            case .badResponse(let code):
                return "Server returned HTTP \(code)."
            case .notText:
                return "That response wasn't readable text."
            }
        }
    }

    func load(urlString: String) {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), url.scheme != nil else {
            errorMessage = LoadError.invalidUrl.errorDescription
            return
        }
        errorMessage = nil
        isLoading = true
        generation += 1
        let myGeneration = generation
        let startedAt = Date()
        let log = AppLog.shared
        log.log("LOAD", "M3U fetch starting (host \(AppLog.hostOnly(trimmed)))")
        let stopTicker = log.startMemoryTicker("m3u load")
        // .detached, not a plain Task {} - a plain Task inherits this
        // class's @MainActor isolation, which would run parse() itself on
        // the main thread. M3uParser's own doc comment references a real
        // 13,510-entry playlist; parsing that many lines synchronously on
        // the main thread would freeze the UI for the duration. Detaching
        // moves the fetch+parse to a background thread; only the final
        // @Published writes hop back via MainActor.run.
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let (data, response) = try await URLSession.shared.data(from: url)
                if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                    throw LoadError.badResponse(http.statusCode)
                }
                guard let text = String(data: data, encoding: .utf8) else {
                    throw LoadError.notText
                }
                // Same parser Fire TV uses on the exact same playlist text -
                // this is the whole point of sharing it rather than porting
                // a second implementation.
                let result = M3uParser.shared.parse(content: text, sourceId: "default")
                stopTicker()
                let secs = String(format: "%.1f", Date().timeIntervalSince(startedAt))
                log.log("LOAD", "M3U parsed after \(secs)s: \(result.channels.count) channels, \(data.count / 1024)KB - \(AppLog.memoryDescription())")
                await MainActor.run {
                    guard let self, self.generation == myGeneration else { return }
                    self.channels = result.channels
                    self.declaredEpgUrls = result.declaredEpgUrls
                    self.isLoading = false
                }
            } catch {
                stopTicker()
                let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                log.log("LOAD", "M3U load FAILED: \(message)")
                await MainActor.run {
                    guard let self, self.generation == myGeneration else { return }
                    self.errorMessage = message
                    self.isLoading = false
                }
            }
        }
    }

    func reset() {
        generation += 1
        isLoading = false
        channels = []
        declaredEpgUrls = []
        errorMessage = nil
    }
}
