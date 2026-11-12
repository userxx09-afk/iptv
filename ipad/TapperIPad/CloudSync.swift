import Foundation

/// Google Drive and OneDrive as sync destinations, signed in from Settings.
///
/// Mirrors the Fire TV app's CloudSync.kt: the same OAuth device-code sign-in,
/// the same client IDs, and the same folder and file names, so an iPad and a
/// Fire TV signed in to the same account share watch history.
///
/// Sign-in shows a short code; the user opens the sign-in page (on this iPad or
/// any other device), enters it, and the app finishes by itself. Afterwards the
/// sync folder is created behind the scenes:
///  - Google Drive: "Tapper IPTV Sync" in My Drive (drive.file scope - the app
///    sees only files it created).
///  - OneDrive: the app folder, Apps/Tapper IPTV (Files.ReadWrite.AppFolder).
///
/// Client IDs come from Info.plist, which the build fills from repository
/// secrets (see project.yml and the TestFlight workflow).

enum CloudProvider: String, CaseIterable, Identifiable {
    case google
    case microsoft

    var id: String { rawValue }

    var label: String { self == .google ? "Google Drive" : "OneDrive" }
    var company: String { self == .google ? "Google" : "Microsoft" }
    var folderDescription: String {
        self == .google ? "\"\(GoogleDriveStore.folderName)\" in My Drive" : "Apps/Tapper IPTV"
    }

    var isConfigured: Bool {
        switch self {
        case .google: return !CloudOAuthConfig.googleClientId.isEmpty && !CloudOAuthConfig.googleClientSecret.isEmpty
        case .microsoft: return !CloudOAuthConfig.microsoftClientId.isEmpty
        }
    }
}

enum CloudOAuthConfig {
    private static func value(_ key: String) -> String {
        let raw = (Bundle.main.object(forInfoDictionaryKey: key) as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        // An unset build setting can come through as the literal "$(NAME)".
        return raw.hasPrefix("$(") ? "" : raw
    }

    static var googleClientId: String { value("TapperGoogleOAuthClientID") }
    static var googleClientSecret: String { value("TapperGoogleOAuthClientSecret") }
    static var microsoftClientId: String { value("TapperMicrosoftOAuthClientID") }
}

// MARK: - HTTP

private enum CloudHTTP {
    static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }()

    /// Stops URLSession following a redirect, so the caller sees the 3xx and
    /// its Location header.
    final class NoRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest) async -> URLRequest? { nil }
    }

    static func send(_ url: URL, method: String = "GET", headers: [String: String] = [:],
                     body: Data? = nil, contentType: String? = nil,
                     followRedirects: Bool = true) async throws -> (Data, Int) {
        let r = try await sendFull(url, method: method, headers: headers, body: body,
                                   contentType: contentType, followRedirects: followRedirects)
        return (r.0, r.1)
    }

    static func sendFull(_ url: URL, method: String = "GET", headers: [String: String] = [:],
                         body: Data? = nil, contentType: String? = nil,
                         followRedirects: Bool = true) async throws -> (Data, Int, String?) {
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("TapperIPTV-iPad", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if let body {
            req.httpBody = body
            if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        }
        do {
            let (data, response) = followRedirects
                ? try await session.data(for: req)
                : try await session.data(for: req, delegate: NoRedirect())
            let http = response as? HTTPURLResponse
            return (data, http?.statusCode ?? 0, http?.value(forHTTPHeaderField: "Location"))
        } catch let error as URLError {
            switch error.code {
            case .notConnectedToInternet: throw SyncError.other("No network connection.")
            case .timedOut: throw SyncError.other("The server didn't answer in time.")
            case .cancelled: throw CancellationError()
            default: throw SyncError.other(error.localizedDescription)
            }
        }
    }

    static func form(_ pairs: [(String, String)]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let text = pairs.map { k, v in
            (k.addingPercentEncoding(withAllowedCharacters: allowed) ?? k) + "=" +
                (v.addingPercentEncoding(withAllowedCharacters: allowed) ?? v)
        }.joined(separator: "&")
        return Data(text.utf8)
    }

    static func json(_ data: Data) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    static func query(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

// MARK: - Sign-in

enum CloudAuth {
    private static let googleDevice = URL(string: "https://oauth2.googleapis.com/device/code")!
    private static let googleToken = URL(string: "https://oauth2.googleapis.com/token")!
    private static let googleScope = "https://www.googleapis.com/auth/drive.file"

    private static let msBase = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private static let msScope = "Files.ReadWrite.AppFolder User.Read offline_access"

    struct DeviceCode {
        let provider: CloudProvider
        let deviceCode: String
        let userCode: String
        let verificationURL: URL
        let interval: Int
        let expires: Date
    }

    struct Tokens {
        let accessToken: String
        let refreshToken: String?
        let expires: Date
    }

    private static func notConfigured(_ p: CloudProvider) -> SyncError {
        .other("\(p.company) sign-in isn't set up in this build. See \"Cloud sync setup\" in the README.")
    }

    static func start(_ p: CloudProvider) async throws -> DeviceCode {
        guard p.isConfigured else { throw notConfigured(p) }
        let r: (Data, Int)
        switch p {
        case .google:
            r = try await CloudHTTP.send(googleDevice, method: "POST",
                body: CloudHTTP.form([("client_id", CloudOAuthConfig.googleClientId), ("scope", googleScope)]),
                contentType: "application/x-www-form-urlencoded")
        case .microsoft:
            r = try await CloudHTTP.send(URL(string: "\(msBase)/devicecode")!, method: "POST",
                body: CloudHTTP.form([("client_id", CloudOAuthConfig.microsoftClientId), ("scope", msScope)]),
                contentType: "application/x-www-form-urlencoded")
        }
        let (data, code) = r
        let j = CloudHTTP.json(data)
        guard (200...299).contains(code),
              let device = j["device_code"] as? String,
              let user = j["user_code"] as? String,
              let urlText = (j["verification_url"] as? String) ?? (j["verification_uri"] as? String),
              let url = URL(string: urlText) else {
            throw SyncError.other(describe(p, j, code))
        }
        let interval = max(1, (j["interval"] as? Int) ?? 5)
        let expiresIn = (j["expires_in"] as? Int) ?? 900
        return DeviceCode(provider: p, deviceCode: device, userCode: user, verificationURL: url,
                          interval: interval, expires: Date().addingTimeInterval(TimeInterval(expiresIn)))
    }

    /// Polls until the user finishes signing in. Cancelling the task stops it.
    static func waitForSignIn(_ dc: DeviceCode) async throws -> Tokens {
        var interval = dc.interval
        while true {
            try await Task.sleep(nanoseconds: UInt64(interval) * 1_000_000_000)
            if Date() > dc.expires { throw SyncError.other("The code expired before sign-in finished. Try again.") }
            let r: (Data, Int)
            switch dc.provider {
            case .google:
                r = try await CloudHTTP.send(googleToken, method: "POST", body: CloudHTTP.form([
                    ("client_id", CloudOAuthConfig.googleClientId),
                    ("client_secret", CloudOAuthConfig.googleClientSecret),
                    ("device_code", dc.deviceCode),
                    ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ]), contentType: "application/x-www-form-urlencoded")
            case .microsoft:
                r = try await CloudHTTP.send(URL(string: "\(msBase)/token")!, method: "POST", body: CloudHTTP.form([
                    ("client_id", CloudOAuthConfig.microsoftClientId),
                    ("device_code", dc.deviceCode),
                    ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ]), contentType: "application/x-www-form-urlencoded")
            }
            let (data, code) = r
            let j = CloudHTTP.json(data)
            if (200...299).contains(code), let tokens = tokens(j, previousRefresh: nil) { return tokens }
            switch j["error"] as? String ?? "" {
            case "authorization_pending": continue
            case "slow_down": interval += 5
            case "access_denied", "authorization_declined":
                throw SyncError.other("Sign-in was cancelled.")
            case "expired_token", "bad_verification_code":
                throw SyncError.other("The code expired before sign-in finished. Try again.")
            default:
                throw SyncError.other(describe(dc.provider, j, code))
            }
        }
    }

    static func refresh(_ p: CloudProvider, refreshToken: String) async throws -> Tokens {
        guard p.isConfigured else { throw notConfigured(p) }
        let r: (Data, Int)
        switch p {
        case .google:
            r = try await CloudHTTP.send(googleToken, method: "POST", body: CloudHTTP.form([
                ("client_id", CloudOAuthConfig.googleClientId),
                ("client_secret", CloudOAuthConfig.googleClientSecret),
                ("refresh_token", refreshToken),
                ("grant_type", "refresh_token"),
            ]), contentType: "application/x-www-form-urlencoded")
        case .microsoft:
            r = try await CloudHTTP.send(URL(string: "\(msBase)/token")!, method: "POST", body: CloudHTTP.form([
                ("client_id", CloudOAuthConfig.microsoftClientId),
                ("refresh_token", refreshToken),
                ("grant_type", "refresh_token"),
                ("scope", msScope),
            ]), contentType: "application/x-www-form-urlencoded")
        }
        let (data, code) = r
        let j = CloudHTTP.json(data)
        if (200...299).contains(code), let tokens = tokens(j, previousRefresh: refreshToken) { return tokens }
        if (j["error"] as? String) == "invalid_grant" {
            throw SyncError.other("The \(p.label) sign-in has expired or was revoked. Sign in again in Settings > Sync.")
        }
        throw SyncError.other(describe(p, j, code))
    }

    static func accountName(_ p: CloudProvider, accessToken: String) async -> String {
        let auth = ["Authorization": "Bearer \(accessToken)"]
        switch p {
        case .google:
            if let r = try? await CloudHTTP.send(
                URL(string: "https://www.googleapis.com/drive/v3/about?fields=user")!, headers: auth),
               (200...299).contains(r.1),
               let user = CloudHTTP.json(r.0)["user"] as? [String: Any] {
                if let email = user["emailAddress"] as? String, !email.isEmpty { return email }
                if let name = user["displayName"] as? String, !name.isEmpty { return name }
            }
        case .microsoft:
            if let r = try? await CloudHTTP.send(
                URL(string: "https://graph.microsoft.com/v1.0/me?$select=mail,userPrincipalName,displayName")!, headers: auth),
               (200...299).contains(r.1) {
                let j = CloudHTTP.json(r.0)
                for key in ["mail", "userPrincipalName", "displayName"] {
                    if let v = j[key] as? String, !v.isEmpty { return v }
                }
            }
        }
        return p.label
    }

    private static func tokens(_ j: [String: Any], previousRefresh: String?) -> Tokens? {
        guard let access = j["access_token"] as? String else { return nil }
        let refresh = (j["refresh_token"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? previousRefresh
        let expiresIn = (j["expires_in"] as? Int) ?? 3600
        return Tokens(accessToken: access, refreshToken: refresh,
                      expires: Date().addingTimeInterval(TimeInterval(expiresIn - 60)))
    }

    private static func describe(_ p: CloudProvider, _ j: [String: Any], _ code: Int) -> String {
        let error = j["error"] as? String ?? ""
        if error == "invalid_client" || error == "unauthorized_client" {
            return "\(p.company) rejected this app's client ID. Check the OAuth app setup (README, \"Cloud sync setup\")."
        }
        if let d = j["error_description"] as? String, !d.isEmpty { return "\(p.company) sign-in failed: \(d)" }
        if !error.isEmpty { return "\(p.company) sign-in failed: \(error)" }
        return "\(p.company) sign-in failed (HTTP \(code))."
    }
}

// MARK: - Session

/// Hands out access tokens for a signed-in account, refreshing as needed and
/// saving rotated refresh tokens (Microsoft issues a new one on every use).
actor CloudSession {
    let provider: CloudProvider
    let account: String
    private var refreshToken: String
    private var cached: CloudAuth.Tokens?
    private let onRotate: @Sendable (String) -> Void

    init(provider: CloudProvider, account: String, refreshToken: String, onRotate: @escaping @Sendable (String) -> Void) {
        self.provider = provider
        self.account = account
        self.refreshToken = refreshToken
        self.onRotate = onRotate
    }

    func accessToken(force: Bool = false) async throws -> String {
        if !force, let c = cached, c.expires > Date() { return c.accessToken }
        let t = try await CloudAuth.refresh(provider, refreshToken: refreshToken)
        if let r = t.refreshToken, r != refreshToken {
            refreshToken = r
            onRotate(r)
        }
        cached = t
        return t.accessToken
    }

    /// An authenticated request, retried once with a fresh token on 401.
    func send(_ url: URL, method: String = "GET", headers: [String: String] = [:],
              body: Data? = nil, contentType: String? = nil) async throws -> (Data, Int) {
        let r = try await sendFull(url, method: method, headers: headers, body: body, contentType: contentType)
        return (r.0, r.1)
    }

    func sendFull(_ url: URL, method: String = "GET", headers: [String: String] = [:],
                  body: Data? = nil, contentType: String? = nil,
                  followRedirects: Bool = true) async throws -> (Data, Int, String?) {
        var h = headers
        h["Authorization"] = "Bearer \(try await accessToken())"
        let first = try await CloudHTTP.sendFull(url, method: method, headers: h, body: body,
                                                 contentType: contentType, followRedirects: followRedirects)
        guard first.1 == 401 else { return first }
        h["Authorization"] = "Bearer \(try await accessToken(force: true))"
        return try await CloudHTTP.sendFull(url, method: method, headers: h, body: body,
                                            contentType: contentType, followRedirects: followRedirects)
    }
}

private func apiMessage(_ data: Data) -> String {
    ((CloudHTTP.json(data)["error"] as? [String: Any])?["message"] as? String) ?? ""
}

// MARK: - Google Drive

final class GoogleDriveStore: SyncStore {
    static let folderName = "Tapper IPTV Sync"
    private static let folderMime = "application/vnd.google-apps.folder"
    private static let api = "https://www.googleapis.com/drive/v3"
    private static let upload = "https://www.googleapis.com/upload/drive/v3"
    private static let prefix = "tapper-watch-"

    let session: CloudSession
    private var folderId: String?
    private var ids: [String: String]?

    init(session: CloudSession) { self.session = session }

    private func check(_ r: (Data, Int), _ what: String) throws -> Data {
        if (200...299).contains(r.1) { return r.0 }
        let m = apiMessage(r.0)
        throw SyncError.other("Google Drive returned HTTP \(r.1) \(what)" + (m.isEmpty ? "." : ": \(m)"))
    }

    /// Finds the sync folder, creating it the first time. Oldest first, so two
    /// devices that both created one at once still settle on the same folder.
    @discardableResult
    func ensureFolder() async throws -> String {
        if let folderId { return folderId }
        let q = "name = '\(Self.folderName)' and mimeType = '\(Self.folderMime)' and trashed = false"
        let url = URL(string: "\(Self.api)/files?q=\(CloudHTTP.query(q))&spaces=drive&orderBy=createdTime&fields=files(id)")!
        let found = CloudHTTP.json(try check(try await session.send(url), "finding the sync folder"))["files"] as? [[String: Any]] ?? []
        let id: String
        if let first = found.first?["id"] as? String {
            id = first
        } else {
            let meta = try JSONSerialization.data(withJSONObject: ["name": Self.folderName, "mimeType": Self.folderMime])
            let made = CloudHTTP.json(try check(try await session.send(URL(string: "\(Self.api)/files?fields=id")!,
                method: "POST", body: meta, contentType: "application/json; charset=UTF-8"), "creating the sync folder"))
            guard let newId = made["id"] as? String else { throw SyncError.other("Google Drive didn't create the sync folder.") }
            id = newId
        }
        folderId = id
        return id
    }

    private func index() async throws -> [String: String] {
        if let ids { return ids }
        let parent = try await ensureFolder()
        var out: [String: String] = [:]
        var pageToken: String?
        repeat {
            let q = "'\(parent)' in parents and trashed = false"
            var text = "\(Self.api)/files?q=\(CloudHTTP.query(q))&spaces=drive&pageSize=1000&fields=nextPageToken,files(id,name)"
            if let pageToken { text += "&pageToken=\(CloudHTTP.query(pageToken))" }
            let j = CloudHTTP.json(try check(try await session.send(URL(string: text)!), "listing the sync folder"))
            for f in j["files"] as? [[String: Any]] ?? [] {
                if let name = f["name"] as? String, let id = f["id"] as? String, out[name] == nil { out[name] = id }
            }
            pageToken = (j["nextPageToken"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        } while pageToken != nil
        ids = out
        return out
    }

    func listShards() async throws -> [String] {
        try await index().keys.filter { $0.hasPrefix(Self.prefix) }
    }

    func read(_ name: String) async throws -> Data? {
        guard let id = try await index()[name] else { return nil }
        let r = try await session.send(URL(string: "\(Self.api)/files/\(id)?alt=media")!)
        if r.1 == 404 { return nil }
        return try check(r, "reading \(name)")
    }

    func write(_ name: String, _ data: Data) async throws {
        if let existing = try await index()[name] {
            let r = try await session.send(URL(string: "\(Self.upload)/files/\(existing)?uploadType=media")!,
                                           method: "PATCH", body: data, contentType: "application/json")
            if (200...299).contains(r.1) { return }
            if r.1 != 404 { _ = try check(r, "saving \(name)") }
            ids?[name] = nil
        }
        let boundary = "tapper\(UUID().uuidString)"
        let meta = try JSONSerialization.data(withJSONObject: ["name": name, "parents": [try await ensureFolder()]])
        var body = Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8)
        body.append(meta)
        body.append(Data("\r\n--\(boundary)\r\nContent-Type: application/json\r\n\r\n".utf8))
        body.append(data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        let made = CloudHTTP.json(try check(try await session.send(
            URL(string: "\(Self.upload)/files?uploadType=multipart&fields=id")!, method: "POST",
            body: body, contentType: "multipart/related; boundary=\(boundary)"), "creating \(name)"))
        if let id = made["id"] as? String { ids?[name] = id }
    }

    /// Drive lists folder contents directly, so there's no index to keep.
    func register(_ name: String) async throws {}
}

// MARK: - OneDrive

final class OneDriveStore: SyncStore {
    private static let root = "https://graph.microsoft.com/v1.0/me/drive/special/approot"
    private static let prefix = "tapper-watch-"

    let session: CloudSession

    init(session: CloudSession) { self.session = session }

    private func check(_ r: (Data, Int), _ what: String) throws -> Data {
        if (200...299).contains(r.1) { return r.0 }
        let m = apiMessage(r.0)
        throw SyncError.other("OneDrive returned HTTP \(r.1) \(what)" + (m.isEmpty ? "." : ": \(m)"))
    }

    private func item(_ name: String) -> URL {
        let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
        return URL(string: "\(Self.root):/\(encoded):/content")!
    }

    /// Opening approot is what makes OneDrive create Apps/Tapper IPTV.
    func ensureFolder() async throws {
        _ = try check(try await session.send(URL(string: "\(Self.root)?$select=id,name")!), "opening the app folder")
    }

    func listShards() async throws -> [String] {
        var out: [String] = []
        var next: String? = "\(Self.root)/children?$select=name&$top=999"
        while let link = next, let url = URL(string: link) {
            let j = CloudHTTP.json(try check(try await session.send(url), "listing the app folder"))
            for v in j["value"] as? [[String: Any]] ?? [] {
                if let name = v["name"] as? String, name.hasPrefix(Self.prefix) { out.append(name) }
            }
            next = j["@odata.nextLink"] as? String
        }
        return out
    }

    func read(_ name: String) async throws -> Data? {
        // Graph answers with a redirect to a pre-signed download URL. Followed
        // by hand so the bearer token isn't sent on to the storage host.
        let r = try await session.sendFull(item(name), followRedirects: false)
        if r.1 == 404 { return nil }
        if (300...399).contains(r.1), let loc = r.2, let url = URL(string: loc) {
            let d = try await CloudHTTP.send(url)
            return try check(d, "reading \(name)")
        }
        return try check((r.0, r.1), "reading \(name)")
    }

    func write(_ name: String, _ data: Data) async throws {
        _ = try check(try await session.send(item(name), method: "PUT", body: data, contentType: "application/json"),
                      "saving \(name)")
    }

    func register(_ name: String) async throws {}
}
