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
///
/// "Sources" (toolbar) is the persisted counterpart to the form above -
/// mirrors Fire TV's AddSourceScreen + SourceStore: saved sources survive
/// relaunch, Xtream credentials live in the Keychain (CredentialVault), and
/// switching sources re-authenticates/reloads instead of retyping
/// everything. The quick-connect form stays as-is alongside it as an
/// unsaved, one-off path - nothing about it changes here.
struct ContentView: View {
    @StateObject private var playlistLoader = PlaylistLoader()
    @StateObject private var xtreamLoader = XtreamLoader()
    @State private var mode: SourceMode = .playlist

    @State private var urlText: String = ""
    @State private var xtreamHost: String = ""
    @State private var xtreamUsername: String = ""
    @State private var xtreamPassword: String = ""

    @State private var sources: [TvSource] = SourceStore.shared.all()
    @State private var activeSheet: ActiveSheet?
    @State private var addSourceBusy = false
    @State private var addSourceError: String?
    @State private var pendingSourceSave: PendingSourceSave?

    /// Whatever row was tapped in a live/movie/episode list - drives the
    /// full-screen player. A series row never sets this directly (it has no
    /// stream of its own); only EpisodeListView's rows and this view's own
    /// channel rows do. See PlayerView.swift for Channel's Identifiable
    /// conformance, required for `.fullScreenCover(item:)` below.
    @State private var playingChannel: Channel?

    private enum SourceMode: String, CaseIterable, Identifiable {
        case playlist = "Playlist URL"
        case xtream = "Xtream Login"
        var id: String { rawValue }
    }

    private enum ActiveSheet: Identifiable {
        case sources
        case addSource
        var id: Int { hashValue }
    }

    /// What's waiting on the in-flight load started from AddSourceView, so
    /// the onChange handlers below know what to persist once it finishes -
    /// and can tell that load apart from one started by the quick-connect
    /// form, which never sets this.
    private enum PendingSourceSave {
        case xtream(name: String, host: String, user: String, pass: String)
        case m3u(name: String, url: String, epg: String?)
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
                    channelList(playlistLoader.channels, namespace: "playlist")
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
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Sources") { activeSheet = .sources }
                }
                // A per-stream preference, not tied to whether a source is
                // loaded - shown alongside Sources rather than gated on
                // hasContent, so it's there to set before connecting too.
                ToolbarItem(placement: .topBarTrailing) {
                    bufferingMenu
                }
                if hasContent {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("New Source") { resetAll() }
                    }
                }
            }
        }
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .sources:
                SourceListView(
                    sources: sources,
                    activeId: SourceStore.shared.activeId,
                    onSelect: { selectSource($0) },
                    onRemove: { removeSource($0) },
                    onAddTapped: {
                        addSourceError = nil
                        activeSheet = .addSource
                    },
                    onDismiss: { activeSheet = nil }
                )
            case .addSource:
                AddSourceView(
                    busy: addSourceBusy,
                    error: addSourceError,
                    onSubmitXtream: { name, host, user, pass in
                        addSourceError = nil
                        addSourceBusy = true
                        pendingSourceSave = .xtream(name: name, host: host, user: user, pass: pass)
                        xtreamLoader.login(host: host, username: user, password: pass)
                    },
                    onSubmitM3u: { name, url, epg in
                        addSourceError = nil
                        addSourceBusy = true
                        pendingSourceSave = .m3u(name: name, url: url, epg: epg)
                        playlistLoader.load(urlString: url)
                    },
                    onCancel: {
                        pendingSourceSave = nil
                        addSourceBusy = false
                        addSourceError = nil
                        activeSheet = nil
                    }
                )
            }
        }
        // Reuses XtreamLoader's existing authenticate+load flow rather than
        // adding a separate auth-only entry point on the bridge - it already
        // validates the login and leaves the app ready to browse on success,
        // so a second code path just for AddSourceView isn't worth the extra
        // surface. Guarded on pendingSourceSave so the quick-connect form's
        // own login (which never sets it) is untouched by this.
        .onChange(of: xtreamLoader.isLoading) { _, isLoading in
            guard !isLoading, case .xtream(let name, let host, let user, let pass)? = pendingSourceSave else { return }
            if xtreamLoader.isLoggedIn {
                let id = "xtream-" + String(UUID().uuidString.prefix(8))
                CredentialVault.put(sourceId: id, username: user, password: pass)
                SourceStore.shared.add(
                    TvSource(id: id, name: name.isEmpty ? host : name, kind: .xtream, location: host, epgUrlOverride: nil, builtIn: false)
                )
                SourceStore.shared.activeId = id
                sources = SourceStore.shared.all()
                mode = .xtream
                xtreamHost = host
                xtreamUsername = user
                xtreamPassword = pass
                pendingSourceSave = nil
                addSourceBusy = false
                addSourceError = nil
                activeSheet = nil
            } else {
                addSourceBusy = false
                addSourceError = xtreamLoader.errorMessage ?? "Couldn't connect."
                pendingSourceSave = nil
            }
        }
        .onChange(of: playlistLoader.isLoading) { _, isLoading in
            guard !isLoading, case .m3u(let name, let url, let epg)? = pendingSourceSave else { return }
            if !playlistLoader.channels.isEmpty {
                let id = "m3u-" + String(UUID().uuidString.prefix(8))
                SourceStore.shared.add(
                    TvSource(id: id, name: name.isEmpty ? "Playlist" : name, kind: .m3u, location: url, epgUrlOverride: epg, builtIn: false)
                )
                SourceStore.shared.activeId = id
                sources = SourceStore.shared.all()
                mode = .playlist
                urlText = url
                pendingSourceSave = nil
                addSourceBusy = false
                addSourceError = nil
                activeSheet = nil
            } else {
                addSourceBusy = false
                addSourceError = playlistLoader.errorMessage ?? "Couldn't load this playlist."
                pendingSourceSave = nil
            }
        }
        .fullScreenCover(item: $playingChannel) { channel in
            PlayerView(channel: channel)
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

    /// Switches the active saved source and loads it - M3U just needs its
    /// stored URL, Xtream needs its Keychain-held credentials alongside the
    /// stored host. A source added before credentials could be looked up
    /// (shouldn't happen via AddSourceView, but guards against a source
    /// surviving a Keychain wipe/reinstall) silently does nothing rather
    /// than crash.
    private func selectSource(_ source: TvSource) {
        SourceStore.shared.activeId = source.id
        activeSheet = nil
        playlistLoader.reset()
        xtreamLoader.reset()
        switch source.kind {
        case .m3u:
            mode = .playlist
            urlText = source.location
            playlistLoader.load(urlString: source.location)
        case .xtream:
            guard let creds = CredentialVault.get(sourceId: source.id) else { return }
            mode = .xtream
            xtreamHost = source.location
            xtreamUsername = creds.username
            xtreamPassword = creds.password
            xtreamLoader.login(host: source.location, username: creds.username, password: creds.password)
        }
    }

    private func removeSource(_ source: TvSource) {
        SourceStore.shared.remove(id: source.id)
        CredentialVault.delete(sourceId: source.id)
        sources = SourceStore.shared.all()
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
                channelList(xtreamLoader.liveChannels, namespace: "live")
                    .tabItem { Label("Live", systemImage: "tv") }
                channelList(xtreamLoader.movies, namespace: "movie")
                    .tabItem { Label("Movies", systemImage: "film") }
                seriesList
                    .tabItem { Label("Shows", systemImage: "tv.badge.wifi") }
            }
        }
    }

    // A series row has no stream of its own (XtreamLoader.loadEpisodes
    // fetches its episodes on demand), so it stays a NavigationLink into
    // EpisodeListView rather than a play button - CategoryFilteredList only
    // takes over the category chips/grouping here, not the tap behavior.
    // FavoriteButton sits outside the NavigationLink in its own HStack slot
    // so starring a show doesn't also push EpisodeListView.
    private var seriesList: some View {
        CategoryFilteredList(channels: xtreamLoader.series, categoryNamespace: "series") { item in
            HStack {
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
                Spacer()
                FavoriteButton(sourceId: item.sourceId, channelId: item.id)
            }
        }
    }

    // Live and movie rows do have a stream, so - unlike seriesList above -
    // each row is a plain Button that opens PlayerView directly rather than
    // navigating anywhere. isPlayable guards against the handful of
    // mms/mmsh/rtmp/rtsp entries M3uParser already flags as unplayable (see
    // its own doc comment) so tapping one shows a greyed-out row instead of
    // a silent no-op or a PlayerView that can only fail immediately.
    // categoryNamespace is a plain String (not Channel.kind) because the
    // same function serves Live, Movies and the M3U playlist list, and this
    // avoids relying on how a Kotlin enum bridges into Swift for something
    // that's purely a UserDefaults storage key here.
    private func channelList(_ channels: [Channel], namespace: String) -> some View {
        CategoryFilteredList(channels: channels, categoryNamespace: namespace) { channel in
            HStack {
                Button {
                    playingChannel = channel
                } label: {
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
                .disabled(!channel.isPlayable)
                Spacer()
                FavoriteButton(sourceId: channel.sourceId, channelId: channel.id)
            }
        }
    }

    // One menu, not a full Settings screen - there isn't one yet for iPad.
    // Takes effect on the next stream start (TapperPlayer.swift reads it
    // fresh each time), not whatever's already playing - same as Fire TV's
    // equivalent control.
    private var bufferingMenu: some View {
        Menu {
            ForEach(BufferSize.allCases) { size in
                Button {
                    PlayerSettingsStore.shared.bufferSize = size
                } label: {
                    if PlayerSettingsStore.shared.bufferSize == size {
                        Label(size.label, systemImage: "checkmark")
                    } else {
                        Text(size.label)
                    }
                }
            }
        } label: {
            Label("Buffering", systemImage: "antenna.radiowaves.left.and.right")
        }
    }
}

#Preview {
    ContentView()
}
