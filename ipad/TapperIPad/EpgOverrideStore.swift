import SwiftUI
import TapperCore

/// Per-channel guide overrides: "this channel's listings are under that guide
/// id", for a channel whose provider names it differently from the guide.
/// The iPad counterpart to Fire TV's EpgOverrideStore; keyed
/// "sourceId|channelId" the same way.
final class EpgOverrideStore: ObservableObject {
    static let shared = EpgOverrideStore()
    private let key = "tapper.epgOverrides"
    @Published private(set) var revision = 0

    private init() {}

    private var map: [String: String] {
        get { UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: key); revision += 1 }
    }

    private func id(_ channel: Channel) -> String { "\(channel.sourceId)|\(channel.id)" }

    func override(for channel: Channel) -> String? { map[id(channel)] }

    func set(_ guideId: String, for channel: Channel) {
        var m = map
        m[id(channel)] = EpgDatabase.normalizeId(guideId)
        map = m
    }

    func clear(for channel: Channel) {
        var m = map
        m.removeValue(forKey: id(channel))
        map = m
    }
}

/// "Set program guide...": search the guide ids the guide holds and pick the
/// one that belongs to this channel.
struct EpgPickerView: View {
    let channel: Channel
    let sourceId: String
    let onDone: () -> Void

    @State private var query = ""
    @State private var results: [(id: String, title: String)] = []
    @ObservedObject private var overrides = EpgOverrideStore.shared

    var body: some View {
        NavigationStack {
            List {
                if let current = overrides.override(for: channel) {
                    Section {
                        Text("Using guide \(current)")
                        Button("Clear override", role: .destructive) {
                            overrides.clear(for: channel)
                            onDone()
                        }
                    }
                }
                Section("Guide channels") {
                    ForEach(results, id: \.id) { r in
                        Button {
                            overrides.set(r.id, for: channel)
                            onDone()
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.id)
                                Text(r.title.isEmpty ? "Nothing on now" : "On now: \(r.title)")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .foregroundStyle(.primary)
                    }
                }
            }
            .searchable(text: $query, prompt: "Search guide ids")
            .navigationTitle("Program guide for \(channel.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDone) } }
            .task(id: query) {
                let q = query.isEmpty ? channel.name : query
                let source = sourceId
                results = await Task.detached(priority: .userInitiated) {
                    EpgDatabase.shared.guideIds(source, matching: q, now: Int(Date().timeIntervalSince1970))
                }.value
            }
        }
    }
}
