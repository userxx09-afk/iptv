import SwiftUI
import TapperCore

/// Episodes for one Xtream series, fetched on demand when this screen opens
/// (see XtreamClient.episodes's own doc comment for why episodes aren't
/// loaded up front for every series). This is the one place in the app
/// where tapping a row leads somewhere - a series has no stream of its own,
/// so a non-interactive row would just be a dead end. It still stops at the
/// episode list, same as everywhere else: tapping an episode doesn't play
/// anything either, since playback is further out than this round covers.
struct EpisodeListView: View {
    @ObservedObject var loader: XtreamLoader
    let seriesItem: Channel

    @State private var episodes: [Channel] = []
    @State private var isLoading = true

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
                    VStack(alignment: .leading, spacing: 2) {
                        Text(episode.name)
                            .font(.body)
                        if let group = episode.group {
                            Text(group)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
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
    }
}
