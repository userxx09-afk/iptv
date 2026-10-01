import Foundation
import TapperCore

/// Drives an Xtream Codes panel login (host/username/password, not a plain
/// M3U URL) through XtreamBridge.kt's callback-based wrapper over
/// XtreamClient's suspend functions - see that file's own doc comment for
/// why a callback bridge is used instead of calling a suspend function
/// directly from Swift.
///
/// Mirrors PlaylistLoader's shape (isLoading/errorMessage/reset) so
/// ContentView can switch between the two loaders without relearning a new
/// pattern, with one addition: `warnings`, since a partially-successful
/// load (login worked, but one catalogue endpoint failed) is a real,
/// expected outcome here in a way it never was for a single M3U fetch.
@MainActor
final class XtreamLoader: ObservableObject {
    @Published private(set) var account: XtreamAccount?
    @Published private(set) var liveChannels: [Channel] = []
    @Published private(set) var movies: [Channel] = []
    @Published private(set) var series: [Channel] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?
    @Published private(set) var warnings: [String] = []

    var isLoggedIn: Bool { account != nil }

    // Held after a successful login so loadEpisodes(for:) can re-authenticate
    // per call - XtreamClient is cheap to construct (it owns no connection
    // itself; the shared Ktor HttpClient underneath it is the long-lived
    // part - see XtreamClient's companion object), so there's no benefit to
    // threading a single instance through instead.
    private var host = ""
    private var username = ""
    private var password = ""

    func login(host rawHost: String, username rawUsername: String, password rawPassword: String) {
        let host = rawHost.trimmingCharacters(in: .whitespacesAndNewlines)
        let username = rawUsername.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty, !username.isEmpty, !rawPassword.isEmpty else {
            errorMessage = "Host, username, and password are all required."
            return
        }

        self.host = host
        self.username = username
        self.password = rawPassword
        errorMessage = nil
        warnings = []
        isLoading = true

        // Fires on a background thread, not the main thread - see
        // XtreamBridge's own doc comment. Every path back into @Published
        // state below hops via DispatchQueue.main.async accordingly.
        XtreamBridge.shared.loginAndLoad(
            host: host,
            username: username,
            password: rawPassword,
            sourceId: "xtream-default",
            onWarning: { [weak self] message in
                DispatchQueue.main.async {
                    self?.warnings.append(message)
                }
            },
            onResult: { [weak self] result, error in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.isLoading = false
                    if let error {
                        self.errorMessage = error
                        return
                    }
                    guard let result else { return }
                    self.account = result.account
                    self.liveChannels = result.live
                    self.movies = result.movies
                    self.series = result.series
                }
            }
        )
    }

    /// Episodes for one series, fetched only when that series is opened -
    /// see XtreamClient.episodes's own doc comment for why this isn't
    /// loaded up front for every series.
    func loadEpisodes(for seriesItem: Channel, completion: @escaping ([Channel]) -> Void) {
        guard let seriesId = seriesItem.seriesId else {
            completion([])
            return
        }
        XtreamBridge.shared.loadEpisodes(
            host: host,
            username: username,
            password: password,
            sourceId: "xtream-default",
            seriesId: seriesId,
            onResult: { episodes, _ in
                DispatchQueue.main.async {
                    completion(episodes ?? [])
                }
            }
        )
    }

    func reset() {
        account = nil
        liveChannels = []
        movies = []
        series = []
        errorMessage = nil
        warnings = []
    }
}
