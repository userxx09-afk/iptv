import Foundation

/// What TMDb knows about a movie or show: the poster, synopsis and rating
/// shown on the detail screens. Same fields as Fire TV's MovieMetadataStore.
struct MovieMetadata: Codable, Equatable {
    let title: String
    let year: Int?
    let overview: String?
    /// A full, ready-to-load image URL.
    let posterUrl: String?
    /// 0-10; nil when TMDb has no votes for the title yet.
    let rating: Double?
}

/// The three honest outcomes of asking TMDb about a title, plus "no key" - a
/// failure (bad key, no network) must never read as "TMDb has nothing on
/// this", or a wrong key is indistinguishable from a real miss.
enum TitleLookup {
    case noKey
    case notFound
    case found(MovieMetadata)
    case failed(String)
}

private struct TmdbError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// TMDb title lookup with a local cache - the iPad counterpart of Fire TV's
/// MovieMetadataStore (and the same lookup rules as the shared TmdbClient:
/// search by cleaned title plus any year found in it, retry once without the
/// year, accept either a v3 API key or a v4 Read Access Token).
///
/// The cache keeps confirmed misses too, so a title TMDb doesn't carry is not
/// asked about again every time it is opened. Failures and a missing key are
/// not cached, so a bad key or a dropped connection is simply retried.
final class MovieMetadataStore: ObservableObject {
    static let shared = MovieMetadataStore()

    /// The app's own personal TMDb key - the same out-of-the-box default the
    /// Fire TV app ships with. Settings can replace or clear it.
    static let builtInKey = "eac8b6cb4fb0acb2293728b5a71399a8"

    private let keyName = "tapper.tmdb.key"
    private let defaults = UserDefaults.standard
    private let cache = TmdbCache()

    /// Answers already known this run, readable instantly from the UI -
    /// coming back to a title shows its poster at once instead of starting
    /// from a blank and a spinner. Found / not-found only (never failures).
    private let memoryLock = NSLock()
    private var memory: [String: TitleLookup] = [:]

    /// Bumped when the key changes.
    @Published private(set) var revision = 0

    private init() {}

    /// Absent -> the built-in key; present but empty -> deliberately cleared.
    var apiKey: String {
        get {
            if defaults.object(forKey: keyName) == nil { return Self.builtInKey }
            return defaults.string(forKey: keyName) ?? ""
        }
        set {
            defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: keyName)
            revision += 1
        }
    }

    func resetToBuiltInKey() {
        defaults.removeObject(forKey: keyName)
        revision += 1
    }

    func clearCache() async {
        memoryLock.lock()
        memory = [:]
        memoryLock.unlock()
        await cache.clear()
    }

    private func cacheKey(_ name: String, isShow: Bool) -> String? {
        let (title, year) = Self.parseTitle(name)
        if title.isEmpty { return nil }
        return "\(isShow ? "S" : "M")|\(title.lowercased())|\(year.map(String.init) ?? "")"
    }

    /// The answer for a title if it is already known this run, else nil.
    func knownLookup(name: String, isShow: Bool) -> TitleLookup? {
        guard let key = cacheKey(name, isShow: isShow) else { return nil }
        memoryLock.lock()
        defer { memoryLock.unlock() }
        return memory[key]
    }

    private func remember(_ key: String, _ value: TitleLookup) {
        memoryLock.lock()
        memory[key] = value
        memoryLock.unlock()
    }

    func lookup(name: String, isShow: Bool) async -> TitleLookup {
        let key = apiKey
        if key.isEmpty { return .noKey }
        guard let cacheKey = cacheKey(name, isShow: isShow) else { return .notFound }
        if let known = knownLookup(name: name, isShow: isShow) { return known }
        let (title, year) = Self.parseTitle(name)

        if let hit = await cache.read(cacheKey) {
            let value: TitleLookup
            if hit.found, let meta = hit.meta { value = .found(meta) } else { value = .notFound }
            remember(cacheKey, value)
            return value
        }

        do {
            let result = try await Self.search(title: title, year: year, isShow: isShow, key: key)
            await cache.write(cacheKey, result)
            let value: TitleLookup = result.map { .found($0) } ?? .notFound
            remember(cacheKey, value)
            return value
        } catch {
            if Task.isCancelled { return .failed("cancelled") }
            let message = (error as? TmdbError)?.message ?? "Couldn't reach TMDb: \(error.localizedDescription)"
            AppLog.shared.log("TMDB", "\(title): \(message)")
            return .failed(message)
        }
    }

    // MARK: - TMDb request

    private static func encode(_ value: String) -> String {
        // urlQueryAllowed leaves & + = # alone, which would split or garble a
        // title such as "Fast & Furious" - encode them too.
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private static func search(title: String, year: Int?, isShow: Bool, key: String) async throws -> MovieMetadata? {
        // v3 keys are 32 hex characters; v4 tokens are long JWTs.
        let isV4 = key.count > 40
        var query = "query=\(encode(title))&include_adult=false"
        if let year {
            query += "&\(isShow ? "first_air_date_year" : "year")=\(year)"
        }
        if !isV4 { query += "&api_key=\(encode(key))" }

        guard let url = URL(string: "https://api.themoviedb.org/3/search/\(isShow ? "tv" : "movie")?\(query)") else {
            throw TmdbError(message: "Couldn't build the TMDb request.")
        }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("TapperIPTV-iPad", forHTTPHeaderField: "User-Agent")
        if isV4 { request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization") }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw TmdbError(message: "TMDb gave an unreadable response.")
        }
        if http.statusCode == 401 {
            throw TmdbError(message: "TMDb rejected the API key (HTTP 401). Check it in Settings - either the \"API Key\" or the \"API Read Access Token\" from TMDb's Settings > API page works.")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw TmdbError(message: "TMDb returned HTTP \(http.statusCode).")
        }

        // Not JSON at all (a proxy's error page served as 200) is a failure,
        // not "no match".
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw TmdbError(message: "TMDb gave an unreadable response.")
        }
        guard let results = root["results"] as? [[String: Any]], let first = results.first else {
            // IPTV listings get years wrong often enough to try once without.
            if year != nil { return try await search(title: title, year: nil, isShow: isShow, key: key) }
            return nil
        }

        let nameKey = isShow ? "name" : "title"
        let dateKey = isShow ? "first_air_date" : "release_date"
        let name = (first[nameKey] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? title
        let resultYear = (first[dateKey] as? String).flatMap { Int($0.prefix(4)) }
        let overview = (first["overview"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let poster = (first["poster_path"] as? String)
            .flatMap { $0.isEmpty ? nil : "https://image.tmdb.org/t/p/w500\($0)" }
        var rating = (first["vote_average"] as? NSNumber)?.doubleValue
        if let r = rating, r.isNaN || r <= 0 { rating = nil }
        return MovieMetadata(title: name, year: resultYear, overview: overview, posterUrl: poster, rating: rating)
    }

    // MARK: - Title cleanup

    /// Strips the quality / source / language noise IPTV VOD titles carry and
    /// pulls out an embedded release year: "EN - Movie Name (2023) 4K HDR"
    /// becomes ("Movie Name", 2023). Same rules as the Fire TV app.
    static func parseTitle(_ raw: String) -> (String, Int?) {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        var year: Int?
        let yearRange = s.range(of: #"[\[(](19|20)\d{2}[\])]"#, options: .regularExpression)
            ?? s.range(of: #"\b(19|20)\d{2}\b"#, options: .regularExpression)
        if let r = yearRange {
            year = Int(String(s[r].filter { $0.isNumber }))
            s.removeSubrange(r)
        }
        s = s.trimmingCharacters(in: .whitespacesAndNewlines)

        // A short leading tag before a spaced dash ("EN - Movie") is a
        // language marker; a hyphenated title ("Spider-Man") has no space
        // before its hyphen and is left alone.
        s = s.replacingOccurrences(of: #"^\S{1,10}\s+-\s+"#, with: "", options: .regularExpression)
        // Anything in parens or brackets.
        s = s.replacingOccurrences(of: #"[\[(][^\[\]()]*[\])]"#, with: " ", options: .regularExpression)

        let noise: Set<String> = [
            "EN", "4K", "2160P", "1080P", "720P", "HD", "SD", "HDR", "HDR10",
            "HEVC", "H264", "H265", "X264", "X265", "WEB-DL", "WEBDL", "WEBRIP",
            "BLURAY", "BLU-RAY", "BRRIP", "DVDRIP", "HDRIP", "CAM", "MULTI",
            "DUBBED", "SUB", "VOSTFR", "VF", "VO",
        ]
        let trimSet = CharacterSet(charactersIn: "-.:|")
        let cleaned = s.split(whereSeparator: { $0.isWhitespace })
            .map { String($0).trimmingCharacters(in: trimSet) }
            .filter { !$0.isEmpty && !noise.contains($0.uppercased()) }
            .joined(separator: " ")
            .trimmingCharacters(in: CharacterSet(charactersIn: " -.:|"))

        if cleaned.isEmpty {
            return (raw.trimmingCharacters(in: .whitespacesAndNewlines), year)
        }
        return (cleaned, year)
    }
}

/// On-disk cache of lookups. Lives in Caches (iOS may reclaim it, which only
/// costs a re-lookup) and is written a couple of seconds after the last
/// change rather than on every lookup.
private actor TmdbCache {
    struct Entry: Codable {
        let found: Bool
        let meta: MovieMetadata?
    }

    private var entries: [String: Entry] = [:]
    private var loaded = false
    private var saveQueued = false
    private let url: URL

    init() {
        let base = (try? FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
                                                 appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        url = base.appendingPathComponent("tapper-tmdb-cache.json")
    }

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        if let data = try? Data(contentsOf: url),
           let decoded = try? JSONDecoder().decode([String: Entry].self, from: data) {
            entries = decoded
        }
    }

    func read(_ key: String) -> Entry? {
        loadIfNeeded()
        return entries[key]
    }

    func write(_ key: String, _ meta: MovieMetadata?) {
        loadIfNeeded()
        entries[key] = Entry(found: meta != nil, meta: meta)
        scheduleSave()
    }

    func clear() {
        loaded = true
        entries = [:]
        try? FileManager.default.removeItem(at: url)
    }

    private func scheduleSave() {
        guard !saveQueued else { return }
        saveQueued = true
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            self.flush()
        }
    }

    private func flush() {
        saveQueued = false
        if let data = try? JSONEncoder().encode(entries) {
            try? data.write(to: url, options: .atomic)
        }
    }
}
