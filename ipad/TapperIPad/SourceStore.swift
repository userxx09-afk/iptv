import Foundation
import Security

/// A configured content source. Mirrors Fire TV's TvSource (data/SourceStore.kt)
/// field-for-field - credentials live in CredentialVault below, never here, so a
/// plain UserDefaults dump of this struct never contains a subscription's
/// password, matching why Fire TV keeps the same split across two stores.
struct TvSource: Identifiable, Codable, Equatable {
    enum Kind: String, Codable {
        case m3u
        case xtream
    }

    let id: String
    var name: String
    var kind: Kind
    /// M3U playlist URL, or Xtream host.
    var location: String
    /// Overrides whatever the playlist declares. The default playlist names
    /// two guides and the first one 404s, so this is a normal need, not an
    /// edge case - matches Fire TV's AddSourceScreen EPG field.
    var epgUrlOverride: String?
    var builtIn: Bool = false

    static let builtIn = TvSource(
        id: "iptv-org",
        name: "iptv-org (free)",
        kind: .m3u,
        location: "https://iptv-org.github.io/iptv/index.m3u",
        epgUrlOverride: nil,
        builtIn: true
    )
}

/// Stores the list of configured sources and which one is active. Plain
/// UserDefaults + JSON - same shape as Fire TV's SharedPreferences-backed
/// SourceStore. Nothing stored here is sensitive; that's CredentialVault's
/// job, for Xtream usernames/passwords only.
final class SourceStore {
    static let shared = SourceStore()

    private let defaults = UserDefaults.standard
    private let sourcesKey = "tapper.sources"
    private let activeKey = "tapper.activeSourceId"

    private init() {}

    func all() -> [TvSource] {
        guard
            let data = defaults.data(forKey: sourcesKey),
            let list = try? JSONDecoder().decode([TvSource].self, from: data),
            !list.isEmpty
        else { return [.builtIn] }
        // The built-in source is never removable - deleting it would leave a
        // new user with no obvious way back in, same reasoning as Fire TV.
        if list.contains(where: { $0.builtIn }) { return list }
        return [.builtIn] + list
    }

    func save(_ sources: [TvSource]) {
        guard let data = try? JSONEncoder().encode(sources) else { return }
        defaults.set(data, forKey: sourcesKey)
    }

    func add(_ source: TvSource) {
        save(all().filter { $0.id != source.id } + [source])
    }

    func remove(id: String) {
        save(all().filter { $0.id != id || $0.builtIn })
    }

    var activeId: String {
        get { defaults.string(forKey: activeKey) ?? TvSource.builtIn.id }
        set { defaults.set(newValue, forKey: activeKey) }
    }

    func active() -> TvSource {
        all().first { $0.id == activeId } ?? .builtIn
    }
}

/// Xtream credentials, kept out of UserDefaults entirely - an Xtream
/// username and password ride along in every stream URL, so a plaintext
/// copy on disk is effectively a resellable subscription for anyone with
/// access to the device's files. Mirrors Fire TV's CredentialVault (Android
/// Keystore-backed EncryptedSharedPreferences) using the iOS Keychain, its
/// direct platform equivalent - same reasoning, same guarantee.
enum CredentialVault {
    private static let service = "io.tapper.ipad.credentials"

    static func put(sourceId: String, username: String, password: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: sourceId,
        ]
        // Delete-then-add rather than update: simpler than branching on
        // whether an item already exists, and this is called rarely enough
        // (once per source add/edit) that the extra round trip costs nothing.
        SecItemDelete(query as CFDictionary)

        var attributes = query
        attributes[kSecValueData as String] = Data(password.utf8)
        // The username has nowhere else to live alongside the password in a
        // single generic-password item, so it rides in kSecAttrGeneric -
        // this field is opaque/unindexed storage, exactly what's needed here.
        attributes[kSecAttrGeneric as String] = Data(username.utf8)
        SecItemAdd(attributes as CFDictionary, nil)
    }

    static func get(sourceId: String) -> (username: String, password: String)? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: sourceId,
            kSecReturnData as String: true,
            kSecReturnAttributes as String: true,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard
            status == errSecSuccess,
            let item = result as? [String: Any],
            let passwordData = item[kSecValueData as String] as? Data,
            let usernameData = item[kSecAttrGeneric as String] as? Data,
            let password = String(data: passwordData, encoding: .utf8),
            let username = String(data: usernameData, encoding: .utf8)
        else { return nil }
        return (username, password)
    }

    static func delete(sourceId: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: sourceId,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
