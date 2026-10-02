import SwiftUI
import TapperCore

/// Episodes for one Xtream series, fetched on demand when this screen opens
/// (see XtreamClient.episodes's own doc comment for why episodes aren't
/// loaded up front for every series). Unlike seriesList one level up, an
/// episode row *does* have its own stream, so tapping one opens PlayerView
/// directly - same tap-to-play pattern as ContentView's channelList, just
/// one screen deeper.
struct EpisodeListView: View {
    @ObservedObject var loader: XtreamLoader
    let seriesItem: Channel

    @State private var episodes: [Channel] = []
    @State private var isLoading = true

    /// Drives the full-screen player for a tapped episode. Separate from
    /// ContentView's own `playingChannel` - this view has its own
    /// `.fullScreenCover`, not a shared one, since EpisodeListView is pushed
    /// on top of ContentView's NavigationStack rather than living inside it.
    @State private var playingEpisode: Channel?

    var body: some View {
        Group {
            if isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if episodes.isEmpty {
                Text("No episodes found for this show.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(episodes, id: \.id) { episode in
                    Button {
                        playingEpisode = episode
                    } label: {
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
                    }
                    .disabled(!episode.isPlayable)
                }
            }
        }
        .navigationTitle(seriesItem.name)
        .onAppear {
            loader.loadEpisodes(for: seriesItem) { result in
                episodes = result
                isLoading = false
            }
        }
        .fullScreenCover(item: $playingEpisode) { episode in
            PlayerView(channel: episode)
        }
    }
}
