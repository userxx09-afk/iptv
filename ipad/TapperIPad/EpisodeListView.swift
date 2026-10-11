import SwiftUI
import TapperCore

/// Episode lists already fetched this run, so reopening a show is instant.
/// Keyed by the active source as well as the show: Xtream ids repeat between
/// providers. Only non-empty lists are kept, so a failed fetch is retried.
private enum EpisodeCache {
    private static var lists: [String: [Channel]] = [:]

    private static func key(_ item: Channel) -> String {
        "\(SourceStore.shared.activeId)|\(item.seriesId ?? item.id)|\(item.name)"
    }

    static func episodes(for item: Channel) -> [Channel]? {
        lists[key(item)]
    }

    static func store(_ episodes: [Channel], for item: Channel) {
        if !episodes.isEmpty { lists[key(item)] = episodes }
    }
}

/// Episodes for one Xtream series, fetched on demand when this screen opens
/// (see XtreamClient.episodes's own doc comment for why episodes aren't
/// loaded up front for every series). Unlike seriesList one level up, an
/// episode row *does* have its own stream, so tapping one opens PlayerView
/// directly - same tap-to-play pattern as ContentView's channelList, just
/// one screen deeper.
struct EpisodeListView: View {
    @ObservedObject var loader: XtreamLoader
    let seriesItem: Channel

    @State private var episodes: [Channel]
    @State private var isLoading: Bool
    /// The load has been asked for - the view can appear more than once (a
    /// player closing counts) and must not fetch again each time.
    @State private var requested = false

    init(loader: XtreamLoader, seriesItem: Channel) {
        self.loader = loader
        self.seriesItem = seriesItem
        // A show opened before this run lists its episodes from the first
        // frame instead of loading them again.
        let cached = EpisodeCache.episodes(for: seriesItem)
        _episodes = State(initialValue: cached ?? [])
        _isLoading = State(initialValue: cached == nil)
    }

    /// Drives the full-screen player for a tapped episode. Separate from
    /// ContentView's own `playingChannel` - this view has its own
    /// `.fullScreenCover`, not a shared one, since EpisodeListView is pushed
    /// on top of ContentView's NavigationStack rather than living inside it.
    @State private var playingEpisode: Channel?

    var body: some View {
        List {
            // Poster, synopsis and rating from TMDb above the episodes.
            Section {
                TitleInfoBlock(name: seriesItem.name, isShow: true)
            }

            Section("Episodes") {
                if isLoading {
                    // Grey placeholder rows rather than a spinner, so the
                    // list does not flash when the real rows arrive.
                    ForEach(0..<4, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Season 1 Episode 1 - Episode title")
                                .font(.body)
                            Text("Season 1")
                                .font(.caption)
                        }
                        .redacted(reason: .placeholder)
                    }
                } else if episodes.isEmpty {
                    Text("No episodes found for this show.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(episodes, id: \.id) { episode in
                        Button {
                            playingEpisode = episode
                        } label: {
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(episode.name)
                                        .font(.body)
                                        .foregroundStyle(episode.isPlayable ? Color.primary : Color.secondary)
                                    if let group = episode.group {
                                        Text(group)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                WatchBadge(channelId: episode.id)
                            }
                        }
                        .disabled(!episode.isPlayable)
                        .contextMenu {
                            Button {
                                WatchStore.shared.setWatched(channel: episode, series: seriesItem, watched: true)
                            } label: {
                                Label("Mark as watched", systemImage: "checkmark.circle")
                            }
                            Button {
                                WatchStore.shared.setWatched(channel: episode, series: seriesItem, watched: false)
                            } label: {
                                Label("Mark as unwatched", systemImage: "eye.slash")
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(seriesItem.name)
        .onAppear {
            guard isLoading, !requested else { return }
            requested = true
            loader.loadEpisodes(for: seriesItem) { result in
                EpisodeCache.store(result, for: seriesItem)
                withAnimation(.easeOut(duration: 0.2)) {
                    episodes = result
                    isLoading = false
                }
            }
        }
        .fullScreenCover(item: $playingEpisode) { episode in
            PlayerView(channel: episode, seriesItem: seriesItem)
        }
    }
}
