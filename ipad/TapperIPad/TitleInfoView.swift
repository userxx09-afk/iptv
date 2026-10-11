import SwiftUI
import TapperCore

/// Poster, matched title, rating and synopsis for one movie or show, looked
/// up from TMDb when it appears - the iPad counterpart of Fire TV's
/// MovieInfoPanel. Says what is going on in every case (no key, looking,
/// nothing found, lookup failed) instead of showing a blank.
struct TitleInfoBlock: View {
    let name: String
    let isShow: Bool

    private enum LoadState {
        case loading
        case noKey
        case notFound
        case failed(String)
        case found(MovieMetadata)
    }

    @State private var state: LoadState = .loading

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .task(id: name) { await load() }
    }

    private func load() async {
        state = .loading
        let result = await MovieMetadataStore.shared.lookup(name: name, isShow: isShow)
        if Task.isCancelled { return }
        switch result {
        case .noKey: state = .noKey
        case .notFound: state = .notFound
        case .failed(let message): state = .failed(message)
        case .found(let meta): state = .found(meta)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            HStack(spacing: 10) {
                ProgressView()
                Text("Looking it up...").foregroundStyle(.secondary)
            }
        case .noKey:
            Text("Add a free TMDb API key in Settings > Movie & show info to see posters, synopses and ratings here.")
                .foregroundStyle(.secondary)
        case .notFound:
            Text("No match found on TMDb for this title.")
                .foregroundStyle(.secondary)
        case .failed(let message):
            Text("Couldn't look this up: \(message)")
                .foregroundStyle(.orange)
        case .found(let meta):
            found(meta)
        }
    }

    private func found(_ meta: MovieMetadata) -> some View {
        HStack(alignment: .top, spacing: 20) {
            if let poster = meta.posterUrl, let url = URL(string: poster) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFit()
                    case .failure:
                        Image(systemName: "photo")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    default:
                        ProgressView()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .frame(width: 200, height: 300)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }

            VStack(alignment: .leading, spacing: 8) {
                // The title TMDb actually matched, which can differ from the
                // listing's own text - a wrong match is otherwise silent.
                Text(meta.title + (meta.year.map { " (\($0))" } ?? ""))
                    .font(.title3.weight(.semibold))
                if let rating = meta.rating {
                    Label(String(format: "%.1f / 10", rating), systemImage: "star.fill")
                        .font(.subheadline)
                        .foregroundStyle(.yellow)
                }
                if let overview = meta.overview {
                    Text(overview)
                        .font(.body)
                }
                Text("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .padding(.top, 6)
            }
        }
    }
}

/// A movie's own screen: poster, synopsis and rating, with Play and the
/// favorites star. Playing is handed back to whoever pushed this screen,
/// which owns the full-screen player.
struct MovieDetailView: View {
    let channel: Channel
    let onPlay: (Channel) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(channel.name)
                    .font(.title2.bold())
                if let group = channel.group {
                    Text(group)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 18) {
                    Button {
                        onPlay(channel)
                    } label: {
                        Label("Play", systemImage: "play.fill")
                            .frame(minWidth: 120)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!channel.isPlayable)

                    FavoriteButton(sourceId: channel.sourceId, channelId: channel.id)
                        .font(.title2)
                }

                Divider()

                TitleInfoBlock(name: channel.name, isShow: false)
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Movie")
        .navigationBarTitleDisplayMode(.inline)
    }
}
