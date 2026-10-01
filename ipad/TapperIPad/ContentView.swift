import SwiftUI
import TapperCore

/// Two ways in: a plain M3U playlist URL (Phase 3's original screen), or an
/// Xtream Codes panel login (host/username/password) unlocking three real
/// catalogues - live channels, movies, shows. Live/movie/playlist rows stay
/// plain (not NavigationLinks) because tapping one still doesn't do
/// anything yet - playback is further out. Shows are the one exception:
/// tapping a series has to lead somewhere (its episode list) for the
/// screen to mean anything at all, so that single row type is a
/// NavigationLink - see EpisodeListView's own doc comment.
struct ContentView: View {
    @StateObject private var playlistLoader = PlaylistLoader()
    @StateObject private var xtreamLoader = XtreamLoader()
    @State private var mode: SourceMode = .playlist

    @State private var urlText: String = ""
    @State private var xtreamHost: String = ""
    @State private var xtreamUsername: String = ""
    @State private var xtreamPassword: String = ""

    private enum SourceMode: String, CaseIterable, Identifiable {
        case playlist = "Playlist URL"
        case xtream = "Xtream Login"
        var id: String { rawValue }
    }

    private var hasContent: Bool {
        !playlistLoader.channels.isEmpty || xtreamLoader.isLoggedIn
    }

    var body: some View {
        NavigationStack {
            Group {
                if !hasContent {
                    loadForm
                } else if !playlistLoader.channels.isEmpty {
                    channelList(playlistLoader.channels)
                } else {
                    xtreamTabs
                }
            }
            .navigationTitle(
                !playlistLoader.channels.isEmpty
                    ? "\(playlistLoader.channels.count) Channels"
                    : "Tapper IPTV"
            )
            .toolbar {
                if hasContent {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("New Source") { resetAll() }
                    }
                }
            }
        }
    }

    private func resetAll() {
        playlistLoader.reset()
        xtreamLoader.reset()
        urlText = ""
        xtreamHost = ""
        xtreamUsername = ""
        xtreamPassword = ""
    }

    private var loadForm: some View {
        VStack(spacing: 20) {
            Picker("Source", selection: $mode) {
                ForEach(SourceMode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 480)

            if mode == .playlist {
                playlistForm
            } else {
                xtreamForm
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var playlistForm: some View {
        VStack(spacing: 16) {
            Text("Paste an M3U playlist URL to load real channels.")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            TextField("https://example.com/playlist.m3u", text: $urlText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                .frame(maxWidth: 480)
                .onSubmit { playlistLoader.load(urlString: urlText) }

            Button {
                playlistLoader.load(urlString: urlText)
            } label: {
                if playlistLoader.isLoading {
                    ProgressView()
                } else {
                    Text("Load Playlist")
                }
            }
            .disabled(urlText.isEmpty || playlistLoader.isLoading)

            if let error = playlistLoader.errorMessage {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 480)
            }
        }
    }

    private var xtreamForm: some View {
        VStack(spacing: 16) {
            Text("Log in with your Xtream Codes panel details.")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            TextField("Host, e.g. http://example.com:8080", text: $xtreamHost)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                .frame(maxWidth: 480)

            TextField("Username", text: $xtreamUsername)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .frame(maxWidth: 480)

            SecureField("Password", text: $xtreamPassword)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 480)
                .onSubmit(attemptXtreamLogin)

            Button(action: attemptXtreamLogin) {
                if xtreamLoader.isLoading {
                    ProgressView()
                } else {
                    Text("Log In")
                }
            }
            .disabled(
                xtreamHost.isEmpty || xtreamUsername.isEmpty || xtreamPassword.isEmpty
                    || xtreamLoader.isLoading
            )

            if let error = xtreamLoader.errorMessage {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 480)
            }
        }
    }

    private func attemptXtreamLogin() {
        xtreamLoader.login(host: xtreamHost, username: xtreamUsername, password: xtreamPassword)
    }

    private var xtreamTabs: some View {
        VStack(spacing: 8) {
            if let account = xtreamLoader.account {
                Text(account.summary(nowUtc: Int64(Date().timeIntervalSince1970 * 1000)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
            }
            if !xtreamLoader.warnings.isEmpty {
                Text(xtreamLoader.warnings.joined(separator: "\n"))
                    .font(.caption2)
                    .foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
            TabView {
                channelList(xtreamLoader.liveChannels)
                    .tabItem { Label("Live", systemImage: "tv") }
                channelList(xtreamLoader.movies)
                    .tabItem { Label("Movies", systemImage: "film") }
                seriesList
                    .tabItem { Label("Shows", systemImage: "tv.badge.wifi") }
            }
        }
    }

    private var seriesList: some View {
        List(xtreamLoader.series, id: \.id) { item in
            NavigationLink {
                EpisodeListView(loader: xtreamLoader, seriesItem: item)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name).font(.body)
                    if let group = item.group {
                        Text(group).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func channelList(_ channels: [Channel]) -> some View {
        List(channels, id: \.id) { channel in
            VStack(alignment: .leading, spacing: 2) {
                Text(channel.name)
                    .font(.body)
                if let group = channel.group {
                    Text(group)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
