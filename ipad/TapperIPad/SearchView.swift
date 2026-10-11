import SwiftUI
import TapperCore

/// One searchable list - "Live TV", "Movies", "Shows", or just "Channels" for
/// a plain playlist - kept separate so results stay grouped by kind the way
/// Fire TV's search groups them (a search for "matrix" separates the live
/// channel from the film of the same name).
struct SearchGroup {
    let title: String
    let channels: [Channel]
    /// "live", "movie", "series" or "playlist" - a movie result opens its
    /// detail screen rather than playing straight away.
    var namespace: String = ""
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
    @State private var playingLive = false
    @State private var programmeHits: [ProgrammeHit] = []
    @State private var guideChannel: Channel?

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

                if !searching && !programmeHits.isEmpty {
                    Section {
                        ForEach(programmeHits) { hit in
                            programmeRow(hit)
                        }
                    } header: {
                        Text("On TV (\(programmeHits.count))")
                    }
                }

                if !searching {
                    ForEach(results) { section in
                        Section {
                            ForEach(Array(section.shown.enumerated()), id: \.offset) { _, channel in
                                row(channel, namespace: section.namespace)
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
            PlayerView(channel: channel, isLive: playingLive)
        }
        .sheet(item: $guideChannel) { channel in
            ChannelGuideView(
                channel: channel,
                onPlay: { picked in
                    guideChannel = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        playingLive = true
                        playing = picked
                    }
                },
                onClose: { guideChannel = nil }
            )
        }
    }

    // A programme found in the guide. Airing now: tap plays the channel.
    // Later: tap opens that channel's schedule.
    @ViewBuilder
    private func programmeRow(_ hit: ProgrammeHit) -> some View {
        let nowSeconds = Int(Date().timeIntervalSince1970)
        let airing = hit.programme.isAiring(at: nowSeconds)
        Button {
            guard let channel = hit.channel else { return }
            if airing && channel.isPlayable {
                playingLive = true
                playing = channel
            } else {
                guideChannel = channel
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(hit.programme.title)
                    .font(.body)
                    .foregroundStyle(hit.channel == nil ? Color.secondary : Color.primary)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(airing ? "On now" : hit.programme.startDate.formatted(.dateTime.weekday(.abbreviated).hour().minute()))
                        .foregroundStyle(airing ? Color.blue : Color.secondary)
                    Text("·").foregroundStyle(.secondary)
                    Text(hit.channel?.name ?? hit.programme.channelId)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .font(.caption)
            }
        }
        .disabled(hit.channel == nil)
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
    private func row(_ channel: Channel, namespace: String) -> some View {
        HStack {
            if channel.seriesId != nil {
                NavigationLink {
                    EpisodeListView(loader: loader, seriesItem: channel)
                } label: {
                    rowLabel(channel)
                }
            } else if namespace == "movie" {
                NavigationLink {
                    MovieDetailView(channel: channel, onPlay: {
                        playingLive = false
                        playing = $0
                    })
                } label: {
                    rowLabel(channel)
                }
            } else {
                Button {
                    playingLive = namespace == "live" || namespace == "playlist"
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
            programmeHits = []
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

        // Programme titles come from the guide, a moment after the channels.
        let hits = await EpgManager.shared.searchProgrammes(q)
        if Task.isCancelled { return }
        programmeHits = hits
    }
}

// MARK: - Index and matching

struct SearchResultSection: Identifiable {
    let id: Int
    let title: String
    let shown: [Channel]
    let total: Int
    var namespace: String = ""
}

/// Pre-lowercased names, one array per group, so a search is a single pass
/// of plain string tests instead of re-lowercasing every item on every
/// keystroke.
struct SearchIndex {
    private static let perGroupCap = 40

    private struct Part {
        let title: String
        let namespace: String
        let channels: [Channel]
        let names: [String]
    }

    private let parts: [Part]

    var itemCount: Int { parts.reduce(0) { $0 + $1.channels.count } }

    static func build(_ groups: [SearchGroup]) -> SearchIndex {
        SearchIndex(parts: groups.map { group in
            Part(title: group.title, namespace: group.namespace, channels: group.channels, names: group.channels.map { $0.name.lowercased() })
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
                out.append(SearchResultSection(id: partIndex, title: part.title, shown: shown, total: total, namespace: part.namespace))
            }
        }
        return out
    }
}
