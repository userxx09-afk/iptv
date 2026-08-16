import SwiftUI
import TapperCore

/// First real screen. Paste an M3U playlist URL, load it, see the actual
/// parsed channel list - real data, parsed by the same M3uParser Fire TV
/// uses, not a placeholder. Deliberately small in scope: rows are plain
/// (not NavigationLinks/Buttons) because tapping one doesn't do anything
/// yet - playback and Xtream-panel logins (as opposed to a raw M3U URL)
/// are further out. No tap affordance is shown rather than one that leads
/// nowhere.
struct ContentView: View {
    @StateObject private var loader = PlaylistLoader()
    @State private var urlText: String = ""

    var body: some View {
        NavigationStack {
            Group {
                if loader.channels.isEmpty {
                    loadForm
                } else {
                    channelList
                }
            }
            .navigationTitle(loader.channels.isEmpty ? "Tapper IPTV" : "\(loader.channels.count) Channels")
            .toolbar {
                if !loader.channels.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("New Playlist") {
                            loader.reset()
                            urlText = ""
                        }
                    }
                }
            }
        }
    }

    private var loadForm: some View {
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
                .onSubmit { loader.load(urlString: urlText) }

            Button {
                loader.load(urlString: urlText)
            } label: {
                if loader.isLoading {
                    ProgressView()
                } else {
                    Text("Load Playlist")
                }
            }
            .disabled(urlText.isEmpty || loader.isLoading)

            if let error = loader.errorMessage {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 480)
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var channelList: some View {
        List(loader.channels, id: \.id) { channel in
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
