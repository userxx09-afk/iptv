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
    /// Bumped by every login() and reset(); a result that arrives for an
    /// older generation (the user switched source mid-load) is dropped
    /// instead of overwriting the newer source's catalogue.
    private var generation = 0

    /// Signs in and loads the library. Uses the copy saved on this iPad when
    /// there is a recent one (see CatalogueCache), so opening the app doesn't
    /// download and parse everything again; [forceRefresh] skips that.
    func login(host rawHost: String, username rawUsername: String, password rawPassword: String,
               forceRefresh: Bool = false) {
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
        generation += 1
        let myGeneration = generation

        if forceRefresh {
            fetchFromServer(host: host, username: username, password: rawPassword,
                            generation: myGeneration, fallback: nil)
            return
        }

        // Reading the saved copy is a disk read and a lot of small parses,
        // so it happens off the main thread.
        Task.detached(priority: .userInitiated) { [weak self] in
            let started = Date()
            let saved = CatalogueCache.load(host: host, username: username)
            await MainActor.run {
                guard let self, self.generation == myGeneration else { return }
                if let saved, saved.isFresh {
                    let secs = String(format: "%.1f", Date().timeIntervalSince(started))
                    AppLog.shared.log("LOAD", "Xtream library read from this iPad in \(secs)s: live \(saved.live.count), movies \(saved.movies.count), shows \(saved.series.count) - \(AppLog.memoryDescription())")
                    self.isLoading = false
                    self.show(saved)
                    self.refreshAccount(host: host, username: username, password: rawPassword, generation: myGeneration)
                } else {
                    self.fetchFromServer(host: host, username: username, password: rawPassword,
                                         generation: myGeneration, fallback: saved)
                }
            }
        }
    }

    private func show(_ saved: CachedCatalogue) {
        account = saved.account
        liveChannels = saved.live
        movies = saved.movies
        series = saved.series
    }

    /// With the library already on screen from the saved copy, checks the
    /// login in the background and brings the account details up to date.
    /// A failure here is only logged: the saved library is still good, and
    /// playback will say so if the account really has lapsed.
    private func refreshAccount(host: String, username: String, password: String, generation myGeneration: Int) {
        XtreamBridge.shared.authenticate(
            host: host, username: username, password: password,
            onResult: { [weak self] account, error in
                if let error {
                    AppLog.shared.log("LOAD", "Account check failed (saved library kept): \(error)")
                    // A refused login is worth saying, since playback won't
                    // work; a network error is not.
                    if error.lowercased().contains("rejected") {
                        DispatchQueue.main.async {
                            guard let self, self.generation == myGeneration else { return }
                            self.warnings.append("The server rejected the saved login. Check the username and password in Settings.")
                        }
                    }
                    return
                }
                guard let account else { return }
                DispatchQueue.main.async {
                    guard let self, self.generation == myGeneration else { return }
                    self.account = account
                }
            }
        )
    }

    /// Downloads the whole library. [fallback] is an out-of-date saved copy,
    /// used if the server can't be reached.
    private func fetchFromServer(host: String, username: String, password: String,
                                 generation myGeneration: Int, fallback: CachedCatalogue?) {
        let startedAt = Date()
        let log = AppLog.shared
        log.log("LOAD", "Xtream login + catalogue fetch starting (host \(host))")
        let stopTicker = log.startMemoryTicker("xtream load")

        // Fires on a background thread, not the main thread - see
        // XtreamBridge's own doc comment. Every path back into @Published
        // state below hops via DispatchQueue.main.async accordingly.
        XtreamBridge.shared.loginAndLoad(
            host: host,
            username: username,
            password: password,
            sourceId: "xtream-default",
            onWarning: { [weak self] message in
                AppLog.shared.log("WARN", message)
                DispatchQueue.main.async {
                    guard let self, self.generation == myGeneration else { return }
                    self.warnings.append(message)
                }
            },
            onResult: { [weak self] result, error in
                stopTicker()
                let secs = String(format: "%.1f", Date().timeIntervalSince(startedAt))
                if let error {
                    log.log("LOAD", "Xtream load FAILED after \(secs)s: \(error)")
                } else if let result {
                    log.log("LOAD", "Xtream data received after \(secs)s: live \(result.live.count), movies \(result.movies.count), shows \(result.series.count) - \(AppLog.memoryDescription())")
                }
                DispatchQueue.main.async {
                    guard let self, self.generation == myGeneration else { return }
                    self.isLoading = false
                    if let error {
                        // The server can't be reached (or refused): an older
                        // saved copy is better than an empty screen.
                        if let fallback {
                            self.show(fallback)
                            self.warnings.append("Couldn't reach the server, so this is the library saved on this iPad.")
                            log.log("LOAD", "Showing the saved library instead")
                        } else {
                            self.errorMessage = error
                        }
                        return
                    }
                    guard let result else { return }
                    self.account = result.account
                    self.liveChannels = result.live
                    self.movies = result.movies
                    self.series = result.series
                    log.log("LOAD", "Xtream catalogue handed to the UI - \(AppLog.memoryDescription())")

                    // Keep a copy for next time - but only a complete one: a
                    // load where movies or shows failed would otherwise be
                    // remembered as if that were everything.
                    if self.warnings.isEmpty, !result.live.isEmpty {
                        let account = result.account
                        let live = result.live, movies = result.movies, series = result.series
                        Task.detached(priority: .utility) {
                            CatalogueCache.save(host: host, username: username, account: account,
                                                live: live, movies: movies, series: series)
                            AppLog.shared.log("LOAD", "Library saved on this iPad")
                        }
                    }
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
        generation += 1
        isLoading = false
        account = nil
        liveChannels = []
        movies = []
        series = []
        errorMessage = nil
        warnings = []
    }
}
