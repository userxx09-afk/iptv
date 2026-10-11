import SwiftUI
import TapperCore

/// One searchable list - "Live TV", "Movies", "Shows", or just "Channels" for
/// a plain playlist - kept separate so results stay grouped by kind the way
/// Fire TV's search groups them (a search for "matrix" separates the live
/// channel from the film of the same name).
struct SearchGroup {
    let title: String
    let channels: [Channel]
}

/// The iPad counterpart to Fire TV's SearchScreen: one search box across
/// everything the active source carries, results grouped by kind, with a star
/// on every result so a find can go straight into favorites.
///
/// Built for catalogues of tens of thousands of items without locking up:
/// the lowercase name index is built once, off the main thread; the search
/// itself runs off the main thread too, and only after typing pauses; and at
/// most 40 rows per group are ever drawn ("40 of 312 - type more to narrow").
///
/// Programme (guide) results come with the guide itself, which the iPad does
/// not have yet.
struct SearchView: View {
    let groups: [SearchGroup]
    @ObservedObject var loader: XtreamLoader
    let onClose: () -> Void

    @State private var query = ""
    @State private var index: SearchIndex?
    @State private var results: [SearchResultSection] = []
    @State private var searching = false
    @State private var playing: Channel?

    private var normalized: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// Re-runs the search when the text changes, and once more when the
    /// index finishes building (typing is accepted before that).
    private struct SearchKey: Equatable {
        let text: String
        let ready: Bool
    }

    var body: some View {
        NavigationStack {
            List {
                if normalized.count < 2 {
                    Text("Type at least two characters.")
                        .foregroundStyle(.secondary)
                } else if searching {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Searching...").foregroundStyle(.secondary)
                    }
                } else if results.isEmpty {
                    Text("Nothing found for \"\(query)\".")
                        .foregroundStyle(.secondary)
                }

                if !searching {
                    ForEach(results) { section in
                        Section {
                            ForEach(Array(section.shown.enumerated()), id: \.offset) { _, channel in
                                row(channel)
                            }
                        } header: {
                            Text(header(for: section))
                        }
                    }
                }
            }
            .searchable(
                text: $query,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: "Channel, movie or show"
            )
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onClose)
                }
            }
        }
        .task {
            let started = Date()
            let built = await Task.detached(priority: .userInitiated) {
                SearchIndex.build(groups)
            }.value
            index = built
            let secs = String(format: "%.2f", Date().timeIntervalSince(started))
            AppLog.shared.log("SEARCH", "Index ready: \(built.itemCount) items in \(secs)s - \(AppLog.memoryDescription())")
        }
        .task(id: SearchKey(text: normalized, ready: index != nil)) {
            await runSearch()
        }
        .fullScreenCover(item: $playing) { channel in
            PlayerView(channel: channel)
        }
    }

    private func header(for section: SearchResultSection) -> String {
        if section.total > section.shown.count {
            return "\(section.title) (\(section.shown.count) of \(section.total) - type more to narrow)"
        }
        return "\(section.title) (\(section.total))"
    }

    // A series has no stream of its own, so its row opens the episode list;
    // everything else plays. The star sits outside the row's own button so
    // starring a result doesn't also open or play it.
    @ViewBuilder
    private func row(_ channel: Channel) -> some View {
        HStack {
            if channel.seriesId != nil {
                NavigationLink {
                    EpisodeListView(loader: loader, seriesItem: channel)
                } label: {
                    rowLabel(channel)
                }
            } else {
                Button {
                    playing = channel
                } label: {
                    rowLabel(channel)
                }
                .disabled(!channel.isPlayable)
            }
            Spacer()
            FavoriteButton(sourceId: channel.sourceId, channelId: channel.id)
        }
    }

    private func rowLabel(_ channel: Channel) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(channel.name)
                .font(.body)
                .foregroundStyle(channel.isPlayable ? Color.primary : Color.secondary)
            if let group = channel.group {
                Text(group)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func runSearch() async {
        let q = normalized
        if q.count < 2 {
            results = []
            searching = false
            return
        }
        searching = true
        // Debounce: typing restarts this task, cancelling the wait.
        try? await Task.sleep(nanoseconds: 300_000_000)
        if Task.isCancelled { return }
        // Index still building - this task re-runs the moment it is ready.
        guard let index else { return }
        let found = await Task.detached(priority: .userInitiated) {
            index.search(q)
        }.value
        if Task.isCancelled { return }
        results = found
        searching = false
    }
}

// MARK: - Index and matching

struct SearchResultSection: Identifiable {
    let id: Int
    let title: String
    let shown: [Channel]
    let total: Int
}

/// Pre-lowercased names, one array per group, so a search is a single pass
/// of plain string tests instead of re-lowercasing every item on every
/// keystroke.
struct SearchIndex {
    private static let perGroupCap = 40

    private struct Part {
        let title: String
        let channels: [Channel]
        let names: [String]
    }

    private let parts: [Part]

    var itemCount: Int { parts.reduce(0) { $0 + $1.channels.count } }

    static func build(_ groups: [SearchGroup]) -> SearchIndex {
        SearchIndex(parts: groups.map { group in
            Part(title: group.title, channels: group.channels, names: group.channels.map { $0.name.lowercased() })
        })
    }

    /// Every word typed must appear in the name. Ranked: names starting with
    /// the first word, then names containing the words together in order,
    /// then everything else - capped per group.
    func search(_ query: String) -> [SearchResultSection] {
        let tokens = query.split(separator: " ").map(String.init)
        guard let first = tokens.first else { return [] }
        let spaced = tokens.joined(separator: " ")
        var out: [SearchResultSection] = []

        for (partIndex, part) in parts.enumerated() {
            var buckets: [[Channel]] = [[], [], []]
            var total = 0
            for i in 0..<part.names.count {
                let name = part.names[i]
                var matches = true
                for token in tokens where !name.contains(token) {
                    matches = false
                    break
                }
                if !matches { continue }
                total += 1
                let score = name.hasPrefix(first) ? 0 : (name.contains(spaced) ? 1 : 2)
                if buckets[score].count < SearchIndex.perGroupCap {
                    buckets[score].append(part.channels[i])
                }
            }
            if total > 0 {
                let shown = Array((buckets[0] + buckets[1] + buckets[2]).prefix(SearchIndex.perGroupCap))
                out.append(SearchResultSection(id: partIndex, title: part.title, shown: shown, total: total))
            }
        }
        return out
    }
}
