import Foundation
import TapperCore

/// A provider's whole library (live channels, movies, shows) as last
/// downloaded, plus the account details that came with it.
struct CachedCatalogue {
    let account: XtreamAccount
    let live: [Channel]
    let movies: [Channel]
    let series: [Channel]
    let savedAt: Date

    var isFresh: Bool { Date().timeIntervalSince(savedAt) < CatalogueCache.maxAge }
}

/// Keeps the last-downloaded Xtream library on disk, so opening the app is a
/// local read instead of downloading and parsing tens of thousands of items
/// every time - the same idea as the Fire TV app's catalogue cache. It also
/// means the app opens with no connection, or a bad one, which is exactly
/// when a download would stall or fail.
///
/// The file is plain text, one item per line (the lines themselves are made
/// and read by the shared CatalogueCodec), so it can be written and read a
/// piece at a time without the whole library ever being in memory twice.
/// It lives in Caches: iOS may clear it when space is short, and the app then
/// simply downloads again.
enum CatalogueCache {
    /// How long a saved library is used without asking the server.
    static let maxAge: TimeInterval = 24 * 3600

    private static let magic = "TAPPERCAT1"
    private static let chunk = 1 << 20

    private static var directory: URL? {
        let fm = FileManager.default
        guard let base = try? fm.url(for: .cachesDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: true) else { return nil }
        let dir = base.appendingPathComponent("Catalogue", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// One file per panel and user. The name is a hash, so no address or
    /// user name shows up in the file system.
    private static func fileURL(host: String, username: String) -> URL? {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in "\(host.lowercased())|\(username)".utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100000001b3
        }
        return directory?.appendingPathComponent(String(hash, radix: 16) + ".tapcat")
    }

    // MARK: - Reading

    /// The saved library, or nil if there isn't a complete one. Reads the
    /// whole file, so call it off the main thread.
    static func load(host: String, username: String) -> CachedCatalogue? {
        guard let url = fileURL(host: host, username: username),
              FileManager.default.fileExists(atPath: url.path) else { return nil }

        var account: XtreamAccount?
        var savedAt: Date?
        var live: [Channel] = []
        var movies: [Channel] = []
        var series: [Channel] = []
        var sawEnd = false

        let readable = readLines(url) { line in
            guard let tag = line.first else { return true }
            let rest = String(line.dropFirst())
            switch tag {
            case "H":
                let parts = rest.split(separator: "\t", maxSplits: 2, omittingEmptySubsequences: false)
                guard parts.count == 3, parts[0] == magic,
                      let seconds = TimeInterval(parts[1]),
                      let decoded = CatalogueCodec.shared.decodeAccount(text: String(parts[2])) else { return false }
                savedAt = Date(timeIntervalSince1970: seconds)
                account = decoded
            case "L":
                if let channel = CatalogueCodec.shared.decode(line: rest) { live.append(channel) }
            case "M":
                if let channel = CatalogueCodec.shared.decode(line: rest) { movies.append(channel) }
            case "S":
                if let channel = CatalogueCodec.shared.decode(line: rest) { series.append(channel) }
            case "E":
                sawEnd = true
            default:
                break
            }
            return true
        }

        guard readable, sawEnd, let account, let savedAt, !live.isEmpty else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return CachedCatalogue(account: account, live: live, movies: movies, series: series, savedAt: savedAt)
    }

    /// Calls [body] with each line; it returns false to stop. Reads in 1MB
    /// pieces, with a pool drained after each so the many temporary strings
    /// made along the way don't pile up.
    private static func readLines(_ url: URL, _ body: (String) -> Bool) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }

        var carry = Data()
        var keepGoing = true
        var reachedEnd = false
        while keepGoing && !reachedEnd {
            autoreleasepool {
                guard let piece = try? handle.read(upToCount: chunk), !piece.isEmpty else {
                    reachedEnd = true
                    return
                }
                carry.append(piece)
                var start = carry.startIndex
                while let newline = carry[start...].firstIndex(of: 0x0A) {
                    let text = String(decoding: carry[start..<newline], as: UTF8.self)
                    start = carry.index(after: newline)
                    if !body(text) {
                        keepGoing = false
                        break
                    }
                }
                carry = Data(carry[start...])
            }
        }
        if keepGoing, !carry.isEmpty {
            keepGoing = body(String(decoding: carry, as: UTF8.self))
        }
        return keepGoing
    }

    // MARK: - Writing

    /// Replaces the saved library. Call off the main thread. Written to a
    /// temporary file and moved into place, so a crash half way never leaves
    /// a broken cache behind.
    static func save(host: String, username: String, account: XtreamAccount,
                     live: [Channel], movies: [Channel], series: [Channel]) {
        guard let url = fileURL(host: host, username: username) else { return }
        let fm = FileManager.default
        let temp = url.appendingPathExtension("tmp")
        try? fm.removeItem(at: temp)
        guard fm.createFile(atPath: temp.path, contents: nil),
              let handle = try? FileHandle(forWritingTo: temp) else { return }

        var buffer = Data()
        buffer.reserveCapacity(chunk + 4096)
        var failed = false

        func flush() {
            guard !buffer.isEmpty else { return }
            do { try handle.write(contentsOf: buffer) } catch { failed = true }
            buffer.removeAll(keepingCapacity: true)
        }
        func put(_ text: String) {
            buffer.append(contentsOf: text.utf8)
            buffer.append(0x0A)
            if buffer.count >= chunk { flush() }
        }
        func write(_ tag: String, _ channels: [Channel]) {
            var index = 0
            while index < channels.count && !failed {
                autoreleasepool {
                    let end = min(index + 2000, channels.count)
                    for i in index..<end {
                        put(tag + CatalogueCodec.shared.encode(channel: channels[i]))
                    }
                    index = end
                }
            }
        }

        let stamp = String(Int(Date().timeIntervalSince1970))
        put("H" + magic + "\t" + stamp + "\t" + CatalogueCodec.shared.encodeAccount(account: account))
        write("L", live)
        write("M", movies)
        write("S", series)
        put("E")
        flush()
        try? handle.close()

        if failed {
            try? fm.removeItem(at: temp)
            return
        }
        try? fm.removeItem(at: url)
        try? fm.moveItem(at: temp, to: url)
    }

    /// Forgets every saved library, so the next open downloads fresh.
    static func clearAll() {
        guard let dir = directory,
              let files = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return }
        for name in files {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
        }
    }
}
