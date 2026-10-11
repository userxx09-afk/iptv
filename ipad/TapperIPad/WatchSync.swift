import Foundation
import UIKit

/// Shares watch progress with the Fire TV app (and other iPads) through a
/// WebDAV folder - the same folder, file names and file format the Fire TV
/// app's "Shared watch history > WebDAV / NAS" option uses, so a film started
/// on one device can be resumed on the other.
///
/// The design is Fire TV's: every device writes only its own file
/// (`tapper-watch-<deviceId>.json`) and merges everyone else's on read, so two
/// devices can sync at the same moment with no locking. A small
/// `tapper-index.json` lists the files, because WebDAV directory listing is
/// not used.

// MARK: - This device

enum SyncDevice {
    private static let idKey = "tapper.device.id"
    private static let labelKey = "tapper.device.label"

    /// Stable per-install id; the shard file name is derived from it.
    static var id: String {
        if let existing = UserDefaults.standard.string(forKey: idKey), !existing.isEmpty {
            return existing
        }
        let made = String(UUID().uuidString.lowercased().prefix(8))
        UserDefaults.standard.set(made, forKey: idKey)
        return made
    }

    /// The name other devices show for this one.
    static var label: String {
        get {
            let saved = UserDefaults.standard.string(forKey: labelKey) ?? ""
            if !saved.isEmpty { return saved }
            let name = UIDevice.current.name
            return name.isEmpty ? "iPad" : name
        }
        set {
            UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: labelKey)
        }
    }

    static var shardName: String { "tapper-watch-\(id).json" }
}

// MARK: - WebDAV

enum SyncError: LocalizedError {
    case notSetUp
    case badAddress
    case http(Int, String)
    case other(String)

    var errorDescription: String? {
        switch self {
        case .notSetUp: return "Sync isn't set up yet."
        case .badAddress: return "That address doesn't look right. It should start with http:// or https://."
        case .http(let code, let name):
            switch code {
            case 401, 403: return "The server refused the username or password (HTTP \(code))."
            case 404, 409: return "The folder wasn't found (HTTP \(code)). Check the address, and that the folder exists on the server."
            default: return "The server returned HTTP \(code) while handling \(name)."
            }
        case .other(let message): return message
        }
    }
}

/// Plain GET / PUT against a WebDAV folder with Basic authentication - the
/// two verbs Fire TV's client uses, so any server that works there works here.
struct WebDavClient {
    private static let indexName = "tapper-index.json"
    private static let prefix = "tapper-watch-"

    let root: String
    let username: String
    let password: String

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }()

    init?(address: String, username: String, password: String) {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", url.host != nil else { return nil }
        var base = trimmed
        while base.hasSuffix("/") { base.removeLast() }
        self.root = base
        self.username = username
        self.password = password
    }

    private func request(_ name: String, method: String, body: Data? = nil) throws -> URLRequest {
        guard let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let url = URL(string: "\(root)/\(encoded)") else { throw SyncError.badAddress }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("TapperIPTV-iPad", forHTTPHeaderField: "User-Agent")
        if !username.isEmpty {
            let token = Data("\(username):\(password)".utf8).base64EncodedString()
            req.setValue("Basic \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return req
    }

    private func perform(_ req: URLRequest) async throws -> (Data, Int) {
        do {
            let (data, response) = try await Self.session.data(for: req)
            return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
        } catch let error as URLError {
            switch error.code {
            case .notConnectedToInternet: throw SyncError.other("No network connection.")
            case .timedOut: throw SyncError.other("The server didn't answer in time.")
            case .cannotFindHost, .dnsLookupFailed: throw SyncError.other("Couldn't find that server. Check the address.")
            case .cannotConnectToHost: throw SyncError.other("Couldn't connect to that server.")
            case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate,
                 .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot:
                throw SyncError.other("The server's security certificate isn't trusted. Use a server with a valid certificate, or an http:// address on your own network.")
            default: throw SyncError.other(error.localizedDescription)
            }
        }
    }

    /// The file's bytes, or nil if it doesn't exist.
    func read(_ name: String) async throws -> Data? {
        let (data, code) = try await perform(try request(name, method: "GET"))
        if code == 404 { return nil }
        guard (200...299).contains(code) else { throw SyncError.http(code, name) }
        return data
    }

    func write(_ name: String, _ data: Data) async throws {
        let (_, code) = try await perform(try request(name, method: "PUT", body: data))
        guard (200...299).contains(code) else { throw SyncError.http(code, name) }
    }

    /// Shard file names, from the shared index.
    func listShards() async throws -> [String] {
        guard let data = try await read(Self.indexName) else { return [] }
        return Self.names(in: data).filter { $0.hasPrefix(Self.prefix) }
    }

    /// Adds [name] to the index, as Fire TV does after each write. The index
    /// is the one file several devices may write, so a simultaneous update can
    /// drop a name; that heals on the next sync because every device re-adds
    /// itself.
    func register(_ name: String) async throws {
        // A missing index is fine (first device); a failed read is not, since
        // writing back from nothing would erase the other devices' names.
        let known = try await read(Self.indexName).map(Self.names(in:)) ?? []
        if known.contains(name) { return }
        var all = known
        all.append(name)
        let unique = all.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
        let data = (try? JSONSerialization.data(withJSONObject: unique)) ?? Data("[]".utf8)
        try await write(Self.indexName, data)
    }

    private static func names(in data: Data) -> [String] {
        guard let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return [] }
        var out: [String] = []
        for item in array {
            if let text = item as? String, !text.isEmpty { out.append(text) }
        }
        return out
    }
}

// MARK: - Settings

/// Where to sync. The address and user name sit in UserDefaults; the password
/// is in the Keychain.
enum SyncConfig {
    private static let urlKey = "tapper.sync.url"
    private static let vaultId = "tapper-sync-webdav"

    static var address: String {
        get { UserDefaults.standard.string(forKey: urlKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: urlKey) }
    }

    static var credentials: (username: String, password: String) {
        CredentialVault.get(sourceId: vaultId) ?? ("", "")
    }

    static var isConfigured: Bool { !address.isEmpty }

    static func save(address: String, username: String, password: String) {
        self.address = address.trimmingCharacters(in: .whitespacesAndNewlines)
        CredentialVault.put(sourceId: vaultId, username: username, password: password)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: urlKey)
        CredentialVault.delete(sourceId: vaultId)
    }

    static func client() -> WebDavClient? {
        guard isConfigured else { return nil }
        let c = credentials
        return WebDavClient(address: address, username: c.username, password: c.password)
    }
}

// MARK: - Sync

@MainActor
final class SyncManager: ObservableObject {
    static let shared = SyncManager()

    @Published private(set) var syncing = false
    @Published private(set) var message = ""
    @Published private(set) var lastSynced: Date?
    @Published private(set) var lastFailed = false
    /// Other devices seen on the last sync, for display.
    @Published private(set) var otherDevices: [String] = []

    private let lastKey = "tapper.sync.last"
    private var again = false

    private init() {
        let t = UserDefaults.standard.double(forKey: lastKey)
        if t > 0 { lastSynced = Date(timeIntervalSince1970: t) }
    }

    var isConfigured: Bool { SyncConfig.isConfigured }

    /// Syncs unless it already did within [seconds] - for the many natural
    /// moments (coming to the foreground, opening Continue) where a fresh pull
    /// is wanted but not a request every time.
    func syncIfStale(seconds: TimeInterval = 45) async {
        guard isConfigured else { return }
        if let last = lastSynced, Date().timeIntervalSince(last) < seconds, !lastFailed { return }
        await syncNow(reason: "refresh")
    }

    /// Fire and forget, for the player closing.
    func syncSoon(reason: String) {
        guard isConfigured else { return }
        Task { await self.syncNow(reason: reason) }
    }

    /// Pull everyone else's progress, merge it, then publish this device's.
    /// Pulling first means what gets published already reflects the others.
    @discardableResult
    func syncNow(reason: String) async -> Bool {
        guard let client = SyncConfig.client() else {
            if isConfigured { message = SyncError.badAddress.localizedDescription; lastFailed = true }
            return false
        }
        if syncing {
            again = true
            return false
        }
        syncing = true
        defer { syncing = false }

        var ok = false
        repeat {
            again = false
            ok = await runOnce(client, reason: reason)
        } while again
        return ok
    }

    private func runOnce(_ client: WebDavClient, reason: String) async -> Bool {
        let mine = SyncDevice.shardName
        do {
            var pulled = 0
            var changed = 0
            var others: [String] = []
            for name in try await client.listShards() where name != mine {
                guard let data = try await client.read(name) else { continue }
                pulled += 1
                let result = WatchStore.shared.importShard(data)
                changed += result.changed
                if let label = result.label { others.append(label) }
            }
            try await client.write(mine, WatchStore.shared.exportOwn(label: SyncDevice.label))
            try await client.register(mine)

            otherDevices = others
            lastSynced = Date()
            UserDefaults.standard.set(lastSynced!.timeIntervalSince1970, forKey: lastKey)
            lastFailed = false
            message = pulled == 0
                ? "Synced. No other devices have shared yet."
                : "Synced with \(pulled) other device\(pulled == 1 ? "" : "s") - \(changed) update\(changed == 1 ? "" : "s") received."
            AppLog.shared.log("SYNC", "\(reason): pulled \(pulled) shard(s), \(changed) changed")
            return true
        } catch {
            lastFailed = true
            message = error.localizedDescription
            AppLog.shared.log("SYNC", "\(reason) failed: \(error.localizedDescription)")
            return false
        }
    }
}
